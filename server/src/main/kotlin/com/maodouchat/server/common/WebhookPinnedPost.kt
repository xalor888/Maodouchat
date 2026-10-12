package com.maodouchat.server.common

// 钉死地址建连发送簇：原 WebhookSecurityUtils 顶层声明按域拆出的簇（同包调用，零行为改动）。
import com.maodouchat.server.repository.isAllowedWebhookUrl
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

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


internal val WEBHOOK_HTTP_HEADER_NAME = Regex("^[!#\$%&'*+.^_`|~0-9A-Za-z-]+$")


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
