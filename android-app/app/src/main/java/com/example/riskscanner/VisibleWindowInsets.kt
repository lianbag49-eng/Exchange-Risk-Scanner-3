package com.example.riskscanner

import android.graphics.Rect
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView

internal data class VisibleClip(val left:Int=0,val top:Int=0,val right:Int=0,val bottom:Int=0)
internal fun visibleClip(width:Int,height:Int,left:Int,top:Int,right:Int,bottom:Int):VisibleClip {
 if(width<=0||height<=0||right<=left||bottom<=top)return VisibleClip()
 return VisibleClip(left.coerceIn(0,width),top.coerceIn(0,height),(width-right).coerceIn(0,width),(height-bottom).coerceIn(0,height))
}

/** Android can report a dialog's measured size larger than its native visible
 * frame while Compose receives consumed/zero system insets. Preserve the native
 * root size and pad only its content; changing root size would create a resize
 * feedback loop. Read this from INSIDE the dialog, not its hosting activity.
 * This observes geometry only; no screens, application contents or identifiers. */
@Composable internal fun rememberVisibleWindowInsets():WindowInsets {
 val view=LocalView.current
 var clip by remember(view){mutableStateOf(VisibleClip())}
 DisposableEffect(view){
  val refresh=Runnable {
   if(view.isAttachedToWindow&&view.width>0&&view.height>0){
    val visible=Rect()
    if(view.getLocalVisibleRect(visible)){
     val frame=Rect();view.getWindowVisibleDisplayFrame(frame)
     val origin=IntArray(2);view.getLocationOnScreen(origin)
     frame.offset(-origin[0],-origin[1])
     if(frame.width()>0&&frame.height()>0&&visible.intersect(frame)){
      clip=visibleClip(view.width,view.height,visible.left,visible.top,visible.right,visible.bottom)
     }
    }
   }
  }
  val listener=ViewTreeObserver.OnGlobalLayoutListener{refresh.run()}
  view.viewTreeObserver.addOnGlobalLayoutListener(listener)
  view.post(refresh)
  onDispose {
   view.removeCallbacks(refresh)
   if(view.viewTreeObserver.isAlive)view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
  }
 }
 // Use the maximum on each edge, never add the same keyboard/system inset twice.
 return WindowInsets.safeDrawing.union(WindowInsets(clip.left,clip.top,clip.right,clip.bottom))
}
