package com.example.riskscanner

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/** Exercises only an invented local record; no accounts, uploads or AI calls. */
class CauseWindowTest {
 @get:Rule val ui=createComposeRule()
 @Test fun footerRemainsInsideTheWindowWithAFullSizeHost(){
  val c=PreventionCase(id="bbbbbbbb-cccc-dddd-eeee-ffffffffffff",createdAt="2026-09-15T09:00:00Z",app=DiagnosticApp("com.ers.fixture.bybit","Bybit","fixture",true),exchangeName="Bybit",notice="TEST ONLY: display geometry fixture",noticeType="security_hold")
  ui.setContent{MaterialTheme{Surface(Modifier.fillMaxSize()){CauseDialog(c,onSave={},close={})}}}
  ui.onNodeWithTag("cause-tab-2").performClick()
  ui.waitForIdle()
  try{
   ui.onNodeWithTag("cause-save-bar").assertIsDisplayed()
   ui.onNodeWithTag("save-cause-finding").assertIsDisplayed().assertIsNotEnabled()
  }catch(e:AssertionError){
   val metrics=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
   val bounds=listOf("cause-tab-2","cause-save-bar","save-cause-finding").joinToString("\n"){tag->runCatching{ui.onNodeWithTag(tag).printToString(2)}.getOrElse{tag+": unavailable"}}
   throw AssertionError("Synthetic dialog geometry: screen=${metrics.widthPixels}x${metrics.heightPixels}, density=${metrics.density}\n$bounds",e)
  }
 }
}
