package com.example.riskscanner

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

class StartupTest{
 @get:Rule val ui=createComposeRule()
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private fun report(i:Int)=PreflightReport(DiagnosticApp("org.example.exchange$i","Exchange $i","fixture",true),System.currentTimeMillis(),listOf("auto_time_off"),listOf("account_identity_unknown","exchange_risk_unknown"),"fixture","unconnected")
 private fun review(r:PreflightReport)=AiPreflightReview(r.id,r.level,r.signals,listOf("check_clock"),Instant.now().toString(),"mock-only",r.fingerprint())
 private fun policy(e:CmcExchange)=StartupPolicy(e.id,"example.com",listOf(StartupTopic("appeal","https://example.com/help")),"public_guidance",Instant.now().toString(),"mock-only",false)
 @Test fun allRegisteredExchangesAndMoreThanFiftyAppsAreProcessedDespiteOneFailure()=runBlocking{
  val exchanges=CmcDirectoryStore(context).load().entries;assertEquals(2448,exchanges.size)
  val sizes=mutableListOf<Int>();val researched=mutableListOf<Int>();val done=mutableListOf<String>();val failed=mutableListOf<String>();var connected=0
  val gateway=object:StartupGateway{
   override suspend fun connect(){connected++}
   override suspend fun preflight(reports:List<PreflightReport>):List<AiPreflightReview>{sizes.add(reports.size);if(sizes.size==2)error("fixture failed batch");return reports.map(::review)}
   override suspend fun policy(exchange:CmcExchange):StartupPolicy{researched.add(exchange.id);return this@StartupTest.policy(exchange)}
  }
  runStartupReview((0 until 121).map(::report),exchanges,gateway,onReviews={done.addAll(it.map{r->r.id})},onPolicy={},onFailure={_,ids,_->failed.addAll(ids)},onProgress={})
  assertEquals(1,connected);assertEquals(listOf(50,50,21),sizes);assertEquals(71,done.size);assertEquals(50,failed.size);assertEquals(exchanges.map{it.id},researched)
 }
 @Test fun cancellationStopsRequestsAndNoAppsNeedsNoConnection()=runBlocking{
  var calls=0;val gateway=object:StartupGateway{
   override suspend fun connect(){calls++}
   override suspend fun preflight(reports:List<PreflightReport>):List<AiPreflightReview>{calls++;throw CancellationException("background")}
   override suspend fun policy(exchange:CmcExchange):StartupPolicy{calls++;return this@StartupTest.policy(exchange)}
  }
  runStartupReview(emptyList(),emptyList(),gateway,{},{},{_,_,_->},{})
  assertEquals(0,calls)
  try{runStartupReview((0..100).map(::report),CmcDirectoryStore(context).load().entries,gateway,{},{},{_,_,_->fail("cancellation must propagate")},{ });fail("expected cancellation")}catch(_:CancellationException){}
  assertEquals(2,calls)
 }
 @Test fun publicSourcesAndExchangeIdentityAreBoundAndNeverPromoteKycClaims(){
  val exchange=CmcDirectoryStore(context).load().entries.first();val id=UUID.randomUUID().toString()
  fun data()=JSONObject().put("requestId",id).put("exchangeId",exchange.id).put("slug",exchange.slug).put("scope","public_exchange_guidance").put("actualCauseConfirmed",false).put("officialVerified",false).put("domain","example.com").put("topics",JSONArray().put(JSONObject().put("code","appeal").put("sourceUrl","https://support.example.com/help"))).put("status","public_guidance").put("reviewedAt",Instant.now().toString()).put("model","mock").put("cached",false)
  assertEquals(exchange.id,parseStartupPolicy(data(),id,exchange).exchangeId)
  for(patch in listOf(data().put("exchangeId",999999),data().put("officialVerified",true),data().put("actualCauseConfirmed",true),data().put("status","verified"),data().put("domain","attacker.test")))assertTrue(runCatching{parseStartupPolicy(patch,id,exchange)}.isFailure)
  assertFalse(startupSource("https://example.com.attacker.test/help","example.com"));assertFalse(startupSource("https://user:pass@example.com","example.com"))
 }
 @Test fun freshInstallAsksOnceThenAutomaticallyReviewsEveryDetectedExchangeWithoutTokens(){
  val directory=CmcDirectoryStore(context).load();val catalog=ExchangeCatalog.load(context)
  val names=listOf("Toobit","CoinW","BingX","Upbit","Bitget","Binance")
  val targets=names.map{name->directory.entries.single{it.name==name}}
  val apps=targets.map{DiagnosticApp("org.example.fixture${it.id}",it.name,"fixture",true)}
  val prefName="ers_startup_test_"+UUID.randomUUID();val store=StartupConsent(context,prefName)
  val calls=java.util.concurrent.atomic.AtomicInteger();val policies=java.util.Collections.synchronizedList(mutableListOf<Int>())
  val gateway=object:StartupGateway{
   override suspend fun connect(){calls.incrementAndGet()}
   override suspend fun preflight(reports:List<PreflightReport>):List<AiPreflightReview>{calls.incrementAndGet();return reports.map(::review)}
   override suspend fun policy(exchange:CmcExchange):StartupPolicy{policies.add(exchange.id);return this@StartupTest.policy(exchange)}
  }
  try{
   assertEquals(0,store.decision())
   ui.setContent{MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFD9B56D))){Surface{
    val ctx=LocalContext.current;val installed=rememberInstalledExchanges(ctx,Unit,{apps},{directory})
    val preflight=rememberPreflight(ctx,installed,emptyList(),false,0,useLegacyAi=false)
    val startup=rememberStartup(ctx,installed,preflight,false,store,{gateway})
    PreventionHome(installed,emptyList(),false,"",{_,_->},{_,_->},{},{},{},{},catalog,preflight,startup=startup)
   }}}
   ui.onNodeWithTag("startup-consent-accept").assertExists();assertEquals(0,calls.get())
   ui.onNodeWithTag("startup-consent-accept").performClick()
   ui.waitUntil(20000){policies.size==targets.size}
   assertEquals(1,store.decision());assertEquals(targets.map{it.id}.toSet(),policies.toSet());assertEquals(2,calls.get())
   ui.onNodeWithTag("startup-ai-count").assertTextEquals("기기 점검 6/6 · AI 검토 6/6")
   screenshot("startup-complete")
   ui.onNodeWithTag("prevention-home").performScrollToNode(hasTestTag("startup-open-coverage"))
   ui.onNodeWithTag("startup-open-coverage").performClick()
   ui.onNodeWithTag("startup-coverage-count").assertTextEquals("전체 2448개 · 검색 2448개")
   ui.onNodeWithTag("startup-coverage-search").performTextInput("Toobit")
   ui.onNodeWithTag("startup-coverage-count").assertTextEquals("전체 2448개 · 검색 1개")
   screenshot("startup-coverage")
   assertEquals(2,calls.get())
  }finally{context.getSharedPreferences(prefName,0).edit().clear().commit()}
 }
 private fun screenshot(name:String){ui.waitForIdle();val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
  for(command in listOf("mkdir -p /sdcard/Download/ers-ui","screencap -p /sdcard/Download/ers-ui/$name.png"))android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use{it.readBytes()}
 }
}
