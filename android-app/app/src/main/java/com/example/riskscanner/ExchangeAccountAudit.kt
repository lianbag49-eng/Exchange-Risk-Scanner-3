package com.example.riskscanner

import java.net.URI
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

enum class AccountProvider(val title:String,val exchange:String,val host:String,val path:String,val docs:String) {
 BYBIT("Bybit · 개인 읽기 API","Bybit","api.bybit.com","/v5/user/query-api","https://bybit-exchange.github.io/docs/v5/user/apikey-info"),
 TOOBIT_AFFILIATE("Toobit · 제휴 계정 API","Toobit","api.toobit.com","/api/v1/agent/inviteUserList","https://api-docs.toobit.com/api/agent.html")
}

data class ApiKycStatus(val uid:String,val level:String,val region:String,val state:String,val scope:String,val permissionCheck:String,val checkedAt:Long) {
 fun json()=JSONObject().put("uid",uid).put("level",level).put("region",region).put("state",state).put("scope",scope).put("permissionCheck",permissionCheck).put("checkedAt",checkedAt).put("source","exchange_api").put("documentAuthenticity","not_checked")
 companion object {
  fun parse(j:JSONObject):ApiKycStatus {
   require(j.getString("source")=="exchange_api"&&j.getString("documentAuthenticity")=="not_checked")
   return ApiKycStatus(j.getString("uid"),j.getString("level"),j.getString("region"),j.getString("state"),j.getString("scope"),j.getString("permissionCheck"),j.getLong("checkedAt")).also {
    require(validExchangeUid(it.uid)&&it.level.length<=40&&it.region.length<=20&&it.checkedAt>0)
    require(it.state in setOf("level_reported","passed_reported","not_passed_reported","unknown"))
    require(it.scope in setOf("main_account","subaccount_api","invited_account"))
    require(it.permissionCheck in setOf("verified_read_only","user_declared_read_only"))
   }
  }
 }
}
data class LinkedExchangeAccount(val id:String,val provider:AccountProvider,val alias:String,val apiKey:String,val secret:String,val uid:String,val status:ApiKycStatus?=null,val lastAttempt:Long=0,val error:String="") {
 override fun toString()="LinkedExchangeAccount(provider=${provider.name}, credentials=redacted)"
 fun json()=JSONObject().put("id",id).put("provider",provider.name).put("alias",alias).put("apiKey",apiKey).put("secret",secret).put("uid",uid).put("status",status?.json()?:JSONObject.NULL).put("lastAttempt",lastAttempt).put("error",error)
 companion object {fun parse(j:JSONObject)=LinkedExchangeAccount(j.getString("id"),AccountProvider.valueOf(j.getString("provider")),j.getString("alias"),j.getString("apiKey"),j.getString("secret"),j.getString("uid"),if(j.isNull("status"))null else ApiKycStatus.parse(j.getJSONObject("status")),j.getLong("lastAttempt"),j.getString("error")).also{
  java.util.UUID.fromString(it.id);validateAccountCredentials(it);require(validExchangeUid(it.uid));require(it.status==null||it.status.uid==it.uid);require(it.error.length<=200)
 }}
}
fun validExchangeUid(uid:String)=uid.matches(Regex("[1-9][0-9]{0,31}"))
fun validateAccountCredentials(account:LinkedExchangeAccount){
 require(account.alias.isNotBlank()&&account.alias.length<=60){"계정 이름을 입력하세요"}
 require(account.apiKey.length in 8..512&&account.secret.length in 8..512&&listOf(account.apiKey,account.secret).all{s->s.all{it.code in 33..126}}){"API Key와 Secret을 확인하세요"}
 require(account.uid.isEmpty()||validExchangeUid(account.uid)){"숫자로 된 거래소 UID를 확인하세요"}
 require(account.provider!=AccountProvider.TOOBIT_AFFILIATE||validExchangeUid(account.uid)){"Toobit에서 확인할 초대 계정 UID가 필요합니다"}
}
internal fun auditHmac(secret:String,value:String)=Mac.getInstance("HmacSHA256").run{init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8),"HmacSHA256"));doFinal(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}}
internal class AccountAuditRequest(val provider:AccountProvider,val url:String,val headers:Map<String,String>) {
 override fun toString()="AccountAuditRequest(provider=${provider.name}, authentication=redacted)"
}
internal fun signedAccountAudit(account:LinkedExchangeAccount,now:Long):AccountAuditRequest {
 validateAccountCredentials(account);require(now>0)
 val p=account.provider
 return when(p){
  AccountProvider.BYBIT->{val time=now.toString();AccountAuditRequest(p,"https://${p.host}${p.path}",mapOf("X-BAPI-API-KEY" to account.apiKey,"X-BAPI-TIMESTAMP" to time,"X-BAPI-RECV-WINDOW" to "5000","X-BAPI-SIGN" to auditHmac(account.secret,time+account.apiKey+"5000")))}
  AccountProvider.TOOBIT_AFFILIATE->{val query="pageIndex=1&pageSize=100&uid=${account.uid}&recvWindow=5000&timestamp=$now";AccountAuditRequest(p,"https://${p.host}${p.path}?$query&signature=${auditHmac(account.secret,query)}",mapOf("X-BB-APIKEY" to account.apiKey))}
 }
}
class AccountAuditError(val kind:String,message:String):Exception(message)
private fun auditApiError(provider:AccountProvider,code:String):Nothing {
 val text=when {
  provider==AccountProvider.TOOBIT_AFFILIATE&&code=="-1219"->"Toobit 제휴 계정 권한이 필요합니다"
  code in setOf("10002","-1021")->"요청 시간이 맞지 않습니다. 휴대폰 자동 날짜·시간을 확인하세요"
  code in setOf("10006","-1003")->"거래소 요청 한도에 도달했습니다. 자동 조회를 중단합니다"
  else->"거래소 API 조회 실패 · 권한·키·IP 허용 목록을 확인하세요"
 }
 throw AccountAuditError(if(code in setOf("10006","-1003"))"rate_limit" else "api_rejected",text)
}
internal fun parseAccountAudit(account:LinkedExchangeAccount,j:JSONObject,now:Long):ApiKycStatus {
 val result=when(account.provider){
  AccountProvider.BYBIT->{
   if(j.opt("retCode")?.toString()!="0")auditApiError(account.provider,j.opt("retCode")?.toString()?:"unknown")
   val r=j.getJSONObject("result");val permission=r.opt("readOnly")
   if(permission !is Number||permission.toDouble()!=1.0)throw AccountAuditError("write_key","읽기 전용 API 키만 연결할 수 있습니다")
   val uid=r.getString("userID");require(validExchangeUid(uid)){"API UID 형식을 확인할 수 없습니다"}
   val raw=r.optString("kycLevel").take(40);val known=raw in setOf("LEVEL_DEFAULT","LEVEL_1","LEVEL_2")
   val main=r.getBoolean("isMaster")
   ApiKycStatus(uid,if(known)raw else "",r.optString("kycRegion").take(20),if(known)"level_reported" else "unknown",if(main)"main_account" else "subaccount_api","verified_read_only",now)
  }
  AccountProvider.TOOBIT_AFFILIATE->{
   if(j.opt("code")?.toString()!="200")auditApiError(account.provider,j.opt("code")?.toString()?:"unknown")
   val list=j.getJSONObject("data").getJSONArray("list")
   if(list.length()!=1)throw AccountAuditError("uid_not_found","해당 UID가 조회되지 않았거나 응답이 일치하지 않습니다. 제휴 관계·조회 권한을 확인하세요")
   val r=list.getJSONObject(0);val uid=r.getString("uid")
   if(uid!=account.uid)throw AccountAuditError("uid_mismatch","연결한 UID와 거래소 응답이 다릅니다. 결과를 적용하지 않았습니다")
   val kyc=r.opt("kycResult")
   ApiKycStatus(uid,"","",when(kyc){true->"passed_reported";false->"not_passed_reported";else->"unknown"},"invited_account","user_declared_read_only",now)
  }
 }
 if(account.uid.isNotEmpty()&&result.uid!=account.uid)throw AccountAuditError("uid_mismatch","연결한 UID와 거래소 응답이 다릅니다. 결과를 적용하지 않았습니다")
 return result
}
internal fun accountAuditHttp(request:AccountAuditRequest):JSONObject {
 val uri=URI(request.url);require(uri.scheme=="https"&&uri.host==request.provider.host&&uri.path==request.provider.path&&uri.userInfo==null&&uri.port==-1&&uri.fragment==null)
 val connection=uri.toURL().openConnection() as HttpsURLConnection
 try{
  connection.requestMethod="GET";connection.instanceFollowRedirects=false;connection.connectTimeout=10000;connection.readTimeout=20000;connection.useCaches=false
  request.headers.forEach{(k,v)->connection.setRequestProperty(k,v)}
  val code=connection.responseCode
  if(code==429)throw AccountAuditError("rate_limit","거래소 요청 한도에 도달했습니다. 자동 조회를 중단합니다")
  if(code!=200)throw AccountAuditError("http_error","거래소 응답 HTTP $code · 연결·권한·공식 API 이용 지역을 확인하세요")
  return JSONObject(connection.inputStream.use{String(it.kycLimitedBytes(65536),Charsets.UTF_8)})
 }catch(e:AccountAuditError){throw e}catch(_:Exception){throw AccountAuditError("connection_failed","거래소 연결 또는 응답 확인에 실패했습니다. 현재 KYC 상태는 미확인입니다")}
 finally{connection.disconnect()}
}
internal fun queryExchangeAccount(account:LinkedExchangeAccount,now:Long=System.currentTimeMillis(),transport:(AccountAuditRequest)->JSONObject=::accountAuditHttp):ApiKycStatus {
 return try{parseAccountAudit(account,transport(signedAccountAudit(account,now)),System.currentTimeMillis())}
 catch(e:AccountAuditError){throw e}catch(_:Exception){throw AccountAuditError("invalid_response","거래소 응답 형식을 확인하지 못했습니다. 현재 KYC 상태는 미확인입니다")}
}
fun apiKycLabel(status:ApiKycStatus)=when(status.state){
 "passed_reported"->"거래소 보고: KYC 통과"
 "not_passed_reported"->"거래소 보고: KYC 미통과 · 심사 중/거절 구분 미제공"
 "level_reported"->"거래소 보고: ${status.level}"
 else->"KYC 정보 미제공 · 미확인"
}
fun apiKycNeedsRefresh(status:ApiKycStatus,now:Long)=now<status.checkedAt||now-status.checkedAt>=300000
