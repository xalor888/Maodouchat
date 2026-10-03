package com.maodouchat.security

import com.maodouchat.MaodouchatApp

object SignalIdentityAccess : SafetySignalPort {

    private val protocol
        get() = MaodouchatApp.instance.signalProtocol

    override fun deviceId(): Int = protocol.getDeviceId()

    override fun localIdentityFingerprint(): String = protocol.getLocalIdentityFingerprint()

    override fun remoteIdentityFingerprint(remoteUserId: String, deviceId: Int): String? =
        protocol.getRemoteIdentityFingerprint(remoteUserId, deviceId)

    override fun safetyCode(remoteUserId: String, deviceId: Int): String? =
        protocol.getSafetyCode(remoteUserId, deviceId)

    /** 标记对端身份「已核验」（阻塞式 Room 写入；调用方负责放到 IO 线程）。 */
    fun markIdentityVerified(remoteUserId: String, deviceId: Int): Boolean =
        protocol.markIdentityVerified(remoteUserId, deviceId)

    /** 该账号的 Signal 存储是否已初始化（安全中心展示用）。 */
    fun isInitializedFor(userId: String): Boolean = protocol.isInitializedFor(userId)

    /** 为本机设备签发「确认目标设备」的批准签名（新设备确认流程用）。 */
    fun signDeviceConfirmation(targetDeviceId: Int, targetIdentityKeyBase64: String): String? =
        protocol.signDeviceConfirmation(targetDeviceId, targetIdentityKeyBase64)
}
