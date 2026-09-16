package com.example.riskscanner

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CauseTest{
 @get:Rule val ui=createComposeRule()
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private val kb get()=CauseKnowledge(context)
 private fun case()=PreventionCase(id="aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",createdAt="2026-09-15T09:00:00Z",app=DiagnosticApp("com.ers.fixture.bybit","Bybit","fixture",true),exchangeName="Bybit",notice="TEST ONLY: API error 10024; private_uid=12345; private_note",noticeType="security_hold")
 private fun review(c:PreventionCase=case(),time:String="2026-09-15T09:01:00Z"):CauseReview{
  val k=kb;val input=causeInput(c,k,emptyList(),null,Instant.parse(time));val eligible=k.eligible(input).map{it.getString("id")}
  val id=UUID.randomUUID().toString();val r=JSONObject().put("requestId",id).put("caseId",c.id).put("inputSha256",input.digest()).put("knowledgeVersion",k.version).put("reviewedAt",time).put("model",if(eligible.isEmpty())"not_invoked" else "synthetic-test-fixture").put("source",if(eligible.isEmpty())"rules_insufficient_evidence" else "ai_cause_hypotheses").put("status",if(eligible.isEmpty())"insufficient_evidence" else "hypotheses").put("actualCauseConfirmed",false).put("officialVerified",false).put("ranked",JSONArray(eligible.take(3))).put("eligible",JSONArray(eligible))
  return parseCauseResponse(r,id,input,k)
 }
 @Test fun outboundSnapshotExcludesRawTextIdentityKeysAndOldAiPredictions(){
  val c=case();val first=review(c);val input=causeInput(c.withCauseReview(first),kb,emptyList(),null)
  assertTrue(input.evidence.any{it.code=="bybit_10024"});assertFalse(input.json().toString().contains("private_"));assertFalse(input.json().toString().contains("12345"));assertFalse(input.json().toString().contains(first.requestId));assertFalse(input.json().toString().contains("compliance_unspecified"))
  val binance=c.copy(exchangeName="Binance",notice="TEST ONLY: 10024")
  assertFalse(causeInput(binance,kb,emptyList(),null).evidence.any{it.code.startsWith("bybit_")})
  assertTrue(kb.eligible(causeInput(c.copy(notice="HTTP 403"),kb,emptyList(),null)).isEmpty())
 }
 @Test fun selectedApiSnapshotRequiresFreshMatchingAccountAndDoesNotMistakeDefaultKycForApproval(){
  val now=Instant.parse("2026-09-15T09:05:00Z");val a=LinkedExchangeAccount(UUID.randomUUID().toString(),AccountProvider.BYBIT,"fixture","fake-api-key","fake-api-secret","1234",ApiKycStatus("1234","LEVEL_DEFAULT","","level_reported","main_account","verified_read_only",now.toEpochMilli()))
  val input=causeInput(case(),kb,emptyList(),a,now);assertTrue(input.evidence.any{it.code=="api_kyc_followup"});assertFalse(input.evidence.any{it.code=="api_kyc_level"});assertFalse(input.json().toString().contains("1234"));assertFalse(input.json().toString().contains("fake-api"))
  assertTrue(runCatching{causeInput(case(),kb,emptyList(),a,now.plusSeconds(301))}.isFailure)
  assertTrue(runCatching{causeInput(case().copy(exchangeName="Binance"),kb,emptyList(),a,now)}.isFailure)
  assertTrue(runCatching{causeInput(case(),kb,emptyList(),a.copy(uid="9999"),now)}.isFailure)
 }
 @Test fun responseBindingRejectsWrongCaseDigestInventedCauseAndOfficialVerdict(){
  val r=review();val result=r.result
  for(patch in listOf(result.toString().let{JSONObject(it).put("caseId",UUID.randomUUID().toString())},JSONObject(result.toString()).put("inputSha256","a".repeat(64)),JSONObject(result.toString()).put("actualCauseConfirmed",true),JSONObject(result.toString()).put("ranked",JSONArray(listOf("document_forgery"))),JSONObject(result.toString()).put("eligible",JSONArray(listOf("api_ip"))).put("ranked",JSONArray(listOf("api_ip"))))){assertTrue(runCatching{parseCauseResponse(patch,r.requestId,r.input,kb)}.isFailure)}
  assertTrue(runCatching{case().copy(id=UUID.randomUUID().toString()).withCauseReview(r)}.isFailure)
  assertEquals(1,case().withCauseReview(r).withCauseReview(r).causeReviews.size)
 }
 @Test fun encryptedHistorySupportsOldRecordsAndExcludesPostFeedbackPredictionsFromComparison(){
  val name="cause_storage_"+UUID.randomUUID();val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE)
  try{
   val c=case();val old=c.json().apply{remove("causeReviews");remove("causeFindings")};assertTrue(PreventionCase.parse(old).causeReviews.isEmpty())
   val first=review(c);val finding=CauseFinding(recordedAt="2026-09-15T09:02:00Z",category="compliance_unspecified",source="support_message",reference="TEST ONLY: official support ticket fixture")
   val updated=c.withCauseReview(first).withCauseFinding(finding).withCauseReview(review(c,"2026-09-15T09:03:00Z"))
   val store=PreventionStorage(context,name);store.load();store.save(listOf(updated));val restored=PreventionStorage(context,name).load().single()
   assertEquals(updated,restored);assertFalse(prefs.getString("encrypted","")!!.contains("support ticket"));assertEquals("최초 후보에 포함 · 사용자 대조",causeComparison(restored));assertFalse(restored.json().getBoolean("actualCauseConfirmed"))
   val correction=finding.copy(id=UUID.randomUUID().toString(),recordedAt="2026-09-15T09:04:00Z",category="api_ip")
   assertEquals("최초 후보에 없음 · 사용자 대조",causeComparison(restored.withCauseFinding(correction)))
   assertEquals("사후 기록 이전 예측 없음 · 비교 제외",causeComparison(c.withCauseFinding(finding).withCauseReview(review(c,"2026-09-15T09:03:00Z"))))
   assertTrue(runCatching{CauseFinding.parse(finding.json().put("officialVerified",true))}.isFailure)
  }finally{prefs.edit().clear().commit()}
 }
 @Test fun hypothesisScreenShowsEvidenceUnknownBoundaryAndSource(){
  val r=review();val k=kb
  ui.setContent{MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFD9B56D),background=Color(0xFF070A09),surface=Color(0xFF101815))){Surface{Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("ERS · 테스트 자료",style=MaterialTheme.typography.titleLarge);CauseReviewView(r,k)}}}}
  ui.onNodeWithTag("cause-boundary").assertTextContains("미확정",substring=true)
  ui.onNodeWithText("반대 단서·충돌").assertExists()
  ui.onNodeWithText("Bybit 공식 API 오류 코드").performScrollTo().assertExists()
  ui.onNodeWithTag("cause-boundary").performScrollTo()
  ui.waitForIdle();val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
  fun shell(s:String){android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(s)).use{it.readBytes()}}
  shell("mkdir -p /sdcard/Download/ers-ui");shell("screencap -p /sdcard/Download/ers-ui/cause-hypotheses.png")
 }
 @Test fun followupRequiresUserReviewAndPreservesProvenance(){
  var c by mutableStateOf(case().withCauseReview(review()))
  ui.setContent{MaterialTheme{CauseDialog(c,onSave={c=it},close={})}}
  ui.onNodeWithTag("cause-tab-2").performClick()
  // The fixed action bar is visible without scrolling the note/history form.
  ui.onNodeWithTag("cause-save-bar").assertIsDisplayed()
  ui.onNodeWithTag("save-cause-finding").assertIsDisplayed().assertIsNotEnabled()
  ui.onNodeWithTag("cause-finding-reference").performScrollTo().performTextInput("TEST ONLY: support says review still pending")
  androidx.test.espresso.Espresso.closeSoftKeyboard()
  ui.waitForIdle()
  ui.onNodeWithTag("save-cause-finding").assertIsDisplayed().assertIsNotEnabled()
  ui.onNodeWithTag("cause-finding-reviewed").performScrollTo().performClick()
  ui.waitForIdle()
  ui.onNodeWithTag("save-cause-finding").assertIsEnabled().assertIsDisplayed().performClick()
  ui.waitUntil(5000){c.causeFindings.size==1}
  ui.onNodeWithTag("cause-comparison").performScrollTo().assertTextEquals("사유 미확인 · 비교 제외")
  ui.onNodeWithTag("save-cause-finding").assertIsDisplayed().assertIsNotEnabled()
  ui.runOnIdle{assertEquals(1,c.causeFindings.size);assertFalse(c.causeFindings.single().json().getBoolean("officialVerified"));assertEquals("unknown",c.causeFindings.single().category)}
 }
}
