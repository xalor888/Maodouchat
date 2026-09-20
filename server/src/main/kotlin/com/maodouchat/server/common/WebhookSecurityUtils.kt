package com.maodouchat.server.common

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

// G45：从 plugins/RoutingHelpers.kt 整体下移到中立包，使 service/ 与 repository/ 不再反向依赖
// route 装配层。逻辑逐字保留——这里守着 DLL 重绑定窗口（校验通过到真正建连之间），
// 只改了所在包名，未改任何判定分支。

internal data class PinnedWebhookResponse(
    val statusCode: Int,
    val body: String
)

/** 响应头读取上限，防对端用超大 header 撑爆内存。随集群一起从 route 层下移。 */
internal const val MAX_WEBHOOK_RESPONSE_HEADER_BYTES = 32 * 1024

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
