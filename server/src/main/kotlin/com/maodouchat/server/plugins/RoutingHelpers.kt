package com.maodouchat.server.plugins

import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.AuthResponse
import com.maodouchat.server.model.DevicePreKeyBundleResponse
import com.maodouchat.server.model.PreKeyBundleResponse
import com.maodouchat.server.model.UploadKeysRequest
import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.repository.IssuedRefreshToken
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.SignalKeyRepository
import com.maodouchat.server.repository.DeviceBundle
import com.maodouchat.server.model.UserResponse
import com.maodouchat.server.model.ErrorResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 从 Routing.kt 拆分的通用辅助函数、限流器扩展和常量。
 * 保持 internal 可见性，同包内路由文件可直接调用。
 */

// ── 常量 ──────────────────────────────

internal const val MAX_JSON_BODY_CHARS = 2_000_000
internal const val MAX_UPLOAD_JSON_BODY_CHARS = 3_000_000
internal const val MAX_GLOBAL_BODY_BYTES = 5L * 1024L * 1024L
internal const val MAX_MUTE_DURATION_MS = 30L * 24 * 60 * 60 * 1000
internal const val MAX_GROUP_MEMBERS_HARD_CAP = 500
/** 广播频道订阅者上限（含创建者）。 */
internal const val MAX_CHANNEL_SUBSCRIBERS = 5_000
/** Effective group size limit (runtime setting, hard-capped). */
internal fun maxGroupMembers(): Int =
    com.maodouchat.server.service.RuntimeConfigService
        .getInt(com.maodouchat.server.service.RuntimeConfigService.KEY_MAX_GROUP_SIZE, 200)
        .coerceIn(2, MAX_GROUP_MEMBERS_HARD_CAP)

internal const val MAX_ATTACHMENT_CIPHER_BYTES = 100L * 1024L * 1024L + 64L
internal const val MAX_ATTACHMENT_CHUNK_BYTES = 4L * 1024L * 1024L
/** Per-user encrypted-attachment quota; env-tunable via USER_STORAGE_QUOTA_BYTES (20 GB default). */
internal val maxAttachmentUserBytes: Long get() = ServerConfig.userStorageQuotaBytes
internal const val ATTACHMENT_UPLOAD_TTL_MS = 24L * 60L * 60L * 1_000L
internal const val MEDIA_ORPHAN_GRACE_MS = 7L * 24L * 60L * 60L * 1_000L
internal const val ATTACHMENT_HASH_HEADER = "X-Content-SHA256"
internal const val ATTACHMENT_CHUNK_HASH_HEADER = "X-Chunk-SHA256"
internal val ATTACHMENT_TOO_LARGE_STATUS = HttpStatusCode(413, "Payload Too Large")
internal val ATTACHMENT_QUOTA_STATUS = HttpStatusCode(507, "Insufficient Storage")
internal val ATTACHMENT_RANGE_NOT_SATISFIABLE = HttpStatusCode(416, "Range Not Satisfiable")

internal val ALLOWED_REACTION_EMOJIS = setOf(
    "\uD83D\uDC4D", "\uD83D\uDC4E", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE0D",
    "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDE21", "\uD83D\uDD25", "\uD83C\uDF89",
    "\uD83D\uDC4F", "\uD83D\uDE4F", "\uD83D\uDC40", "\uD83E\uDD14", "\uD83D\uDCAF",
    "\u2705", "\uD83D\uDE80", "\u2B50", "\uD83C\uDF1F", "\uD83E\uDD73", "\uD83E\uDD70",
    "\uD83D\uDCAA", "\uD83E\uDD1D", "\uD83D\uDE0A", "\uD83D\uDE4C", "\uD83E\uDD29",
    "\uD83E\uDD72", "\uD83E\uDD23", "\uD83D\uDC4C", "\uD83E\uDEF6",
    "\uD83D\uDCA1", "\uD83C\uDFAF", "\uD83D\uDCCC", "\uD83E\uDDE9"
)

// ── 限流器扩展 ────────────────────────

internal class BoundedRateLimiter(
    private val maxBuckets: Int = 10_000,
    private val windowMs: Long = 60_000L,
    private val capacitySweepIntervalMs: Long = 1_000L,
) {
    private val buckets = ConcurrentHashMap<String, MutableList<Long>>()
    private val bucketSlots = Semaphore(maxBuckets)
    private val lastCapacitySweepAt = AtomicLong(Long.MIN_VALUE)

    init {
        require(maxBuckets > 0)
        require(windowMs > 0L)
        require(capacitySweepIntervalMs > 0L)
    }

    fun acquire(
        key: String,
        maxPerMinute: Int = 10,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val normalizedKey = key.takeIf(String::isNotBlank) ?: return false
        if (maxPerMinute <= 0) return false
        val windowStart = now - windowMs
        if (bucketSlots.availablePermits() == 0) sweepAtCapacityIfDue(now, windowStart)
        var allowed = false
        buckets.compute(normalizedKey) { _, existing ->
            val timestamps = existing ?: run {
                if (!bucketSlots.tryAcquire()) return@compute null
                mutableListOf()
            }
            timestamps.removeAll { it < windowStart }
            if (timestamps.size < maxPerMinute) {
                timestamps.add(now)
                allowed = true
            }
            timestamps
        }
        return allowed
    }

    fun acquireSendCodeIp(ip: String, maxPerMinute: Int = 20): Boolean =
        acquire(ip, maxPerMinute)

    fun allowPreKeyFetch(
        requesterId: String,
        targetUserId: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean = acquire("$requesterId:$targetUserId", maxPerMinute = 10, now = now)

    fun reset(key: String): Boolean {
        val normalizedKey = key.takeIf(String::isNotBlank) ?: return false
        var removed = false
        buckets.computeIfPresent(normalizedKey) { _, _ ->
            bucketSlots.release()
            removed = true
            null
        }
        return removed
    }

    private fun sweepAtCapacityIfDue(now: Long, windowStart: Long) {
        while (true) {
            val previous = lastCapacitySweepAt.get()
            if (previous != Long.MIN_VALUE && now >= previous && now - previous < capacitySweepIntervalMs) return
            if (lastCapacitySweepAt.compareAndSet(previous, now)) break
        }
        buckets.keys.forEach { key ->
            buckets.computeIfPresent(key) { _, timestamps ->
                timestamps.removeAll { it < windowStart }
                if (timestamps.isEmpty()) {
                    bucketSlots.release()
                    null
                } else {
                    timestamps
                }
            }
        }
    }

}

// ── 地址解析 ──────────────────────────

internal fun ApplicationCall.remoteHost(): String {
    return resolveClientAddress(
        trustProxyHeaders = ServerConfig.trustProxyHeaders,
        xRealIp = request.headers["X-Real-IP"],
        xForwardedFor = request.headers[HttpHeaders.XForwardedFor],
        directAddress = request.local.remoteHost
    )
}

internal fun resolveClientAddress(
    trustProxyHeaders: Boolean,
    xRealIp: String?,
    xForwardedFor: String?,
    directAddress: String?
): String {
    fun proxyIp(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= 64 && it.all { char -> char.isDigit() || char in "abcdefABCDEF:." } }

    if (trustProxyHeaders) {
        proxyIp(xRealIp)?.let { return it }
        proxyIp(xForwardedFor?.substringAfterLast(','))?.let { return it }
    }
    return directAddress
        ?.trim()
        ?.take(100)
        ?.takeIf { it.isNotBlank() }
        ?: "unknown"
}

internal fun String?.bearerTokenOrNull(): String? {
    val header = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val separator = header.indexOf(' ')
    if (separator <= 0 || !header.substring(0, separator).equals("Bearer", ignoreCase = true)) return null
    val token = header.substring(separator + 1).trim()
    return token.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
}


// ── 认证辅助 ──────────────────────────

internal fun issueAuthResponse(
    user: UserResponse,
    authTokenRepo: AuthTokenRepository,
    issuedRefreshToken: IssuedRefreshToken? = null
): AuthResponse {
    val refreshToken = issuedRefreshToken ?: authTokenRepo.issueRefreshToken(user.id)
    val access = JwtConfig.generateAccessToken(
        userId = user.id,
        tokenVersion = authTokenRepo.getAccessTokenVersion(user.id),
        authSessionId = refreshToken.sessionId
    )
    return AuthResponse(
        token = access.token,
        userId = user.id,
        name = user.name,
        refreshToken = refreshToken.token,
        expiresAt = access.expiresAtMs,
        refreshExpiresAt = refreshToken.expiresAt
    )
}

// ── 验证辅助 ──────────────────────────

internal fun String.isValidBase64Field(maxLength: Int): Boolean {
    if (isBlank() || length > maxLength) return false
    return all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' }
}

internal fun UploadKeysRequest.isValid(): Boolean {
    // 9.138：registrationId 收紧到 libsignal 非扩展区间（客户端 generateRegistrationId(false) 生成 1..16380）
    return registrationId in 1..16_380 &&
        deviceId in 1..255 &&
        // 9.312：Signed PreKey ID 同样必须 1..16_777_215（与 PreKey / libsignal 24-bit 一致）
        signedPreKeyId in 1..16_777_215 &&
        identityKey.isValidBase64Field(maxLength = 4096) &&
        signedPreKey.isValidBase64Field(maxLength = 4096) &&
        signedPreKeySignature.isValidBase64Field(maxLength = 4096) &&
        // 签名长度合理性检查：Ed25519 签名 = 64 字节 -> Base88 字符；宽松到 64..512
        signedPreKeySignature.length in 64..512 &&
        (deviceName == null || (deviceName.trim().isNotBlank() && deviceName.length <= 50)) &&
        // 最少 10 个 PreKey：确保客户端有足够一次性密钥，避免频繁拉取
        preKeys.size in 10..100 &&
        preKeys.map { it.keyId }.distinct().size == preKeys.size &&
        // PreKey ID 范围：1..16777215（Signal 协议上限）
        preKeys.all { it.keyId in 1..16_777_215 && it.publicKey.isValidBase64Field(maxLength = 4096) }
}

internal fun String.normalizedEmail(): String = trim().lowercase()

/** Returns whether [email]'s normalized domain is forbidden for new registrations. */
internal fun isRegistrationEmailDomainBlocked(
    email: String,
    blocklist: Set<String> = ServerConfig.emailDomainBlocklist,
): Boolean {
    val domain = email.normalizedEmail().substringAfterLast('@', "")
    return domain.isNotBlank() && domain in blocklist
}

internal fun hasContentModerationAccess(userRepo: com.maodouchat.server.repository.UserRepository, userId: String): Boolean =
    com.maodouchat.server.service.AdminIdentityResolver.resolve(userId, userRepo).hasContentModerationAccess

internal fun restrictionMessage(until: Long, action: String): String {
    val remainingMinutes = ((until - System.currentTimeMillis()).coerceAtLeast(0) + 59_999L) / 60_000L
    return "$action，约 ${remainingMinutes.coerceAtLeast(1)} 分钟后恢复"
}

/** 通用 {"status":"ok"} 应答。收敛各路由文件的私有 `respondOk` 拷贝。 */
internal suspend fun ApplicationCall.respondOk() {
    respond(kotlinx.serialization.json.buildJsonObject { put("status", "ok") })
}

// ── Signal 密钥辅助 ───────────────────

internal fun DeviceBundle.toPreKeyBundleResponse(): PreKeyBundleResponse = PreKeyBundleResponse(
    registrationId = registrationId,
    deviceId = deviceId,
    identityKey = identityKey,
    signedPreKeyId = signedPreKeyId,
    signedPreKey = signedPreKey,
    signedPreKeySignature = signedPreKeySignature,
    preKeyId = preKeyId,
    preKey = preKey
)

internal fun DeviceBundle.toDevicePreKeyBundleResponse(): DevicePreKeyBundleResponse = DevicePreKeyBundleResponse(
    userId = userId,
    deviceId = deviceId,
    registrationId = registrationId,
    identityKey = identityKey,
    signedPreKeyId = signedPreKeyId,
    signedPreKey = signedPreKey,
    signedPreKeySignature = signedPreKeySignature,
    preKeyId = preKeyId,
    preKey = preKey
)

internal suspend fun ApplicationCall.canFetchKeys(
    requesterId: String,
    targetUserId: String,
    conversationQueryRepository: ConversationQueryRepository,
    preKeyFetchTracker: BoundedRateLimiter,
    allowSelf: Boolean = false
): Boolean {
    if (targetUserId == requesterId) {
        if (allowSelf) return true
        respond(HttpStatusCode.BadRequest, ErrorResponse("不能获取自己的密钥包"))
        return false
    }
    if (!conversationQueryRepository.shareConversation(requesterId, targetUserId)) {
        respond(HttpStatusCode.Forbidden, ErrorResponse("只能获取会话参与者的密钥包"))
        return false
    }
    if (!preKeyFetchTracker.allowPreKeyFetch(requesterId, targetUserId)) {
        respond(HttpStatusCode.TooManyRequests, ErrorResponse("请求过于频繁"))
        return false
    }
    return true
}

/**
 * Line-anchored, port-aware SDP video detection. Mirrors the client-side
 * [com.maodouchat.webrtc.CallType.detectFromSdp] so an audio-only call is not
 * misclassified as video (which would wrongly show the video UI / notification).
 * A bare `contains("m=video")` also matches attribute lines (e.g. `a=...`) and a
 * disabled `m=video 0 ...` (port 0), so we require a media line whose port > 0.
 */
fun sdpHasActiveVideo(sdp: String?): Boolean {
    if (sdp.isNullOrBlank()) return false
    return sdp.lineSequence().any { line ->
        val t = line.trimStart()
        if (!t.startsWith("m=video", ignoreCase = true)) return@any false
        val port = t.substring(7).trimStart().takeWhile { it.isDigit() }
        port.toIntOrNull()?.let { it > 0 } ?: false
    }
}
