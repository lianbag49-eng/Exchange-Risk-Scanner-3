package com.example.riskscanner

object RiskEngine {
    fun evaluate(s: DeviceSnapshot, kycCountry: String): RiskResult {
        val signals = mutableListOf<RiskSignal>()
        fun add(label: String, value: String, points: Int, triggered: Boolean) {
            signals += RiskSignal(label, value, if (triggered) points else 0, triggered)
        }
        add("VPN 활성", s.vpnActive.toString(), 20, s.vpnActive)
        add("루팅/시스템 변조 의심", s.rootedSuspected.toString(), 40, s.rootedSuspected)
        add("에뮬레이터 의심", s.emulatorSuspected.toString(), 40, s.emulatorSuspected)
        add("개발자 옵션 활성", s.developerOptions.toString(), 8, s.developerOptions)
        add("ADB/USB 디버깅 활성", s.adbEnabled.toString(), 15, s.adbEnabled)
        add("보안 잠금화면 미설정", s.secureLockScreen.toString(), 10, !s.secureLockScreen)
        val mismatch = kycCountry.isNotBlank() && s.deviceCountry.isNotBlank() && !kycCountry.equals(s.deviceCountry, ignoreCase = true)
        add("KYC 국가 / 기기 국가 불일치", "$kycCountry / ${s.deviceCountry}", 20, mismatch)
        val score = signals.sumOf { it.points }.coerceAtMost(100)
        val level = when {
            s.rootedSuspected || s.emulatorSuspected || score >= 60 -> "HIGH"
            score >= 30 -> "MEDIUM"
            else -> "LOW"
        }
        return RiskResult(score, level, signals)
    }
}
