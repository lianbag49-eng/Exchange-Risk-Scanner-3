package com.example.riskscanner

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ExchangeDiscoveryTest {
 private val time="2026-09-15T06:03:37.480Z"
 private fun row(id:Int,name:String="Exchange $id",slug:String="exchange-$id",status:String="active")=CmcExchange(id,name,slug,status)
 private fun page(rows:List<CmcExchange>)=JSONObject().put("status",JSONObject().put("timestamp",time).put("error_code",0)).put("data",JSONArray().apply{rows.forEach{put(it.json())}})
 @Test fun bundledCatalogIncludesAllListingStatesAndRetainsExistingLogos(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val bundled=context.assets.open("cmc-directory.json").bufferedReader().use{CmcDirectory.parse(JSONObject(it.readText()))}
  assertEquals(2448,bundled.entries.size)
  assertEquals(mapOf("active" to 978,"inactive" to 307,"untracked" to 1163),bundled.entries.groupingBy{it.status}.eachCount())
  assertEquals(2448,bundled.entries.map{it.id}.toSet().size)
  val catalog=ExchangeCatalog.load(context)
  assertTrue(catalog.size>=2449);assertEquals("기타 거래소",catalog.last().name)
  assertTrue(catalog.count{it.logo!=null}>=52)
  assertTrue(catalog.any{it.name=="Tapbit"});assertTrue(catalog.any{it.name=="MGBX"})
  assertFalse(bundled.needsRefresh(java.time.Instant.parse(time).toEpochMilli()+1000))
  assertTrue(bundled.needsRefresh(java.time.Instant.parse(time).toEpochMilli()+86400000))
 }
 @Test fun fullDirectoryPaginationRejectsDuplicatesMissingPagesAndApiErrors(){
  val requested=mutableListOf<String>();var call=0
  val directory=fetchCmcDirectory(2){url->requested.add(url);call++;if(call==1)page(listOf(row(1),row(2,status="inactive"))) else page(listOf(row(3,status="untracked")))}
  assertEquals(listOf(1,2,3),directory.entries.map{it.id});assertEquals(2,requested.size)
  assertTrue(requested[0].contains("listing_status=active,inactive,untracked"));assertTrue(requested[1].contains("start=3"))
  assertTrue(requested.all{it.startsWith(CMC_DIRECTORY_SOURCE)&&!it.contains("key",true)})
  assertTrue(runCatching{fetchCmcDirectory(2){page(listOf(row(1),row(2)))}}.isFailure)
  assertTrue(runCatching{fetchCmcDirectory(2){page(emptyList())}}.isFailure)
  assertTrue(runCatching{fetchCmcDirectory(2){page(listOf(row(1))).put("status",JSONObject().put("error_code",429))}}.isFailure)
  assertTrue(runCatching{CmcDirectory.parse(directory.json().put("complete",false))}.isFailure)
  assertTrue(runCatching{CmcExchange.parse(row(4).json().put("slug","../other"))}.isFailure)
  assertTrue(runCatching{CmcExchange.parse(row(4).json().put("status","verified"))}.isFailure)
 }
 @Test fun nameAndPackageMatchesNeverEstablishAppAccountOrKycAuthenticity(){
  val directory=CmcDirectory(time,listOf(row(1,"Binance","binance"),row(2,"Binance TR","trbinance"),row(3,"Gate","gate-io"),row(4,"Bybit","bybit"),row(5,"Toobit","toobit")))
  val apps=listOf(
   DiagnosticApp("org.example.fake","Binance: Verified KYC","1",true),
   DiagnosticApp("org.example.regional","Binance TR","1",true),
   DiagnosticApp("org.example.korean","바이비트","1",true),
   DiagnosticApp("org.toobit.fake","Unrelated Label","1",true),
   DiagnosticApp("org.example.tool","Gateway Settings","1",true),
   DiagnosticApp("org.example.cyrillic","Bіnance","1",true))
  val candidates=recognizeExchangeApps(apps,directory)
  assertEquals(4,candidates.size)
  assertEquals(listOf(2),candidates.first{it.app.packageName=="org.example.regional"}.exchanges.map{it.id})
  assertEquals("패키지 이름 일치 후보",candidates.first{it.app.packageName=="org.toobit.fake"}.basis)
  for(c in candidates){assertFalse(c.officialAppVerified);assertEquals("unknown",c.accountIdentity);assertEquals("not_verified",c.kycAuthenticity)}
  assertTrue(candidates.none{it.app.packageName in setOf("org.example.tool","org.example.cyrillic")})
 }
 @Test fun invalidDirectoryCannotReplaceLastGoodCache(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext;val file=File(context.filesDir,"cmc-directory-v1.json")
  val previous=if(file.exists())file.readBytes() else null;val store=CmcDirectoryStore(context)
  try{
   val good=CmcDirectory(time,listOf(row(1)));store.save(good);assertEquals(good.entries,store.load().entries)
   val bytes=file.readBytes()
   assertTrue(runCatching{store.save(CmcDirectory(time,listOf(row(1),row(1))))}.isFailure)
   assertArrayEquals(bytes,file.readBytes())
   file.writeText("corrupt public cache")
   assertEquals("bundled",store.load().origin);assertEquals(2448,store.load().entries.size)
  }finally{if(previous==null)file.delete() else file.writeBytes(previous)}
 }
}
