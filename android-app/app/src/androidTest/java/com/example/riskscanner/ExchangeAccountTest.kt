package com.example.riskscanner

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExchangeAccountTest {
 private val now=1700000000000L
 private fun account(p:AccountProvider=AccountProvider.BYBIT)=LinkedExchangeAccount("11111111-2222-3333-4444-555555555555",p,"Synthetic account","ers_test_read_key","ers_test_secret_only","123456789")
 private fun bybit(readOnly:Any=1,level:Any="LEVEL_1",uid:String="123456789")=JSONObject().put("retCode",0).put("result",JSONObject().put("readOnly",readOnly).put("userID",uid).put("kycLevel",level).put("kycRegion","KR").put("isMaster",true))
 private fun toobit(kyc:Any=true,uid:String="123456789")=JSONObject().put("code",200).put("data",JSONObject().put("list",JSONArray().put(JSONObject().put("uid",uid).put("kycResult",kyc))))
 private fun fails(kind:String,block:()->Unit){try{block();fail("Expected $kind")}catch(e:AccountAuditError){assertEquals(kind,e.kind)}}

 @Test fun signedReadRequestsHaveKnownSignaturesAndFixedDestinations(){
  // Expected HMAC values calculated independently with Python hashlib/hmac.
  val b=signedAccountAudit(account(),now)
  assertEquals("https://api.bybit.com/v5/user/query-api",b.url)
  assertEquals("6ccec238a7b95020811458e23deaf89868938ba4d3c7d7c53d8742183b57aa75",b.headers["X-BAPI-SIGN"])
  assertEquals("1700000000000",b.headers["X-BAPI-TIMESTAMP"])
  assertEquals("5000",b.headers["X-BAPI-RECV-WINDOW"])
  val t=signedAccountAudit(account(AccountProvider.TOOBIT_AFFILIATE),now)
  assertEquals("https://api.toobit.com/api/v1/agent/inviteUserList?pageIndex=1&pageSize=100&uid=123456789&recvWindow=5000&timestamp=1700000000000&signature=7c556b87ab95f17890e4ed866cc0380c9fb573cb19fffe61cc27735d3caef1a2",t.url)
  assertEquals(mapOf("X-BB-APIKEY" to "ers_test_read_key"),t.headers)
  for(url in listOf("http://api.bybit.com/v5/user/query-api","https://other.example/v5/user/query-api","https://api.bybit.com/v5/order/create","https://api.bybit.com@other.example/v5/user/query-api")){
   assertTrue(runCatching{accountAuditHttp(AccountAuditRequest(AccountProvider.BYBIT,url,emptyMap()))}.isFailure)
  }
  assertFalse(account().toString().contains(account().apiKey));assertFalse(t.toString().contains("signature="))
 }

 @Test fun officialResponsesRequireReadOnlyAndCorrectUidWithoutAuthenticityClaims(){
  val a=account();val s=parseAccountAudit(a,bybit(),now)
  assertEquals("level_reported",s.state);assertEquals("verified_read_only",s.permissionCheck)
  assertEquals("not_checked",s.json().getString("documentAuthenticity"))
  assertEquals(s,ApiKycStatus.parse(s.json()))
  for(permission in listOf(0,"1",JSONObject.NULL))fails("write_key"){parseAccountAudit(a,bybit(permission),now)}
  fails("uid_mismatch"){parseAccountAudit(a,bybit(uid="987654321"),now)}
  assertEquals("unknown",parseAccountAudit(a,bybit(level="FUTURE_LEVEL"),now).state)
  assertEquals("unknown",parseAccountAudit(a,bybit(level=JSONObject.NULL),now).state)
  fails("api_rejected"){parseAccountAudit(a,JSONObject().put("retCode",10003),now)}
  fails("rate_limit"){parseAccountAudit(a,JSONObject().put("retCode",10006),now)}
  val malformed=bybit();malformed.getJSONObject("result").remove("isMaster")
  fails("invalid_response"){queryExchangeAccount(a,now){malformed}}
  fails("invalid_response"){queryExchangeAccount(a,now){throw IllegalStateException(a.secret)}}
  assertTrue(runCatching{ApiKycStatus.parse(s.json().put("documentAuthenticity","verified"))}.isFailure)
  assertFalse(apiKycNeedsRefresh(s,now+299999));assertTrue(apiKycNeedsRefresh(s,now+300000));assertTrue(apiKycNeedsRefresh(s,now-1))
 }

 @Test fun affiliateResultsCannotApplyToAnotherAccountOrTurnUnknownIntoPass(){
  val a=account(AccountProvider.TOOBIT_AFFILIATE)
  val passed=parseAccountAudit(a,toobit(),now)
  assertEquals("passed_reported",passed.state);assertEquals("invited_account",passed.scope)
  assertEquals("user_declared_read_only",passed.permissionCheck)
  val unpassed=parseAccountAudit(a,toobit(false),now)
  assertEquals("not_passed_reported",unpassed.state);assertTrue(apiKycLabel(unpassed).contains("심사 중/거절 구분 미제공"))
  for(missing in listOf(JSONObject.NULL,"true",1))assertEquals("unknown",parseAccountAudit(a,toobit(missing),now).state)
  fails("uid_mismatch"){parseAccountAudit(a,toobit(uid="987654321"),now)}
  val empty=toobit();empty.getJSONObject("data").put("list",JSONArray())
  fails("uid_not_found"){parseAccountAudit(a,empty,now)}
  fails("api_rejected"){parseAccountAudit(a,JSONObject().put("code",-1219),now)}
  fails("rate_limit"){parseAccountAudit(a,JSONObject().put("code",-1003),now)}
  assertTrue(runCatching{signedAccountAudit(a.copy(uid="0"),now)}.isFailure)
  assertTrue(runCatching{signedAccountAudit(a.copy(uid="123&uid=0"),now)}.isFailure)
 }

 @Test fun credentialsAreEncryptedAndCorruptionDoesNotOverwriteExistingData(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val prefs=context.getSharedPreferences("ers_exchange_connections",0);val before=prefs.getString("encrypted",null)
  val storage=ExchangeAccountStorage(context)
  try{
   val a=account().copy(status=parseAccountAudit(account(),bybit(),now),lastAttempt=now)
   storage.save(listOf(a));assertEquals(listOf(a),storage.load())
   val encoded=prefs.getString("encrypted",null)!!;val blob=Base64.decode(encoded,Base64.NO_WRAP)
   val raw=String(blob,Charsets.ISO_8859_1)
   for(privateValue in listOf(a.apiKey,a.secret,a.uid,a.alias))assertFalse(raw.contains(privateValue))
   assertTrue(runCatching{storage.save(listOf(a.copy(uid="987654321")))}.isFailure)
   assertEquals(encoded,prefs.getString("encrypted",null))
   blob[blob.lastIndex]=(blob.last().toInt() xor 1).toByte()
   val tampered=Base64.encodeToString(blob,Base64.NO_WRAP);prefs.edit().putString("encrypted",tampered).commit()
   assertTrue(runCatching{storage.load()}.isFailure);assertEquals(tampered,prefs.getString("encrypted",null))
   storage.save(emptyList());assertTrue(storage.load().isEmpty())
  }finally{prefs.edit().apply{if(before==null)remove("encrypted") else putString("encrypted",before)}.commit()}
 }
}
