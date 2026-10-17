package com.maodouchat.crypto

import com.maodouchat.network.ApiService
import org.json.JSONObject

/** 密封发送人证书的客户端助手。保守路径：走服务端 HMAC token 方案
 * （`v1.{userId}.{deviceId}.{expiresAt}.{sigHex}`，见 [fetchCertificate]），不用 libsignal
 * 的 SealedSessionCipher 加解密——那会破坏现有信封格式与在途消息的兼容性。
 * 证书以 `X-Maodouchat-Sealed-Sender-Cert` header 注入；[isImplemented] 恒为 false，
 * 引入任何依赖 SealedSessionCipher 的代码前必须先检查它。 */
object SealedSenderSupport {
    private const val MAX_CERT_CHARS = 16_000
    private const val MAX_CERT_TTL_MS = 7L * 24L * 60L * 60L * 1000L

    /**
     * 显式标记：libsignal `SealedSessionCipher` 加解密路径是否已实现。
     *
     * 当前为 `false`：本项目走服务端 HMAC token 方案，客户端不做 SealedSessionCipher 加解密。
     * 调用方在引入任何依赖 `org.signal.libsignal.metadata.SealedSessionCipher` 的代码前，
     * 必须先检查此值，避免运行时 `ClassNotFoundException` / `NoClassDefFoundError`。
     */
    @JvmStatic
    fun isImplemented(): Boolean = false

    private val cacheLock = Any()
    private val cachedCertificates = mutableMapOf<CacheKey, Certificate>()
    private var cacheGeneration: Long = 0L

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
            if (cert.isBlank() || cert.length > MAX_CERT_CHARS || expiresAt <= 0L) error("invalid sealed sender certificate")
            if (expiresAt > System.currentTimeMillis() + MAX_CERT_TTL_MS) error("sealed sender certificate TTL too long")
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
