package com.example.riskscanner
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.Assert.*
import java.io.File
class ERSUiTest {
 @get:Rule val ui=createAndroidComposeRule<MainActivity>()
 @Before fun keepLegacyJourneysOffline(){
  ui.waitForIdle()
  if(ui.onAllNodesWithText("기기 점검만").fetchSemanticsNodes().isNotEmpty())ui.onNodeWithText("기기 점검만").performClick()
 }
 private fun screenshot(name:String){
  ui.waitForIdle()
  InstrumentationRegistry.getInstrumentation().waitForIdleSync()
  android.os.SystemClock.sleep(300)
  val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
  fun shell(command:String){android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use{it.readBytes()}}
  shell("mkdir -p /sdcard/Download/ers-ui")
  shell("screencap -p /sdcard/Download/ers-ui/$name.png")
 }
 @Test fun offlineLogosAndAccountJourney(){
  val catalog=ExchangeCatalog.load(ui.activity);assertTrue(catalog.size>=2449);assertEquals((1..50).toList(),catalog.take(50).map{it.rank})
  catalog.filter{it.logo!=null}.forEach{val b=Base64.decode(it.logo,Base64.DEFAULT);assertNotNull(BitmapFactory.decodeByteArray(b,0,b.size))}
  ui.onNodeWithTag("prevention-home").performScrollToNode(hasTestTag("home-rescan"))
  ui.waitUntil(15000){ui.onAllNodes(hasTestTag("home-rescan") and isEnabled()).fetchSemanticsNodes().size==1}
  ui.onNodeWithTag("prevention-home").assertExists()
  screenshot("home")
  ui.onNodeWithTag("open-exchange-discovery").performClick()
  ui.waitUntil(15000){ui.onAllNodes(hasTestTag("rescan-installed") and isEnabled()).fetchSemanticsNodes().size==1}
  ui.onNodeWithTag("discovery-catalog-count").assertTextContains("CMC 목록",substring=true)
  ui.onNodeWithTag("discovery-no-identity-claim").assertExists()
  screenshot("exchange-discovery")
  ui.onNodeWithText("CMC 전체 목록").performClick()
  ui.onNodeWithTag("cmc-search").performTextInput("Tapbit")
  ui.onNodeWithTag("cmc-entry-1645").assertExists()
  ui.onNodeWithTag("close-discovery").performClick()
  ui.onNodeWithTag("open-account-audit").performClick()
  ui.onNodeWithTag("audit-all").assertIsNotEnabled()
  ui.onNodeWithTag("no-connected-accounts").performScrollTo().assertExists()
  ui.onNodeWithTag("add-api-account").performScrollTo().performClick()
  ui.onNodeWithTag("account-api-key").performScrollTo().assertExists()
  ui.onNodeWithTag("account-api-secret").performScrollTo().assertExists()
  ui.onNodeWithTag("connect-api-account").performScrollTo().assertIsNotEnabled()
  ui.onNodeWithTag("close-account-audit").performScrollTo().performClick()
  ui.onNodeWithTag("nav-1").performClick()
  ui.onNodeWithText("작업자 (선택)").assertDoesNotExist()
  ui.onNodeWithContentDescription("Binance 로고").assertExists()
  screenshot("account")
  ui.onNodeWithText("선택 ›").performClick();screenshot("exchanges")
  ui.onNodeWithText("거래소 검색").performTextInput("Tapbit")
  ui.onNodeWithContentDescription("Tapbit 로고").performClick()
  ui.onNodeWithText("거래소 UID").performScrollTo().performTextInput("123456789")
  ui.onNodeWithText("계정 추가 및 점검").performScrollTo().performClick()
  ui.onNodeWithText("스캔 결과").assertExists();screenshot("result")
  ui.onNodeWithTag("open-kyc-review").performScrollTo().performClick()
  ui.onNodeWithText("AI KYC 검토").assertExists()
  ui.onNodeWithTag("kyc-endpoint").assertTextContains(DEFAULT_AI_SERVER)
  ui.onNodeWithTag("kyc-test-ai").performScrollTo().assertIsNotEnabled()
  ui.onNodeWithTag("kyc-run").performScrollTo().assertIsNotEnabled()
  screenshot("ai-kyc")
  ui.onNodeWithText("닫기",useUnmergedTree=true).performClick()
  ui.onNodeWithTag("open-app-diagnostic").performScrollTo().performClick()
  ui.onNodeWithTag("diagnostic-app-search").assertExists()
  screenshot("app-diagnostic-start")
  ui.onNodeWithTag("diagnostic-capture").performScrollTo().assertIsNotEnabled()
  ui.onNodeWithTag("run-app-diagnostic").performScrollTo().assertIsNotEnabled()
  ui.onNodeWithTag("diagnostic-check-connection").performScrollTo().assertIsNotEnabled()
  ui.onNodeWithTag("diagnostic-endpoint").performScrollTo().assertTextContains(DEFAULT_AI_SERVER)
  ui.onNodeWithTag("diagnostic-test-ai").performScrollTo().assertIsNotEnabled()
  screenshot("ai-connection")
  screenshot("app-diagnostic")
  // Exercise Android's real consent UI against the emulator's Settings app only.
  // No exchange credentials, real user screenshots or external AI requests are involved.
  ui.onNodeWithTag("diagnostic-app-search").performScrollTo().performTextInput("com.android.settings")
  val settingsApp=diagnosticApps(ui.activity).first{it.packageName=="com.android.settings"}
  ui.onNodeWithText("${settingsApp.label} · ${settingsApp.version}\n${settingsApp.packageName}").performScrollTo().performClick()
  val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
  if(android.os.Build.VERSION.SDK_INT>=33)automation.grantRuntimePermission(ui.activity.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
  ui.onNodeWithTag("diagnostic-capture").performScrollTo().performClick()
  automation.serviceInfo=automation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS}
  fun systemClick(text:String):Boolean {
   val nodes=automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text).orEmpty()
   for(node in nodes){var n:android.view.accessibility.AccessibilityNodeInfo?=node;repeat(4){if(n?.isClickable==true)return n!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);n=n?.parent}}
   return false
  }
  fun systemAwait(label:String,condition:()->Boolean){try{ui.waitUntil(10000,condition)}catch(e:Exception){
   screenshot("capture-test-failure")
   val labels=mutableListOf<String>()
   fun visit(n:android.view.accessibility.AccessibilityNodeInfo?){if(n==null)return;if(n.text!=null)labels.add(n.text.toString());for(i in 0 until n.childCount)visit(n.getChild(i))}
   visit(automation.rootInActiveWindow)
   throw AssertionError(label+" · "+labels.joinToString(" | ").take(2000),e)
  }}
  systemAwait("system consent visible"){automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("A single app")?.isNotEmpty()==true||automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/button1")?.isNotEmpty()==true}
  if(systemClick("A single app")){systemAwait("choose entire screen"){systemClick("Entire screen")}}
  systemAwait("confirm screen sharing"){
   automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/button1")?.firstOrNull()?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true||systemClick("Start now")||systemClick("Share screen")||systemClick("Start recording")
  }
  ui.waitUntil(10000){DiagnosticCaptureBus.state.value.active&&DiagnosticCaptureBus.state.value.message.contains("거래소 오류 화면")}
  val nm=ui.activity.getSystemService(android.app.NotificationManager::class.java)
  ui.waitUntil(5000){nm.activeNotifications.any{it.id==81}}
  // The same explicit pending action that the user taps in the foreground notification.
  nm.activeNotifications.first{it.id==81}.notification.actions.first().actionIntent.send()
  ui.waitUntil(10000){!DiagnosticCaptureBus.state.value.active}
  ui.runOnUiThread{ui.activity.startActivity(android.content.Intent(ui.activity,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))}
  ui.onNodeWithTag("diagnostic-image-preview").performScrollTo().assertExists()
  screenshot("app-diagnostic-captured")
  ui.onNodeWithTag("run-app-diagnostic").performScrollTo().assertIsNotEnabled()
  ui.onNodeWithTag("close-app-diagnostic").performScrollTo().performClick()
  ui.onNodeWithContentDescription("결과 닫기").performClick()
  ui.onNodeWithTag("nav-0").performClick()
  ui.onNodeWithTag("prevention-home").assertExists()
  ui.onNodeWithTag("nav-2").performClick()
  ui.onNodeWithContentDescription("Tapbit 로고").assertExists();screenshot("account-history")
  val storage=RecordStorage(ui.activity);val records=storage.load(catalog)
  val review=KycReview("test-review-id","a".repeat(64),"2026-09-15T00:00:00Z","mock-only","review_required","unknown",mapOf("exchange" to "match","uid" to "unreadable","country" to "unreadable"),listOf("uid_unreadable","country_unreadable"))
  assertTrue(records.first().diagnostics.isEmpty())
  val diagnostic=AppDiagnosticReport("11111111-2222-3333-4444-555555555555","b".repeat(64),"2026-09-15T00:00:00Z","mock-only",DiagnosticApp("org.example.exchange","Example Exchange","1.0",true),"review_available","network_error",listOf("vpn_present"),listOf("network_error"),listOf("check_network","confirm_with_exchange"))
  storage.save(listOf(records.first().copy(kycReviews=listOf(review),diagnostics=listOf(diagnostic))))
  assertEquals(review,storage.load(catalog).first().kycReviews.first())
  assertEquals(diagnostic,storage.load(catalog).first().diagnostics.first())
  for(key in listOf("actualCauseConfirmed","officialVerified","sourcePackageVerified"))assertTrue(runCatching{AppDiagnosticReport.parse(diagnostic.json().put(key,true))}.isFailure)
  assertTrue(runCatching{AppDiagnosticReport.parse(diagnostic.json().put("screenNotice","confirmed_ban"))}.isFailure)
  val falseClaim=review.json().put("officialVerified",true)
  assertTrue(runCatching{KycReview.parse(falseClaim)}.isFailure)
 }
 @Test fun unlinkedInstalledAppCanEnterReviewWithoutFabricatingAccountIdentity(){
  ui.onNodeWithTag("open-exchange-discovery").performClick()
  ui.waitUntil(15000){ui.onAllNodes(hasTestTag("rescan-installed") and isEnabled()).fetchSemanticsNodes().size==1}
  ui.onNodeWithTag("discovery-app-list").performScrollToNode(hasTestTag("show-unmatched"))
  ui.onNodeWithTag("show-unmatched").performClick()
  ui.onNodeWithTag("discovery-app-list").performScrollToNode(hasTestTag("assign-exchange-com.android.settings"))
  ui.onNodeWithTag("assign-exchange-com.android.settings").performClick()
  ui.onNodeWithTag("cmc-search").performTextInput("Tapbit")
  // Complete the separate IME transition before touching the result action.
  androidx.test.espresso.Espresso.closeSoftKeyboard()
  ui.waitForIdle()
  ui.onNodeWithText("화면 검토").assertIsDisplayed().performClick()
  ui.waitUntil(5000){ui.onAllNodesWithText("Tapbit 설치 후보 · 계정 미확인 · 오류 화면과 기기 상태 검토").fetchSemanticsNodes().size==1}
  ui.onNodeWithText("Tapbit 설치 후보 · 계정 미확인 · 오류 화면과 기기 상태 검토").assertExists()
  ui.onNodeWithTag("diagnostic-capture").performScrollTo().assertIsEnabled()
  ui.onNodeWithTag("run-app-diagnostic").performScrollTo().assertIsNotEnabled()
  ui.onNodeWithTag("close-app-diagnostic").performScrollTo().performClick()
 }
 @Test fun aiConnectionTestUsesOnlyFixtureAndRejectsFailedInference(){
  assertEquals(DEFAULT_AI_SERVER,reviewServerAddress(null))
  assertEquals(DEFAULT_AI_SERVER,reviewServerAddress("  "))
  assertEquals("https://custom.example/review",reviewServerAddress(" https://custom.example/review "))
  val stages=mutableListOf<String>();var checked=false;var sampleBytes:ByteArray?=null
  val fixtureApp=DiagnosticApp("org.example.ers.connectiontest","ERS Example Exchange","test-fixture",true)
  val sampleReport=AppDiagnosticReport("11111111-2222-3333-4444-555555555555","b".repeat(64),"2026-09-15T00:00:00Z","mock-only",fixtureApp,"review_available","network_error",emptyList(),listOf("network_error"),listOf("check_network","confirm_with_exchange"))
  val result=aiConnectionSelfTest("https://test.invalid","x".repeat(32),check={base,token->assertEquals("https://test.invalid",base);assertEquals(32,token.length);checked=true;"mock authentication"},diagnose={base,token,image,app,device->
   assertTrue(checked);assertEquals("https://test.invalid",base);assertEquals(32,token.length)
   assertEquals(fixtureApp,app);assertEquals(35,device.getInt("sdk"));assertFalse(device.getBoolean("vpn"));assertFalse(device.getBoolean("proxy"));assertTrue(device.getBoolean("autoTime"));assertTrue(device.getBoolean("networkValidated"));assertEquals("WIFI",device.getString("networkType"));assertEquals(6,device.length())
   val bitmap=BitmapFactory.decodeByteArray(image,0,image.size);assertNotNull(bitmap);assertEquals(960,bitmap.width);assertEquals(640,bitmap.height);assertFalse(likelyBlankCapture(bitmap));bitmap.recycle();sampleBytes=image
   sampleReport
  },onProgress={stages.add(it)})
  assertTrue(result.contains("가상 이미지 분석 완료"));assertEquals(2,stages.size);assertTrue(sampleBytes!!.all{it==0.toByte()})
  var invoked=false
  assertTrue(runCatching{aiConnectionSelfTest("https://test.invalid","x".repeat(32),check={_,_->error("rejected token")},diagnose={_,_,_,_,_->invoked=true;sampleReport})}.isFailure)
  assertFalse(invoked)
  assertTrue(runCatching{aiConnectionSelfTest("https://test.invalid","x".repeat(32),check={_,_->"ok"},diagnose={_,_,_,_,_->sampleReport.copy(screenNotice="unknown",status="needs_more_evidence")})}.isFailure)
  assertTrue(runCatching{aiConnectionSelfTest("https://test.invalid","x".repeat(32),check={_,_->"ok"},diagnose={_,_,_,_,_->error("provider unavailable")})}.isFailure)
 }
 @Test fun masksAreFlattenedIntoPixelsAndCaptureDoesNotAcceptForgedConsent(){
  val source=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.WHITE)}
  assertTrue(likelyBlankCapture(source))
  val original=diagnosticJpeg(source);source.recycle()
  val redacted=redactDiagnosticImage(original,listOf(MaskRect(.25f,.25f,.75f,.75f)))
  val result=BitmapFactory.decodeByteArray(redacted,0,redacted.size)
  assertTrue(android.graphics.Color.red(result.getPixel(50,50))<10)
  assertTrue(android.graphics.Color.red(result.getPixel(5,5))>240)
  assertFalse(likelyBlankCapture(result));result.recycle()
  assertTrue(runCatching{redactDiagnosticImage(original,listOf(MaskRect(-1f,0f,1f,1f)))}.isFailure)
  assertTrue(diagnosticApps(ui.activity).any{it.packageName=="com.android.settings"})
  val owner="test-no-user-consent"
  DiagnosticCaptureBus.state.value=DiagnosticCapture(owner,true)
  ui.runOnUiThread {androidx.core.content.ContextCompat.startForegroundService(ui.activity,android.content.Intent(ui.activity,DiagnosticCaptureService::class.java).setAction("start").putExtra("owner",owner).putExtra("result",android.app.Activity.RESULT_OK).putExtra("consent",android.content.Intent()))}
  ui.waitUntil(10000){!DiagnosticCaptureBus.state.value.active}
  assertNull(DiagnosticCaptureBus.state.value.image)
  DiagnosticCaptureBus.clear(owner)
  original.fill(0);redacted.fill(0)
 }
}
