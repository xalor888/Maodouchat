package com.maodouchat.server.common

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// isValidSignalPayload restated by hand: the type must be one of the
// six lowercase signaling types, a non-terminal type needs a non-blank
// payload, and the payload is capped at 32_768 UTF-16 units.
internal fun isValidSignalPayloadOracle179(type: String, payload: String): Boolean {
    val payloadRequired = type !in setOf("hang-up", "busy", "reject")
    return type in setOf("offer", "answer", "ice-candidate", "hang-up", "busy", "reject") &&
        (!payloadRequired || payload.isNotBlank()) &&
        payload.length <= 32_768
}

// isValidCallId restated by hand: non-blank, at most 100 units, and
// the whole id matches the call-id alphabet.
internal val CALL_ID_REGEX179: Regex = Regex("^[A-Za-z0-9_-]{1,100}$")

internal fun isValidCallIdOracle179(callId: String): Boolean =
    callId.isNotBlank() && callId.length <= 100 && CALL_ID_REGEX179.matches(callId)

// readPinnedWebhookResponse restated by hand: a byte-at-a-time HTTP/1.x
// head parser over a scripted stream. Informational (1xx) responses are
// skipped (at most five, never a 101 upgrade); the body is read by
// framing — empty for 204/304, chunked, or a capped content-length
// prefix — and decoded as UTF-8. Every failure is the same
// IllegalStateException message the production code throws.
internal class WebhookStreamOracle179(private val bytes: ByteArray) {
    private var pos: Int = 0

    fun read(): Int =
        if (pos < bytes.size) bytes[pos++].toInt() and 0xFF else -1

    fun readInto(buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= bytes.size) return -1
        val k = minOf(len, bytes.size - pos)
        bytes.copyInto(buf, off, pos, pos + k)
        pos += k
        return k
    }

    fun skip(n: Long): Long {
        val k = minOf(n, (bytes.size - pos).toLong())
        pos += k.toInt()
        return k
    }

    fun readAsciiLine(maxBytes: Int): String? {
        if (maxBytes <= 0) throw IllegalStateException("line is too large")
        val output = ByteArrayOutputStream(minOf(maxBytes, 256))
        while (output.size() < maxBytes) {
            val next = read()
            if (next < 0) {
                return if (output.size() == 0) {
                    null
                } else {
                    String(output.toByteArray(), Charsets.US_ASCII)
                }
            }
            if (next == '\n'.code) {
                val raw = output.toByteArray()
                val length = if (raw.lastOrNull() == '\r'.code.toByte()) raw.size - 1 else raw.size
                return String(raw, 0, length, Charsets.US_ASCII)
            }
            output.write(next)
        }
        throw IllegalStateException("line is too large")
    }

    fun readPrefix(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        while (output.size() < maxBytes) {
            val read = readInto(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
            if (read < 0) break
            if (read > 0) output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    fun readChunkedPrefix(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        while (output.size() < maxBytes) {
            val chunkSize = readAsciiLine(128)?.substringBefore(';')?.trim()?.toLongOrNull(16)
                ?: throw IllegalStateException("webhook chunk size is invalid")
            if (chunkSize == 0L) break
            var remaining = chunkSize
            val buffer = ByteArray(8192)
            while (remaining > 0 && output.size() < maxBytes) {
                val read = readInto(
                    buffer, 0,
                    minOf(buffer.size.toLong(), remaining, (maxBytes - output.size()).toLong()).toInt()
                )
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
}

internal data class WebhookHeadOracle179(
    val statusCode: Int,
    val chunked: Boolean,
    val contentLength: Long?,
)

internal val WEBHOOK_STATUS_LINE179: Regex =
    Regex("^HTTP/1\\.[01] ([1-5][0-9]{2})(?:[ \\t].*)?$")

internal val WEBHOOK_HEADER_NAME179: Regex =
    Regex("^[!#\$%&'*+.^_`|~0-9A-Za-z-]+$")

internal fun readWebhookHeadOracle179(stream: WebhookStreamOracle179): WebhookHeadOracle179 {
    val statusLine = stream.readAsciiLine(8 * 1024)
        ?: throw IllegalStateException("webhook response is empty")
    val statusMatch = WEBHOOK_STATUS_LINE179.matchEntire(statusLine)
        ?: throw IllegalStateException("webhook response status is invalid")
    val statusCode = statusMatch.groupValues[1].toInt()
    var headerBytes = statusLine.length + 2
    var sawTransferEncoding = false
    val transferEncodings = mutableListOf<String>()
    val contentLengths = mutableListOf<Long>()
    while (true) {
        val line = stream.readAsciiLine(32 * 1024 - headerBytes)
            ?: throw IllegalStateException("webhook response headers are incomplete")
        headerBytes += line.length + 2
        if (headerBytes > 32 * 1024) {
            throw IllegalStateException("webhook response headers are too large")
        }
        if (line.isEmpty()) break
        val separator = line.indexOf(':')
        if (separator <= 0) throw IllegalStateException("webhook response header is invalid")
        val name = line.substring(0, separator)
        val value = line.substring(separator + 1).trim()
        if (!WEBHOOK_HEADER_NAME179.matches(name)) {
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
    return WebhookHeadOracle179(
        statusCode = statusCode,
        chunked = sawTransferEncoding,
        contentLength = contentLength
    )
}

internal fun readPinnedWebhookResponseOracle179(
    bytes: ByteArray,
    maxBodyBytes: Int,
): PinnedWebhookResponse {
    require(maxBodyBytes in 0..64 * 1024)
    val stream = WebhookStreamOracle179(bytes)
    var informationalResponses = 0
    var finalHead: WebhookHeadOracle179? = null
    while (finalHead == null) {
        val candidate = readWebhookHeadOracle179(stream)
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
    val body = when {
        responseHead.statusCode == 204 || responseHead.statusCode == 304 -> ByteArray(0)
        responseHead.chunked -> stream.readChunkedPrefix(maxBodyBytes)
        responseHead.contentLength != null ->
            stream.readPrefix(minOf(responseHead.contentLength, maxBodyBytes.toLong()).toInt())
        else -> stream.readPrefix(maxBodyBytes)
    }
    return PinnedWebhookResponse(responseHead.statusCode, String(body, Charsets.UTF_8))
}

// Production isValidSignalPayload checks the signaling type/payload.
internal fun isValidSignalPayloadProduction179(type: String, payload: String): Boolean =
    CallSignalingValidators.isValidSignalPayload(type, payload)

// Production isValidCallId checks the call id shape.
internal fun isValidCallIdProduction179(callId: String): Boolean =
    CallSignalingValidators.isValidCallId(callId)

// Production readPinnedWebhookResponse parses a real response stream.
internal fun readPinnedWebhookResponseProduction179(
    bytes: ByteArray,
    maxBodyBytes: Int,
): PinnedWebhookResponse =
    readPinnedWebhookResponse(ByteArrayInputStream(bytes), maxBodyBytes)

// ---------- generators ----------

internal data class ServerCommonCase179(
    val type: String,
    val payload: String,
    val callId: String,
    val bytes: List<Int>,
    val maxBodyBytes: Int,
)

internal val CALLSIG_TYPE_POOL179: List<String> = listOf(
    "", " ", "offer", "answer", "ice-candidate", "hang-up", "busy", "reject",
    "OFFER", "Offer", "offer ", " offer", "hang_up", "ice_candidate",
    "call", "hang-up\n", "a".repeat(30),
)

internal val CALLSIG_PAYLOAD_POOL179: List<String> = listOf(
    "", " ", "  ", "x", "{}", "a".repeat(100), "a".repeat(32_767),
    "a".repeat(32_768), "a".repeat(32_769), "a".repeat(40_000),
    "  x  ", "\u00a0", "sdp-content", "x\ny",
)

internal val CALLSIG_CALLID_POOL179: List<String> = listOf(
    "", " ", "abc", "a".repeat(99), "a".repeat(100), "a".repeat(101),
    "a-b_c9", "a b", "a/b", "a:b", "a.b", "a@b", "  abc  ", "a\nb",
    "a".repeat(200), "ABC-123_xyz", "é", "a\u00e9b",
)

internal val WEBHOOK_STATUS_POOL179: List<String> = listOf(
    "", "HTTP/1.1 200 OK", "HTTP/1.0 200 OK", "HTTP/1.1 101 Switching Protocols",
    "HTTP/1.1 100 Continue", "HTTP/1.1 204 No Content", "HTTP/1.1 304 Not Modified",
    "HTTP/1.1 404 Not Found", "HTTP/1.1 500 Internal Server Error",
    "HTTP/1.1 200", "HTTP/1.1 200 ", "HTTP/1.1 200\tOK", "HTTP/2 200 OK",
    "HTTP/1.1 99 OK", "HTTP/1.1 600 OK", "HTTP/1.1 20 OK", "HTTP/1.1 2000 OK",
    "http/1.1 200 OK", "HTTP/1.1  200 OK", "HTTP/1.1 200  OK",
    "HTTP/1.1 199 Early Hints", "HTTP/1.1 103 Early Hints",
)

internal val WEBHOOK_HEADER_POOL179: List<String> = listOf(
    "", "Content-Length: 5", "Content-Length: 0", "Content-Length: -1",
    "Content-Length: abc", "Content-Length: 5, 5", "Content-Length: 5, 6",
    "Content-Length: 9999999999999999999999",
    "Transfer-Encoding: chunked", "Transfer-Encoding: Chunked",
    "Transfer-Encoding: gzip", "Transfer-Encoding: chunked, gzip",
    "Transfer-Encoding: chunked,chunked",
    "Transfer-Encoding: chunked\r\nContent-Length: 5",
    "Content-Type: application/json", "X-Custom: v", "Bad Header: v",
    ": novalue", "NoColon", "X-Empty:", "X-Space:   ",
    "X-Tab:\tvalue", "X-Ctrl:\u0001bad", "X-Del:\u007fbad",
    "content-length: 5", "CONTENT-LENGTH: 5",
    "Transfer-Encoding : chunked", "Transfer-Encoding:  chunked  ",
)

internal val WEBHOOK_BODY_POOL179: List<String> = listOf(
    "", "hello", "{}", "{\"ok\":true}", "a".repeat(100), "a".repeat(1000),
    "caf\u00e9", "\uD83D\uDE00", "line1\nline2", "a\u0000b",
)

internal val WEBHOOK_CHUNK_POOL179: List<String> = listOf(
    "0", "5", "a", "A", "f", "10", "ff", "FF", "0x10", "-1", "+5",
    " 5 ", "5;ext=x", "zz", "", "100000000000000000000",
    "5 ", "0005", "0;", "g", "1g",
)

internal fun randomWebhookBytes179(rnd: Random): List<Int> {
    val mode = rnd.nextInt(5)
    val sb = StringBuilder()
    when (mode) {
        0 -> {
            // Well-formed response, optionally chunked or length-framed.
            sb.append(WEBHOOK_STATUS_POOL179[rnd.nextInt(WEBHOOK_STATUS_POOL179.size)])
            sb.append("\r\n")
            val framing = rnd.nextInt(3)
            when (framing) {
                0 -> sb.append("Content-Length: ${rnd.nextInt(0, 20)}\r\n")
                1 -> sb.append("Transfer-Encoding: chunked\r\n")
                else -> sb.append("Content-Type: application/json\r\n")
            }
            if (rnd.nextInt(3) == 0) {
                sb.append(WEBHOOK_HEADER_POOL179[rnd.nextInt(WEBHOOK_HEADER_POOL179.size)])
                sb.append("\r\n")
            }
            sb.append("\r\n")
            when (framing) {
                0, 2 -> sb.append(WEBHOOK_BODY_POOL179[rnd.nextInt(WEBHOOK_BODY_POOL179.size)])
                else -> {
                    repeat(rnd.nextInt(0, 3)) {
                        val chunk = "ab".repeat(rnd.nextInt(0, 4))
                        sb.append(chunk.length.toString(16))
                        sb.append("\r\n")
                        sb.append(chunk)
                        sb.append("\r\n")
                    }
                    sb.append("0\r\n\r\n")
                }
            }
        }
        1 -> {
            // Random header block after a status line.
            sb.append(WEBHOOK_STATUS_POOL179[rnd.nextInt(WEBHOOK_STATUS_POOL179.size)])
            sb.append("\r\n")
            repeat(rnd.nextInt(0, 4)) {
                sb.append(WEBHOOK_HEADER_POOL179[rnd.nextInt(WEBHOOK_HEADER_POOL179.size)])
                sb.append(if (rnd.nextInt(2) == 0) "\r\n" else "\n")
            }
            sb.append("\r\n")
            sb.append(WEBHOOK_BODY_POOL179[rnd.nextInt(WEBHOOK_BODY_POOL179.size)])
        }
        2 -> {
            // Random chunked stream.
            sb.append("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n")
            repeat(rnd.nextInt(0, 4)) {
                sb.append(WEBHOOK_CHUNK_POOL179[rnd.nextInt(WEBHOOK_CHUNK_POOL179.size)])
                sb.append("\r\n")
                sb.append("xy".repeat(rnd.nextInt(0, 4)))
                sb.append("\r\n")
            }
            sb.append("0\r\n\r\n")
        }
        3 -> {
            // Random informational responses first.
            repeat(rnd.nextInt(0, 8)) {
                sb.append("HTTP/1.1 ${100 + rnd.nextInt(0, 4)} Continue\r\n\r\n")
            }
            sb.append("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
        }
        else -> {
            // Byte soup.
            val alphabet = "HTTP/1.0123456789 :\r\nchunked.-_abcXYZ\t"
            val len = rnd.nextInt(0, 80)
            repeat(len) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
        }
    }
    val out = ArrayList<Int>()
    for (b in sb.toString().toByteArray(Charsets.UTF_8)) out.add(b.toInt() and 0xFF)
    return out
}

internal fun randomServerCommonCase179(rnd: Random): ServerCommonCase179 {
    return ServerCommonCase179(
        type = if (rnd.nextInt(2) == 0) {
            CALLSIG_TYPE_POOL179[rnd.nextInt(CALLSIG_TYPE_POOL179.size)]
        } else {
            listOf("offer", "answer", "hang-up", "x").random(rnd)
        },
        payload = if (rnd.nextInt(2) == 0) {
            CALLSIG_PAYLOAD_POOL179[rnd.nextInt(CALLSIG_PAYLOAD_POOL179.size)]
        } else {
            "p".repeat(rnd.nextInt(0, 100))
        },
        callId = if (rnd.nextInt(2) == 0) {
            CALLSIG_CALLID_POOL179[rnd.nextInt(CALLSIG_CALLID_POOL179.size)]
        } else {
            val alphabet = "abcXYZ019-_ /.\n\u00e9"
            val len = rnd.nextInt(0, 110)
            val sb = StringBuilder()
            repeat(len) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
            sb.toString()
        },
        bytes = randomWebhookBytes179(rnd),
        maxBodyBytes = if (rnd.nextInt(2) == 0) {
            listOf(0, 1, 2, 5, 100, 1024, 65_536, -1, 65_537, 100_000).random(rnd)
        } else {
            rnd.nextInt(-10, 70_000)
        },
    )
}

internal fun escJsonCommon179(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20) {
                    sb.append("\\u")
                    val v = ch.code
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

internal fun serverCommonWireJson179(c: ServerCommonCase179): String =
    "{\"type\":${escJsonCommon179(c.type)}," +
        "\"payload\":${escJsonCommon179(c.payload)}," +
        "\"callId\":${escJsonCommon179(c.callId)}," +
        "\"bytes\":[${c.bytes.joinToString(",")}]," +
        "\"maxBodyBytes\":${c.maxBodyBytes}}"

internal fun webhookProjectionJson179(r: PinnedWebhookResponse): String =
    "{\"status\":${r.statusCode},\"body\":${escJsonCommon179(r.body)}}"

internal fun serverCommonProjectionJson179(
    payloadOk: Boolean,
    callIdOk: Boolean,
    webhook: PinnedWebhookResponse,
): String =
    "{\"payload\":$payloadOk," +
        "\"callId\":$callIdOk," +
        "\"webhook\":${webhookProjectionJson179(webhook)}}"

class ServerCommonFuzzTest {

    private fun checkServerCommon179(c: ServerCommonCase179) {
        val bytes = ByteArray(c.bytes.size) { c.bytes[it].toByte() }
        // The webhook parser throws on malformed input, so each side is
        // run under runCatching and the outcomes (value or exception
        // class + message) are compared, not just the values.
        fun outcome179(block: () -> String): String =
            runCatching(block).fold(
                { it },
                { "THROW:${it.javaClass.simpleName}:${it.message ?: ""}" },
            )
        assertEquals(
            outcome179 {
                serverCommonProjectionJson179(
                    isValidSignalPayloadOracle179(c.type, c.payload),
                    isValidCallIdOracle179(c.callId),
                    readPinnedWebhookResponseOracle179(bytes, c.maxBodyBytes),
                )
            },
            outcome179 {
                serverCommonProjectionJson179(
                    isValidSignalPayloadProduction179(c.type, c.payload),
                    isValidCallIdProduction179(c.callId),
                    readPinnedWebhookResponseProduction179(bytes, c.maxBodyBytes),
                )
            },
            "serverCommon($c)"
        )
    }

    @Test
    fun pinnedBoundaries() {
        // Every signaling type against every payload shape.
        for (type in CALLSIG_TYPE_POOL179) {
            for (payload in CALLSIG_PAYLOAD_POOL179) {
                checkServerCommon179(
                    ServerCommonCase179(
                        type, payload, "abc",
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"
                            .toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF },
                        100,
                    )
                )
            }
        }
        // Every call id shape against a fixed well-formed response.
        for (callId in CALLSIG_CALLID_POOL179) {
            checkServerCommon179(
                ServerCommonCase179(
                    "offer", "{}", callId,
                    "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"
                        .toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF },
                    100,
                )
            )
        }
        // Every status line against a fixed pair of headers.
        for (status in WEBHOOK_STATUS_POOL179) {
            checkServerCommon179(
                ServerCommonCase179(
                    "offer", "{}", "abc",
                    (status + "\r\nContent-Length: 2\r\n\r\nok")
                        .toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF },
                    100,
                )
            )
        }
        // Every header line against a fixed 200 status.
        for (header in WEBHOOK_HEADER_POOL179) {
            checkServerCommon179(
                ServerCommonCase179(
                    "offer", "{}", "abc",
                    ("HTTP/1.1 200 OK\r\n" + header + "\r\n\r\nhello")
                        .toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF },
                    100,
                )
            )
        }
        // Every chunk-size spelling.
        for (chunk in WEBHOOK_CHUNK_POOL179) {
            checkServerCommon179(
                ServerCommonCase179(
                    "offer", "{}", "abc",
                    ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                        chunk + "\r\nhello\r\n0\r\n\r\n")
                        .toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF },
                    100,
                )
            )
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(179003)
        repeat(4000) { checkServerCommon179(randomServerCommonCase179(rnd)) }
    }

    @Test
    fun sweepExhaustive() {
        // Payload lengths around the 32_768 cap.
        for (len in listOf(0, 1, 32_766, 32_767, 32_768, 32_769, 33_000)) {
            checkServerCommon179(
                ServerCommonCase179(
                    "offer", "a".repeat(len), "abc",
                    "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                        .toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF },
                    100,
                )
            )
        }
        // Call-id lengths 0..110.
        for (len in 0..110) {
            checkServerCommon179(
                ServerCommonCase179(
                    "offer", "{}", "a".repeat(len),
                    "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                        .toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF },
                    100,
                )
            )
        }
        // maxBodyBytes sweep across the require window.
        for (max in listOf(-2, -1, 0, 1, 2, 100, 65_535, 65_536, 65_537, 100_000)) {
            checkServerCommon179(
                ServerCommonCase179(
                    "offer", "{}", "abc",
                    "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello"
                        .toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF },
                    max,
                )
            )
        }
        // Informational counts 0..7 before a final 200.
        for (n in 0..7) {
            val head = "HTTP/1.1 100 Continue\r\n\r\n".repeat(n) +
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"
            checkServerCommon179(
                ServerCommonCase179(
                    "offer", "{}", "abc",
                    head.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF },
                    100,
                )
            )
        }
        // Chunked bodies with a growing payload.
        for (len in listOf(0, 1, 5, 100, 1000, 5000)) {
            val body = "x".repeat(len)
            val wire = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                body.length.toString(16) + "\r\n" + body + "\r\n0\r\n\r\n"
            checkServerCommon179(
                ServerCommonCase179(
                    "offer", "{}", "abc",
                    wire.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF },
                    100,
                )
            )
        }
    }
}
