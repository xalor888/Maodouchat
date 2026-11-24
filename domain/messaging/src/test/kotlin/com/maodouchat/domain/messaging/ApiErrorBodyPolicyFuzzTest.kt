// Fuzz / random-oracle tests for ApiErrorBodyPolicy
// (checklist line 931, 130th block). Test-only, zero production changes.
// The oracle is hand-written from the production source plus the
// kotlinx 1.6.2 decode rules pinned by the fuzz-129 wsprobes: lenient
// number tokens, the consumeNumericLiteral long machine, strict
// strings, unknown keys skipped, duplicate keys validated in order
// with last-wins. No ApiErrorBodyPolicy fuzz test existed before this
// block. (fuzz-94/fuzz-119 set this cluster aside for want of a
// serialization plugin and JSON surface; both are in place since
// the fuzz-129 harness.)
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// ---------- shared random-text helpers ----------

private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

private val TEXT_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00A0 to 0x00FF, 0x0100 to 0x017F, 0x0370 to 0x03FF, 0x0400 to 0x04FF,
    0x2000 to 0x206F, 0x3000 to 0x30FF, 0x4E00 to 0x4FFF, 0xFF00 to 0xFFEF,
    0x10000 to 0x1FFFF, 0x20000 to 0x2A6DF
)

private fun randomCodePoint(rnd: Random): Int {
    val (lo, hi) = TEXT_RANGES[rnd.nextInt(TEXT_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomUnit(rnd: Random, sb: StringBuilder) {
    when (rnd.nextInt(20)) {
        0 -> sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)])
        1 -> sb.append(rnd.nextInt(0x00, 0x20).toChar())
        2 -> {
            val hi = rnd.nextBoolean()
            sb.append(if (hi) rnd.nextInt(0xD800, 0xDC00).toChar() else rnd.nextInt(0xDC00, 0xE000).toChar())
        }
        else -> sb.append(Character.toChars(randomCodePoint(rnd)))
    }
}

private fun randomText(rnd: Random, maxLen: Int): String {
    val len = rnd.nextInt(0, maxLen + 1)
    val sb = StringBuilder()
    repeat(len) { randomUnit(rnd, sb) }
    return sb.toString()
}

// ---------- oracle: mini JSON parser (kotlinx 1.6.2 lenient tokens) ----------

private sealed interface JVal
private data object JNull : JVal
private data class JBool(val v: Boolean) : JVal
private data class JNum(val lit: String) : JVal
private data class JStr(val v: String) : JVal
private data class JArr(val items: List<JVal>) : JVal
private data class JObj(val entries: List<Pair<String, JVal>>) : JVal

private fun tokenClass(c: Char): Int = when (c) {
    '\t', '\n', '\r', ' ' -> 3
    '"' -> 1
    ',' -> 4
    ':' -> 5
    '[' -> 8
    '\\' -> 2
    ']' -> 9
    '{' -> 6
    '}' -> 7
    else -> if (c.code < 0x20) 127 else 0
}

private fun asciiFold(c: Char): Char = if (c in 'A'..'Z') (c.code or 32).toChar() else c

private class JsonParser(private val s: String) {
    private var i = 0

    fun parse(): JVal {
        skipWs()
        val v = parseValue()
        skipWs()
        if (i != s.length) error("trailing")
        return v
    }

    private fun skipWs() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    }

    private fun parseValue(): JVal {
        if (i >= s.length) error("eof")
        val c = s[i]
        return when (c) {
            '{' -> parseObj()
            '[' -> parseArr()
            '"' -> JStr(parseString())
            'n' -> {
                val tok = (parseNum() as JNum).lit
                if (tok == "null") JNull else JNum(tok)
            }
            't', 'T', 'f', 'F' -> boolOrNum()
            else -> if (tokenClass(c) == 0) parseNum() else error("unexpected")
        }
    }

    private fun boolOrNum(): JVal {
        val tok = (parseNum() as JNum).lit
        if (tok.length == 4 && tok.indices.all { asciiFold(tok[it]) == "true"[it] }) return JBool(true)
        if (tok.length == 5 && tok.indices.all { asciiFold(tok[it]) == "false"[it] }) return JBool(false)
        return JNum(tok)
    }

    private fun parseObj(): JVal {
        i++
        skipWs()
        val entries = ArrayList<Pair<String, JVal>>()
        if (i < s.length && s[i] == '}') {
            i++
            return JObj(entries)
        }
        while (true) {
            skipWs()
            if (i >= s.length || s[i] != '"') error("key")
            val k = parseString()
            skipWs()
            if (i >= s.length || s[i] != ':') error("colon")
            i++
            skipWs()
            entries.add(k to parseValue())
            skipWs()
            when {
                i < s.length && s[i] == ',' -> { i++; continue }
                i < s.length && s[i] == '}' -> { i++; return JObj(entries) }
                else -> error("obj sep")
            }
        }
    }

    private fun parseArr(): JVal {
        i++
        skipWs()
        val items = ArrayList<JVal>()
        if (i < s.length && s[i] == ']') {
            i++
            return JArr(items)
        }
        while (true) {
            skipWs()
            items.add(parseValue())
            skipWs()
            when {
                i < s.length && s[i] == ',' -> { i++; continue }
                i < s.length && s[i] == ']' -> { i++; return JArr(items) }
                else -> error("arr sep")
            }
        }
    }

    private fun parseString(): String {
        i++
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) error("unterminated")
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= s.length) error("escape eof")
                    when (val e = s[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) error("u escape")
                            val code = s.substring(i, i + 4).toIntOrNull(16) ?: error("bad hex")
                            i += 4
                            sb.append(code.toChar())
                        }
                        else -> error("bad escape")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun parseNum(): JVal {
        val start = i
        while (i < s.length && tokenClass(s[i]) == 0) i++
        if (i == start) error("num")
        return JNum(s.substring(start, i))
    }
}

// Port of AbstractJsonLexer.consumeNumericLiteral (kotlinx 1.6.2).
private fun parseKotlinxLong(lit: String): Long? {
    var result = 0L
    var exponent = 0L
    var isNegative = false
    var isExponentPositive = false
    var isExponent = false
    val start = 0
    var i = 0
    while (i < lit.length) {
        val c = lit[i]
        if (c == 'e' || c == 'E') {
            if (isExponent) return null
            if (i == start) return null
            isExponentPositive = true
            isExponent = true
            i++
            continue
        }
        if (c == '-' && isExponent) {
            if (i == start) return null
            isExponentPositive = false
            i++
            continue
        }
        if (c == '+' && isExponent) {
            if (i == start) return null
            isExponentPositive = true
            i++
            continue
        }
        if (c == '-' && i == start) {
            isNegative = true
            i++
            continue
        }
        if (tokenClass(c) != 0) break
        i++
        val digit = c - '0'
        if (digit !in 0..9) return null
        if (isExponent) {
            exponent = exponent * 10 + digit
        } else {
            result = result * 10 - digit
            if (result > 0) return null
        }
    }
    if (i != lit.length) return null
    if (i == start || (isNegative && start == i - 1)) return null
    if (isExponent) {
        val factor = if (isExponentPositive) Math.pow(10.0, exponent.toDouble())
        else Math.pow(10.0, -exponent.toDouble())
        val value = result.toDouble() * factor
        if (value > 9.223372036854776E18 || value < -9.223372036854776E18) return null
        if (Math.floor(value) != value) return null
        result = value.toLong()
    }
    if (isNegative) return result
    if (result == Long.MIN_VALUE) return null
    return -result
}

// ---------- oracle: ErrorResponse decode ----------

private fun aebDecodeOracle(body: String): ErrorResponse? {
    val root = try {
        JsonParser(body).parse()
    } catch (e: Exception) {
        return null
    }
    val obj = root as? JObj ?: return null
    var error: String? = null
    var code: String? = null
    var retry: Long? = null
    fun numLit(v: JVal): String? = when (v) {
        is JNum -> v.lit
        is JStr -> v.v
        else -> null
    }
    for ((key, v) in obj.entries) {
        when (key) {
            "error" -> {
                if (v is JNull) return null
                error = (v as? JStr)?.v ?: return null
            }
            "code" -> {
                if (v is JNull) code = null
                else code = (v as? JStr)?.v ?: return null
            }
            "retryAfterSeconds" -> {
                if (v is JNull) retry = null
                else retry = parseKotlinxLong(numLit(v) ?: return null) ?: return null
            }
            else -> { /* unknown key: skipped */ }
        }
    }
    return error?.let { ErrorResponse(it, code, retry) }
}

private fun aebErrorOracle(body: String): String? =
    aebDecodeOracle(body)?.error?.takeIf { it.isNotBlank() }

private fun aebRetryOracle(body: String, headerValue: String?): Long? {
    val fromBody = aebDecodeOracle(body)?.retryAfterSeconds?.takeIf { it > 0L }
    if (fromBody != null) return fromBody.coerceAtMost(86_400L)
    val raw = headerValue?.trim().orEmpty()
    if (raw.isEmpty()) return null
    raw.toLongOrNull()?.takeIf { it > 0L }?.let { return it.coerceAtMost(86_400L) }
    return null
}

// ---------- generators (mirrored by Oracle130 GEN, seed 130002) ----------

private val AEB_BODY_POOL = arrayOf(
    "{\"error\":\"oops\"}",
    "{\"error\":\"oops\",\"code\":\"E1\"}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":30}",
    "{\"error\":\"oops\",\"code\":null,\"retryAfterSeconds\":null}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":\"60\"}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":1e2}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":90000}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":86400}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":86401}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":0}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":-5}",
    "{\"error\":\"\"}",
    "{\"error\":\" \"}",
    "{\"error\":null}",
    "{\"error\":5}",
    "{\"error\":\"oops\",\"error\":\"second\"}",
    "{\"code\":\"E1\"}",
    "{}",
    "[]",
    "null",
    "true",
    "123",
    "\"str\"",
    "{\"error\":\"oops\",}",
    "{\"error\":\"oops\"",
    "{\"error\":\"oops\"} x",
    " {\"error\":\"oops\"} ",
    "{\"error\":\"oops\",\"unknown\":xyz}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":1.5}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":9223372036854775807}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":9223372036854775808}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":\"1e2\"}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":\"x\"}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":true}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":[1]}",
    "{\"error\":\"oops\",\"code\":5}",
    "{\"error\":\"\\u00e9\"}",
    "{\"error\":\"a\\uD800b\"}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":-0}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":\"+5\"}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":\" 5\"}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":\".5\"}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":\"5e\"}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":\"0x1p4\"}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":1e400}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":5e-999}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":\"9007199254740993\"}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":\"9007199254740993e0\"}",
    "{\"error\": \"oops\" , \"code\" : \"E2\" }",
    "{\"error\":\"oops\",\"retryAfterSeconds\":null,\"retryAfterSeconds\":45}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":45,\"retryAfterSeconds\":null}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":45,\"retryAfterSeconds\":\"bad\"}",
    "{\"error\":5,\"error\":\"oops\"}",
    "{\"error\":\"oops\",\"code\":\"a\",\"code\":null}",
    "",
    " ",
    "{",
    "}",
    "{,}",
    "{\"error\":}",
    "{\"error\": 1,}",
    "{'error': 'oops'}",
    "{\"error\":\"oops\",\"retryAfterSeconds\":\"\\ud800\"}"
)

private val AEB_RETRY_LIT_POOL = arrayOf(
    "0", "1", "30", "86400", "86401", "-5", "1e2", "1.5", "\"60\"", "\"x\"",
    "true", "null", "9223372036854775807", "9223372036854775808", "-0", "+5", "007"
)

private val AEB_HEADER_POOL = arrayOf(
    "", " ", "30", "86400", "86401", "0", "-5", "+12", " 45 ", "1e2", "abc",
    "99999999999999999999", "12x", "x12", "007", "9223372036854775807",
    "9223372036854775808", "-9223372036854775808", "\u0663\u0660", "\uFF15",
    "  ", "\t60\n", "5 5", "+", "-", "+-5", "-+5", "5.0"
)

private fun genAebBody(rnd: Random): String {
    return when (rnd.nextInt(4)) {
        0, 1 -> AEB_BODY_POOL[rnd.nextInt(AEB_BODY_POOL.size)]
        2 -> randomText(rnd, 24)
        else -> "{\"error\":\"${randomText(rnd, 8)}\",\"retryAfterSeconds\":${AEB_RETRY_LIT_POOL[rnd.nextInt(AEB_RETRY_LIT_POOL.size)]}}"
    }
}

private fun genAebHeader(rnd: Random): String? {
    return when (rnd.nextInt(4)) {
        0 -> null
        1 -> AEB_HEADER_POOL[rnd.nextInt(AEB_HEADER_POOL.size)]
        else -> randomText(rnd, 8)
    }
}

class ApiErrorBodyPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Minimal valid body decodes; missing required error fails.
        assertEquals(ErrorResponse("oops"), ApiErrorBodyPolicy.parseErrorResponse("{\"error\":\"oops\"}"))
        assertEquals(
            ErrorResponse("oops", "E1", 30),
            ApiErrorBodyPolicy.parseErrorResponse("{\"error\":\"oops\",\"code\":\"E1\",\"retryAfterSeconds\":30}")
        )
        assertNull(ApiErrorBodyPolicy.parseErrorResponse("{\"code\":\"E1\"}"))
        assertNull(ApiErrorBodyPolicy.parseErrorResponse("{}"))
        assertNull(ApiErrorBodyPolicy.parseErrorResponse("[]"))
        assertNull(ApiErrorBodyPolicy.parseErrorResponse(""))
        // Non-nullable error rejects null / numbers; nullable fields take null.
        assertNull(ApiErrorBodyPolicy.parseErrorResponse("{\"error\":null}"))
        assertNull(ApiErrorBodyPolicy.parseErrorResponse("{\"error\":5}"))
        assertEquals(
            ErrorResponse("oops", null, null),
            ApiErrorBodyPolicy.parseErrorResponse("{\"error\":\"oops\",\"code\":null,\"retryAfterSeconds\":null}")
        )
        // Quoted numbers decode for the long field; fractions do not.
        assertEquals(100L, ApiErrorBodyPolicy.parseErrorResponse("{\"error\":\"oops\",\"retryAfterSeconds\":\"1e2\"}")?.retryAfterSeconds)
        assertNull(ApiErrorBodyPolicy.parseErrorResponse("{\"error\":\"oops\",\"retryAfterSeconds\":1.5}"))
        assertNull(ApiErrorBodyPolicy.parseErrorResponse("{\"error\":\"oops\",\"retryAfterSeconds\":1e400}"))
        // parseError drops blank errors, keeps the rest.
        assertEquals("oops", ApiErrorBodyPolicy.parseError("{\"error\":\"oops\"}"))
        assertNull(ApiErrorBodyPolicy.parseError("{\"error\":\"\"}"))
        assertNull(ApiErrorBodyPolicy.parseError("{\"error\":\" \"}"))
        assertNull(ApiErrorBodyPolicy.parseError("{\"error\":\"oops\",\"error\":\"\"}"))
        assertEquals("second", ApiErrorBodyPolicy.parseError("{\"error\":\"oops\",\"error\":\"second\"}"))
        // Retry-After: body wins and caps at a day; header is the fallback.
        assertEquals(30L, ApiErrorBodyPolicy.parseRetryAfterSeconds("{\"error\":\"oops\",\"retryAfterSeconds\":30}", "5"))
        assertEquals(86_400L, ApiErrorBodyPolicy.parseRetryAfterSeconds("{\"error\":\"oops\",\"retryAfterSeconds\":90000}", null))
        assertEquals(86_400L, ApiErrorBodyPolicy.parseRetryAfterSeconds("{\"error\":\"oops\"}", "90000"))
        assertEquals(45L, ApiErrorBodyPolicy.parseRetryAfterSeconds("{\"error\":\"oops\"}", " 45 "))
        assertNull(ApiErrorBodyPolicy.parseRetryAfterSeconds("{\"error\":\"oops\",\"retryAfterSeconds\":0}", null))
        assertNull(ApiErrorBodyPolicy.parseRetryAfterSeconds("{\"error\":\"oops\"}", "0"))
        assertNull(ApiErrorBodyPolicy.parseRetryAfterSeconds("{\"error\":\"oops\"}", "-5"))
        assertNull(ApiErrorBodyPolicy.parseRetryAfterSeconds("{\"error\":\"oops\"}", "1e2"))
        assertNull(ApiErrorBodyPolicy.parseRetryAfterSeconds("{\"error\":\"oops\"}", "abc"))
        assertNull(ApiErrorBodyPolicy.parseRetryAfterSeconds("{\"error\":\"oops\"}", null))
        // A missing/invalid/non-positive body value falls back to header.
        assertEquals(30L, ApiErrorBodyPolicy.parseRetryAfterSeconds("{\"error\":\"oops\",\"retryAfterSeconds\":0}", "30"))
        assertEquals(30L, ApiErrorBodyPolicy.parseRetryAfterSeconds("not json", "30"))
        assertNull(ApiErrorBodyPolicy.parseRetryAfterSeconds("not json", null))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle130.genAeb consumption order exactly, so seed
        // 130002 replays the same 1500 GEN lines drive130.py ran.
        val rnd = Random(130002)
        repeat(1500) {
            when (rnd.nextInt(3)) {
                0 -> {
                    val body = genAebBody(rnd)
                    assertEquals(aebDecodeOracle(body), ApiErrorBodyPolicy.parseErrorResponse(body))
                }
                1 -> {
                    val body = genAebBody(rnd)
                    assertEquals(aebErrorOracle(body), ApiErrorBodyPolicy.parseError(body))
                }
                else -> {
                    val body = genAebBody(rnd)
                    val header = genAebHeader(rnd)
                    assertEquals(aebRetryOracle(body, header), ApiErrorBodyPolicy.parseRetryAfterSeconds(body, header))
                }
            }
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every pool body through all three entry points.
        for (body in AEB_BODY_POOL) {
            assertEquals(aebDecodeOracle(body), ApiErrorBodyPolicy.parseErrorResponse(body))
            assertEquals(aebErrorOracle(body), ApiErrorBodyPolicy.parseError(body))
            assertEquals(aebRetryOracle(body, null), ApiErrorBodyPolicy.parseRetryAfterSeconds(body, null))
        }
        // Every header literal against an error-only body and a bad body.
        for (header in AEB_HEADER_POOL) {
            assertEquals(
                aebRetryOracle("{\"error\":\"oops\"}", header),
                ApiErrorBodyPolicy.parseRetryAfterSeconds("{\"error\":\"oops\"}", header)
            )
            assertEquals(
                aebRetryOracle("not json", header),
                ApiErrorBodyPolicy.parseRetryAfterSeconds("not json", header)
            )
        }
        // retryAfterSeconds literal matrix (bare + quoted) x now-free decode.
        for (lit in AEB_RETRY_LIT_POOL) {
            val body = "{\"error\":\"oops\",\"retryAfterSeconds\":$lit}"
            assertEquals(aebDecodeOracle(body), ApiErrorBodyPolicy.parseErrorResponse(body))
        }
        for (lit in AEB_RETRY_LIT_POOL) {
            val body = "{\"error\":\"oops\",\"retryAfterSeconds\":\"$lit\"}"
            assertEquals(aebDecodeOracle(body), ApiErrorBodyPolicy.parseErrorResponse(body))
        }
        // Whitespace/control singles as the whole body, and as error text.
        for (u in WS_CHARS) {
            val s = u.toString()
            assertEquals(aebDecodeOracle(s), ApiErrorBodyPolicy.parseErrorResponse(s))
            val body = "{\"error\":\"${s}x\"}"
            assertEquals(aebDecodeOracle(body), ApiErrorBodyPolicy.parseErrorResponse(body))
        }
    }
}
