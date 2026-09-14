package com.example.riskscanner

import android.app.KeyguardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import java.io.File
import java.util.Locale
import java.util.TimeZone

class DeviceInspector(private val context: Context) {
    fun snapshot(): DeviceSnapshot = DeviceSnapshot(
        deviceCountry = Locale.getDefault().country.orEmpty(),
        timezoneId = TimeZone.getDefault().id,
        locale = Locale.getDefault().toLanguageTag(),
        networkType = networkType(),
        vpnActive = isVpnActive(),
        rootedSuspected = isRootSuspected(),
        emulatorSuspected = isEmulatorSuspected(),
        developerOptions = setting(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
        adbEnabled = setting(Settings.Global.ADB_ENABLED),
        secureLockScreen = (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure,
        securityPatch = Build.VERSION.SECURITY_PATCH ?: ""
    )

    private fun setting(name: String): Boolean = try {
        Settings.Global.getInt(context.contentResolver, name, 0) == 1
    } catch (_: Exception) { false }

    private fun networkType(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return "NONE"
        val c = cm.getNetworkCapabilities(n) ?: return "UNKNOWN"
        return when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "OTHER"
        }
    }

    private fun isVpnActive(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    private fun isRootSuspected(): Boolean = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su", "/system/app/Magisk.apk"
    ).any { File(it).exists() } || (Build.TAGS ?: "").contains("test-keys")

    private fun isEmulatorSuspected(): Boolean {
        val fp = (Build.FINGERPRINT ?: "").lowercase()
        val model = (Build.MODEL ?: "").lowercase()
        val hardware = (Build.HARDWARE ?: "").lowercase()
        return fp.startsWith("generic") || fp.contains("emulator") || model.contains("emulator") || model.contains("google_sdk") || hardware.contains("goldfish") || hardware.contains("ranchu")
    }
}
