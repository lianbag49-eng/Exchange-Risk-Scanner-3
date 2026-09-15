package com.example.riskscanner

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PreventionTest{
 @get:Rule val ui=createComposeRule()
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private val app=DiagnosticApp("com.ers.fixture.binance","Binance","test-1",true)
 private fun storageName()="ers_prevention_test_"+UUID.randomUUID()
 private fun screenshot(name:String){
  ui.waitForIdle();val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
  fun shell(command:String){android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use{it.readBytes()}}
  shell("mkdir -p /sdcard/Download/ers-ui");shell("screencap -p /sdcard/Download/ers-ui/$name.png")
 }
 @Test fun firstCompositionFindsAppAndIncidentSurvivesCloseAndEncryptedReload(){
  val name=storageName();val storage=PreventionStorage(context,name);val initial=storage.load();val calls=AtomicInteger()
  val directory=CmcDirectoryStore(context).load()
  var cases by mutableStateOf(initial);var draft by mutableStateOf<PreventionCase?>(null)
  try{
   ui.setContent{
    MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFD9B56D),onPrimary=Color(0xFF070A09),background=Color(0xFF070A09),surface=Color(0xFF101815)))){Surface{
     val installed=rememberInstalledExchanges(LocalContext.current,Unit,appReader={calls.incrementAndGet();listOf(app)},directoryReader={directory})
     PreventionHome(installed,cases,false,"",onRecord={e,a->draft=PreventionCase(app=a,exchangeName=e.name,exchangeInfoUrl=e.infoUrl)},onInspect={_,_->},onCase={draft=it},onDiscovery={},onAccounts={},onReset={})
     draft?.let{d->val current=cases.find{it.id==d.id}?:d
      PreventionCaseDialog(current,cases.any{it.id==d.id},cases,onSave={next->
       val updated=if(cases.any{it.id==next.id})cases.map{if(it.id==next.id)next else it} else listOf(next)+cases
       storage.save(updated);cases=updated;draft=next
      },onDelete={},onInspect={},close={draft=null})
     }
    }}
   }
   ui.waitUntil(15000){ui.onAllNodes(hasTestTag("home-rescan") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
   assertTrue(calls.get()>=1)
   ui.onNodeWithTag("home-installed-count").assertTextEquals("설치 후보 1개")
   ui.onNodeWithTag("home-identity-boundary").assertExists()
   screenshot("prevention-auto-home")
   val exchange=recognizeExchangeApps(listOf(app),directory).single().exchanges.single()
   ui.onNodeWithTag("prevention-home").performScrollToNode(hasTestTag("record-${app.packageName}-${exchange.id}"))
   ui.onNodeWithTag("record-${app.packageName}-${exchange.id}").performClick()
   ui.onNodeWithTag("save-prevention-case").performScrollTo().assertIsNotEnabled()
   ui.onNodeWithTag("case-notice-type").performScrollTo().performClick()
   ui.onNodeWithTag("case-notice-type-security_hold").performClick()
   ui.onNodeWithTag("case-notice").performScrollTo().performTextInput("TEST ONLY: security review pending")
   ui.onNodeWithTag("save-prevention-case").performScrollTo().performClick()
   ui.onNodeWithTag("case-action").performScrollTo().performTextInput("TEST ONLY: contacted official support")
   ui.onNodeWithTag("case-support").performScrollTo().performTextInput("TEST ONLY: review completed")
   ui.onNodeWithTag("case-outcome").performScrollTo().performClick()
   ui.onNodeWithTag("case-outcome-resolved").performClick()
   ui.onNodeWithTag("save-prevention-update").performScrollTo().performClick()
   ui.onNodeWithTag("case-current-outcome").performScrollTo().assertTextContains("해결됨",substring=true)
   screenshot("prevention-case")
   ui.onNodeWithTag("close-prevention-case").performScrollTo().performClick()
   val restored=PreventionStorage(context,name).load().single()
   assertEquals("resolved",restored.outcome);assertEquals(1,restored.updates.size);assertEquals("",restored.accountLabel)
   assertNotNull(restored.device);assertNotNull(restored.updates.single().device)
   assertFalse(restored.json().getBoolean("actualCauseConfirmed"));assertEquals("unknown",restored.json().getString("accountIdentity"))
   ui.onNodeWithTag("prevention-home").performScrollToNode(hasTestTag("case-${restored.id}"))
   ui.onNodeWithTag("case-${restored.id}").performClick()
   ui.onNodeWithTag("case-current-outcome").performScrollTo().assertTextContains("해결됨",substring=true)
   ui.onNodeWithTag("case-source-boundary").performScrollTo().assertTextContains("실제 판정 원인 미확정",substring=true)
  }finally{context.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear().commit()}
 }
 @Test fun failedRefreshKeepsPreviousObservationAndSuccessfulEmptyScanIsNotAnAccountVerdict()=runBlocking{
  val state=InstalledExchangeState();val directory=CmcDirectoryStore(context).load()
  state.scan({listOf(app)},{directory});assertEquals(1,state.candidates.size);val time=state.checkedAt
  state.scan({error("package query unavailable")},{directory});assertEquals(1,state.candidates.size);assertEquals(time,state.checkedAt);assertTrue(state.error.isNotEmpty());assertFalse(state.scanning)
  state.scan({emptyList()},{directory});assertTrue(state.candidates.isEmpty());assertTrue(state.error.isEmpty())
 }
 @Test fun encryptedReloadAndCorruptionNeverOverwriteEvidence(){
  val name=storageName();val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE)
  try{
   val storage=PreventionStorage(context,name);storage.load()
   val case=PreventionCase(app=app,exchangeName="Binance",notice="sensitive incident text",device=PreventionDevice.capture(context))
   storage.save(listOf(case));val encrypted=prefs.getString("encrypted",null)!!
   assertFalse(encrypted.contains(case.notice));assertFalse(encrypted.contains(app.packageName))
   assertEquals(case,PreventionStorage(context,name).load().single())
   prefs.edit().putString("encrypted","corrupt-existing-evidence").commit()
   assertTrue(runCatching{storage.load()}.isFailure)
   assertTrue(runCatching{storage.save(emptyList())}.isFailure)
   assertEquals("corrupt-existing-evidence",prefs.getString("encrypted",null))
   storage.reset();assertTrue(storage.load().isEmpty())
  }finally{prefs.edit().clear().commit()}
 }
 @Test fun evidenceCannotBecomeVerifiedOrBindToAnotherAppAndUpdatesAreAppendOnly(){
  val case=PreventionCase(app=app,exchangeName="Binance")
  val report=AppDiagnosticReport(UUID.randomUUID().toString(),"a".repeat(64),case.createdAt,"test-fixture",app,"review_available","security_hold",emptyList(),listOf("security_hold"),listOf("official_support"))
  val reviewed=case.withReview(report).withReview(report);assertEquals(1,reviewed.reviews.size)
  assertTrue(runCatching{case.withReview(report.copy(app=app.copy(packageName="com.ers.fixture.other")))}.isFailure)
  assertTrue(runCatching{PreventionCase.parse(case.json().put("actualCauseConfirmed",true))}.isFailure)
  assertTrue(runCatching{PreventionCase.parse(case.json().put("accountIdentity","verified"))}.isFailure)
  val first=PreventionUpdate(action="support contacted",outcome="persists",supportReply="user-supplied response")
  val second=PreventionUpdate(action="observed again",outcome="resolved")
  val updated=case.append(first).append(second)
  assertEquals(listOf(first,second),PreventionCase.parse(updated.json()).updates)
  assertFalse(updated.updates.first().json().getBoolean("supportReplyVerified"))
  assertTrue(runCatching{PreventionUpdate.parse(first.json().put("supportReplyVerified",true))}.isFailure)
  assertTrue(runCatching{case.append(first.copy(outcome="cause_confirmed"))}.isFailure)
  assertTrue(runCatching{updated.append(first)}.isFailure)
 }
}
