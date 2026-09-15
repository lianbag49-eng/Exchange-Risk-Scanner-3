package com.example.riskscanner

import java.net.URI
import java.time.Instant
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

data class IdentitySubject(val id:String,val label:String,val decision:String,val checkedAt:String,val providerStatus:String="",val verificationLevel:String="",val reviewId:String="",val reviewDate:String="",val reasons:List<String> = emptyList(),val applicantComment:String="",val documentAuthenticity:String="not_separately_reported",val error:String="")
data class IdentityResult(val status:String,val checkedAt:String,val subjects:List<IdentitySubject>)
internal fun parseIdentityResult(j:JSONObject,requestId:String):IdentityResult{
 require(j.getString("requestId")==requestId&&j.getString("source")=="identity_gateway")
 val status=j.getString("status");require(status in setOf("queried","not_configured","no_authorized_subjects"))
 val checkedAt=j.getString("checkedAt");Instant.parse(checkedAt)
 val rows=j.getJSONArray("subjects");require(rows.length()<=20&&(status=="queried"||rows.length()==0));val seen=mutableSetOf<String>()
 val subjects=(0 until rows.length()).map{i->val r=rows.getJSONObject(i);val id=r.getString("id");UUID.fromString(id);require(seen.add(id))
  require(r.getString("source")=="sumsub_api"&&r.getString("scope")=="independent_identity_review"&&r.getString("exchangeAccountBinding")=="unverified"&&r.get("exchangeKycVerified")==false&&r.get("actualExchangeCauseConfirmed")==false)
  fun str(key:String,max:Int)=r.optString(key,"").also{require(it.length<=max)}
  val label=str("label",80);require(label.isNotBlank());val time=r.getString("checkedAt");Instant.parse(time)
  val decision=r.getString("decision");require(decision in setOf("approved","rejected","resubmission","pending","unknown","unavailable"))
  if(decision=="unavailable")IdentitySubject(id,label,decision,time,error=str("error",80))
  else{
   val ps=str("providerStatus",40);if(decision in setOf("approved","rejected","resubmission"))require(ps=="completed")
   val list=r.getJSONArray("reasons");require(list.length()<=20);val reasons=(0 until list.length()).map{list.getString(it).also{v->require(v.matches(Regex("[A-Z0-9_]{1,80}")))}};require(reasons.distinct().size==reasons.size)
   require(decision in setOf("rejected","resubmission")||reasons.isEmpty())
   val doc=r.getString("documentAuthenticity");val expected=if("FORGERY" in reasons)"provider_reported_forgery" else if("GRAPHIC_EDITOR" in reasons)"provider_reported_edit" else "not_separately_reported";require(doc==expected)
   IdentitySubject(id,label,decision,time,ps,str("verificationLevel",120),str("reviewId",120),str("reviewDate",80),reasons,str("applicantComment",1000),doc)
  }
 }
 return IdentityResult(status,checkedAt,subjects)
}
internal fun requestIdentityResult(endpoint:String,token:String):IdentityResult{
 require(token.trim().length>=32);val root=kycEndpoint(endpoint).toString().removeSuffix("/v1/kyc/reviews");val id=UUID.randomUUID().toString()
 val connection=URI(root+"/v1/identity/status").toURL().openConnection() as HttpsURLConnection
 try{
  connection.requestMethod="POST";connection.instanceFollowRedirects=false;connection.connectTimeout=10000;connection.readTimeout=60000;connection.doOutput=true;connection.setRequestProperty("Authorization","Bearer "+token.trim());connection.setRequestProperty("Content-Type","application/json")
  connection.outputStream.use{it.write(JSONObject().put("requestId",id).put("consent",true).toString().toByteArray(Charsets.UTF_8))}
  val code=connection.responseCode;check(code==200){when(code){401->"서버 접속 토큰을 확인하세요";404->"신원 검증 조회 기능을 서버에 배포해야 합니다";429->"요청 한도에 도달했습니다. 잠시 후 다시 조회하세요";else->"신원 검증 결과 조회 실패 · HTTP $code"}}
  return parseIdentityResult(JSONObject(connection.inputStream.use{String(it.kycLimitedBytes(131072),Charsets.UTF_8)}),id)
 }finally{connection.disconnect()}
}
fun identityDecisionLabel(value:String)=when(value){"approved"->"검증업체 보고: 승인";"rejected"->"검증업체 보고: 거절";"resubmission"->"검증업체 보고: 재제출 요청";"pending"->"검증업체 심사 진행 중";"unavailable"->"조회 실패 · 현재 결과 미확인";else->"미확인"}
fun identityReasonLabel(value:String)=when(value){"FORGERY"->"위변조 사유 보고";"GRAPHIC_EDITOR"->"이미지 편집 사유 보고";"UNSATISFACTORY_PHOTOS"->"사진 품질 관련 사유";else->"업체 사유 코드: $value"}
