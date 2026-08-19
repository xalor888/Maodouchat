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
import com.maodouchat.server.repository.ChatRepository
import com.maodouchat.server.repository.SignalKeyRepository
import com.maodouchat.server.model.UserResponse
import com.maodouchat.server.model.ErrorResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
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

internal const val MAX_AI_IMAGE_BYTES = 1_200_000
internal const val MAX_AI_IMAGE_DIMENSION = 4_096
internal const val MAX_AI_IMAGE_PIXELS = 16_000_000L
/** 8.52 修复 AI-3：图片分析发送前降采样的最长边（视觉模型按像素计费）。 */
internal const val AI_IMAGE_TARGET_MAX_EDGE = 1_568
internal const val MAX_AI_FILE_BYTES = 1_200_000
internal const val MAX_AI_TEXT_FILE_CHARS = 120_000
internal val ALLOWED_AI_TEXT_FILE_EXTENSIONS = setOf("txt", "md", "markdown", "csv", "json", "xml")
internal const val MAX_ATTACHMENT_CIPHER_BYTES = 100L * 1024L * 1024L + 64L
internal const val MAX_ATTACHMENT_CHUNK_BYTES = 4L * 1024L * 1024L
internal const val MAX_ATTACHMENT_USER_BYTES = 1024L * 1024L * 1024L
internal const val ATTACHMENT_UPLOAD_TTL_MS = 24L * 60L * 60L * 1_000L
internal const val ATTACHMENT_HASH_HEADER = "X-Content-SHA256"
internal const val ATTACHMENT_CHUNK_HASH_HEADER = "X-Chunk-SHA256"
internal val ATTACHMENT_TOO_LARGE_STATUS = HttpStatusCode(413, "Payload Too Large")
internal val ATTACHMENT_QUOTA_STATUS = HttpStatusCode(507, "Insufficient Storage")
internal val ATTACHMENT_RANGE_NOT_SATISFIABLE = HttpStatusCode(416, "Range Not Satisfiable")
internal const val MAX_WEBHOOK_RESPONSE_HEADER_BYTES = 32 * 1024

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

internal data class PinnedWebhookResponse(
    val statusCode: Int,
    val body: String
)

/**
 * Sends one HTTP/1.1 JSON request to an already validated DNS result. Connecting to the
 * concrete address closes the DNS-rebinding window between policy validation and I/O.
 */
internal fun postPinnedWebhookJson(
    url: String,
    body: String,
    headers: Map<String, String>,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    maxResponseBodyBytes: Int = 0
): PinnedWebhookResponse {
    require(connectTimeoutMs > 0 && readTimeoutMs > 0)
    require(maxResponseBodyBytes in 0..64 * 1024)
    val uri = URI(url)
    val scheme = uri.scheme?.lowercase().orEmpty()
    val host = uri.host?.trim()?.removeSurrounding("[", "]")?.removeSuffix(".")?.takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("webhook host is missing")
    if (uri.userInfo != null || uri.fragment != null || scheme !in setOf("http", "https")) {
        throw IllegalArgumentException("webhook URL is invalid")
    }
    val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
    require(port in 1..65_535) { "webhook port is invalid" }
    if (!com.maodouchat.server.repository.BotRepository.isAllowedWebhookUrl(url)) {
        throw SecurityException("webhook URL is not allowed")
    }

    val addresses = InetAddress.getAllByName(host).distinctBy { it.hostAddress }
    if (addresses.isEmpty()) throw SecurityException("webhook DNS resolution failed")
    val allowLoopback = scheme == "http"
    if (addresses.any { !it.isAllowedWebhookAddress(allowLoopback) }) {
        throw SecurityException("webhook DNS resolved to a blocked address")
    }

    val requestTarget = buildString {
        append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
        uri.rawQuery?.let { append('?').append(it) }
    }
    val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
    val safeHeaders = headers.mapKeys { (name, _) ->
        require(WEBHOOK_HTTP_HEADER_NAME.matches(name)) { "webhook request header name is invalid" }
        name
    }.mapValues { (_, value) ->
        require(value.all { it == '\t' || it in ' '..'~' }) { "webhook request header value is invalid" }
        value
    }
    require(safeHeaders.keys.none { it.lowercase() in WEBHOOK_RESERVED_REQUEST_HEADERS }) {
        "webhook request header is reserved"
    }

    return openPinnedWebhookSocket(scheme, host, addresses, port, connectTimeoutMs, readTimeoutMs).use { socket ->
        val output = socket.getOutputStream().buffered()
        val hostHeader = if (host.contains(':')) "[$host]" else host
        val includePort = (scheme == "https" && port != 443) || (scheme == "http" && port != 80)
        output.write("POST $requestTarget HTTP/1.1\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Host: $hostHeader${if (includePort) ":$port" else ""}\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Content-Type: application/json\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Content-Length: ${bodyBytes.size}\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Connection: close\r\n".toByteArray(StandardCharsets.US_ASCII))
        safeHeaders.forEach { (name, value) ->
            output.write("$name: $value\r\n".toByteArray(StandardCharsets.US_ASCII))
        }
        output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(bodyBytes)
        output.flush()
        readPinnedWebhookResponse(socket.getInputStream(), maxResponseBodyBytes)
    }
}

internal fun InetAddress.isAllowedWebhookAddress(allowLoopback: Boolean): Boolean {
    if (allowLoopback) return isLoopbackAddress
    if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) return false
    if (this !is Inet4Address) {
        val octets = address.map { it.toInt() and 0xff }
        if (octets.size != 16 || octets[0] !in 0x20..0x3f) return false
        return when {
            // IANA special-purpose / transition ranges must not tunnel to a blocked IPv4 target.
            octets[0] == 0x20 && octets[1] == 0x01 && octets[2] <= 0x01 -> false // 2001::/23
            octets[0] == 0x20 && octets[1] == 0x01 && octets[2] == 0x0d && octets[3] == 0xb8 -> false // 2001:db8::/32
            octets[0] == 0x20 && octets[1] == 0x02 -> false // 2002::/16 (6to4)
            octets[0] == 0x3f && octets[1] == 0xff && (octets[2] and 0xf0) == 0 -> false // 3fff::/20
            else -> true
        }
    }
    val octets = address.map { it.toInt() and 0xff }
    val first = octets[0]
    val second = octets[1]
    return when {
        first == 0 || first == 10 || first == 127 || first >= 224 -> false
        first == 100 && second in 64..127 -> false
        first == 169 && second == 254 -> false
        first == 168 && second == 63 && octets[2] == 129 && octets[3] == 16 -> false
        first == 172 && second in 16..31 -> false
        first == 192 && second == 168 -> false
        first == 198 && second in 18..19 -> false
        first == 192 && second == 0 && octets[2] in setOf(0, 2) -> false
        first == 192 && second == 31 && octets[2] == 196 -> false
        first == 192 && second == 52 && octets[2] == 193 -> false
        first == 192 && second == 88 && octets[2] == 99 -> false
        first == 192 && second == 175 && octets[2] == 48 -> false
        first == 198 && second == 51 && octets[2] == 100 -> false
        first == 203 && second == 0 && octets[2] == 113 -> false
        else -> true
    }
}

private fun openPinnedWebhookSocket(
    scheme: String,
    host: String,
    addresses: List<InetAddress>,
    port: Int,
    connectTimeoutMs: Int,
    readTimeoutMs: Int
): Socket {
    var lastFailure: Exception? = null
    val setupDeadlineNanos = System.nanoTime() + connectTimeoutMs * 1_000_000L
    addresses.forEachIndexed { index, address ->
        val remainingNanos = setupDeadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) return@forEachIndexed
        val remainingAddresses = addresses.size - index
        val attemptConnectTimeoutMs = (remainingNanos / 1_000_000L / remainingAddresses)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        try {
            return openSinglePinnedWebhookSocket(
                scheme,
                host,
                address,
                port,
                attemptConnectTimeoutMs,
                readTimeoutMs,
                setupDeadlineNanos
            )
        } catch (failure: Exception) {
            lastFailure?.let(failure::addSuppressed)
            lastFailure = failure
        }
    }
    throw lastFailure ?: java.net.SocketTimeoutException("webhook connection setup timed out")
}

private fun openSinglePinnedWebhookSocket(
    scheme: String,
    host: String,
    address: InetAddress,
    port: Int,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    setupDeadlineNanos: Long
): Socket {
    val plain = Socket()
    var activeSocket: Socket = plain
    try {
        plain.connect(InetSocketAddress(address, port), connectTimeoutMs)
        if (scheme == "http") {
            plain.soTimeout = readTimeoutMs
            return plain
        }
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(plain, host, port, true) as SSLSocket
        activeSocket = tls
        val handshakeTimeoutMs = ((setupDeadlineNanos - System.nanoTime()) / 1_000_000L)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        tls.soTimeout = handshakeTimeoutMs
        val parameters = tls.sslParameters
        parameters.endpointIdentificationAlgorithm = "HTTPS"
        if (!host.isIpLiteralHost()) {
            parameters.serverNames = listOf(SNIHostName(host))
        }
        tls.sslParameters = parameters
        tls.startHandshake()
        tls.soTimeout = readTimeoutMs
        return tls
    } catch (failure: Exception) {
        runCatching { activeSocket.close() }
        if (activeSocket !== plain) runCatching { plain.close() }
        throw failure
    }
}

private fun String.isIpLiteralHost(): Boolean {
    if (contains(':')) return true
    val parts = split('.')
    return parts.size == 4 && parts.all { part ->
        part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
    }
}

private data class PinnedWebhookResponseHead(
    val statusCode: Int,
    val chunked: Boolean,
    val contentLength: Long?
)

internal fun readPinnedWebhookResponse(input: InputStream, maxBodyBytes: Int): PinnedWebhookResponse {
    require(maxBodyBytes in 0..64 * 1024)
    var informationalResponses = 0
    var finalHead: PinnedWebhookResponseHead? = null
    while (finalHead == null) {
        val candidate = readPinnedWebhookResponseHead(input)
        if (candidate.statusCode !in 100..199) {
            finalHead = candidate
        } else {
            if (candidate.statusCode == 101) {
                throw IllegalStateException("webhook protocol upgrades are unsupported")
            }
            informationalResponses++
            if (informationalResponses > 5) {
                throw IllegalStateException("webhook sent too many informational responses")
            }
        }
    }
    val responseHead = requireNotNull(finalHead)
    if (maxBodyBytes == 0) return PinnedWebhookResponse(responseHead.statusCode, "")
    val bytes = when {
        responseHead.statusCode == 204 || responseHead.statusCode == 304 -> ByteArray(0)
        responseHead.chunked -> input.readChunkedPrefix(maxBodyBytes)
        responseHead.contentLength != null -> input.readPrefix(minOf(responseHead.contentLength, maxBodyBytes.toLong()).toInt())
        else -> input.readPrefix(maxBodyBytes)
    }
    return PinnedWebhookResponse(responseHead.statusCode, String(bytes, StandardCharsets.UTF_8))
}

private fun readPinnedWebhookResponseHead(input: InputStream): PinnedWebhookResponseHead {
    val statusLine = input.readAsciiLine(8 * 1024)
        ?: throw IllegalStateException("webhook response is empty")
    val statusMatch = WEBHOOK_HTTP_STATUS_LINE.matchEntire(statusLine)
        ?: throw IllegalStateException("webhook response status is invalid")
    val statusCode = statusMatch.groupValues[1].toInt()
    var headerBytes = statusLine.length + 2
    var sawTransferEncoding = false
    val transferEncodings = mutableListOf<String>()
    val contentLengths = mutableListOf<Long>()
    while (true) {
        val line = input.readAsciiLine(MAX_WEBHOOK_RESPONSE_HEADER_BYTES - headerBytes)
            ?: throw IllegalStateException("webhook response headers are incomplete")
        headerBytes += line.length + 2
        if (headerBytes > MAX_WEBHOOK_RESPONSE_HEADER_BYTES) {
            throw IllegalStateException("webhook response headers are too large")
        }
        if (line.isEmpty()) break
        val separator = line.indexOf(':')
        if (separator <= 0) throw IllegalStateException("webhook response header is invalid")
        val name = line.substring(0, separator)
        val value = line.substring(separator + 1).trim()
        if (!WEBHOOK_HTTP_HEADER_NAME.matches(name)) {
            throw IllegalStateException("webhook response header name is invalid")
        }
        if (value.any { (it < ' ' && it != '\t') || it == '\u007f' }) {
            throw IllegalStateException("webhook response header value is invalid")
        }
        if (name.equals("Transfer-Encoding", true)) {
            sawTransferEncoding = true
            transferEncodings += value.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        }
        if (name.equals("Content-Length", true)) {
            val parsed = value.split(',').map { part ->
                part.trim().toLongOrNull()?.takeIf { it >= 0L }
                    ?: throw IllegalStateException("webhook content length is invalid")
            }
            contentLengths += parsed
        }
    }
    val contentLength = contentLengths.firstOrNull()
    if (contentLengths.any { it != contentLength }) {
        throw IllegalStateException("webhook content lengths conflict")
    }
    if (sawTransferEncoding && transferEncodings != listOf("chunked")) {
        throw IllegalStateException("webhook transfer encoding is unsupported")
    }
    if (sawTransferEncoding && contentLength != null) {
        throw IllegalStateException("webhook response framing is ambiguous")
    }
    return PinnedWebhookResponseHead(
        statusCode = statusCode,
        chunked = sawTransferEncoding,
        contentLength = contentLength
    )
}

private val WEBHOOK_HTTP_STATUS_LINE = Regex("^HTTP/1\\.[01] ([1-5][0-9]{2})(?:[ \\t].*)?$")
private val WEBHOOK_HTTP_HEADER_NAME = Regex("^[!#\$%&'*+.^_`|~0-9A-Za-z-]+$")
private val WEBHOOK_RESERVED_REQUEST_HEADERS = setOf(
    "connection",
    "content-length",
    "content-type",
    "expect",
    "host",
    "proxy-connection",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade"
)

private fun InputStream.readAsciiLine(maxBytes: Int): String? {
    if (maxBytes <= 0) throw IllegalStateException("line is too large")
    val output = ByteArrayOutputStream(minOf(maxBytes, 256))
    while (output.size() < maxBytes) {
        val next = read()
        if (next < 0) return if (output.size() == 0) null else String(output.toByteArray(), StandardCharsets.US_ASCII)
        if (next == '\n'.code) {
            val bytes = output.toByteArray()
            val length = if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
            return String(bytes, 0, length, StandardCharsets.US_ASCII)
        }
        output.write(next)
    }
    throw IllegalStateException("line is too large")
}

private fun InputStream.readPrefix(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (output.size() < maxBytes) {
        val read = read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
        if (read < 0) break
        if (read > 0) output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun InputStream.readChunkedPrefix(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    while (output.size() < maxBytes) {
        val chunkSize = readAsciiLine(128)?.substringBefore(';')?.trim()?.toLongOrNull(16)
            ?: throw IllegalStateException("webhook chunk size is invalid")
        if (chunkSize == 0L) break
        var remaining = chunkSize
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0 && output.size() < maxBytes) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining, (maxBytes - output.size()).toLong()).toInt())
            if (read < 0) throw IllegalStateException("webhook chunk is incomplete")
            if (read > 0) {
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
        if (output.size() >= maxBytes) break
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0 && read() < 0) throw IllegalStateException("webhook chunk is incomplete")
            remaining -= if (skipped > 0) skipped else 1
        }
        if (readAsciiLine(2) != "") throw IllegalStateException("webhook chunk terminator is invalid")
    }
    return output.toByteArray()
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
        signedPreKeyId > 0 &&
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
    AdminAccess.isAdmin(userId) || userRepo.isModerator(userId)

internal fun restrictionMessage(until: Long, action: String): String {
    val remainingMinutes = ((until - System.currentTimeMillis()).coerceAtLeast(0) + 59_999L) / 60_000L
    return "$action，约 ${remainingMinutes.coerceAtLeast(1)} 分钟后恢复"
}

// ── Signal 密钥辅助 ───────────────────

internal fun SignalKeyRepository.DeviceBundle.toPreKeyBundleResponse(): PreKeyBundleResponse = PreKeyBundleResponse(
    registrationId = registrationId,
    deviceId = deviceId,
    identityKey = identityKey,
    signedPreKeyId = signedPreKeyId,
    signedPreKey = signedPreKey,
    signedPreKeySignature = signedPreKeySignature,
    preKeyId = preKeyId,
    preKey = preKey
)

internal fun SignalKeyRepository.DeviceBundle.toDevicePreKeyBundleResponse(): DevicePreKeyBundleResponse = DevicePreKeyBundleResponse(
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
    chatRepo: ChatRepository,
    preKeyFetchTracker: BoundedRateLimiter,
    allowSelf: Boolean = false
): Boolean {
    if (targetUserId == requesterId) {
        if (allowSelf) return true
        respond(HttpStatusCode.BadRequest, ErrorResponse("不能获取自己的密钥包"))
        return false
    }
    if (!chatRepo.shareChat(requesterId, targetUserId)) {
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
