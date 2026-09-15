package com.example.riskscanner

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.net.URI
import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONObject

const val CMC_DIRECTORY_SOURCE="https://pro-api.coinmarketcap.com/public-api/v1/exchange/map"
data class CmcExchange(val id:Int,val name:String,val slug:String,val status:String){
 val infoUrl:String get()="https://coinmarketcap.com/exchanges/$slug/"
 fun json()=JSONObject().put("id",id).put("name",name).put("slug",slug).put("status",status)
 companion object {fun parse(j:JSONObject)=CmcExchange(j.getInt("id"),j.getString("name"),j.getString("slug").lowercase(Locale.ROOT),j.getString("status")).also{
  require(it.id>0&&it.name.isNotBlank()&&it.name.length<=160&&it.name.none{c->c.isISOControl()})
  require(it.slug.matches(Regex("[a-z0-9][a-z0-9-]{0,159}"))&&it.status in setOf("active","inactive","untracked"))
 }}
}
data class CmcDirectory(val checkedAt:String,val entries:List<CmcExchange>,val origin:String="bundled"){
 fun json()=JSONObject().put("source",CMC_DIRECTORY_SOURCE).put("checkedAt",checkedAt).put("complete",true).put("entries",JSONArray().apply{entries.forEach{put(it.json())}})
 fun needsRefresh(now:Long)=runCatching{val age=now-Instant.parse(checkedAt).toEpochMilli();age<0||age>=86400000}.getOrDefault(true)
 companion object {fun parse(j:JSONObject,origin:String="bundled"):CmcDirectory {
  require(j.getString("source")==CMC_DIRECTORY_SOURCE&&j.getBoolean("complete"))
  val time=j.getString("checkedAt");Instant.parse(time)
  val rows=j.getJSONArray("entries");require(rows.length() in 1..50000)
  val entries=(0 until rows.length()).map{CmcExchange.parse(rows.getJSONObject(it))}
  require(entries.map{it.id}.toSet().size==entries.size)
  return CmcDirectory(time,entries,origin)
 }}
}
class CmcDirectoryStore(context:Context){
 private val appContext=context.applicationContext
 private val cache=AtomicFile(File(context.filesDir,"cmc-directory-v1.json"))
 fun load():CmcDirectory=runCatching{cache.openRead().use{CmcDirectory.parse(JSONObject(String(it.kycLimitedBytes(10_000_000),Charsets.UTF_8)),"cache")}}.getOrElse{
  appContext.assets.open("cmc-directory.json").bufferedReader().use{CmcDirectory.parse(JSONObject(it.readText()))}
 }
 fun save(directory:CmcDirectory){
  val data=CmcDirectory.parse(directory.json()).json().toString().toByteArray(Charsets.UTF_8)
  val output=cache.startWrite()
  try{output.write(data);cache.finishWrite(output)}catch(e:Exception){cache.failWrite(output);throw e}
 }
}
internal fun directoryUrl(start:Int,limit:Int):String{
 require(start>=1&&limit in 1..5000)
 return "$CMC_DIRECTORY_SOURCE?listing_status=active,inactive,untracked&start=$start&limit=$limit&sort=id&aux=is_active,status"
}
internal fun directoryHttp(url:String):JSONObject{
 val uri=URI(url);require(uri.scheme=="https"&&uri.host=="pro-api.coinmarketcap.com"&&uri.path=="/public-api/v1/exchange/map"&&uri.port==-1&&uri.userInfo==null&&uri.fragment==null)
 val connection=uri.toURL().openConnection() as HttpsURLConnection
 try{
  connection.requestMethod="GET";connection.instanceFollowRedirects=false;connection.connectTimeout=10000;connection.readTimeout=15000;connection.setRequestProperty("Accept","application/json")
  if(connection.responseCode==429)throw IllegalStateException("CMC 요청 한도 · 잠시 후 다시 갱신하세요")
  check(connection.responseCode==200){"CMC 목록 조회 실패 · HTTP ${connection.responseCode}"}
  return JSONObject(connection.inputStream.use{String(it.kycLimitedBytes(2_000_000),Charsets.UTF_8)})
 }finally{connection.disconnect()}
}
internal fun fetchCmcDirectory(pageSize:Int=5000,transport:(String)->JSONObject=::directoryHttp):CmcDirectory {
 require(pageSize in 1..5000)
 val entries=mutableListOf<CmcExchange>();val seen=mutableSetOf<Int>();var start=1;var checked=""
 repeat(10){
  val j=transport(directoryUrl(start,pageSize));val status=j.getJSONObject("status")
  check(status.getInt("error_code")==0){"CMC가 목록 갱신을 거부했습니다"}
  if(checked.isEmpty()){checked=status.getString("timestamp");Instant.parse(checked)}
  val rows=j.getJSONArray("data");require(rows.length()<=pageSize)
  for(i in 0 until rows.length()){
   val e=CmcExchange.parse(rows.getJSONObject(i));check(seen.add(e.id)){"CMC 목록 페이지가 중복되어 갱신을 중단했습니다"};entries.add(e)
  }
  if(rows.length()<pageSize){check(entries.isNotEmpty()){ "CMC 목록이 비어 있어 기존 목록을 유지합니다" };return CmcDirectory(checked,entries,"live")}
  start+=rows.length()
 }
 error("CMC 목록을 끝까지 받지 못했습니다. 기존 목록을 유지합니다")
}
fun cmcStatus(status:String)=when(status){"active"->"CMC 활성";"inactive"->"CMC 비활성";"untracked"->"CMC 미추적";else->"CMC 상태 미확인"}
private val localeAliases=mapOf(
 "binance" to listOf("바이낸스"),"coinbase-exchange" to listOf("coinbase"),"gdax" to listOf("coinbase"),"upbit" to listOf("업비트"),"bithumb" to listOf("빗썸"),"bitget" to listOf("비트겟"),"bybit" to listOf("바이비트"),"okx" to listOf("okex","오케이엑스"),"gate-io" to listOf("gate.io","gate","게이트"),"htx" to listOf("huobi"),"toobit" to listOf("투빗"),"tapbit" to listOf("탭비트"),"ourbit" to listOf("아워비트"),"deepcoin" to listOf("딥코인"),"coinw" to listOf("코인더블유"),"bingx" to listOf("빙엑스"),"crypto-com-exchange" to listOf("crypto.com"),"bitstamp" to listOf("bitstamp"))
internal fun discoveryWords(s:String)=Normalizer.normalize(s,Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+")," ").trim().replace(Regex("\\s+")," ")
data class ExchangeAppCandidate(val app:DiagnosticApp,val exchanges:List<CmcExchange>,val basis:String){
 val officialAppVerified:Boolean get()=false
 val kycAuthenticity:String get()="not_verified"
 val accountIdentity:String get()="unknown"
}
internal fun recognizeExchangeApps(apps:List<DiagnosticApp>,directory:CmcDirectory):List<ExchangeAppCandidate>{
 val aliases=directory.entries.map{e->e to (listOf(e.name,e.slug.replace('-',' '))+localeAliases[e.slug].orEmpty()).map(::discoveryWords).filter{it.isNotBlank()}.distinct()}
 return apps.distinctBy{it.packageName}.mapNotNull{app->
  val label=discoveryWords(app.label)
  val matches=aliases.mapNotNull{(e,words)->val score=words.filter{w->label==w||(w.length>=3&&label.startsWith("$w "))}.maxOfOrNull{it.length};if(score==null)null else e to score}
  if(matches.isNotEmpty()){
   val best=matches.maxOf{it.second};ExchangeAppCandidate(app,matches.filter{it.second==best}.map{it.first},"앱 이름 일치 후보")
  }else{
   val parts=app.packageName.lowercase(Locale.ROOT).split('.')
   val matching=aliases.filter{(_,words)->words.any{it.length>=4&&!it.contains(' ')&&it in parts}}.map{it.first}
   if(matching.isEmpty())null else ExchangeAppCandidate(app,matching,"패키지 이름 일치 후보")
  }
 }
}
