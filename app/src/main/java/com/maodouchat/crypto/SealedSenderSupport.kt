package com.maodouchat.crypto

import com.maodouchat.network.ApiService
import org.json.JSONObject

/**
 * Client helpers for server-issued sealed-sender certificates.
 *
 * ## 架构说明（保守路径，未实现 libsignal SealedSessionCipher 加解密）
 *
 * 本项目的"密封发送人"采用**服务端 HMAC token** 方案，**不**使用 libsignal 的
 * `org.signal.libsignal.metadata.SealedSessionCipher` 加解密路径：
 *
 * 1. 服务端 [SealedSenderCertificateService]（server 模块）用 HMAC-SHA256 签发 base64url
 *    token：`v1.{userId}.{deviceId}.{expiresAt}.{sigHex}`，**不**生成 libsignal 的
 *    `ServerCertificate` / `SenderCertificate`。
 * 2. 客户端通过 [fetchCertificate] 拉取该 token，缓存后作为
 *    `X-Maodouchat-Sealed-Sender-Cert` HTTP/WS header 注入（见 [ApiService] 与 [WebSocketClient]）。
 * 3. 服务端 [SealedSenderDelivery.authorize] 验证 token 后，**仅在 Push 通知和 Bot Webhook
 *    中 redact senderId** 为 "sealed"；chat WS/REST 历史仍保留真实 senderId，因为
 *    libsignal session addressing 解密时需要真实 peer id。
 *
 * 因此客户端**无需**也**不应**调用 `SealedSessionCipher.encrypt/decrypt`——那需要服务端
 * 用 libsignal trustRoot 私钥签发 `ServerCertificate`，并会破坏现有 `EncryptedMessageEnvelope`
 * 信封格式与所有在途消息的兼容性。本项目的设计选择就是用 HMAC token 实现等价的元数据
 * 隐藏，避免破坏 chat 路径的 senderId。
 *
 * [isImplemented] 显式返回 `false` 标记此保守路径。运行时调用方应优先检查 [isImplemented]
 * 来判断是否启用任何依赖 `SealedSessionCipher` 的代码路径；目前没有任何调用方依赖该路径，
 * 因此不会触发 `ClassNotFoundException`。
 *
 * libsignal-android 0.41.0 AAR 本身只包含 Android logger 类，`org.signal.libsignal.metadata`
 * 包通过传递依赖 `libsignal-client:0.41.0` 提供——若未来切换到完整 SealedSessionCipher
 * 实现，可直接使用，无需改 Gradle。
 */
object SealedSenderSupport {
    /**
     * 显式标记：libsignal `SealedSessionCipher` 加解密路径是否已实现。
     *
     * 当前为 `false`：本项目走服务端 HMAC token 方案，客户端不做 SealedSessionCipher 加解密。
     * 调用方在引入任何依赖 `org.signal.libsignal.metadata.SealedSessionCipher` 的代码前，
     * 必须先检查此值，避免运行时 `ClassNotFoundException` / `NoClassDefFoundError`。
     */
    @JvmStatic
    fun isImplemented(): Boolean = false

    private data class CacheKey(
        val userId: String,
        val deviceId: Int
    )

    private val cacheLock = Any()
    private val cachedCertificates = mutableMapOf<CacheKey, Certificate>()
    private var cacheGeneration: Long = 0L

    data class Certificate(
        val certificate: String,
        val expiresAt: Long,
        val deviceId: Int,
        val userId: String
    )

    fun clearCache() = synchronized(cacheLock) {
        cacheGeneration += 1L
        cachedCertificates.clear()
    }

    fun clearCache(userId: String, deviceId: Int) = synchronized(cacheLock) {
        cacheGeneration += 1L
        cachedCertificates.remove(CacheKey(userId, deviceId))
    }

    fun peekCached(
        userId: String,
        deviceId: Int,
        nowMs: Long = System.currentTimeMillis()
    ): Certificate? = synchronized(cacheLock) {
        val key = CacheKey(userId, deviceId)
        val cached = cachedCertificates[key] ?: return@synchronized null
        if (cached.expiresAt <= nowMs + REFRESH_SKEW_MS) {
            cachedCertificates.remove(key)
            return@synchronized null
        }
        cached
    }

    suspend fun fetchCertificate(token: String, userId: String, deviceId: Int = 1): Result<Certificate> {
        require(userId.isNotBlank()) { "sealed sender certificate owner is blank" }
        require(deviceId > 0) { "sealed sender device id must be positive" }

        val cached = peekCached(userId, deviceId)
        if (cached != null) {
            return Result.success(cached)
        }
        val requestGeneration = synchronized(cacheLock) { cacheGeneration }
        return ApiService.getSealedSenderCertificate(token, deviceId).mapCatching { raw ->
            val o = JSONObject(raw)
            val cert = o.optString("certificate")
            val expiresAt = o.optLong("expiresAt")
            val uid = o.optString("userId")
            val dev = o.optInt("deviceId", deviceId)
            if (cert.isBlank() || expiresAt <= 0L) error("invalid sealed sender certificate")
            if (uid != userId || dev != deviceId) error("sealed sender certificate owner mismatch")
            val certificate = Certificate(cert, expiresAt, dev, uid)
            synchronized(cacheLock) {
                if (cacheGeneration != requestGeneration) error("sealed sender certificate cache invalidated")
                cachedCertificates[CacheKey(uid, dev)] = certificate
            }
            certificate
        }
    }

    /** Header name for optional delivery paths. */
    const val HEADER = "X-Maodouchat-Sealed-Sender-Cert"

    /**
     * True when a non-expired cert is cached (within refresh skew).
     *
     * 注意：此处仅表示**服务端 HMAC token 证书**已缓存就绪，可用于 HTTP/WS header 注入
     * 与服务端 [SealedSenderDelivery.authorize] 验证。**不**代表 libsignal
     * `SealedSessionCipher` 加解密路径已启用——后者永远未实现，见 [isImplemented]。
     * UI（如密聊状态条）可据此显示"密封发送人元数据保护已就绪"。
     */
    fun isReady(
        userId: String,
        deviceId: Int,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean = peekCached(userId, deviceId, nowMs) != null

    /**
     * Seconds until cache expiry, or 0 if missing/expired.
     * Used by secret-chat UI to show sealed readiness health.
     */
    fun secondsUntilExpiry(
        userId: String,
        deviceId: Int,
        nowMs: Long = System.currentTimeMillis()
    ): Long = peekCached(userId, deviceId, nowMs)
        ?.let { ((it.expiresAt - nowMs) / 1000L).coerceAtLeast(0L) }
        ?: 0L

    private const val REFRESH_SKEW_MS = 60_000L
}
