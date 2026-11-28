package com.maodouchat.core.crypto

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import kotlinx.serialization.SerializationException
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// Exception mapping restated by hand. Ground truth, read from
// SignalKeyExchangeModels.kt / SignalExchangeModels.kt /
// NetworkErrorModels.kt / ApiExceptionModels.kt:
//   - SignalExchangeException passes through untouched.
//   - ApiException maps kind-for-kind (HTTP/TIMEOUT/NETWORK/
//     INVALID_RESPONSE/UNEXPECTED), copying statusCode, serverMessage
//     and serverCode, cause = the ApiException itself.
//   - SocketTimeoutException -> TIMEOUT (checked before IOException,
//     which it extends); any other IOException -> NETWORK.
//   - SerializationException / IllegalArgumentException ->
//     INVALID_RESPONSE. Anything else (RuntimeException, Error, plain
//     Exception, IllegalStateException) -> UNEXPECTED.
//   - shouldUseLegacyBundleEndpoint: failure == HTTP && statusCode == 404.
// The cause chain never changes the outcome: only the outermost type
// decides, so the oracle reads kind/failure/status/message/code off the
// description and ignores cause.
internal data class ExDesc187(
    val kind: String,
    val failure: String?,
    val statusCode: Int?,
    val serverMessage: String?,
    val serverCode: String?,
    val cause: ExDesc187?
)

internal data class ExchangeResult187(
    val failure: String,
    val statusCode: Int?,
    val serverMessage: String?,
    val serverCode: String?,
    val legacy: Boolean
)

internal fun exchangeResultOracle187(d: ExDesc187): ExchangeResult187 {
    val failure: String
    var status: Int? = null
    var msg: String? = null
    var code: String? = null
    when (d.kind) {
        "signal", "api" -> {
            failure = d.failure ?: "UNEXPECTED"
            status = d.statusCode
            msg = d.serverMessage
            code = d.serverCode
        }
        "timeout" -> failure = "TIMEOUT"
        "io", "io-connect", "io-unknownhost", "io-file", "io-eof", "io-socket" ->
            failure = "NETWORK"
        "serial", "illegal", "illegal-numberformat" -> failure = "INVALID_RESPONSE"
        else -> failure = "UNEXPECTED"
    }
    return ExchangeResult187(failure, status, msg, code, failure == "HTTP" && status == 404)
}

internal fun escJsonExchange187(s: String): String {
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
                if (ch.code < 0x20 || ch.code in 0xD800..0xDFFF) {
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

internal fun optStrWireJson187(v: String?): String = v?.let { escJsonExchange187(it) } ?: "null"

internal fun optIntWireJson187(v: Int?): String = v?.toString() ?: "null"

internal fun exDescWireJson187(d: ExDesc187): String =
    "{\"kind\":${escJsonExchange187(d.kind)}," +
        "\"failure\":${optStrWireJson187(d.failure)}," +
        "\"statusCode\":${optIntWireJson187(d.statusCode)}," +
        "\"serverMessage\":${optStrWireJson187(d.serverMessage)}," +
        "\"serverCode\":${optStrWireJson187(d.serverCode)}," +
        "\"cause\":${d.cause?.let { exDescWireJson187(it) } ?: "null"}}"

internal fun exchangeResultWireJson187(r: ExchangeResult187): String =
    "{\"failure\":${escJsonExchange187(r.failure)}," +
        "\"statusCode\":${optIntWireJson187(r.statusCode)}," +
        "\"serverMessage\":${optStrWireJson187(r.serverMessage)}," +
        "\"serverCode\":${optStrWireJson187(r.serverCode)}," +
        "\"legacy\":${r.legacy}}"

// ---------- production ----------

internal fun buildThrowable187(d: ExDesc187): Throwable {
    val cause = d.cause?.let { buildThrowable187(it) }
    val t: Throwable = when (d.kind) {
        "signal" -> SignalExchangeException(
            SignalExchangeFailure.valueOf(d.failure ?: "UNEXPECTED"),
            d.statusCode, d.serverMessage, d.serverCode, cause
        )
        "api" -> ApiException(
            ApiFailureKind.valueOf(d.failure ?: "UNEXPECTED"),
            d.statusCode, d.serverMessage, d.serverCode, cause = cause
        )
        "timeout" -> SocketTimeoutException(d.serverMessage ?: "timeout")
        "io" -> IOException(d.serverMessage ?: "io")
        "io-connect" -> ConnectException(d.serverMessage ?: "connect")
        "io-unknownhost" -> UnknownHostException(d.serverMessage ?: "unknown host")
        "io-file" -> FileNotFoundException(d.serverMessage ?: "missing")
        "io-eof" -> EOFException(d.serverMessage ?: "eof")
        "io-socket" -> SocketException(d.serverMessage ?: "socket")
        "serial" -> SerializationException(d.serverMessage ?: "bad payload")
        "illegal" -> IllegalArgumentException(d.serverMessage ?: "bad argument")
        "illegal-numberformat" -> NumberFormatException(d.serverMessage ?: "not a number")
        "illegal-state" -> IllegalStateException(d.serverMessage ?: "bad state")
        "runtime" -> RuntimeException(d.serverMessage ?: "runtime")
        "error" -> Error(d.serverMessage ?: "error")
        "npe" -> NullPointerException(d.serverMessage ?: "null")
        "oom" -> OutOfMemoryError(d.serverMessage ?: "out of memory")
        else -> Exception(d.serverMessage ?: "other")
    }
    // Signal/api take the cause in the constructor; the stdlib types
    // get it here. initCause on an already-set cause throws, so only
    // do it for the kinds built without one.
    if (cause != null && d.kind != "signal" && d.kind != "api") {
        runCatching { t.initCause(cause) }
    }
    return t
}

internal fun exchangeResultProduction187(d: ExDesc187): ExchangeResult187 {
    val t = buildThrowable187(d)
    val mapped = t.toExchangeException()
    return ExchangeResult187(
        mapped.failure.name, mapped.statusCode, mapped.serverMessage,
        mapped.serverCode, t.shouldUseLegacyBundleEndpoint()
    )
}

// ---------- generators ----------

internal val EXCHANGE_KINDS187 = listOf(
    "signal", "api", "timeout", "io", "io-connect", "io-unknownhost",
    "io-file", "io-eof", "io-socket", "serial", "illegal",
    "illegal-numberformat", "illegal-state", "runtime", "error",
    "other", "npe", "oom"
)

internal val EXCHANGE_SIGNAL_FAILURES187 = listOf(
    "SIGNED_PREKEY_MISSING", "HTTP", "EMPTY_RESPONSE", "TIMEOUT",
    "NETWORK", "INVALID_RESPONSE", "UNEXPECTED"
)

internal val EXCHANGE_API_FAILURES187 = listOf(
    "HTTP", "TIMEOUT", "NETWORK", "INVALID_RESPONSE", "UNEXPECTED"
)

internal val EXCHANGE_STATUSES187 = listOf<Int?>(
    null, 0, 200, 400, 401, 403, 404, 405, 408, 409, 410, 429, 500, 502, 503,
    -1, Int.MAX_VALUE, Int.MIN_VALUE
)

internal val EXCHANGE_MESSAGES187 = listOf<String?>(
    null, "", "  ", "timeout", "DEVICE_ID_CONFLICT", "not found",
    "caf\u00e9", "\uD83D\uDE00", "x".repeat(200), "a\nb", "  padded  ",
    "rate limited", "bad request"
)

internal val EXCHANGE_CODES187 = listOf<String?>(
    null, "", "DEVICE_ID_CONFLICT", "RATE_LIMITED", "  ", "caf\u00e9",
    "SIGNED_PREKEY_MISSING"
)

internal fun randomExDesc187(rnd: Random, depth: Int): ExDesc187 {
    val kind = EXCHANGE_KINDS187[rnd.nextInt(EXCHANGE_KINDS187.size)]
    val failure = when (kind) {
        "signal" -> EXCHANGE_SIGNAL_FAILURES187[rnd.nextInt(EXCHANGE_SIGNAL_FAILURES187.size)]
        "api" -> EXCHANGE_API_FAILURES187[rnd.nextInt(EXCHANGE_API_FAILURES187.size)]
        else -> null
    }
    val cause = if (depth > 0 && rnd.nextInt(10) < 4) randomExDesc187(rnd, depth - 1) else null
    return ExDesc187(
        kind = kind,
        failure = failure,
        statusCode = EXCHANGE_STATUSES187[rnd.nextInt(EXCHANGE_STATUSES187.size)],
        serverMessage = EXCHANGE_MESSAGES187[rnd.nextInt(EXCHANGE_MESSAGES187.size)],
        serverCode = EXCHANGE_CODES187[rnd.nextInt(EXCHANGE_CODES187.size)],
        cause = cause
    )
}

internal fun randomExchangeCase187(rnd: Random): ExDesc187 = randomExDesc187(rnd, 3)

internal fun exchangeCaseWireJson187(c: ExDesc187): String = exDescWireJson187(c)

class SignalExchangeMappingFuzzTest {

    private fun checkExchange187(c: ExDesc187) {
        assertEquals(
            exchangeResultWireJson187(exchangeResultOracle187(c)),
            exchangeResultWireJson187(exchangeResultProduction187(c)),
            "exchange($c)"
        )
    }

    @Test
    fun pinnedSignalExchangeMapping() {
        // Every kind on its own, plus every failure x status for the two
        // kinds that carry a failure.
        for (kind in EXCHANGE_KINDS187) {
            checkExchange187(ExDesc187(kind, null, null, null, null, null))
        }
        for (failure in EXCHANGE_SIGNAL_FAILURES187) {
            for (status in listOf<Int?>(null, 404, 400, 500, 403, 405)) {
                checkExchange187(ExDesc187("signal", failure, status, "m", "C", null))
            }
        }
        for (failure in EXCHANGE_API_FAILURES187) {
            for (status in listOf<Int?>(null, 404, 400, 500, 403, 405)) {
                checkExchange187(ExDesc187("api", failure, status, "m", "C", null))
            }
        }
        // Cause chains: the outer type decides, the cause is ignored.
        checkExchange187(
            ExDesc187("signal", "HTTP", 404, "m", "C",
                ExDesc187("api", "TIMEOUT", 500, "inner", "X", null))
        )
        checkExchange187(
            ExDesc187("api", "HTTP", 404, "m", "C",
                ExDesc187("signal", "UNEXPECTED", null, "inner", null,
                    ExDesc187("io", null, null, "deep", null, null)))
        )
        checkExchange187(
            ExDesc187("io", null, null, "outer", null,
                ExDesc187("api", "HTTP", 404, "inner", null, null))
        )
        checkExchange187(
            ExDesc187("timeout", null, null, null, null,
                ExDesc187("timeout", null, null, null, null,
                    ExDesc187("serial", null, null, null, null, null)))
        )
        // Null vs empty message/code on the kinds that copy them.
        for (msg in listOf<String?>(null, "", "  ", "x")) {
            for (code in listOf<String?>(null, "", "  ", "C")) {
                checkExchange187(ExDesc187("signal", "HTTP", 404, msg, code, null))
                checkExchange187(ExDesc187("api", "HTTP", 404, msg, code, null))
            }
        }
    }

    @Test
    fun randomSignalExchangeMappingMatchProduction() {
        val rnd = Random(187003)
        repeat(2500) { checkExchange187(randomExchangeCase187(rnd)) }
    }

    @Test
    fun sweepSignalExchangeMappingBoundaries() {
        // 404 is the only legacy status; neighbours must not trigger it.
        for (status in listOf(403, 404, 405, 0, -1, Int.MAX_VALUE, Int.MIN_VALUE)) {
            for (failure in listOf("HTTP", "TIMEOUT", "NETWORK", "UNEXPECTED")) {
                checkExchange187(ExDesc187("signal", failure, status, null, null, null))
                checkExchange187(ExDesc187("api", failure, status, null, null, null))
            }
        }
        // Deep cause chains (depth 5) under every outer kind.
        for (kind in EXCHANGE_KINDS187) {
            var deep: ExDesc187? = null
            repeat(5) { deep = ExDesc187("api", "HTTP", 404, "d", "C", deep) }
            checkExchange187(ExDesc187(kind, "HTTP", 404, "outer", null, deep))
        }
        // A cause that is itself a full signal exception must not leak
        // its fields into the outer result.
        checkExchange187(
            ExDesc187("other", null, 500, "outer-msg", "OUTER",
                ExDesc187("signal", "HTTP", 404, "inner-msg", "INNER", null))
        )
    }
}
