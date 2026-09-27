package com.maodouchat.security

import com.maodouchat.MaodouchatApp

/**
 * 本机信号身份的读写入口（U02 延伸：自 `ui/screen/contacts/ContactSubScreens` 的
 * app 单例直连收口）。
 *
 * 它是 [SafetySignalPort] 的生产实现（扫码核验），也承载一处「已核验」写入
 * （安全码比对通过后的 [markIdentityVerified]，原实现包在 `withContext(Dispatchers.IO)` 里，
 * 因为 trust 写入内部是阻塞式 Room 查询——调用点继续包 IO，本类不替调用方选线程）。
 */
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
}
