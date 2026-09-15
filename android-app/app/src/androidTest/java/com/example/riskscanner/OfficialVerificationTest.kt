package com.example.riskscanner

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.net.URI
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OfficialVerificationTest{
 @get:Rule val ui=createComposeRule()
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private val id="11111111-2222-3333-4444-555555555555"
 private val now=1700000000000L
 private fun account()=LinkedExchangeAccount(id,AccountProvider.BINANCE,"Synthetic Binance","ers_test_read_key","ers_test_secret_only","123456789")
 private fun permissions()=JSONObject().put("enableReading",true).apply{for(k in listOf("enableWithdrawals","enableInternalTransfer","enableMargin","enableFutures","permitsUniversalTransfer","enableVanillaOptions","enableSpotAndMarginTrading","enableFixApiTrade","enablePortfolioMarginTrading"))put(k,false)}
 private fun transport(r:AccountAuditRequest)=when(URI(r.url).path){
  "/sapi/v1/account/apiRestrictions"->permissions()
  "/api/v3/account"->JSONObject().put("uid",123456789L).put("canTrade",true).put("canDeposit",true).put("canWithdraw",false).put("balances",JSONArray().put(JSONObject().put("asset","NEVER_STORE_BALANCES")))
  "/sapi/v1/account/status"->JSONObject().put("data","Synthetic account notice")
  "/sapi/v1/account/apiTradingStatus"->JSONObject().put("data",JSONObject().put("isLocked",true).put("plannedRecoverTime",0).put("updateTime",now).put("triggerCondition",JSONObject().put("GCR",150)))
  else->error("Unexpected path")
 }
 private fun fails(kind:String,block:()->Unit){try{block();fail("Expected failure")}catch(e:AccountAuditError){assertEquals(kind,e.kind)}}
 private fun screenshot(name:String){ui.waitForIdle();val automation=InstrumentationRegistry.getInstrumentation().uiAutomation;for(command in listOf("mkdir -p /sdcard/Download/ers-ui","screencap -p /sdcard/Download/ers-ui/$name.png"))android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use{it.readBytes()}}
 @Test fun binanceRequiresReadOnlyKeysAndOnlyAllowsSignedReadEndpoints(){
  val request=signedBinanceAudit(account(),"/sapi/v1/account/apiRestrictions",now)
  assertTrue(request.url.endsWith("signature=37cf7446f9f623e5ad556365bab5c0e1937097f610c8a3dbb96b537b4931a0ba"));assertEquals(account().apiKey,request.headers["X-MBX-APIKEY"])
  assertFalse(request.toString().contains("signature="));requireBinanceReadOnly(permissions())
  for(key in listOf("enableWithdrawals","enableInternalTransfer","enableSpotAndMarginTrading","enableUnknownWrite"))fails("write_key"){requireBinanceReadOnly(permissions().put(key,true))}
  val incomplete=permissions();incomplete.remove("enableWithdrawals");fails("write_key"){requireBinanceReadOnly(incomplete)}
  assertTrue(runCatching{signedBinanceAudit(account(),"/sapi/v1/capital/withdraw/apply",now)}.isFailure)
  assertTrue(runCatching{accountAuditHttp(AccountAuditRequest(AccountProvider.BINANCE,"https://api.binance.com/sapi/v1/capital/withdraw/apply",emptyMap()))}.isFailure)
 }
 @Test fun officialRestrictionsBindUidAndNeverBecomeKycOrHiddenCause(){
  val paths=mutableListOf<String>();val report=queryExchangeAccount(account(),now){paths.add(URI(it.url).path);transport(it)}
  assertEquals(binanceAuditPaths.toList(),paths);assertEquals("unknown",report.state);assertEquals("not_checked",report.json().getString("documentAuthenticity"))
  assertEquals(true,report.restrictions!!.apiLocked);assertTrue(report.restrictions!!.requiresFollowup);assertFalse(report.restrictions!!.json().getBoolean("actualCauseConfirmed"))
  assertFalse(report.json().toString().contains("NEVER_STORE_BALANCES"));assertEquals(report,ApiKycStatus.parse(report.json()))
  fails("uid_mismatch"){queryExchangeAccount(account().copy(uid="987654321"),now,::transport)}
  val legacy=ApiKycStatus("123456789","LEVEL_1","KR","level_reported","main_account","verified_read_only",now);val j=legacy.json();j.remove("restrictions");assertEquals(legacy,ApiKycStatus.parse(j))
  assertTrue(runCatching{ApiRestrictions.parse(report.restrictions!!.json().put("actualCauseConfirmed",true))}.isFailure)
 }
 @Test fun failedRestrictionLookupsStayUnknownAndDoNotEraseUid(){
  val report=queryExchangeAccount(account(),now){if(URI(it.url).path in setOf("/sapi/v1/account/status","/sapi/v1/account/apiTradingStatus"))throw AccountAuditError("http_error","Synthetic timeout") else transport(it)}
  assertEquals("123456789",report.uid);assertNull(report.restrictions!!.apiLocked);assertEquals("",report.restrictions!!.accountStatus);assertEquals(2,report.restrictions!!.unavailable.size)
  fails("rate_limit"){queryExchangeAccount(account(),now){if(URI(it.url).path.endsWith("apiTradingStatus"))throw AccountAuditError("rate_limit","limit") else transport(it)}}
  try{auditApiError(AccountProvider.BYBIT,"10009")}catch(e:AccountAuditError){assertTrue(e.message!!.contains("지역"));assertFalse(e.message!!.contains("IP 차단"))}
 }
 private fun identityRow()=JSONObject().put("id",id).put("label","Synthetic identity").put("source","sumsub_api").put("scope","independent_identity_review").put("decision","approved").put("providerStatus","completed").put("checkedAt","2026-09-15T00:00:00Z").put("reasons",JSONArray()).put("documentAuthenticity","not_separately_reported").put("exchangeAccountBinding","unverified").put("exchangeKycVerified",false).put("actualExchangeCauseConfirmed",false)
 private fun identityResponse(row:JSONObject)=JSONObject().put("requestId",id).put("source","identity_gateway").put("status","queried").put("checkedAt","2026-09-15T00:00:00Z").put("subjects",JSONArray().put(row))
 @Test fun identityDecisionsRemainProviderScopedAndBoundToTheRequest(){
  assertEquals("approved",parseIdentityResult(identityResponse(identityRow()),id).subjects.single().decision)
  for(row in listOf(identityRow().put("exchangeKycVerified",true),identityRow().put("actualExchangeCauseConfirmed",true),identityRow().put("providerStatus","pending"),identityRow().put("documentAuthenticity","genuine"),identityRow().put("reasons",JSONArray().put("FORGERY"))))assertTrue(runCatching{parseIdentityResult(identityResponse(row),id)}.isFailure)
  assertTrue(runCatching{parseIdentityResult(identityResponse(identityRow()),UUID.randomUUID().toString())}.isFailure)
  val old=identityRow().put("decision","pending").put("providerStatus","pending");assertEquals("pending",parseIdentityResult(identityResponse(old),id).subjects.single().decision)
 }
 @Test fun previousAiConsentKeepsTokenButRequiresConfirmationForNewRestrictionCodes(){
  val name="ers_scope_migration_"+UUID.randomUUID();val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE);val store=AutoPreflightConfigStore(context,name)
  try{
   val original=AutoPreflightConfig("https://example.invalid","synthetic-token-".repeat(3),true);store.save(original)
   val key=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.getKey("ers_auto_preflight_v1",null) as SecretKey
   val blob=Base64.decode(prefs.getString("encrypted",null),Base64.NO_WRAP);val decrypt=Cipher.getInstance("AES/GCM/NoPadding");decrypt.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,blob.copyOfRange(0,12)))
   val j=JSONObject(String(decrypt.doFinal(blob.copyOfRange(12,blob.size)),Charsets.UTF_8));j.remove("scopeVersion")
   val encrypt=Cipher.getInstance("AES/GCM/NoPadding");encrypt.init(Cipher.ENCRYPT_MODE,key);prefs.edit().putString("encrypted",Base64.encodeToString(encrypt.iv+encrypt.doFinal(j.toString().toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)).commit()
   assertFalse(store.load().enabled);assertEquals(original.token,store.load().token);store.save(original);assertTrue(store.load().enabled)
  }finally{prefs.edit().clear().commit()}
 }
 @Test fun officialScreenRequiresSeparateConsentAndKeepsAccountConnectionAvailable(){
  ui.setContent{MaterialTheme(colorScheme=darkColorScheme()){OfficialVerificationDialog(onAccounts={},close={})}}
  ui.onNodeWithTag("identity-scope").assertExists()
  ui.onNodeWithTag("query-identity").performScrollTo().assertIsNotEnabled()
  ui.onNodeWithTag("identity-token").performScrollTo().performTextInput("synthetic-only-token-".repeat(3))
  ui.onNodeWithTag("query-identity").performScrollTo().assertIsNotEnabled()
  ui.onNodeWithTag("identity-consent").performScrollTo().performClick()
  ui.onNodeWithTag("query-identity").performScrollTo().assertIsEnabled()
  // Do not perform an actual identity or network lookup in a UI test.
  ui.onNodeWithTag("verification-connect-account").performScrollTo().assertIsEnabled()
 }
 @Test fun restrictionUiShowsReportedLockAndUnknownActualCause(){
  val value=queryExchangeAccount(account(),now,::transport).restrictions!!
  ui.setContent{MaterialTheme(colorScheme=darkColorScheme()){Surface{Column{RestrictionsView(value)}}}}
  ui.onNodeWithTag("official-api-lock").assertTextContains("잠김 보고",substring=true)
  ui.onNodeWithTag("official-cause-boundary").assertTextContains("비공개 심사 사유·KYC 진위는 미확인",substring=true)
  screenshot("official-restrictions")
 }
 @Test fun approvedProviderResultIsNotDisplayedAsAuthenticExchangeKyc(){
  val result=parseIdentityResult(identityResponse(identityRow()),id)
  ui.setContent{MaterialTheme(colorScheme=darkColorScheme()){Surface{Column{IdentityResultView(result)}}}}
  ui.onNodeWithTag("identity-decision-$id").assertTextEquals("검증업체 보고: 승인")
  ui.onNodeWithText("문서 진위 단독 결과: 미제공·미확인").assertExists()
  ui.onNodeWithText("거래소 계정과의 동일성·기존 KYC 연결: 미확인").assertExists()
  screenshot("official-verification")
 }
}
