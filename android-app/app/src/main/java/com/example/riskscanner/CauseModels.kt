package com.example.riskscanner

import android.content.Context
import java.net.URI
import java.time.Instant
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONObject

val causeLabels=linkedMapOf("api_ip" to "API 키 IP 설정 불일치","api_environment" to "API 키·접속 환경 불일치","api_permission" to "API 접근 권한 부족","request_time" to "서명 요청 시간 불일치","request_limit" to "API 요청 한도 초과","regional_access" to "지역 서비스 이용 제한","compliance_unspecified" to "컴플라이언스 제한 · 세부 미공개","trading_unspecified" to "거래 제한 · 세부 미공개","api_trading_lock" to "API 거래 잠금","kyc_followup" to "KYC 추가 확인·미완료","security_cooldown" to "보안 설정 변경 후 출금 대기","deposit_confirmation" to "입금 확인 수 미충족","service_maintenance" to "자산·네트워크 점검","network_connectivity" to "기기 네트워크 연결 문제")
val causeFindingSources=linkedMapOf("exchange_notice" to "거래소 계정 안내를 직접 확인","support_message" to "공식 고객지원 답변을 직접 확인","self_observation" to "조치 후 직접 관찰")
internal fun causeStrings(j:JSONObject,key:String,max:Int=40):List<String>{val a=j.getJSONArray(key);require(a.length()<=max);return (0 until a.length()).map{a.getString(it).also{s->require(s.length<=80)}}.also{require(it.distinct().size==it.size)}}
fun causeExchange(c:PreventionCase):String{
 val slug=c.exchangeInfoUrl.removeSuffix("/").substringAfterLast('/')
 return when{slug=="bybit"||c.exchangeName.equals("Bybit",true)->"bybit";slug=="binance"||c.exchangeName.equals("Binance",true)->"binance";slug=="okx"||c.exchangeName.equals("OKX",true)->"okx";else->"other"}
}
data class CauseEvidence(val code:String,val origin:String,val observedAt:String){
 fun json()=JSONObject().put("code",code).put("origin",origin).put("observedAt",observedAt)
 companion object{fun parse(j:JSONObject)=CauseEvidence(j.getString("code"),j.getString("origin"),j.getString("observedAt")).also{require(it.code.matches(Regex("[a-z0-9_]{1,48}"))&&it.origin in setOf("user_report","device_observation","exchange_api"));Instant.parse(it.observedAt);require(it.observedAt.length<=32&&it.observedAt.endsWith("Z"))}}
}
data class CauseInput(val caseId:String,val exchange:String,val knowledgeVersion:String,val evidence:List<CauseEvidence>){
 fun digest()=preflightHash((listOf(caseId,exchange,knowledgeVersion)+evidence.map{"${it.code}:${it.origin}:${it.observedAt}"}.sorted()).joinToString("|"))
 fun json()=JSONObject().put("caseId",caseId).put("exchange",exchange).put("knowledgeVersion",knowledgeVersion).put("evidence",JSONArray().apply{evidence.forEach{put(it.json())}})
 companion object{fun parse(j:JSONObject):CauseInput{
  val a=j.getJSONArray("evidence");require(a.length()<=40)
  return CauseInput(j.getString("caseId").also{UUID.fromString(it)},j.getString("exchange").also{require(it in setOf("bybit","binance","okx","other"))},j.getString("knowledgeVersion").also{require(it.matches(Regex("[0-9.-]{1,30}")))},(0 until a.length()).map{CauseEvidence.parse(a.getJSONObject(it))}).also{require(it.evidence.map{e->e.code}.distinct().size==it.evidence.size)}
 }}
}
class CauseKnowledge(context:Context){
 val json=JSONObject(context.assets.open("cause-knowledge.json").bufferedReader().use{it.readText()})
 val version=json.getString("version");val reviewedAt=json.getString("reviewedAt");val definitions=json.getJSONObject("evidence")
 val rules=json.getJSONArray("rules").let{a->(0 until a.length()).map{a.getJSONObject(it)}}
 val sources=json.getJSONArray("sources").let{a->(0 until a.length()).map{a.getJSONObject(it)}}
 fun label(code:String)=definitions.optJSONObject(code)?.optString("label")?:code
 fun eligible(input:CauseInput):List<JSONObject>{
  require(input.knowledgeVersion==version)
  input.evidence.forEach{e->val d=definitions.getJSONObject(e.code);require(d.getString("origin")==e.origin&&input.exchange in causeStrings(d,"exchanges"))}
  val codes=input.evidence.map{it.code}.toSet()
  return rules.filter{r->input.exchange in causeStrings(r,"exchanges")&&(0 until r.getJSONArray("groups").length()).any{i->val g=r.getJSONArray("groups").getJSONArray(i);(0 until g.length()).all{g.getString(it) in codes}}}
 }
 fun support(rule:JSONObject,input:CauseInput):List<String>{val codes=input.evidence.map{it.code}.toSet();val a=rule.getJSONArray("groups");return (0 until a.length()).flatMap{i->val g=a.getJSONArray(i);val values=(0 until g.length()).map{g.getString(it)};if(values.all{it in codes})values else emptyList()}.distinct()}
 fun sourceIds(rule:JSONObject,exchange:String)=if(rule.getString("id")=="kyc_followup")listOf(if(exchange=="binance")"binance_kyc" else "bybit_kyc")else causeStrings(rule,"sources")
 fun manualCodes()=definitions.keys().asSequence().filter{definitions.getJSONObject(it).getBoolean("manual")}.toList()
}
fun causeInput(case:PreventionCase,kb:CauseKnowledge,selected:List<String>,account:LinkedExchangeAccount?,now:Instant=Instant.now()):CauseInput{
 val exchange=causeExchange(case);val evidence=mutableListOf<CauseEvidence>()
 fun add(code:String,origin:String,time:String){if(kb.definitions.has(code))evidence.add(CauseEvidence(code,origin,time))}
 add("notice_"+case.noticeType,"user_report",case.createdAt)
 if(exchange=="bybit")Regex("(?<![A-Za-z0-9])100(?:02|03|05|06|09|10|24|27)(?![A-Za-z0-9])").findAll(case.notice).map{it.value}.distinct().forEach{add("bybit_"+it,"user_report",case.createdAt)}
 selected.forEach{require(it in kb.manualCodes());add(it,"user_report",now.toString())}
 case.device?.let{d->if(d.network=="NONE")add("device_offline","device_observation",d.observedAt)else if(d.validated==true)add("device_online","device_observation",d.observedAt)}
 account?.let{a->
  require(a.provider.exchange.equals(exchange,true)){"사건과 선택 계정의 거래소가 다릅니다"}
  val status=requireNotNull(a.status){"계정 조회 결과가 없습니다"};require(status.uid==a.uid&&a.error.isEmpty()&&now.toEpochMilli()-status.checkedAt in 0..300000){"계정 상태를 먼저 새로 조회하세요"}
  val time=Instant.ofEpochMilli(status.checkedAt).toString()
  if(exchange=="binance")status.restrictions?.apiLocked?.let{add(if(it)"api_locked" else "api_unlocked","exchange_api",time)}
  if(exchange=="bybit"){if(status.state=="not_passed_reported"||(status.state=="level_reported"&&status.level=="LEVEL_DEFAULT"))add("api_kyc_followup","exchange_api",time)else if(status.state=="passed_reported"||(status.state=="level_reported"&&status.level in setOf("LEVEL_1","LEVEL_2")))add("api_kyc_level","exchange_api",time)}
 }
 return CauseInput(case.id,exchange,kb.version,evidence).also{kb.eligible(it)}
}
data class CauseReview(val input:CauseInput,val response:String,val localAccountId:String=""){
 val result get()=JSONObject(response)
 val requestId get()=result.getString("requestId")
 val reviewedAt get()=result.getString("reviewedAt")
 val ranked get()=causeStrings(result,"ranked",3)
 fun json()=JSONObject().put("input",input.json()).put("result",result).put("localAccountId",localAccountId)
 companion object{fun parse(j:JSONObject):CauseReview{
  val input=CauseInput.parse(j.getJSONObject("input"));val r=j.getJSONObject("result");val account=j.optString("localAccountId","");if(account.isNotEmpty())UUID.fromString(account)
  require(r.getString("caseId")==input.caseId&&r.getString("inputSha256")==input.digest()&&r.getString("knowledgeVersion")==input.knowledgeVersion&&r.get("actualCauseConfirmed")==false&&r.get("officialVerified")==false)
  UUID.fromString(r.getString("requestId"));Instant.parse(r.getString("reviewedAt"));require(r.getString("model").length<=120)
  val ranked=causeStrings(r,"ranked",3);val eligible=causeStrings(r,"eligible",14);require(eligible.all{it in causeLabels}&&ranked.all{it in eligible}&&ranked.size==minOf(3,eligible.size))
  require(if(ranked.isEmpty())r.getString("source")=="rules_insufficient_evidence"&&r.getString("status")=="insufficient_evidence"&&r.getString("model")=="not_invoked" else r.getString("source")=="ai_cause_hypotheses"&&r.getString("status")=="hypotheses")
  return CauseReview(input,r.toString(),account)
 }}
}
internal fun parseCauseResponse(j:JSONObject,id:String,input:CauseInput,kb:CauseKnowledge,accountId:String=""):CauseReview{
 require(j.getString("requestId")==id)
 val r=CauseReview.parse(JSONObject().put("input",input.json()).put("result",j).put("localAccountId",accountId))
 require(causeStrings(j,"eligible").toSet()==kb.eligible(input).map{it.getString("id")}.toSet())
 return r
}
internal fun requestCauseReview(endpoint:String,token:String,input:CauseInput,kb:CauseKnowledge,accountId:String=""):CauseReview{
 require(token.trim().length>=32);val root=kycEndpoint(endpoint).toString().removeSuffix("/v1/kyc/reviews");val id=UUID.randomUUID().toString()
 val connection=URI(root+"/v1/causes").toURL().openConnection() as HttpsURLConnection
 try{
  connection.requestMethod="POST";connection.instanceFollowRedirects=false;connection.connectTimeout=10000;connection.readTimeout=60000;connection.doOutput=true;connection.setRequestProperty("Authorization","Bearer "+token.trim());connection.setRequestProperty("Content-Type","application/json")
  connection.outputStream.use{it.write(input.json().put("requestId",id).put("consent",true).toString().toByteArray(Charsets.UTF_8))}
  val code=connection.responseCode;check(code==200){when(code){401->"서버 접속 토큰을 확인하세요";400->"앱과 서버의 공식 자료 버전·전송 항목을 확인하세요";404->"원인 분석 기능을 서버에 배포해야 합니다";429->"요청 한도에 도달했습니다. 잠시 후 다시 시도하세요";else->"원인 분석 실패 · HTTP $code"}}
  return parseCauseResponse(JSONObject(connection.inputStream.use{String(it.kycLimitedBytes(131072),Charsets.UTF_8)}),id,input,kb,accountId)
 }finally{connection.disconnect()}
}
/** User-adjudicated follow-up; never an exchange-attested or model-generated training label. */
data class CauseFinding(val id:String=UUID.randomUUID().toString(),val recordedAt:String=Instant.now().toString(),val category:String,val source:String,val reference:String){
 fun json()=JSONObject().put("id",id).put("recordedAt",recordedAt).put("category",category).put("source",source).put("reference",reference).put("userReviewed",true).put("officialVerified",false)
 companion object{fun parse(j:JSONObject)=CauseFinding(j.getString("id").also{UUID.fromString(it)},j.getString("recordedAt").also{Instant.parse(it)},j.getString("category").also{require(it in causeLabels||it in setOf("other","unknown"))},j.getString("source").also{require(it in causeFindingSources)},j.getString("reference").also{require(it.trim().length in 10..1500)}).also{require(j.get("userReviewed")==true&&j.get("officialVerified")==false)}}
}
fun PreventionCase.withCauseReview(review:CauseReview):PreventionCase{
 CauseReview.parse(review.json());require(review.input.caseId==id&&review.input.exchange==causeExchange(this))
 if(causeReviews.any{it.requestId==review.requestId})return this
 require(causeReviews.size<20){"사건당 원인 분석은 20개까지 저장합니다"};return copy(causeReviews=causeReviews+review)
}
fun PreventionCase.withCauseFinding(finding:CauseFinding):PreventionCase{
 CauseFinding.parse(finding.json());require(causeFindings.size<50&&causeFindings.none{it.id==finding.id});return copy(causeFindings=causeFindings+finding)
}
fun causeComparison(case:PreventionCase):String{
 val finding=case.causeFindings.lastOrNull()?:return "사후 확인 기록 대기"
 val cutoff=case.causeFindings.minOf{Instant.parse(it.recordedAt)}
 val prediction=case.causeReviews.filter{Instant.parse(it.reviewedAt).isBefore(cutoff)}.minByOrNull{Instant.parse(it.reviewedAt)}?:return "사후 기록 이전 예측 없음 · 비교 제외"
 if(finding.category=="unknown")return "사유 미확인 · 비교 제외"
 if(finding.source=="self_observation")return "직접 관찰 기록 · 공식 사유 대조에서 제외"
 return if(finding.category in prediction.ranked)"최초 후보에 포함 · 사용자 대조" else "최초 후보에 없음 · 사용자 대조"
}
