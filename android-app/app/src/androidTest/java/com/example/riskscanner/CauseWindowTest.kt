package com.example.riskscanner

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/** Invented local record only. Logs geometry, never text, account data or images. */
class CauseWindowTest {
 @get:Rule val ui=createComposeRule()
 @Test fun footerRemainsInsideTheWindowWithAFullSizeHost(){
  val c=PreventionCase(id="bbbbbbbb-cccc-dddd-eeee-ffffffffffff",createdAt="2026-09-15T09:00:00Z",app=DiagnosticApp("com.ers.fixture.bybit","Bybit","fixture",true),exchangeName="Bybit",notice="TEST ONLY: display geometry fixture",noticeType="security_hold")
  ui.setContent{MaterialTheme{Surface(Modifier.fillMaxSize()){CauseDialog(c,onSave={},close={})}}}
  ui.onNodeWithTag("cause-tab-2").performClick()
  ui.waitForIdle()
  val geometry=StringBuilder()
  onView(isRoot()).inRoot(isDialog()).check { view,error ->
   if(error!=null)throw error
   fun inspect(v:View,depth:Int){
    if(depth>8)return
    val global=Rect();val visible=v.getGlobalVisibleRect(global);val frame=Rect();v.getWindowVisibleDisplayFrame(frame);val screen=IntArray(2);v.getLocationOnScreen(screen)
    geometry.append("depth=$depth class=${v.javaClass.simpleName} size=${v.width}x${v.height} measured=${v.measuredWidth}x${v.measuredHeight} screen=${screen.joinToString()} shown=${v.isShown} alpha=${v.alpha} visible=$visible global=$global frame=$frame clip=${v.clipBounds}\n")
    if(v is ViewGroup)for(i in 0 until v.childCount)inspect(v.getChildAt(i),depth+1)
   }
   inspect(view,0)
  }
  println("ERS_NATIVE_GEOMETRY\n$geometry")
  try{
   ui.onNodeWithTag("cause-save-bar").assertIsDisplayed()
   ui.onNodeWithTag("save-cause-finding").assertIsDisplayed().assertIsNotEnabled()
  }catch(e:AssertionError){
   val metrics=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
   val bounds=listOf("cause-tab-2","cause-save-bar","save-cause-finding").joinToString("\n"){tag->ui.onNodeWithTag(tag).printToString(2)}
   throw AssertionError("Synthetic dialog geometry: screen=${metrics.widthPixels}x${metrics.heightPixels}, density=${metrics.density}\n$geometry\n$bounds",e)
  }
 }
}
