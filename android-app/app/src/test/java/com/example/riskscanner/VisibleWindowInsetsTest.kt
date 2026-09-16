package com.example.riskscanner

import org.junit.Assert.assertEquals
import org.junit.Test

class VisibleWindowInsetsTest {
 @Test fun fullNativeViewportNeedsNoExtraPadding(){assertEquals(VisibleClip(),visibleClip(320,640,0,0,320,640))}
 @Test fun measuredButClippedFooterUsesActualVisibleArea(){assertEquals(VisibleClip(bottom=72),visibleClip(320,640,0,0,320,568))}
 @Test fun keyboardAndLandscapeInsetsAreNotHardcoded(){assertEquals(VisibleClip(left=24,top=15,right=48,bottom=260),visibleClip(1080,720,24,15,1032,460))}
 @Test fun transientDetachedOrInvalidFramesDoNotProduceNegativePadding(){
  assertEquals(VisibleClip(),visibleClip(0,0,0,0,0,0))
  assertEquals(VisibleClip(),visibleClip(320,640,20,20,10,10))
  assertEquals(VisibleClip(),visibleClip(320,640,-10,-10,330,650))
 }
}
