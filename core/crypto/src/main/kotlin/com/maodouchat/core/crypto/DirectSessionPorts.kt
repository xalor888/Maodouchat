package com.maodouchat.core.crypto

/**
 * M03 直接会话密码学端口集合（契约先行）。
 * 页面/Widget/Worker/AI 只依赖这些端口，不直接触碰 libsignal 原语；
 * 实现暂由 `SignalProtocol` 宽门面承担，迁移完成后删宽门面。
 */

/** 加密账户引导：初始化/恢复身份密钥、签名预密钥、设备 id 与账号注册。 */
interface CryptoAccountBootstrapper {
    suspend fun ensureReady(token: String?, userId: String): Boolean
    fun localCryptoReady(): Boolean
}

/** 一次性预密钥清单：本地未消费 PreKey 的生成与计数。 */
interface PreKeyInventory {
    suspend fun remainingCount(): Int
    suspend fun replenishToThreshold(): Boolean
}

/** 预密钥发布：把签名预密钥 + OTPK 上传到服务端（幂等，失败保留私钥待重试）。 */
interface PreKeyPublisher {
    suspend fun publish(token: String, expectedUserId: String): Boolean
}

/** 直接会话管理：1:1 Signal 会话的建立与清理。 */
interface DirectSessionManager {
    suspend fun ensureSession(token: String, recipientId: String, deviceId: Int): Result<Unit>
    fun cleanupStaleSessions(activeContactIds: Set<String>)
}

/** 直接消息加解密：1:1 会话密文的加密与解密。 */
interface DirectMessageCipher {
    suspend fun encryptTextEnvelope(token: String, recipientId: String, plaintext: String): Result<String>
    fun decryptTextEnvelope(senderId: String, content: String): String?
}

/** 信封编解码：wire 信封格式的结构解析与生成（不含加密，只负责结构分派）。 */
interface EnvelopeCodec {
    fun envelopePayloadType(content: String): String?
    fun isEncryptedEnvelope(content: String): Boolean
    fun isSenderKeyEnvelope(content: String): Boolean
}
