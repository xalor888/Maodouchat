package com.maodouchat.domain.messaging

// 密聊信任集合归一化与准入判定：新设备、设备指纹、转发白名单三处共用同一形状。
object SecretTrustPolicy {
    fun normalizeIds(raw: Collection<String>): Set<String> =
        raw.map { it.trim() }.filter { it.isNotBlank() }.toSet()

    fun normalizeId(id: String): String = id.trim()

    fun isDeviceTrusted(enabled: Boolean, deviceId: String, knownDevices: Set<String>): Boolean {
        if (deviceId.isBlank()) return false
        if (!enabled) return true
        return deviceId in knownDevices
    }

    fun isFingerprintVerified(enabled: Boolean, fingerprint: String, verified: Set<String>): Boolean {
        if (fingerprint.isBlank()) return false
        if (!enabled) return true
        return fingerprint in verified
    }

    fun isForwardAllowed(enabled: Boolean, targetId: String, whitelist: Set<String>): Boolean {
        if (targetId.isBlank()) return false
        if (!enabled) return true
        // 白名单为空 = 完全禁止；非空则必须命中。
        return whitelist.isNotEmpty() && targetId in whitelist
    }
}
