package com.example.riskscanner

data class RiskSignal(val label: String, val value: String, val points: Int, val triggered: Boolean)
data class RiskResult(val score: Int, val level: String, val signals: List<RiskSignal>)
data class DeviceSnapshot(
    val deviceCountry: String,
    val timezoneId: String,
    val locale: String,
    val networkType: String,
    val vpnActive: Boolean,
    val rootedSuspected: Boolean,
    val emulatorSuspected: Boolean,
    val developerOptions: Boolean,
    val adbEnabled: Boolean,
    val secureLockScreen: Boolean,
    val securityPatch: String,
    val httpProxy: Boolean = false,
    val networkValidated: Boolean? = null,
    val deviceModel: String = ""
)
