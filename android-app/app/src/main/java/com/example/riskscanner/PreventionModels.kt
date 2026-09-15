package com.example.riskscanner

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

val preventionNotices=linkedMapOf("security_hold" to "리스크·보안 제한", "login_rejected" to "로그인 거절", "kyc_required" to "KYC 추가 확인", "withdrawal_restricted" to "출금 제한", "network_error" to "네트워크 오류", "region_restricted" to "지역 이용 제한", "rate_limit" to "재시도 대기", "unknown" to "기타·사유 안내 없음")
val preventionOutcomes=linkedMapOf("pending" to "확인 중", "persists" to "동일 현상 지속", "resolved" to "해결됨 · 사용자 확인", "unknown" to "결과 미확인")

/** Direct observations at collection time, never a reconstruction of a past incident. */
data class PreventionDevice(val observedAt:String,val sdk:Int,val network:String,val validated:Boolean?,val vpn:Boolean,val proxy:Boolean,val autoTime:Boolean){
 fun json()=JSONObject().put("source","device_observation").put("observedAt",observedAt).put("sdk",sdk).put("network",network).put("validated",validated?:JSONObject.NULL).put("vpn",vpn).put("proxy",proxy).put("autoTime",autoTime)
 companion object{
  fun capture(context:Context):PreventionDevice{val j=diagnosticDevice(context);return PreventionDevice(Instant.now().toString(),j.getInt("sdk"),j.getString("networkType"),if(j.isNull("networkValidated"))null else j.getBoolean("networkValidated"),j.getBoolean("vpn"),j.getBoolean("proxy"),j.getBoolean("autoTime"))}
  fun parse(j:JSONObject):PreventionDevice{require(j.getString("source")=="device_observation");return PreventionDevice(j.getString("observedAt").also{Instant.parse(it)},j.getInt("sdk").also{require(it in 26..100)},j.getString("network").also{require(it in setOf("NONE","UNKNOWN","VPN","WIFI","CELLULAR","ETHERNET","OTHER"))},if(j.isNull("validated"))null else j.getBoolean("validated"),j.getBoolean("vpn"),j.getBoolean("proxy"),j.getBoolean("autoTime"))}
 }
}

data class PreventionUpdate(val id:String=UUID.randomUUID().toString(),val recordedAt:String=Instant.now().toString(),val action:String,val outcome:String,val supportReply:String="",val device:PreventionDevice?=null){
 fun json()=JSONObject().put("id",id).put("recordedAt",recordedAt).put("source","user_report").put("action",action).put("outcome",outcome).put("supportReply",supportReply).put("supportReplyVerified",false).put("device",device?.json()?:JSONObject.NULL)
 companion object{fun parse(j:JSONObject):PreventionUpdate{
  require(j.getString("source")=="user_report"&&!j.getBoolean("supportReplyVerified"))
  return PreventionUpdate(j.getString("id").also{UUID.fromString(it)},j.getString("recordedAt").also{Instant.parse(it)},j.getString("action").also{require(it.length<=1500)},j.getString("outcome").also{require(it in preventionOutcomes)},j.getString("supportReply").also{require(it.length<=2000)},if(j.isNull("device"))null else PreventionDevice.parse(j.getJSONObject("device")))
 }}
}

data class PreventionCase(val id:String=UUID.randomUUID().toString(),val createdAt:String=Instant.now().toString(),val app:DiagnosticApp,val exchangeName:String,val exchangeInfoUrl:String="",val noticeType:String="unknown",val notice:String="",val occurredAt:String="",val accountLabel:String="",val device:PreventionDevice?=null,val updates:List<PreventionUpdate> = emptyList(),val reviews:List<AppDiagnosticReport> = emptyList(),val causeReviews:List<CauseReview> = emptyList(),val causeFindings:List<CauseFinding> = emptyList()){
 val outcome:String get()=updates.lastOrNull()?.outcome?:"pending"
 fun append(update:PreventionUpdate):PreventionCase{
  require(updates.size<100){"사건당 조치 기록은 100개까지 보관할 수 있습니다"}
  require(updates.none{it.id==update.id})
  PreventionUpdate.parse(update.json())
  return copy(updates=updates+update)
 }
 fun withReview(review:AppDiagnosticReport):PreventionCase{
  require(review.app.packageName==app.packageName){"사건과 AI 검토의 앱이 다릅니다. 해당 앱의 새 사건에 저장하세요"}
  AppDiagnosticReport.parse(review.json())
  if(reviews.any{it.requestId==review.requestId})return this
  require(reviews.size<20){"사건당 AI 검토는 20개까지 보관할 수 있습니다"}
  return copy(reviews=reviews+review)
 }
 fun json()=JSONObject().put("id",id).put("createdAt",createdAt).put("app",app.json()).put("exchangeName",exchangeName).put("exchangeInfoUrl",exchangeInfoUrl).put("noticeType",noticeType).put("notice",notice).put("occurredAt",occurredAt).put("accountLabel",accountLabel).put("noticeSource","user_report").put("accountIdentity","unknown").put("officialAppVerified",false).put("actualCauseConfirmed",false).put("device",device?.json()?:JSONObject.NULL).put("updates",JSONArray().apply{updates.forEach{put(it.json())}}).put("reviews",JSONArray().apply{reviews.forEach{put(it.json())}}).put("causeReviews",JSONArray().apply{causeReviews.forEach{put(it.json())}}).put("causeFindings",JSONArray().apply{causeFindings.forEach{put(it.json())}})
 companion object{fun parse(j:JSONObject):PreventionCase{
  require(j.getString("noticeSource")=="user_report"&&j.getString("accountIdentity")=="unknown"&&!j.getBoolean("officialAppVerified")&&!j.getBoolean("actualCauseConfirmed"))
  val updates=j.getJSONArray("updates");val reviews=j.getJSONArray("reviews");val hypotheses=if(j.has("causeReviews"))j.getJSONArray("causeReviews")else JSONArray();val findings=if(j.has("causeFindings"))j.getJSONArray("causeFindings")else JSONArray();require(updates.length()<=100&&reviews.length()<=20&&hypotheses.length()<=20&&findings.length()<=50)
  return PreventionCase(j.getString("id").also{UUID.fromString(it)},j.getString("createdAt").also{Instant.parse(it)},DiagnosticApp.parse(j.getJSONObject("app")),j.getString("exchangeName").also{require(it.isNotBlank()&&it.length<=160)},j.getString("exchangeInfoUrl").also{require(it.isEmpty()||it.matches(Regex("https://coinmarketcap.com/exchanges/[a-z0-9-]+/?")))},j.getString("noticeType").also{require(it in preventionNotices)},j.getString("notice").also{require(it.length<=2000)},j.getString("occurredAt").also{require(it.length<=80)},j.getString("accountLabel").also{require(it.length<=80)},if(j.isNull("device"))null else PreventionDevice.parse(j.getJSONObject("device")),(0 until updates.length()).map{PreventionUpdate.parse(updates.getJSONObject(it))},(0 until reviews.length()).map{AppDiagnosticReport.parse(reviews.getJSONObject(it))},(0 until hypotheses.length()).map{CauseReview.parse(hypotheses.getJSONObject(it))},(0 until findings.length()).map{CauseFinding.parse(findings.getJSONObject(it))}).also{c->
   require(c.updates.map{it.id}.distinct().size==c.updates.size&&c.reviews.map{it.requestId}.distinct().size==c.reviews.size)
   require(c.reviews.all{it.app.packageName==c.app.packageName})
   require(c.causeReviews.map{it.requestId}.distinct().size==c.causeReviews.size&&c.causeFindings.map{it.id}.distinct().size==c.causeFindings.size)
   require(c.causeReviews.all{it.input.caseId==c.id&&it.input.exchange==causeExchange(c)})
  }
 }}
}

/** Separate from legacy UID records. Failed reads lock writes until an explicit reset. */
class PreventionStorage(context:Context,private val preferenceName:String="ers_prevention"){
 private val prefs=context.getSharedPreferences(preferenceName,Context.MODE_PRIVATE)
 private var readable=false
 private fun key(create:Boolean):SecretKey{
  val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
  (store.getKey("ers_prevention_v1",null) as? SecretKey)?.let{return it}
  check(create){"기록 암호화 키를 찾지 못했습니다"}
  return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{init(KeyGenParameterSpec.Builder("ers_prevention_v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())}.generateKey()
 }
 @Synchronized fun load():List<PreventionCase>{
  readable=false
  val stored=prefs.getString("encrypted",null)?:return emptyList<PreventionCase>().also{readable=true}
  val blob=Base64.decode(stored,Base64.NO_WRAP);require(blob.size in 29..8_000_000)
  val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,blob.copyOfRange(0,12)))
  val plain=cipher.doFinal(blob.copyOfRange(12,blob.size))
  try{
   val data=JSONObject(String(plain,Charsets.UTF_8));require(data.getInt("version")==1)
   val rows=data.getJSONArray("cases");require(rows.length()<=200)
   val cases=(0 until rows.length()).map{PreventionCase.parse(rows.getJSONObject(it))};require(cases.map{it.id}.distinct().size==cases.size)
   readable=true;return cases
  }finally{plain.fill(0)}
 }
 @Synchronized fun save(cases:List<PreventionCase>){
  check(readable){"기록 복구 실패로 저장이 잠겨 있습니다. 기존 기록을 보존합니다"}
  require(cases.size<=200){"사건 기록은 200개까지 보관할 수 있습니다. 불필요한 사건을 삭제하세요"}
  cases.forEach{PreventionCase.parse(it.json())};require(cases.map{it.id}.distinct().size==cases.size)
  val plain=JSONObject().put("version",1).put("cases",JSONArray().apply{cases.forEach{put(it.json())}}).toString().toByteArray(Charsets.UTF_8)
  try{
   require(plain.size<=7_900_000){"저장 공간 한도입니다. 불필요한 사건을 삭제하세요"}
   val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(true));val blob=cipher.iv+cipher.doFinal(plain)
   check(prefs.edit().putString("encrypted",Base64.encodeToString(blob,Base64.NO_WRAP)).commit()){"사건 기록 저장 실패"}
  }finally{plain.fill(0)}
 }
 @Synchronized fun reset(){check(prefs.edit().remove("encrypted").commit()){"기록 삭제 실패"};readable=true}
}
