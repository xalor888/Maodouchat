package com.maodouchat.domain.messaging

// 媒体自动下载模式归一化：非法值落回仅 Wi-Fi。
object MediaAutoDownloadPolicy {
    const val MODE_WIFI_ONLY = "wifi_only"
    const val MODE_ALWAYS = "always"
    const val MODE_OFF = "off"

    fun normalizeMode(mode: String): String = when (mode) {
        MODE_ALWAYS, MODE_OFF -> mode
        else -> MODE_WIFI_ONLY
    }
}
