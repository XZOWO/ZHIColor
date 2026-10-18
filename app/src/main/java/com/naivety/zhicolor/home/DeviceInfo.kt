package com.naivety.zhicolor.home

import android.os.Build
import com.naivety.zhicolor.BuildConfig
import java.util.concurrent.TimeUnit

object ColorOsVersion {
    val keys = listOf("ro.build.version.oplusrom", "ro.build.version.opporom", "ro.build.version.oplusrom.display")
    fun resolve(values: Map<String, String>): String {
        val raw = keys.firstNotNullOfOrNull { key -> values[key]?.trim()?.takeIf { it.isNotBlank() } }
            ?: return "未识别"
        return if (raw.startsWith("ColorOS", ignoreCase = true)) raw else "ColorOS " + raw.removePrefix("V").removePrefix("v")
    }
}

data class DeviceInfo(val device: String, val android: String, val colorOs: String, val appVersion: String)

object DeviceInfoLoader {
    private val deviceInfo = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DeviceInfo(
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            colorOs = ColorOsVersion.resolve(ColorOsVersion.keys.associateWith(::property)),
            appVersion = "v${BuildConfig.VERSION_NAME}",
        )
    }

    fun cached(): DeviceInfo? = if (deviceInfo.isInitialized()) deviceInfo.value else null

    fun load(): DeviceInfo = deviceInfo.value
    private fun property(key: String): String = runCatching {
        val process = ProcessBuilder("/system/bin/getprop", key).redirectErrorStream(true).start()
        try {
            if (process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0) process.inputStream.bufferedReader().readText().trim() else ""
        } finally { process.destroy() }
    }.getOrDefault("")
}
