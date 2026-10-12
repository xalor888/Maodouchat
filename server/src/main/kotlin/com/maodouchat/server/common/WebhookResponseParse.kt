package com.maodouchat.server.common

// 响应解析簇：原 WebhookSecurityUtils 顶层声明按域拆出的簇（同包调用，零行为改动）。
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

// G45：从 plugins/RoutingHelpers.kt 整体下移到中立包，使 service/ 与 repository/ 不再反向依赖
// route 装配层。逻辑逐字保留——这里守着 DLL 重绑定窗口（校验通过到真正建连之间），
// 只改了所在包名，未改任何判定分支。

internal data class PinnedWebhookResponse(
    val statusCode: Int,
    val body: String
)


/** 响应头读取上限，防对端用超大 header 撑爆内存。随集群一起从 route 层下移。 */
internal const val MAX_WEBHOOK_RESPONSE_HEADER_BYTES = 32 * 1024


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


private data class PinnedWebhookResponseHead(
    val statusCode: Int,
    val chunked: Boolean,
    val contentLength: Long?
)


private val WEBHOOK_HTTP_STATUS_LINE = Regex("^HTTP/1\\.[01] ([1-5][0-9]{2})(?:[ \\t].*)?$")


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
