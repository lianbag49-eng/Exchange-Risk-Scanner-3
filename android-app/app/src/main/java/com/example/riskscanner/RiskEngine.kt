package com.example.riskscanner

object RiskEngine {
    fun evaluate(s: DeviceSnapshot, kycCountry: String, strict: Boolean = false): RiskResult {
        val signals = mutableListOf<RiskSignal>()
        fun add(label: String, value: String, points: Int, triggered: Boolean) {
            signals += RiskSignal(label, value, if (triggered) points else 0, triggered)
        }
        add("VPN 활성", s.vpnActive.toString(), 20, s.vpnActive)
        add("루팅/시스템 변조 의심", s.rootedSuspected.toString(), 40, s.rootedSuspected)
        add("에뮬레이터 의심", s.emulatorSuspected.toString(), 40, s.emulatorSuspected)
        add("개발자 옵션 활성", s.developerOptions.toString(), 8, s.developerOptions)
        add("ADB/USB 디버깅 활성", s.adbEnabled.toString(), 15, s.adbEnabled)
        add("보안 잠금화면 미설정", (!s.secureLockScreen).toString(), 10, !s.secureLockScreen)
        val mismatch = kycCountry.isNotBlank() && s.deviceCountry.isNotBlank() && !kycCountry.equals(s.deviceCountry, ignoreCase = true)
        add("입력 국가 / 기기 언어 지역 차이 (위치 증거 아님)", "$kycCountry / ${s.deviceCountry}", 0, mismatch)
        val patchAge = try { java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(s.securityPatch), java.time.LocalDate.now()) } catch (_: Exception) { -1L }
        add("보안 패치 경과", if(patchAge < 0) "확인 불가" else "${patchAge}일", 15, patchAge > if(strict) 90 else 180)
        add("네트워크 연결", s.networkType, 0, s.networkType == "NONE")
        add("현재 시간대", s.timezoneId, 0, false)
        add("공인 IP·ISP·접속 국가", "확인 불가", 0, false)
        add("거래소 로그인 이력·KYC 진위", "확인 불가", 0, false)
        add("HTTP 프록시 설정",s.httpProxy.toString(),0,s.httpProxy)
        add("인터넷 연결 검증",s.networkValidated?.toString()?:"확인 불가",10,s.networkValidated==false)
        add("기기 정보",s.deviceModel.ifBlank{"확인 불가"},0,false)
        val score = signals.sumOf { it.points }.coerceAtMost(100)
        val level = when {
            s.rootedSuspected || s.emulatorSuspected || score >= 60 -> "HIGH"
            score >= 30 -> "MEDIUM"
            else -> "LOW"
        }
        return RiskResult(score, level, signals)
    }
}
