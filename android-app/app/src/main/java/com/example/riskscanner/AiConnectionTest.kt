package com.example.riskscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.json.JSONObject

const val DEFAULT_AI_SERVER = "https://ers-ai-review.onrender.com"
fun reviewServerAddress(saved:String?):String = saved?.trim()?.takeIf{it.isNotEmpty()} ?: DEFAULT_AI_SERVER

// This fixture is created locally and contains no account, installed-app or device data.
internal fun aiConnectionSample():ByteArray {
 val bitmap=Bitmap.createBitmap(960,640,Bitmap.Config.ARGB_8888)
 try {
  val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
  val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=38f}
  canvas.drawText("ERS EXAMPLE EXCHANGE",48f,84f,paint)
  paint.textSize=24f;canvas.drawText("SYNTHETIC TEST SCREEN - NO REAL ACCOUNT",48f,136f,paint)
  paint.textSize=52f;canvas.drawText("Network error",48f,292f,paint)
  paint.textSize=32f;canvas.drawText("Unable to connect to the server.",48f,368f,paint)
  canvas.drawText("Check your internet connection.",48f,420f,paint)
  canvas.drawText("Try again later.",48f,472f,paint)
  return diagnosticJpeg(bitmap)
 } finally {bitmap.recycle()}
}

internal fun aiConnectionSelfTest(
 base:String,token:String,
 check:(String,String)->String = ::diagnosticConnection,
 diagnose:(String,String,ByteArray,DiagnosticApp,JSONObject)->AppDiagnosticReport = ::requestDiagnostic,
 onProgress:(String)->Unit = {}
):String {
 onProgress("1/2 · API 인증·모델 확인 중…")
 check(base,token)
 onProgress("2/2 · 가상 오류 이미지 분석 중…")
 val sample=aiConnectionSample()
 try {
  val app=DiagnosticApp("org.example.ers.connectiontest","ERS Example Exchange","test-fixture",true)
  val device=JSONObject().put("sdk",35).put("networkType","WIFI").put("networkValidated",true)
   .put("vpn",false).put("proxy",false).put("autoTime",true)
  val report=diagnose(base,token,sample,app,device)
  require(report.app==app&&report.status=="review_available"&&report.screenNotice=="network_error"&&report.facts.isEmpty()&&"network_error" in report.candidates){
   "AI 응답은 받았지만 가상 오류 이미지 판독이 예상과 다릅니다. 실제 분석 성공으로 확인하지 않았습니다."
  }
  return "API 인증·가상 이미지 분석 완료 · ${report.model}\n샘플의 네트워크 오류 안내를 판독했습니다. 실제 계정 상태·오류 원인은 별도 검토가 필요합니다."
 } finally {sample.fill(0)}
}
