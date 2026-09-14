package com.example.riskscanner

/** Local, user-supplied evidence. Never represented as an exchange API verification. */
data class AccountEvidence(
 val kyc:String="미확인", val restriction:String="미확인", val unusualLogin:String="미확인",
 val twoFactor:String="미확인", val loginCountry:String="", val notice:String=""
)
object AccountEvidenceEngine {
 fun signals(e:AccountEvidence,kycCountry:String):List<RiskSignal> {
  val out=mutableListOf<RiskSignal>()
  fun add(label:String,value:String,points:Int=0){out+=RiskSignal(label,value,points,points>0)}
  add("KYC 상태 · 사용자 입력",e.kyc,if(e.kyc=="거절")20 else 0)
  add("계정 제한 · 사용자 입력",e.restriction,if(e.restriction=="제한 있음")30 else 0)
  add("낯선 로그인 · 사용자 입력",e.unusualLogin,if(e.unusualLogin=="있음")30 else 0)
  add("2단계 인증 · 사용자 입력",e.twoFactor,if(e.twoFactor=="미설정")10 else 0)
  add("최근 로그인 국가 · 사용자 입력",e.loginCountry.ifBlank{"미확인"})
  if(e.loginCountry.isNotBlank() && e.loginCountry!=kycCountry) add("로그인/KYC 국가 차이 · 입력값 비교","${e.loginCountry} / $kycCountry · 여행 등 정상 사유 확인 필요",10)
  val notice=e.notice.lowercase()
  if(notice.isBlank()) add("거래소 안내문 분석","미확인")
  else {
   val indicators=listOf("withdrawal suspended","withdrawals suspended","account restricted","account frozen","verification failed","kyc rejected","unusual login","출금 제한","출금 정지","계정 제한","계정 동결","인증 실패","인증 거절","비정상 로그인")
   val found=indicators.filter { notice.contains(it) }
   add("거래소 안내문 분석 · 진위 미검증",if(found.isEmpty())"위험 문구 미검출 · 안전 또는 진위 확인을 의미하지 않음" else "주의 문구: "+found.joinToString(", "),if(found.isEmpty())0 else 15)
  }
  add("KYC 진위·실제 제한 여부","미확인 · 거래소 공식 응답 필요")
  return out
 }
 fun combine(device:RiskResult,e:AccountEvidence,country:String):RiskResult {
  val signals=device.signals+signals(e,country)
  val score=signals.sumOf{it.points}.coerceAtMost(100)
  return RiskResult(score,if(device.level=="HIGH"||score>=60)"HIGH" else if(score>=30)"MEDIUM" else "LOW",signals)
 }
}
