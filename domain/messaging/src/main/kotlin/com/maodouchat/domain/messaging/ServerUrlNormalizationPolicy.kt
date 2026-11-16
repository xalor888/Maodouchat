package com.maodouchat.domain.messaging

// 服务器地址归一化 + WS 地址推导：纯字符串变换。
object ServerUrlNormalizationPolicy {

    fun normalizeBaseUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        val schemeEnd = normalized.indexOf("://")
        if (schemeEnd <= 0) return normalized
        return normalized.substring(0, schemeEnd).lowercase() + normalized.substring(schemeEnd)
    }

    /** 由 HTTP(S) 服务器地址推导 WebSocket 地址（http→ws，https→wss，路径 /ws）。 */
    fun wsUrlFor(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        val schemeEnd = normalized.indexOf("://")
        val lowerScheme = if (schemeEnd > 0) normalized.substring(0, schemeEnd).lowercase() else ""
        return when {
            lowerScheme == "https" -> "wss://${normalized.substring(schemeEnd + 3)}/ws"
            lowerScheme == "http" -> "ws://${normalized.substring(schemeEnd + 3)}/ws"
            else -> "$normalized/ws"
        }
    }
}
