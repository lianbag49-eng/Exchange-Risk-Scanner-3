package com.example.riskscanner

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONObject

data class DiagnosticApp(val packageName:String,val label:String,val version:String,val enabled:Boolean) {
 fun json()=JSONObject().put("packageName",packageName).put("label",label).put("version",version).put("enabled",enabled)
 companion object {fun parse(j:JSONObject)=DiagnosticApp(j.getString("packageName"),j.getString("label"),j.getString("version"),j.getBoolean("enabled")).also{require(it.packageName.matches(Regex("[A-Za-z0-9_.]{3,160}"))&&it.label.length in 1..80&&it.version.length<=80)}}
}
@Suppress("DEPRECATION")
fun diagnosticApps(context:Context):List<DiagnosticApp> {
 val pm=context.packageManager
 return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0).mapNotNull { r->
  runCatching{val p=r.activityInfo.packageName;val info=pm.getPackageInfo(p,0);DiagnosticApp(p,r.loadLabel(pm).toString().take(80),(info.versionName?:"알 수 없음").take(80),r.activityInfo.applicationInfo.enabled)}.getOrNull()
 }.distinctBy{it.packageName}.filter{it.packageName!=context.packageName}.sortedBy{it.label.lowercase()}
}
fun diagnosticDevice(context:Context):JSONObject {
 val cm=context.getSystemService(ConnectivityManager::class.java);val caps=cm.getNetworkCapabilities(cm.activeNetwork)
 val type=when{cm.activeNetwork==null->"NONE";caps==null->"UNKNOWN";caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)->"VPN";caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)->"WIFI";caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)->"CELLULAR";caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)->"ETHERNET";else->"OTHER"}
 return JSONObject().put("sdk",Build.VERSION.SDK_INT).put("networkType",type).put("networkValidated",caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?:JSONObject.NULL).put("vpn",cm.allNetworks.any{cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true}).put("proxy",cm.defaultProxy!=null).put("autoTime",Settings.Global.getInt(context.contentResolver,Settings.Global.AUTO_TIME,0)==1)
}
data class MaskRect(val left:Float,val top:Float,val right:Float,val bottom:Float)
fun redactDiagnosticImage(image:ByteArray,masks:List<MaskRect>):ByteArray {
 val source=BitmapFactory.decodeByteArray(image,0,image.size)?:error("이미지를 읽지 못했습니다")
 val bitmap=source.copy(Bitmap.Config.ARGB_8888,true);source.recycle()
 try{val canvas=Canvas(bitmap);val paint=Paint().apply{color=Color.BLACK;style=Paint.Style.FILL}
  masks.forEach { r->require(listOf(r.left,r.top,r.right,r.bottom).all{it.isFinite()&&it in 0f..1f});require(r.right>r.left&&r.bottom>r.top);canvas.drawRect(r.left*bitmap.width-3,r.top*bitmap.height-3,r.right*bitmap.width+3,r.bottom*bitmap.height+3,paint)}
  return diagnosticJpeg(bitmap)
 }finally{bitmap.recycle()}
}
fun diagnosticJpeg(bitmap:Bitmap):ByteArray=ByteArrayOutputStream().use{out->require(bitmap.compress(Bitmap.CompressFormat.JPEG,88,out));out.toByteArray().also{require(it.size<=4*1024*1024){"이미지 크기를 줄여 주세요"}}}
fun likelyBlankCapture(bitmap:Bitmap):Boolean {
 var minimum=255;var maximum=0
 for(y in 0 until 16)for(x in 0 until 16){val p=bitmap.getPixel(x*(bitmap.width-1)/15,y*(bitmap.height-1)/15);val brightness=(Color.red(p)+Color.green(p)+Color.blue(p))/3;minimum=minOf(minimum,brightness);maximum=maxOf(maximum,brightness)}
 return maximum-minimum<6
}
val diagnosticLabels=mapOf(
 "app_disabled" to "선택 앱이 비활성 상태", "network_unvalidated" to "Android가 인터넷 연결을 검증하지 못함", "no_network" to "활성 네트워크 없음", "vpn_present" to "VPN 연결 감지 · 오류 원인 확정 아님", "proxy_present" to "프록시 설정 감지 · 오류 원인 확정 아님", "auto_time_off" to "자동 날짜·시간 꺼짐",
 "connectivity_issue" to "인터넷 연결 문제 가능성", "network_error" to "네트워크 오류 안내", "login_rejected" to "로그인 거절 안내", "kyc_required" to "KYC 확인 필요 안내", "withdrawal_restricted" to "출금 제한 안내", "security_hold" to "보안 제한 안내", "region_restricted" to "지역 이용 제한 안내", "maintenance" to "점검 안내", "update_required" to "업데이트 필요 안내", "rate_limit" to "요청 횟수 제한 안내", "clock_error" to "기기 시간 오류 안내", "unknown" to "명확한 오류 안내를 판독하지 못함",
 "open_app_settings" to "선택 앱의 시스템 설정 확인", "check_network" to "인터넷 연결과 동일 오류 재발 여부 확인", "check_clock" to "자동 날짜·시간 설정 확인", "official_login_recovery" to "거래소 공식 로그인 복구 절차 확인", "official_kyc_page" to "거래소 공식 KYC 상태·요청 자료 확인", "official_restriction_notice" to "공식 출금 제한 사유·해제 조건 확인", "official_support" to "공식 고객지원에 제한 사유 문의", "official_eligibility" to "거래소 공식 지역별 이용 가능 여부 확인", "official_service_status" to "거래소 공식 점검 공지 확인", "official_app_update" to "공식 스토어의 앱 업데이트 확인", "respect_retry_window" to "화면의 재시도 대기 시간 준수", "redact_and_retry" to "민감정보·인증 입력 화면을 제외하고 다시 준비", "provide_clear_error" to "오류 안내가 읽히는 화면 준비", "confirm_with_exchange" to "계정·서버 측 실제 원인은 거래소 공식 확인 필요",
 "redaction_required" to "민감정보 재확인 필요", "review_available" to "확인 근거와 원인 후보", "needs_more_evidence" to "추가 근거 필요")
fun diagnosticText(code:String)=diagnosticLabels[code]?:"확인 필요"
data class AppDiagnosticReport(val requestId:String,val imageSha256:String,val reviewedAt:String,val model:String,val app:DiagnosticApp,val status:String,val screenNotice:String,val facts:List<String>,val candidates:List<String>,val checks:List<String>) {
 fun json()=JSONObject().put("requestId",requestId).put("imageSha256",imageSha256).put("reviewedAt",reviewedAt).put("model",model).put("app",app.json()).put("status",status).put("screenNotice",screenNotice).put("facts",JSONArray(facts)).put("candidates",JSONArray(candidates)).put("checks",JSONArray(checks)).put("actualCauseConfirmed",false).put("officialVerified",false).put("sourcePackageVerified",false)
 companion object {fun parse(j:JSONObject):AppDiagnosticReport {
  require(!j.getBoolean("actualCauseConfirmed")&&!j.getBoolean("officialVerified")&&!j.getBoolean("sourcePackageVerified")){"검증 범위를 확인할 수 없는 응답입니다"}
  val id=j.getString("requestId");UUID.fromString(id);val hash=j.getString("imageSha256");require(hash.matches(Regex("[0-9a-f]{64}")))
  val status=j.getString("status");require(status in listOf("redaction_required","review_available","needs_more_evidence"));val notice=j.getString("screenNotice");require(notice in diagnosticNoticeCodes)
  fun codes(key:String,allowed:Set<String>):List<String>{val a=j.getJSONArray(key);require(a.length()<=20);return (0 until a.length()).map{a.getString(it).also{code->require(code in allowed)}}}
  val facts=codes("facts",setOf("app_disabled","network_unvalidated","no_network","vpn_present","proxy_present","auto_time_off"));val candidates=codes("candidates",diagnosticNoticeCodes-"unknown"+"connectivity_issue");val checks=codes("checks",diagnosticLabels.keys-diagnosticNoticeCodes-facts.toSet()-setOf("connectivity_issue","redaction_required","review_available","needs_more_evidence","app_disabled","network_unvalidated","no_network","vpn_present","proxy_present","auto_time_off"))
  if(status=="redaction_required")require(candidates.isEmpty()&&notice=="unknown")
  return AppDiagnosticReport(id,hash,j.getString("reviewedAt").take(40),j.getString("model").take(80),DiagnosticApp.parse(j.getJSONObject("app")),status,notice,facts,candidates,checks)
 }}
}
private val diagnosticNoticeCodes=setOf("network_error","login_rejected","kyc_required","withdrawal_restricted","security_hold","region_restricted","maintenance","update_required","rate_limit","clock_error","unknown")
private fun diagnosticPost(base:String,token:String,path:String,body:JSONObject):JSONObject {
 require(token.trim().length>=32){"검토 서버 접속 토큰을 입력하세요"};val root=kycEndpoint(base).toString().removeSuffix("/v1/kyc/reviews")
 val connection=URI(root+path).toURL().openConnection() as HttpsURLConnection
 try{connection.requestMethod="POST";connection.instanceFollowRedirects=false;connection.connectTimeout=15000;connection.readTimeout=60000;connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Authorization","Bearer "+token.trim());connection.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8))};val code=connection.responseCode
  if(code!=200)error(when(code){401->"서버 접속 토큰을 확인하세요";429->"요청이 많습니다. 잠시 후 다시 시도하세요";413->"이미지 크기를 줄여 주세요";422->"이 화면은 AI가 검토하지 못했습니다";502->"AI 연결 또는 판독 실패 · 서버 키·모델·이용 한도를 확인하세요";else->"요청 실패 · HTTP $code"})
  return JSONObject(connection.inputStream.use{String(it.kycLimitedBytes(65536),Charsets.UTF_8)})
 }finally{connection.disconnect()}
}
fun diagnosticConnection(base:String,token:String):String {val r=diagnosticPost(base,token,"/v1/connection-check",JSONObject());require(r.getBoolean("providerReachable")&&!r.getBoolean("inferenceTested"));return "API 인증·모델 조회 확인: "+r.getString("model").take(80)+" · 실제 이미지 판독은 아직 미확인"}
fun requestDiagnostic(base:String,token:String,image:ByteArray,app:DiagnosticApp,device:JSONObject):AppDiagnosticReport {
 val id=UUID.randomUUID().toString();val hash=MessageDigest.getInstance("SHA-256").digest(image).joinToString(""){"%02x".format(it)}
 val body=JSONObject().put("requestId",id).put("consent",true).put("app",app.json()).put("device",device).put("image",JSONObject().put("mimeType","image/jpeg").put("data",Base64.encodeToString(image,Base64.NO_WRAP)))
 return AppDiagnosticReport.parse(diagnosticPost(base,token,"/v1/diagnostics",body)).also{require(it.requestId==id&&it.imageSha256==hash&&it.app==app){"검토 대상과 응답이 일치하지 않습니다"}}
}
