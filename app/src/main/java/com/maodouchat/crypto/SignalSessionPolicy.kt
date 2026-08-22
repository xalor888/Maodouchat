package com.maodouchat.crypto

/**
 * 何时向对端（含自己的其他设备）建立 Signal session。
 * 当前设备不得对自己取 PreKey / 建会话：旧服务端会拒自取，拖垮整批 fan-out。
 */
object SignalSessionPolicy {
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
