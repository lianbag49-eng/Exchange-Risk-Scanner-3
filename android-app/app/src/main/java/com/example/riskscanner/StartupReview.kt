package com.example.riskscanner

import android.content.Context
import java.net.URI
import java.time.Instant
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal const val STARTUP_SCOPE="ers-startup-v1"
internal val startupTopics=linkedMapOf("identity_requirement" to "KYC 요건", "document_quality" to "신분증·촬영 품질", "name_match" to "명의·정보 일치", "security_hold" to "보안 변경 후 대기", "source_of_funds" to "자금 출처 자료", "jurisdiction" to "서비스 지역 조건", "account_security" to "계정 보안", "account_restriction" to "계정 제한 안내", "appeal" to "고객지원·재심사", "official_app" to "공식 앱 배포 안내")
data class StartupTopic(val code:String,val sourceUrl:String)
data class StartupPolicy(val exchangeId:Int,val domain:String,val topics:List<StartupTopic>,val status:String,val reviewedAt:String,val model:String,val cached:Boolean)
internal fun startupSource(url:String,domain:String):Boolean=runCatching{val u=URI(url);u.scheme=="https"&&u.userInfo==null&&u.port==-1&&u.host!=null&&(u.host==domain||u.host.endsWith(".$domain"))&&url.length<=2048}.getOrDefault(false)
internal fun parseStartupPolicy(j:JSONObject,id:String,exchange:CmcExchange):StartupPolicy{
 require(j.getString("requestId")==id&&j.getInt("exchangeId")==exchange.id&&j.getString("slug")==exchange.slug)
 require(j.getString("scope")=="public_exchange_guidance"&&!j.getBoolean("actualCauseConfirmed")&&!j.getBoolean("officialVerified"))
 val domain=j.getString("domain");require(domain.length in 3..253&&domain.matches(Regex("[a-z0-9-]+(?:\\.[a-z0-9-]+)+")))
 val at=j.getString("reviewedAt");Instant.parse(at);val model=j.getString("model");require(model.length in 1..120)
 val rows=j.getJSONArray("topics");require(rows.length()<=10)
 val topics=(0 until rows.length()).map{val row=rows.getJSONObject(it);val code=row.getString("code");val url=row.getString("sourceUrl");require(code in startupTopics&&startupSource(url,domain));StartupTopic(code,url)}
 require(topics.distinctBy{it.code}.size==topics.size)
 val status=j.getString("status");require(status==if(topics.isEmpty())"insufficient_sources" else "public_guidance")
 return StartupPolicy(exchange.id,domain,topics,status,at,model,j.getBoolean("cached"))
}
internal fun startupError(code:String)=when(code){
 "startup_daily_limit"->"오늘 AI 검토 한도 도달 · 미완료 항목은 다음 날 재시도"
 "startup_busy","startup_session_limit"->"검토 서버 혼잡 · 잠시 후 미완료 재시도"
 "startup_session_expired"->"검토 연결 만료 · 미완료 재시도"
 "exchange_not_in_server_directory"->"서버 거래소 목록 갱신 필요 · 기기 점검 결과 유지"
 "directory_unavailable","directory_identity_mismatch","official_site_unavailable"->"거래소 공식 사이트 자료 확보 실패 · 미확인"
 "provider_credentials"->"AI 서버 인증 설정 오류 · 운영자 확인 필요"
 "provider_quota"->"외부 AI 사용 한도 · 운영자 확인 필요"
 "provider_search_configuration"->"AI 검색 모델 설정 확인 필요"
 else->"AI 조회 실패 · 미완료 재시도로 다시 확인"
}
internal interface StartupGateway{
 suspend fun connect()
 suspend fun preflight(reports:List<PreflightReport>):List<AiPreflightReview>
 suspend fun policy(exchange:CmcExchange):StartupPolicy
}
internal class HttpStartupGateway:StartupGateway{
 private var token=""
 private fun post(path:String,body:JSONObject):JSONObject{
  val conn=URI(DEFAULT_AI_SERVER.trimEnd('/')+path).toURL().openConnection() as HttpsURLConnection
  try{
   conn.requestMethod="POST";conn.instanceFollowRedirects=false;conn.connectTimeout=15000;conn.readTimeout=70000;conn.doOutput=true
   conn.setRequestProperty("Content-Type","application/json");if(token.isNotEmpty())conn.setRequestProperty("Authorization","Bearer $token")
   conn.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8))}
   val status=conn.responseCode
   if(status!=200){val code=runCatching{JSONObject(conn.errorStream.use{String(it.kycLimitedBytes(8192),Charsets.UTF_8)}).getString("error")}.getOrDefault("");error(if(status==404)"서버 자동 검토 기능 배포 대기" else startupError(code))}
   return JSONObject(conn.inputStream.use{String(it.kycLimitedBytes(131072),Charsets.UTF_8)})
  }finally{conn.disconnect()}
 }
 override suspend fun connect(){
  val j=post("/v1/startup/session",JSONObject().put("requestId",UUID.randomUUID().toString()).put("consent",true).put("scope",STARTUP_SCOPE))
  require(j.getString("scope")==STARTUP_SCOPE)
  Instant.parse(j.getString("expiresAt"));token=j.getString("token");require(token.matches(Regex("[a-f0-9]{64}")))
 }
 override suspend fun preflight(reports:List<PreflightReport>):List<AiPreflightReview>{
  val id=UUID.randomUUID().toString();return parseAiPreflight(post("/v1/startup/preflight",JSONObject().put("requestId",id).put("consent",true).put("items",JSONArray().apply{reports.forEach{put(it.item())}})),id,reports)
 }
 override suspend fun policy(exchange:CmcExchange):StartupPolicy{
  val id=UUID.randomUUID().toString();return parseStartupPolicy(post("/v1/startup/exchange",JSONObject().put("requestId",id).put("consent",true).put("exchangeId",exchange.id)),id,exchange)
 }
}
internal class StartupConsent(context:Context,name:String="ers_startup_consent"){
 private val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE)
 fun decision():Int=if(prefs.getString("scope","")==STARTUP_SCOPE)prefs.getInt("decision",0) else 0
 fun save(enabled:Boolean){check(prefs.edit().putString("scope",STARTUP_SCOPE).putInt("decision",if(enabled)1 else -1).commit())}
}
/** No fixed exchange list or truncated matches. Cancellation and one failed batch
 * never turn the remaining apps into completed or low-risk reviews. */
internal suspend fun runStartupReview(reports:List<PreflightReport>,exchanges:List<CmcExchange>,gateway:StartupGateway,onReviews:suspend(List<AiPreflightReview>)->Unit,onPolicy:suspend(StartupPolicy)->Unit,onFailure:suspend(String,List<String>,List<Int>)->Unit,onProgress:suspend(String)->Unit){
 if(reports.isEmpty()&&exchanges.isEmpty())return
 try{gateway.connect()}catch(e:CancellationException){throw e}catch(e:Exception){onFailure(e.message?:"자동 AI 연결 실패",reports.map{it.id},exchanges.map{it.id});return}
 for((index,batch) in reports.chunked(50).withIndex()){
  currentCoroutineContext().ensureActive();onProgress("앱 AI 일괄 검토 · ${index+1}/${reports.chunked(50).size} 묶음")
  try{onReviews(gateway.preflight(batch))}catch(e:CancellationException){throw e}catch(e:Exception){onFailure(e.message?:"AI 검토 실패",batch.map{it.id},emptyList())}
 }
 for((index,exchange) in exchanges.distinctBy{it.id}.withIndex()){
  currentCoroutineContext().ensureActive();onProgress("${exchange.name} 공개 안내 검색 · ${index+1}/${exchanges.distinctBy{it.id}.size}")
  try{onPolicy(gateway.policy(exchange))}catch(e:CancellationException){throw e}catch(e:Exception){onFailure(e.message?:"공개 자료 조회 실패",emptyList(),listOf(exchange.id))}
 }
}
