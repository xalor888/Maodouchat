package com.maodouchat.crypto

/**
 * 何时向对端（含自己的其他设备）建立 Signal session。
 * 当前设备不得对自己取 PreKey / 建会话：旧服务端会拒自取，拖垮整批 fan-out。
 */
object SignalSessionPolicy {
    /**
     * Chooses the device candidates for outbound encryption.
     *
     * A successful discovery response is authoritative, but a failed/empty
     * discovery must not discard durable sessions. This is what lets a sender
     * continue through offline periods and exhausted one-time-prekey pools.
     */
    fun candidateDeviceIds(
        discoveredDeviceIds: List<Int>?,
        persistedSessionDeviceIds: List<Int>,
    ): List<Int> {
        val persisted = persistedSessionDeviceIds.filter { it in 1..255 }.distinct().sorted()
        val discovered = discoveredDeviceIds
            ?.filter { it in 1..255 }
            ?.distinct()
            ?.sorted()
        return when {
            !discovered.isNullOrEmpty() -> discovered
            persisted.isNotEmpty() -> persisted
            else -> emptyList()
        }
    }

    /** A device-id migration invalidates an otherwise present outbound ratchet. */
    fun shouldEnsureSession(
        hasSession: Boolean,
        requiresReestablishment: Boolean,
    ): Boolean = requiresReestablishment || !hasSession

    fun shouldEstablishSession(
        recipientId: String,
        deviceId: Int,
        currentUserId: String?,
        localDeviceId: Int,
    ): Boolean {
        if (recipientId.isBlank()) return false
        if (deviceId <= 0) return false
        val self = currentUserId?.takeIf { it.isNotBlank() } ?: return true
        return !(recipientId == self && deviceId == localDeviceId)
    }
}
