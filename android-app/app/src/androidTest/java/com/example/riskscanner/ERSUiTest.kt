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
  ui.onNodeWithContentDescription("결과 닫기").performClick()
  ui.onNodeWithTag("nav-0").performClick()
  ui.onNodeWithContentDescription("Tapbit 로고").assertExists();screenshot("home-account")
  val storage=RecordStorage(ui.activity);val records=storage.load(catalog)
  val review=KycReview("test-review-id","a".repeat(64),"2026-09-15T00:00:00Z","mock-only","review_required","unknown",mapOf("exchange" to "match","uid" to "unreadable","country" to "unreadable"),listOf("uid_unreadable","country_unreadable"))
  storage.save(listOf(records.first().copy(kycReviews=listOf(review))))
  assertEquals(review,storage.load(catalog).first().kycReviews.first())
  val falseClaim=review.json().put("officialVerified",true)
  assertTrue(runCatching{KycReview.parse(falseClaim)}.isFailure)
 }
}

