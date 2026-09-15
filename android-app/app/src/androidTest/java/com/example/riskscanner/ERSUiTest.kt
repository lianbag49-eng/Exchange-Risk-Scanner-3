package com.example.riskscanner
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File
class ERSUiTest {
 @get:Rule val ui=createAndroidComposeRule<MainActivity>()
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
  val catalog=ExchangeCatalog.load(ui.activity);assertEquals(53,catalog.size);assertEquals((1..50).toList(),catalog.take(50).map{it.rank})
  catalog.filter{it.logo!=null}.forEach{val b=Base64.decode(it.logo,Base64.DEFAULT);assertNotNull(BitmapFactory.decodeByteArray(b,0,b.size))}
  screenshot("home")
  ui.onNodeWithTag("nav-1").performClick()
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
  ui.onNodeWithTag("kyc-endpoint").assertExists()
  ui.onNodeWithTag("kyc-run").performScrollTo().assertIsNotEnabled()
  screenshot("ai-kyc")
  ui.onNodeWithText("닫기",useUnmergedTree=true).performClick()
  ui.onNodeWithTag("open-app-diagnostic").performScrollTo().performClick()
  ui.onNodeWithTag("diagnostic-app-search").assertExists()
  ui.onNodeWithTag("diagnostic-capture").performScrollTo().assertIsNotEnabled()
  ui.onNodeWithTag("run-app-diagnostic").performScrollTo().assertIsNotEnabled()
  ui.onNodeWithTag("diagnostic-check-connection").performScrollTo().assertIsNotEnabled()
  screenshot("app-diagnostic")
  ui.onNodeWithTag("close-app-diagnostic").performScrollTo().performClick()
  ui.onNodeWithContentDescription("결과 닫기").performClick()
  ui.onNodeWithTag("nav-0").performClick()
  ui.onNodeWithContentDescription("Tapbit 로고").assertExists();screenshot("home-account")
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
