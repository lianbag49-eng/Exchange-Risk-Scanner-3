package com.example.riskscanner

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow

data class DiagnosticCapture(val owner:String="",val active:Boolean=false,val image:ByteArray?=null,val message:String="")
object DiagnosticCaptureBus {
 val state=MutableStateFlow(DiagnosticCapture())
 fun clear(owner:String){if(state.value.owner==owner){state.value.image?.fill(0);state.value=DiagnosticCapture()}}
}
class DiagnosticCaptureService:Service() {
 private val handler=Handler(Looper.getMainLooper())
 private var owner="";private var projection:MediaProjection?=null;private var display:VirtualDisplay?=null;private var reader:ImageReader?=null;private var finishing=false;private var capturing=false
 override fun onBind(intent:Intent?)=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  when(intent?.action){
   "capture"->{if(intent.getStringExtra("owner")==owner&&!capturing&&!finishing){capturing=true;handler.postDelayed({capture()},1000)}}
   "stop"->{if(intent.getStringExtra("owner")==owner)finish("화면 공유를 중지했습니다")}
   "start"->{if(projection!=null)return START_NOT_STICKY;owner=intent.getStringExtra("owner")?:return START_NOT_STICKY
    try{val nm=getSystemService(NotificationManager::class.java);nm.createNotificationChannel(NotificationChannel("ers_capture","ERS 화면 1회 캡처",NotificationManager.IMPORTANCE_LOW))
     val notice=Notification.Builder(this,"ers_capture").setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("오류 화면을 연 뒤 1회 캡처").setContentText("인증 입력·OTP·시드 화면은 캡처하지 마세요").setOngoing(true).addAction(Notification.Action.Builder(null,"현재 화면 1회 캡처",action("capture",1)).build()).addAction(Notification.Action.Builder(null,"공유 중지",action("stop",2)).build()).build()
     if(Build.VERSION.SDK_INT>=29)startForeground(81,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(81,notice)
     @Suppress("DEPRECATION") val data=intent.getParcelableExtra<Intent>("consent")?:error("화면 공유 허용 정보가 없습니다")
     projection=getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent.getIntExtra("result",Activity.RESULT_CANCELED),data)?:error("화면 공유가 허용되지 않았습니다")
     projection!!.registerCallback(object:MediaProjection.Callback(){override fun onStop(){finish("시스템에서 화면 공유를 중지했습니다")}},handler)
     DiagnosticCaptureBus.state.value=DiagnosticCapture(owner,true,message="거래소 오류 화면을 열고 알림의 ‘현재 화면 1회 캡처’를 누른 뒤 ERS로 돌아오세요")
     handler.postDelayed({finish("2분이 지나 화면 공유를 종료했습니다")},120000)
    }catch(e:Exception){finish("화면 공유를 시작하지 못했습니다. 이미지 선택을 이용하세요")}
   }
  };return START_NOT_STICKY
 }
 private fun action(name:String,code:Int)=PendingIntent.getService(this,code,Intent(this,DiagnosticCaptureService::class.java).setAction(name).putExtra("owner",owner),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
 private fun capture(){if(finishing)return
  try{val wm=getSystemService(WindowManager::class.java)
   @Suppress("DEPRECATION") val metrics=android.util.DisplayMetrics().also{wm.defaultDisplay.getRealMetrics(it)}
   val bounds=if(Build.VERSION.SDK_INT>=30)wm.maximumWindowMetrics.bounds else android.graphics.Rect(0,0,metrics.widthPixels,metrics.heightPixels)
   val scale=minOf(1.0,2048.0/maxOf(bounds.width(),bounds.height()));val width=(bounds.width()*scale).toInt().coerceAtLeast(1);val height=(bounds.height()*scale).toInt().coerceAtLeast(1)
   reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2)
   reader!!.setOnImageAvailableListener({source->
    if(finishing)return@setOnImageAvailableListener
    val frame=source.acquireLatestImage()?:return@setOnImageAvailableListener
    var bitmap:Bitmap?=null;var cropped:Bitmap?=null
    try{val plane=frame.planes[0];val stride=plane.pixelStride;val padding=plane.rowStride-stride*width;bitmap=Bitmap.createBitmap(width+padding/stride,height,Bitmap.Config.ARGB_8888);bitmap.copyPixelsFromBuffer(plane.buffer);cropped=Bitmap.createBitmap(bitmap,0,0,width,height)
     if(likelyBlankCapture(cropped)){finish("화면이 비어 있거나 캡처가 차단되어 판독할 수 없습니다. 차단을 우회하지 않습니다")}else{val bytes=diagnosticJpeg(cropped);finish("1회 캡처 완료 · 전송 전 개인정보를 가려 주세요",bytes)}
    }catch(e:Exception){finish("캡처를 읽지 못했습니다. 이미지 선택을 이용하세요")}
    finally{if(cropped!==bitmap)cropped?.recycle();bitmap?.recycle();frame.close()}
   },handler)
   display=projection?.createVirtualDisplay("ERS one-shot",width,height,metrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,handler)?:error("공유 종료")
   handler.postDelayed({finish("캡처할 수 없는 화면입니다. 공유가 종료되었습니다")},5000)
  }catch(e:Exception){finish("캡처가 허용되지 않았거나 공유가 끝났습니다")}
 }
 private fun finish(message:String,image:ByteArray?=null){if(finishing){image?.fill(0);return};finishing=true;handler.removeCallbacksAndMessages(null)
  // Keep only one bounded JPEG in memory. Neither raw frames nor images are written to disk.
  if(DiagnosticCaptureBus.state.value.owner==owner){DiagnosticCaptureBus.state.value.image?.fill(0);DiagnosticCaptureBus.state.value=DiagnosticCapture(owner,false,image,message)}else image?.fill(0)
  release();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
 }
 private fun release(){reader?.setOnImageAvailableListener(null,null);display?.release();display=null;reader?.close();reader=null;val p=projection;projection=null;p?.stop()}
 override fun onDestroy(){handler.removeCallbacksAndMessages(null);if(!finishing){finishing=true;if(DiagnosticCaptureBus.state.value.owner==owner)DiagnosticCaptureBus.state.value=DiagnosticCapture(owner,false,message="화면 공유가 종료되었습니다")};release();super.onDestroy()}
}
