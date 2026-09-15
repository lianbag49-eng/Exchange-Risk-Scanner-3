package com.example.riskscanner

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import javax.net.ssl.HttpsURLConnection

val preflightSignals=linkedMapOf(
 "app_debuggable" to "앱에 디버깅 허용 플래그가 설정됨",
 "no_screen_lock" to "보안 화면 잠금이 설정되지 않음",
 "adb_enabled" to "USB 디버깅이 켜져 있음",
 "security_patch_old" to "보안 패치 날짜가 1년 이상 지남",
 "auto_time_off" to "자동 날짜·시간이 꺼져 있음",
 "app_disabled" to "앱이 비활성 상태",
 "network_unvalidated" to "인터넷 연결 검증 실패",
 "no_network" to "활성 네트워크 없음",
 "kyc_followup" to "연결된 계정 중 API가 KYC 미완료를 보고함",
 "vpn_present" to "VPN 사용 감지 · 이것만으로 위험 판정하지 않음",
 "proxy_present" to "프록시 설정 감지 · 이것만으로 위험 판정하지 않음",
 "user_reported_open_incident" to "이 앱에 미해결 사용자 사건 기록이 있음"
)
val preflightUnknowns=linkedMapOf("app_metadata_missing" to "앱 설정 정보", "device_observation_missing" to "기기 상태 일부", "security_patch_unknown" to "보안 패치 날짜", "official_app_unverified" to "공식 앱 배포자·서명", "account_identity_unknown" to "현재 앱에 로그인된 계정", "exchange_risk_unknown" to "거래소 내부 리스크·제한 사유", "api_status_unknown" to "현재 공식 계정 상태", "latest_version_unknown" to "스토어 최신 앱 버전")
val preflightChecks=linkedMapOf("verify_app_source" to "공식 사이트가 안내하는 앱 배포 경로 확인", "check_security_settings" to "기기 잠금·디버깅 등 보안 설정 확인", "check_network" to "네트워크 연결 상태 확인", "check_clock" to "자동 날짜·시간 설정 확인", "install_updates" to "공식 OS·앱 업데이트 확인", "official_kyc_page" to "공식 계정 KYC 상태·추가 요청 확인", "official_support" to "거래소 고객지원에서 실제 사유 확인", "collect_notice" to "오류가 발생하면 안내 문구·화면 근거 추가")
private val preflightMedium=setOf("no_screen_lock","adb_enabled","security_patch_old","auto_time_off","app_disabled","network_unvalidated","no_network","kyc_followup","user_reported_open_incident")
fun preflightLevel(signals:List<String>,unknowns:List<String>)=when{
 "app_debuggable" in signals->"HIGH"
 signals.any{it in preflightMedium}->"MEDIUM"
 unknowns.any{it in setOf("app_metadata_missing","device_observation_missing")}->"UNKNOWN"
 else->"LOW"
}
fun preflightLevelLabel(level:String)=when(level){"HIGH"->"높음";"MEDIUM"->"주의";"LOW"->"낮음 · 관측된 신호 기준";else->"미확인"}
internal fun preflightHash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
data class PreflightReport(val app:DiagnosticApp,val checkedAt:Long,val signals:List<String>,val unknowns:List<String>,val installer:String,val accountSummary:String){
 val id get()=preflightHash(app.packageName)
 val level get()=preflightLevel(signals,unknowns)
 fun item()=JSONObject().put("id",id).put("signals",JSONArray(signals)).put("unknowns",JSONArray(unknowns))
 fun fingerprint()="$id:${signals.sorted().joinToString(",")}:${unknowns.sorted().joinToString(",")}"
}
internal fun preflightDigest(items:List<PreflightReport>)=preflightHash(items.joinToString("|"){it.fingerprint()})
data class PreflightAccountSummary(val text:String,val needsFollowup:Boolean,val unknown:Boolean)
internal fun summarizePreflightAccounts(accounts:List<LinkedExchangeAccount>,candidate:ExchangeAppCandidate,now:Long,error:Boolean=false):PreflightAccountSummary{
 if(error)return PreflightAccountSummary("계정 연결 기록을 읽지 못함 · 현재 상태 미확인",false,true)
 val names=candidate.exchanges.map{it.name.lowercase(java.util.Locale.ROOT)}.toSet()
 val relevant=accounts.filter{it.provider.exchange.lowercase(java.util.Locale.ROOT) in names}
 if(relevant.isEmpty())return PreflightAccountSummary("공식 계정 미연결 · 현재 KYC 미확인",false,true)
 val current=relevant.filter{it.error.isEmpty()&&it.status!=null&&!apiKycNeedsRefresh(it.status,now)}
 val follow=current.count{a->val status=a.status!!;status.state=="not_passed_reported"||(status.state=="level_reported"&&status.level=="LEVEL_DEFAULT")}
 val unknown=current.size<relevant.size||current.any{it.status!!.state=="unknown"}
 return PreflightAccountSummary("이 거래소에 연결된 계정 ${relevant.size}개 · 최신 응답 ${current.size}개 · KYC 추가 확인 $follow개"+(if(unknown)" · 미확인 항목 있음" else "")+"\n현재 앱 로그인과 동일한 계정인지는 미확인",follow>0,unknown)
}
@Suppress("DEPRECATION")
internal fun collectPreflight(context:Context,candidate:ExchangeAppCandidate,cases:List<PreventionCase>,accounts:List<LinkedExchangeAccount>,accountReadError:Boolean=false):PreflightReport{
 val app=candidate.app;val now=System.currentTimeMillis();val signals=mutableListOf<String>();val unknown=mutableListOf("official_app_unverified","account_identity_unknown","exchange_risk_unknown","latest_version_unknown")
 val info=runCatching{context.packageManager.getApplicationInfo(app.packageName,0)}.getOrNull()
 if(info==null)unknown.add("app_metadata_missing") else if(info.flags and ApplicationInfo.FLAG_DEBUGGABLE!=0)signals.add("app_debuggable")
 if(!app.enabled)signals.add("app_disabled")
 val device=runCatching{diagnosticDevice(context)}.getOrNull()
 if(device==null)unknown.add("device_observation_missing") else{
  if(device.optString("networkType")=="NONE")signals.add("no_network")
  if(!device.isNull("networkValidated")&&!device.getBoolean("networkValidated"))signals.add("network_unvalidated")
  if(device.getBoolean("vpn"))signals.add("vpn_present")
  if(device.getBoolean("proxy"))signals.add("proxy_present")
  if(!device.getBoolean("autoTime"))signals.add("auto_time_off")
 }
 fun observe(read:()->Boolean,flag:String){val result=runCatching{read()}.getOrNull();if(result==null)unknown.add("device_observation_missing") else if(result)signals.add(flag)}
 observe({!context.getSystemService(KeyguardManager::class.java).isDeviceSecure},"no_screen_lock")
 observe({Settings.Global.getInt(context.contentResolver,Settings.Global.ADB_ENABLED)==1},"adb_enabled")
 val patchAge=runCatching{ChronoUnit.DAYS.between(LocalDate.parse(Build.VERSION.SECURITY_PATCH),LocalDate.now()).also{require(it>=0)}}.getOrNull()
 if(patchAge==null)unknown.add("security_patch_unknown") else if(patchAge>=365)signals.add("security_patch_old")
 val installer=runCatching{if(Build.VERSION.SDK_INT>=30)context.packageManager.getInstallSourceInfo(app.packageName).installingPackageName else context.packageManager.getInstallerPackageName(app.packageName)}.getOrNull()?.take(160)?:"미확인"
 val summary=summarizePreflightAccounts(accounts,candidate,now,accountReadError)
 if(summary.needsFollowup)signals.add("kyc_followup");if(summary.unknown)unknown.add("api_status_unknown")
 if(cases.any{it.app.packageName==app.packageName&&it.outcome!="resolved"})signals.add("user_reported_open_incident")
 return PreflightReport(app,now,signals.distinct(),unknown.distinct(),installer,summary.text)
}

data class AiPreflightReview(val id:String,val level:String,val focus:List<String>,val checks:List<String>,val reviewedAt:String,val model:String,val fingerprint:String)
internal fun parseAiPreflight(json:JSONObject,requestId:String,reports:List<PreflightReport>):List<AiPreflightReview>{
 require(json.getString("requestId")==requestId&&json.getString("inputSha256")==preflightDigest(reports))
 require(json.getString("source")=="ai_preflight"&&json.getString("riskScope")=="observed_technical_signals"&&!json.getBoolean("actualCauseConfirmed")&&!json.getBoolean("officialVerified"))
 val time=json.getString("reviewedAt");Instant.parse(time);val model=json.getString("model");require(model.length in 1..120)
 val rows=json.getJSONArray("reviews");require(rows.length()==reports.size);val seen=mutableSetOf<String>()
 return (0 until rows.length()).map{i->val row=rows.getJSONObject(i);val id=row.getString("id");require(seen.add(id));val report=reports.single{it.id==id};require(row.getString("level")==report.level)
  fun codes(key:String,allowed:Set<String>):List<String>{val a=row.getJSONArray(key);require(a.length()<=20);return (0 until a.length()).map{a.getString(it).also{v->require(v in allowed)}}.also{require(it.distinct().size==it.size)}}
  val focus=codes("focus",report.signals.toSet());val checks=codes("checks",preflightChecks.keys);require(checks.isNotEmpty()&&checks.size<=8&&(report.signals.isEmpty()||focus.isNotEmpty())&&("app_debuggable" !in report.signals||"app_debuggable" in focus))
  AiPreflightReview(id,report.level,focus,checks,time,model,report.fingerprint())
 }
}
internal fun requestAiPreflight(base:String,token:String,reports:List<PreflightReport>):List<AiPreflightReview>{
 require(token.trim().length>=32&&reports.size in 1..50)
 val root=kycEndpoint(base).toString().removeSuffix("/v1/kyc/reviews")
 val id=UUID.randomUUID().toString();val body=JSONObject().put("requestId",id).put("consent",true).put("items",JSONArray().apply{reports.forEach{put(it.item())}})
 val connection=URI(root+"/v1/preflight").toURL().openConnection() as HttpsURLConnection
 try{connection.requestMethod="POST";connection.instanceFollowRedirects=false;connection.connectTimeout=10000;connection.readTimeout=60000;connection.doOutput=true;connection.setRequestProperty("Authorization","Bearer "+token.trim());connection.setRequestProperty("Content-Type","application/json");connection.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8))}
  val code=connection.responseCode;check(code==200){when(code){401->"AI 서버 접속 토큰을 확인하세요";404->"서버에 사진 없는 AI 사전 검토 기능을 먼저 배포해야 합니다";429->"AI 요청 한도 · 5분 후 다시 시도합니다";else->"AI 사전 검토 실패 · HTTP $code"}}
  return parseAiPreflight(JSONObject(connection.inputStream.use{String(it.kycLimitedBytes(131072),Charsets.UTF_8)}),id,reports)
 }finally{connection.disconnect()}
}
