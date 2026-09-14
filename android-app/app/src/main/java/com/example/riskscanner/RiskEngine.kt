package com.example.riskscanner

import java.time.LocalDate
import java.time.temporal.ChronoUnit

object RiskEngine {
    fun evaluate(s: DeviceSnapshot, kycCountry: String, strict: Boolean = false, today: LocalDate = LocalDate.now()): RiskResult {
        val signals = mutableListOf<RiskSignal>()
        fun add(label: String, value: String, points: Int, triggered: Boolean, advice: String, checked: Boolean = true) {
            signals += RiskSignal(label, value, if (triggered && checked) points else 0, triggered && checked, advice, checked)
        }
        add("VPN 연결", if(s.vpnActive) "활성" else "미감지", if(strict) 8 else 5, s.vpnActive, "VPN은 보안·개인정보 보호에도 사용됩니다. 본인이 설정한 연결인지 확인하세요.")
        add("시스템 변조 징후", if(s.rootedSuspected) "의심 징후 감지" else "의심 징후 미감지", 40, s.rootedSuspected, "공식 OS와 제조사 보안 업데이트를 사용하세요. 파일 기반 휴리스틱이며 확정 판정이 아닙니다.")
        add("가상 기기 징후", if(s.emulatorSuspected) "의심 징후 감지" else "의심 징후 미감지", 20, s.emulatorSuspected, "테스트용 환경인지 확인하고 실제 계정은 신뢰하는 기기에서 관리하세요.")
        add("개발자 옵션", if(s.developerOptions) "활성" else "비활성", if(strict) 10 else 5, s.developerOptions, "사용하지 않는 개발자 기능은 기기 설정에서 꺼 주세요.")
        add("USB 디버깅", if(s.adbEnabled) "활성" else "비활성", 20, s.adbEnabled, "개발자 옵션에서 USB 디버깅을 끄고 알 수 없는 컴퓨터의 디버깅 승인을 해제하세요.")
        add("화면 잠금", if(s.secureLockScreen) "보안 잠금 설정됨" else "보안 잠금 없음", 20, !s.secureLockScreen, "PIN·비밀번호와 생체 인증을 설정하세요.")
        val mismatch = kycCountry.isNotBlank() && s.deviceCountry.isNotBlank() && !kycCountry.equals(s.deviceCountry, true)
        add("KYC / 언어·지역 설정", "$kycCountry / ${s.deviceCountry.ifBlank { "미확인" }}", 5, mismatch, "지역 설정은 실제 위치·IP 국가가 아닙니다. 본인의 KYC 정보가 정확한지만 확인하세요.", s.deviceCountry.isNotBlank())
        add("HTTP 프록시 설정", if(s.proxyConfigured) "설정됨" else "미감지", if(strict) 15 else 10, s.proxyConfigured, "회사·학교 프록시 등 본인이 아는 설정인지 네트워크 관리자에게 확인하세요.")
        add("인터넷 연결 검증", if(s.networkValidated) "Android 연결 검증 완료" else "검증되지 않음", 5, !s.networkValidated, "로그인 포털 또는 인터넷 연결을 확인하세요. 연결 검증은 네트워크 안전성 보증이 아닙니다.")
        val age = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(s.securityPatch), today) }.getOrNull()
        val validPatch = age != null && age >= 0
        val outdated = validPatch && age!! > (if(strict) 90 else 180)
        add("Android 보안 패치", if(validPatch) "${s.securityPatch} · ${age}일 경과" else "날짜 미확인", if(strict) 20 else 15, outdated, "설정 > 소프트웨어 업데이트에서 최신 보안 패치를 확인하세요.", validPatch)
        add("IP·ISP·로그인 이력·KYC 진위", "연결된 외부 조회 없음", 0, false, "이 앱은 거래소 내부 리스크 판정 또는 제재 가능성을 조회하지 않습니다.", false)
        add("현재 시간대", s.timezoneId, 0, false, "시간대 설정은 위치 증거가 아닙니다.")
        val score = signals.sumOf { it.points }.coerceAtMost(100)
        val level = when {
            s.rootedSuspected || score >= (if(strict) 50 else 60) -> "HIGH"
            score >= (if(strict) 20 else 30) -> "MEDIUM"
            else -> "LOW"
        }
        return RiskResult(score, level, signals)
    }
}
