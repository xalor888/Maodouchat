// ApiHttpErrors composition (143rd block). Oracle reimplements the
// wiring from production text: error-body decode, retry precedence,
// session-changed constants. The JSON decode itself reuses the
// kotlinx.serialization Json decoder primitive (same as 142's reuse
// of trim/lowercase); the Python differential model reimplements the
// decode independently. ApiErrorBodyPolicy's own decode was already
// fuzzed by block 130; this block covers the ApiHttpErrors layer,
// which no FuzzTest has ever called.
package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import com.maodouchat.core.model.HttpResult
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- api-error oracles ----------

private val API_ERROR_JSON_ORACLE = Json { ignoreUnknownKeys = true }

private fun parseErrorResponseOracle(body: String): ErrorResponse? = try {
    API_ERROR_JSON_ORACLE.decodeFromString(ErrorResponse.serializer(), body)
} catch (_: Exception) {
    null
}

private fun parseErrorOracle(body: String): String? {
    return parseErrorResponseOracle(body)?.error?.takeIf { it.isNotBlank() }
}

private fun parseRetryAfterSecondsOracle(body: String, headerValue: String? = null): Long? {
    val fromBody = parseErrorResponseOracle(body)?.retryAfterSeconds?.takeIf { it > 0L }
    if (fromBody != null) return fromBody.coerceAtMost(86_400L)
    val raw = headerValue?.trim().orEmpty()
    if (raw.isEmpty()) return null
    raw.toLongOrNull()?.takeIf { it > 0L }?.let { return it.coerceAtMost(86_400L) }
    return null
}

private fun apiExceptionFromHttpOracle(
    statusCode: Int,
    body: String,
    retryAfterHeader: String? = null,
    url: String? = null,
): ApiException {
    val error = parseErrorResponseOracle(body)
    return ApiException(
        kind = ApiFailureKind.HTTP,
        statusCode = statusCode,
        serverMessage = error?.error,
        serverCode = error?.code,
        retryAfterSeconds = parseRetryAfterSecondsOracle(body, retryAfterHeader)
    )
}

private fun sessionChangedResultOracle(): HttpResult = HttpResult(
    code = 409,
    isSuccessful = false,
    body = "{\"error\":\"session_changed\",\"code\":\"SESSION_CHANGED\"}"
)

private fun sessionChangedExceptionOracle(): ApiException = apiExceptionFromHttpOracle(
    sessionChangedResultOracle().code,
    sessionChangedResultOracle().body
)

// ---------- generators (mirrored by Oracle143 GEN, seed 143003) ----------

private val API_BODY_POOL = arrayOf(
    "",
    " ",
    "{}",
    "[]",
    "\"x\"",
    "null",
    "5",
    "{\"error\":\"oops\"}",
    "{\"error\":\"oops\",\"code\":\"E1\",\"retryAfterSeconds\":30}",
    "{\"error\":\"oops\",\"code\":null,\"retryAfterSeconds\":null}",
    "{\"error\":\"session_changed\",\"code\":\"SESSION_CHANGED\"}",
    "{\"error\":\"oops\",\"code\":\"E1\"}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":0}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":-5}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":86400}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":86401}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":999999999}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":9223372036854775807}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":9223372036854775808}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":1.5}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":\"30\"}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":true}",
    "{\"error\":\"\"}",
    "{\"error\":\"   \"}",
    "{\"error\":null}",
    "{\"error\":5}",
    "{\"error\":true}",
    "{\"error\":[\"x\"]}",
    "{\"error\":{\"x\":1}}",
    "{\"code\":\"E1\"}",
    "{\"error\":\"oops\",\"unknown\":1,\"code\":\"E1\"}",
    "{\"error\":\"oops\",\"error\":\"second\"}",
    "{\"error\":\"oops\",}",
    "{'error':'oops'}",
    "{\"error\":\"oops\"",
    "{\"error\":\"o\\u00e9\"}",
    "{\"error\":\"a\\uD800b\"}",
    "{\"error\":\"a\nb\"}",
    "{\"error\":\"a\\tb\"}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":5e2}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":5.0}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":-0}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":+30}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":007}",
    "  {\"error\":\"oops\"}  ",
    "{\"ERROR\":\"oops\"}",
    "{\"error\":\"oops\"} trailing",
    "{\"error\":\"oops\"} {\"error\":\"x\"}",
    "{\"error\":\"\\ud83d\\ude00\"}",
    "{\"error\":\"x\",\"code\":\"E1\",\"retryAfterSeconds\":30,\"extra\":{\"a\":[1,2,{\"b\":null}]}}",
)
private val API_HEADER_POOL = arrayOf<String?>(
    null, "", " ", "30", "0", "-5", "86400", "86401", "999999999",
    "9223372036854775807", "9223372036854775808", "1.5", "abc", " 30 ",
    "+30", "007", "30x", "x30", "30\n", "\t60\t",
)
private val API_STATUS_POOL = intArrayOf(
    -1, 0, 1, 200, 201, 400, 401, 403, 404, 409, 429, 500, 502, 503,
    Int.MAX_VALUE, Int.MIN_VALUE,
)
private val API_URL_POOL = arrayOf(
    "", "https://chat.mdou.me/api/x", "https://example.com/", "not a url",
)

private fun randomApiBody143(rnd: Random): String = when (rnd.nextInt(5)) {
    0 -> API_BODY_POOL[rnd.nextInt(API_BODY_POOL.size)]
    1 -> {
        val err = when (rnd.nextInt(4)) {
            0 -> "\"oops\""
            1 -> "\"\""
            2 -> "5"
            else -> "null"
        }
        val code = when (rnd.nextInt(3)) {
            0 -> ""
            1 -> ",\"code\":\"E${rnd.nextInt(10)}\""
            else -> ",\"code\":${rnd.nextInt(100)}"
        }
        val retry = when (rnd.nextInt(4)) {
            0 -> ""
            1 -> ",\"retryAfterSeconds\":${rnd.nextInt(-10, 100000)}"
            2 -> ",\"retryAfterSeconds\":${rnd.nextLong()}"
            else -> ",\"retryAfterSeconds\":\"${rnd.nextInt(100)}\""
        }
        "{\"error\":$err$code$retry}"
    }
    2 -> {
        val sb = StringBuilder()
        val len = rnd.nextInt(0, 20)
        repeat(len) {
            when (rnd.nextInt(5)) {
                0 -> sb.append(('a' + rnd.nextInt(26)))
                1 -> sb.append(('0' + rnd.nextInt(10)))
                2 -> sb.append("{}\",:[] ".get(rnd.nextInt(7)))
                3 -> sb.appendCodePoint(rnd.nextInt(0x20, 0x2FFF))
                else -> sb.append(API_BODY_POOL[rnd.nextInt(API_BODY_POOL.size)])
            }
        }
        sb.toString()
    }
    3 -> "{\"error\":\"oops\",\"retryAfterSeconds\":${rnd.nextInt(-100, 200000)}}"
    else -> randomApiBody143(rnd)
}

private fun randomApiHeader143(rnd: Random): String? = when (rnd.nextInt(4)) {
    0 -> null
    1 -> API_HEADER_POOL[rnd.nextInt(API_HEADER_POOL.size)]
    2 -> rnd.nextInt(-100, 200000).toString()
    else -> rnd.nextLong().toString()
}

private fun randomApiStatus143(rnd: Random): Int =
    if (rnd.nextInt(3) == 0) API_STATUS_POOL[rnd.nextInt(API_STATUS_POOL.size)]
    else rnd.nextInt()

private fun randomApiUrl143(rnd: Random): String? = when (rnd.nextInt(3)) {
    0 -> null
    1 -> API_URL_POOL[rnd.nextInt(API_URL_POOL.size)]
    else -> "https://example.com/" + rnd.nextInt(100)
}

class ApiHttpErrorsFuzzTest {

    private fun assertApiExceptionEquals(expected: ApiException, actual: ApiException, msg: String) {
        assertEquals(expected.kind, actual.kind, msg)
        assertEquals(expected.statusCode, actual.statusCode, msg)
        assertEquals(expected.serverMessage, actual.serverMessage, msg)
        assertEquals(expected.serverCode, actual.serverCode, msg)
        assertEquals(expected.retryAfterSeconds, actual.retryAfterSeconds, msg)
    }

    @Test
    fun pinnedBoundaries() {
        // Session-changed constants: production and oracle agree exactly.
        assertEquals(sessionChangedResultOracle(), ApiHttpErrors.sessionChangedResult())
        assertApiExceptionEquals(
            sessionChangedExceptionOracle(), ApiHttpErrors.sessionChangedException(), "sessionEx",
        )
        val sessionBody = "{\"error\":\"session_changed\",\"code\":\"SESSION_CHANGED\"}"
        assertApiExceptionEquals(
            apiExceptionFromHttpOracle(409, sessionBody),
            ApiHttpErrors.apiExceptionFromHttp(409, sessionBody), "sessionBody",
        )
        for (body in API_BODY_POOL) {
            for (status in listOf(200, 400, 404, 409, 500)) {
                for (header in listOf<String?>(null, "30", "0", "86401", "abc")) {
                    assertApiExceptionEquals(
                        apiExceptionFromHttpOracle(status, body, header),
                        ApiHttpErrors.apiExceptionFromHttp(status, body, header),
                        "apiEx status=$status body=$body header=$header",
                    )
                    assertEquals(
                        parseRetryAfterSecondsOracle(body, header),
                        ApiErrorBodyPolicy.parseRetryAfterSeconds(body, header),
                        "retry body=$body header=$header",
                    )
                }
            }
            assertEquals(parseErrorOracle(body), ApiErrorBodyPolicy.parseError(body), "parseError $body")
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(143003)
        repeat(1500) { i ->
            val status = randomApiStatus143(rnd)
            val body = randomApiBody143(rnd)
            val header = randomApiHeader143(rnd)
            val url = randomApiUrl143(rnd)
            assertApiExceptionEquals(
                apiExceptionFromHttpOracle(status, body, header, url),
                ApiHttpErrors.apiExceptionFromHttp(status, body, header, url),
                "apiEx iter=$i",
            )
            assertEquals(sessionChangedResultOracle(), ApiHttpErrors.sessionChangedResult(), "sessionResult iter=$i")
            assertApiExceptionEquals(
                sessionChangedExceptionOracle(), ApiHttpErrors.sessionChangedException(), "sessionEx iter=$i",
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        fun editsOf(target: String): List<String> {
            val out = mutableListOf<String>()
            val edits = " _Xa019\"{}:,.\u0000"
            for (pos in target.indices) {
                for (rep in edits) out.add(target.substring(0, pos) + rep + target.substring(pos + 1))
                out.add(target.substring(0, pos) + target.substring(pos + 1))
                val ch = target[pos]
                out.add(target.substring(0, pos) + (if (ch.isUpperCase()) ch.lowercaseChar() else ch.uppercaseChar()) + target.substring(pos + 1))
            }
            for (pos in 0..target.length) {
                for (ins in listOf("_", "X", "0", "\"")) out.add(target.substring(0, pos) + ins + target.substring(pos))
            }
            return out
        }
        for (base in listOf(
            "{\"error\":\"oops\",\"code\":\"E1\",\"retryAfterSeconds\":30}",
            "{\"error\":\"session_changed\",\"code\":\"SESSION_CHANGED\"}",
            "{\"error\":\"oops\"}",
            "{\"error\":\"oops\",\"retryAfterSeconds\":86401}",
        )) {
            for (input in editsOf(base)) {
                assertApiExceptionEquals(
                    apiExceptionFromHttpOracle(409, input, "30"),
                    ApiHttpErrors.apiExceptionFromHttp(409, input, "30"),
                    "apiEx sweep $input",
                )
                assertEquals(
                    parseRetryAfterSecondsOracle(input, "30"),
                    ApiErrorBodyPolicy.parseRetryAfterSeconds(input, "30"),
                    "retry sweep $input",
                )
            }
        }
        for (header in listOf("30", "0", "86401", "abc", " 30 ", "+30", "007", "1.5", "9223372036854775808")) {
            for (input in editsOf(header)) {
                assertEquals(
                    parseRetryAfterSecondsOracle("{}", input),
                    ApiErrorBodyPolicy.parseRetryAfterSeconds("{}", input),
                    "header sweep $input",
                )
            }
        }
        for (code in 0x00..0x7F) {
            val input = String(Character.toChars(code))
            assertApiExceptionEquals(
                apiExceptionFromHttpOracle(409, input),
                ApiHttpErrors.apiExceptionFromHttp(409, input),
                "single-unit body code=$code",
            )
        }
    }
}
