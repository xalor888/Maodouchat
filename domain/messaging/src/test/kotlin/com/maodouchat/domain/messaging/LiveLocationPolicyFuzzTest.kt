// Fuzz / random-oracle tests for LiveLocationPolicy
// (checklist line 931, 129th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: isLive /
// remainingMs / formatRemaining / remainingFromUntil are pure long
// comparisons and arithmetic; isLiveMessage goes through
// Message.parsedLocation, whose oracle below re-implements the
// content pre-processing (strip "<meta>" tail, unescape HTML entities)
// and the kotlinx LocationPayload decode (ignoreUnknownKeys, required
// latitude/longitude doubles, finite + range check) with a small
// hand-written JSON parser -- deliberately independent of the
// kotlinx decoder production uses. Number tokens are lenient
// (maximal token-class-0 runs) and Long fields run the
// consumeNumericLiteral state machine ("1e2" -> 100, "5e" -> 5,
// "1.0" fails), matching kotlinx 1.6.2 as decompiled. No
// LiveLocationPolicy fuzz test existed before this block.
package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

// ---------- oracle: pure time functions ----------

private fun llIsLiveOracle(live: Boolean, until: Long?, now: Long): Boolean {
    if (!live) return false
    val u = until ?: return true
    return u > now
}

private fun llRemainingMsOracle(until: Long?, now: Long): Long {
    val u = until ?: return 0L
    return (u - now).coerceAtLeast(0L)
}

private fun llFormatRemainingOracle(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${totalSec}s"
    }
}

// ---------- oracle: mini JSON parser (independent of kotlinx) ----------

private sealed interface JVal
private data object JNull : JVal
private data class JBool(val v: Boolean) : JVal
private data class JNum(val lit: String) : JVal
private data class JStr(val v: String) : JVal
private data class JArr(val items: List<JVal>) : JVal
private data class JObj(val entries: List<Pair<String, JVal>>) : JVal {
    // Last occurrence wins, like the streaming decoder's assignments.
    val map: Map<String, JVal> = entries.toMap()
}

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
            // "null" is the only lowercase-n token that is null;
            // anything else is a number token (NULL/Null fail below).
            'n' -> {
                val tok = (parseNum() as JNum).lit
                if (tok == "null") JNull else JNum(tok)
            }
            't', 'T', 'f', 'F' -> boolOrNum()
            else -> if (llTokenClass(c) == 0) parseNum() else error("unexpected")
        }
    }

    // Whole-token match, like the lexer's lenient literal: "truex"
    // is one number token, not a bool followed by junk (an unknown
    // key's "truex" value skips fine; a known bool field still rejects
    // it in conversion). true/false fold ASCII case; "null" does not.
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
                // kotlinx takes raw control chars inside strings verbatim.
                else -> sb.append(c)
            }
        }
    }

    // Lenient number token: consumeStringLenient takes the maximal run
    // of token-class-0 chars; the per-field conversion decides whether
    // the run is a valid number (Long state machine / parseDouble).
    private fun parseNum(): JVal {
        val start = i
        while (i < s.length && llTokenClass(s[i]) == 0) i++
        if (i == start) error("num")
        return JNum(s.substring(start, i))
    }
}

// ---------- oracle: content pre-processing + payload decode ----------

private val LL_NUM_DEC = Regex("&#(\\d{1,7});")
private val LL_NUM_HEX = Regex("&#x([0-9a-fA-F]{1,6});", RegexOption.IGNORE_CASE)

private fun llUnescapeOracle(raw: String): String {
    if (raw.isEmpty() || raw.indexOf('&') < 0) return raw
    var out = raw
    out = LL_NUM_DEC.replace(out) { match ->
        match.groupValues[1].toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { code ->
            String(intArrayOf(code), 0, 1)
        } ?: match.value
    }
    out = LL_NUM_HEX.replace(out) { match ->
        match.groupValues[1].toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }?.let { code ->
            String(intArrayOf(code), 0, 1)
        } ?: match.value
    }
    return out
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}

private fun llParsedContentOracle(content: String): String {
    val idx = content.lastIndexOf("<meta>")
    val body = if (idx < 0) content else content.substring(0, idx)
    return llUnescapeOracle(body)
}

private data class LlPayload(val live: Boolean, val until: Long?)

// Token class of one char in kotlinx's AbstractJsonLexer (CHAR_TO_TOKEN
// table, 1.6.2): non-zero for control chars, whitespace, '"', ',', ':',
// '[', '\\', ']', '{', '}'; zero for everything else.
private fun llTokenClass(c: Char): Int = when (c) {
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

// Quoted booleans go through consumeBooleanLenient: the string must be
// exactly "true"/"false" modulo ASCII case folding.
private fun llBoolString(s: String): Boolean? {
    if (s.length == 4 && s.indices.all { asciiFold(s[it]) == "true"[it] }) return true
    if (s.length == 5 && s.indices.all { asciiFold(s[it]) == "false"[it] }) return false
    return null
}

// Faithful port of AbstractJsonLexer.consumeNumericLiteral (kotlinx
// 1.6.2), over the raw literal text (quotes stripped for quoted
// numbers): mantissa accumulates negatively in long (wrap past zero
// is overflow), exponent digits accumulate in long with silent wrap,
// then mantissa.toDouble() * 10.0.pow(signed exponent), range-checked
// against 2^63 as a double, floor-checked, converted with d2l.
private fun llParseKotlinxLong(lit: String): Long? {
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
        if (llTokenClass(c) != 0) break
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

private fun llDecodePayloadOracle(json: String): LlPayload? {
    val root = try {
        JsonParser(json).parse()
    } catch (e: Exception) {
        return null
    }
    val obj = root as? JObj ?: return null
    // Production decodes each field as it is read, so every occurrence
    // is validated (fail-fast), not just the last one; the last value
    // wins. Numbers may be bare tokens or quoted strings; doubles and
    // floats go through parseDouble/parseFloat plus the decoder's
    // finite check, longs through the consumeNumericLiteral machine.
    var lat: Double? = null
    var lon: Double? = null
    var live = false
    var until: Long? = null
    var haveLat = false
    var haveLon = false
    fun numLit(v: JVal): String? = when (v) {
        is JNum -> v.lit
        is JStr -> v.v
        else -> null
    }
    for ((key, v) in obj.entries) {
        when (key) {
            "latitude" -> {
                val lit = numLit(v) ?: return null
                lat = lit.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
                haveLat = true
            }
            "longitude" -> {
                val lit = numLit(v) ?: return null
                lon = lit.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
                haveLon = true
            }
            "accuracyMeters" -> {
                if (v !is JNull) {
                    val lit = numLit(v) ?: return null
                    lit.toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
                }
            }
            "label" -> {
                // Non-nullable String: explicit null fails the decode.
                if (v is JNull) return null
                (v as? JStr) ?: return null
            }
            "capturedAt" -> {
                // Non-nullable Long: explicit null fails the decode.
                if (v is JNull) return null
                val lit = numLit(v) ?: return null
                llParseKotlinxLong(lit) ?: return null
            }
            "live" -> {
                live = when (v) {
                    is JBool -> v.v
                    is JStr -> llBoolString(v.v) ?: return null
                    else -> return null
                }
            }
            "liveUntil" -> {
                if (v !is JNull) {
                    val lit = numLit(v) ?: return null
                    until = llParseKotlinxLong(lit) ?: return null
                } else {
                    until = null
                }
            }
            "sessionId" -> {
                if (v !is JNull) {
                    (v as? JStr) ?: return null
                }
            }
            else -> { /* unknown key: skipped, value already parsed */ }
        }
    }
    if (!haveLat || !haveLon) return null
    val la = lat!!
    val lo = lon!!
    if (!la.isFinite() || !lo.isFinite()) return null
    if (la !in -90.0..90.0 || lo !in -180.0..180.0) return null
    return LlPayload(live, until)
}

private fun llIsLiveMessageOracle(type: MessageType, content: String, now: Long): Boolean {
    if (type != MessageType.LOCATION) return false
    val payload = llDecodePayloadOracle(llParsedContentOracle(content)) ?: return false
    return llIsLiveOracle(payload.live, payload.until, now)
}

// ---------- generators (mirrored by Oracle129 GEN, seed 129001) ----------

private val LL_LONG_POOL = longArrayOf(
    Long.MIN_VALUE, Long.MIN_VALUE + 1, -2, -1, 0, 1, 2, 999, 1000,
    59_999, 60_000, 3_599_999, 3_600_000, 3_600_001, 86_399_999,
    Long.MAX_VALUE - 1, Long.MAX_VALUE
)

private val LL_LAT_POOL = arrayOf(
    "0", "-90", "90", "-90.000001", "90.000001", "1e2", "-1.5e-3",
    "1e400", "-1e400", "0.1", "45", "180", "-180", "1.0",
    "9007199254740993", "-0", "0.000001", "89.999999"
)

private val LL_LON_POOL = arrayOf(
    "0", "-180", "180", "-180.000001", "180.000001", "1e2", "1e400",
    "0.1", "-179.5", "179.5", "1.0", "-0"
)

private val LL_ACC_POOL = arrayOf("0", "1.5", "1e400", "-1e-3", "100", "3.4028235e38", "1e-50")

private val LL_MALFORMED_POOL = arrayOf(
    "", " ", "{", "}", "[]", "null", "true", "123", "\"str\"", "{,}",
    "{\"latitude\":}", "{\"latitude\": 1,}", "{\"latitude\": 01}",
    "{\"latitude\": +1}", "{\"latitude\": NaN}",
    "{\"latitude\": 1.5, \"longitude\": 2.5",
    "{\"latitude\": 1.5, \"longitude\": 2.5} x",
    "{\"latitude\": \"12\", \"longitude\": 2}",
    "{\"latitude\": true, \"longitude\": 2}",
    "{\"latitude\": null, \"longitude\": 2}",
    "{\"latitude\": [1], \"longitude\": 2}",
    "{\"longitude\": 2}", "{\"latitude\": 1}",
    "{\"latitude\": 1.0, \"longitude\": 2.0}",
    "{\"latitude\": 1, \"longitude\": 2, \"liveUntil\": \"5\"}",
    "{\"latitude\": 1, \"longitude\": 2, \"liveUntil\": 1.0}",
    "{\"latitude\": 1, \"longitude\": 2, \"liveUntil\": 1e2}",
    "{\"latitude\": 1, \"longitude\": 2, \"capturedAt\": 1.5}",
    "{\"latitude\": 1, \"longitude\": 2, \"live\": 1}",
    "{\"latitude\": 1, \"longitude\": 2, \"label\": 5}",
    "{\"latitude\": 1, \"longitude\": 2, \"accuracyMeters\": \"x\"}",
    "{\"latitude\": 1, \"longitude\": 2} trailing",
    " {\"latitude\": 1, \"longitude\": 2} ",
    "{\"latitude\": 1, \"latitude\": 2, \"longitude\": 3}",
    "{\"latitude\": 1e400, \"longitude\": 2}",
    "{\"latitude\": 1, \"longitude\": 2, \"liveUntil\": 9223372036854775808}",
    "{\"latitude\": 1, \"longitude\": 2, \"liveUntil\": -9223372036854775809}",
    "{\"latitude\": 1, \"longitude\": 2, \"live\": null}",
    "{\"latitude\": 1, \"longitude\": 2, \"liveUntil\": null}",
    "{\"latitude\": 1, \"longitude\": 2, \"label\": null}",
    "{\"latitude\": 1, \"longitude\": 2, \"sessionId\": 7}",
    "{\"latitude\": 1, \"longitude\": 2, \"accuracyMeters\": null}",
    "{\"latitude\": 1, \"longitude\": 2, \"capturedAt\": null}",
    "{\"latitude\": 1, \"longitude\": 2, \"liveUntil\": 1e400}",
    "{\"latitude\": .5, \"longitude\": 2}",
    "{\"latitude\": 1., \"longitude\": 2}",
    "{\"latitude\": 1, \"longitude\": 2,}",
    "{'latitude': 1, 'longitude': 2}",
    "{\"latitude\": 1, \"longitude\": \"\\ud800\"}",
    "{\"latitude\": 1, \"longitude\": 2, \"label\": \"a\\uD83D\\uDE00b\"}",
    "{\"latitude\": 1, \"longitude\": 2, \"label\": \"a\\uD800b\"}"
)

private fun genLlLong(rnd: Random): Long =
    if (rnd.nextInt(2) == 0) LL_LONG_POOL[rnd.nextInt(LL_LONG_POOL.size)]
    else rnd.nextLong(-10_000_000L, 10_000_001L)

private fun genLlUntil(rnd: Random): Long? =
    if (rnd.nextInt(4) == 0) null else genLlLong(rnd)

private fun genLlPayloadJson(rnd: Random): String {
    val sb = StringBuilder("{")
    var first = true
    fun field(s: String) {
        if (!first) sb.append(",")
        first = false
        sb.append(s)
    }
    if (rnd.nextInt(10) != 0) {
        field("\"latitude\":${LL_LAT_POOL[rnd.nextInt(LL_LAT_POOL.size)]}")
    } else if (rnd.nextInt(3) == 0) {
        field("\"latitude\":\"12\"")
    }
    if (rnd.nextInt(10) != 0) {
        field("\"longitude\":${LL_LON_POOL[rnd.nextInt(LL_LON_POOL.size)]}")
    }
    if (rnd.nextInt(3) == 0) field("\"live\":${rnd.nextBoolean()}")
    if (rnd.nextInt(3) == 0) {
        val u = genLlUntil(rnd)
        field("\"liveUntil\":${if (u == null) "null" else u}")
    }
    if (rnd.nextInt(4) == 0) field("\"label\":\"s${rnd.nextInt(100)}\"")
    if (rnd.nextInt(4) == 0) field("\"sessionId\":\"s${rnd.nextInt(100)}\"")
    if (rnd.nextInt(4) == 0) field("\"accuracyMeters\":${LL_ACC_POOL[rnd.nextInt(LL_ACC_POOL.size)]}")
    if (rnd.nextInt(4) == 0) field("\"capturedAt\":${genLlLong(rnd)}")
    if (rnd.nextInt(4) == 0) field("\"unknownKey\":${rnd.nextInt(100)}")
    sb.append("}")
    return sb.toString()
}

private fun genLlContent(rnd: Random): String = when (rnd.nextInt(5)) {
    0 -> genLlPayloadJson(rnd)
    1 -> LL_MALFORMED_POOL[rnd.nextInt(LL_MALFORMED_POOL.size)]
    2 -> randomText(rnd, 24)
    3 -> genLlPayloadJson(rnd).replace("\"", "&quot;")
    else -> genLlPayloadJson(rnd) + "<meta>{\"x\":1}</meta>"
}

private fun genLlType(rnd: Random): MessageType =
    if (rnd.nextInt(2) == 0) MessageType.LOCATION
    else MessageType.entries[rnd.nextInt(MessageType.entries.size)]

private fun llMsg(type: MessageType, content: String): Message =
    Message(id = "m", chatId = "c", senderId = "s", content = content, type = type)

class LiveLocationPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // isLive: null payload never live; live=false never live; no
        // deadline means live forever; deadline is a strict > test.
        assertFalse(LiveLocationPolicy.isLive(null, 0L))
        assertFalse(LiveLocationPolicy.isLive(LocationPayload(0.0, 0.0, live = false), 0L))
        assertTrue(LiveLocationPolicy.isLive(LocationPayload(0.0, 0.0, live = true), 0L))
        assertTrue(LiveLocationPolicy.isLive(LocationPayload(0.0, 0.0, live = true, liveUntil = 100L), 99L))
        assertFalse(LiveLocationPolicy.isLive(LocationPayload(0.0, 0.0, live = true, liveUntil = 100L), 100L))
        assertFalse(LiveLocationPolicy.isLive(LocationPayload(0.0, 0.0, live = true, liveUntil = 100L), 101L))
        assertTrue(LiveLocationPolicy.isLive(LocationPayload(0.0, 0.0, live = true, liveUntil = Long.MAX_VALUE), Long.MAX_VALUE - 1))
        assertFalse(LiveLocationPolicy.isLive(LocationPayload(0.0, 0.0, live = true, liveUntil = Long.MIN_VALUE), Long.MIN_VALUE))
        // remainingMs: no deadline is 0; past deadlines clamp to 0;
        // the subtraction wraps on overflow, then clamps.
        assertEquals(0L, LiveLocationPolicy.remainingMs(LocationPayload(0.0, 0.0), 500L))
        assertEquals(0L, LiveLocationPolicy.remainingMs(LocationPayload(0.0, 0.0, liveUntil = 100L), 500L))
        assertEquals(1L, LiveLocationPolicy.remainingMs(LocationPayload(0.0, 0.0, liveUntil = 1000L), 999L))
        assertEquals(0L, LiveLocationPolicy.remainingMs(LocationPayload(0.0, 0.0, liveUntil = Long.MAX_VALUE), Long.MIN_VALUE))
        assertEquals(1L, LiveLocationPolicy.remainingMs(LocationPayload(0.0, 0.0, liveUntil = Long.MIN_VALUE), Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, LiveLocationPolicy.remainingMs(LocationPayload(0.0, 0.0, liveUntil = Long.MAX_VALUE), 0L))
        // remainingFromUntil mirrors remainingMs, with null -> 0.
        assertEquals(0L, LiveLocationPolicy.remainingFromUntil(null, 500L))
        assertEquals(500L, LiveLocationPolicy.remainingFromUntil(1000L, 500L))
        assertEquals(0L, LiveLocationPolicy.remainingFromUntil(100L, 500L))
        assertEquals(0L, LiveLocationPolicy.remainingFromUntil(Long.MAX_VALUE, Long.MIN_VALUE))
        // formatRemaining: negative and sub-second clamp to 0s; hours
        // only appear at 3600s, minutes at 60s.
        assertEquals("0s", LiveLocationPolicy.formatRemaining(-1500L))
        assertEquals("0s", LiveLocationPolicy.formatRemaining(-1L))
        assertEquals("0s", LiveLocationPolicy.formatRemaining(0L))
        assertEquals("0s", LiveLocationPolicy.formatRemaining(999L))
        assertEquals("1s", LiveLocationPolicy.formatRemaining(1000L))
        assertEquals("59s", LiveLocationPolicy.formatRemaining(59_999L))
        assertEquals("1m", LiveLocationPolicy.formatRemaining(60_000L))
        assertEquals("1m", LiveLocationPolicy.formatRemaining(61_000L))
        assertEquals("59m", LiveLocationPolicy.formatRemaining(3_599_000L))
        assertEquals("1h 0m", LiveLocationPolicy.formatRemaining(3_600_000L))
        assertEquals("1h 1m", LiveLocationPolicy.formatRemaining(3_661_000L))
        assertEquals("23h 59m", LiveLocationPolicy.formatRemaining(86_399_000L))
        assertEquals("2562047788015h 12m", LiveLocationPolicy.formatRemaining(Long.MAX_VALUE))
        // isLiveMessage: non-LOCATION never live, whatever the content.
        assertFalse(LiveLocationPolicy.isLiveMessage(llMsg(MessageType.TEXT, "{\"latitude\": 1, \"longitude\": 2, \"live\": true}"), 0L))
        assertFalse(LiveLocationPolicy.isLiveMessage(llMsg(MessageType.SYSTEM, "{\"latitude\": 1, \"longitude\": 2, \"live\": true}"), 0L))
        // LOCATION with a live payload is live; malformed or
        // out-of-range payloads decode to null and are not live.
        assertTrue(LiveLocationPolicy.isLiveMessage(llMsg(MessageType.LOCATION, "{\"latitude\": 1, \"longitude\": 2, \"live\": true}"), 0L))
        assertTrue(LiveLocationPolicy.isLiveMessage(llMsg(MessageType.LOCATION, "{\"latitude\": 1, \"longitude\": 2, \"live\": true, \"liveUntil\": 100}"), 99L))
        assertFalse(LiveLocationPolicy.isLiveMessage(llMsg(MessageType.LOCATION, "{\"latitude\": 1, \"longitude\": 2, \"live\": true, \"liveUntil\": 100}"), 100L))
        assertFalse(LiveLocationPolicy.isLiveMessage(llMsg(MessageType.LOCATION, "{\"latitude\": 91, \"longitude\": 2, \"live\": true}"), 0L))
        assertFalse(LiveLocationPolicy.isLiveMessage(llMsg(MessageType.LOCATION, "{\"latitude\": 1, \"longitude\": 181, \"live\": true}"), 0L))
        assertFalse(LiveLocationPolicy.isLiveMessage(llMsg(MessageType.LOCATION, "{\"latitude\": 1e400, \"longitude\": 2, \"live\": true}"), 0L))
        assertFalse(LiveLocationPolicy.isLiveMessage(llMsg(MessageType.LOCATION, "{\"latitude\": 1, \"longitude\": 2}"), 0L))
        assertFalse(LiveLocationPolicy.isLiveMessage(llMsg(MessageType.LOCATION, ""), 0L))
        assertFalse(LiveLocationPolicy.isLiveMessage(llMsg(MessageType.LOCATION, "{\"latitude\": 1, \"longitude\": 2, \"liveUntil\": \"5\"}"), 0L))
        assertFalse(LiveLocationPolicy.isLiveMessage(llMsg(MessageType.LOCATION, "{\"latitude\": 1, \"longitude\": 2, \"liveUntil\": 1.0}"), 0L))
        // Entity-encoded JSON is unescaped before decoding; a trailing
        // <meta> block is stripped before decoding.
        assertTrue(LiveLocationPolicy.isLiveMessage(llMsg(MessageType.LOCATION, "{&quot;latitude&quot;: 1, &quot;longitude&quot;: 2, &quot;live&quot;: true}"), 0L))
        assertTrue(LiveLocationPolicy.isLiveMessage(llMsg(MessageType.LOCATION, "{\"latitude\": 1, \"longitude\": 2, \"live\": true}<meta>{\"x\":1}</meta>"), 0L))
        // A "<meta>" inside a string value still truncates the body
        // (lastIndexOf), so the JSON is cut mid-string and undecodable.
        assertFalse(LiveLocationPolicy.isLiveMessage(llMsg(MessageType.LOCATION, "{\"latitude\": 1, \"longitude\": 2, \"live\": true, \"label\": \"a<meta>b\"}"), 0L))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle129.genLl consumption order exactly, so seed
        // 129001 replays the same 1500 GEN lines drive129.py ran.
        val rnd = Random(129001)
        repeat(1500) {
            when (rnd.nextInt(5)) {
                0 -> {
                    val live = rnd.nextBoolean()
                    val until = genLlUntil(rnd)
                    val now = genLlLong(rnd)
                    val payload = LocationPayload(0.0, 0.0, live = live, liveUntil = until)
                    assertEquals(llIsLiveOracle(live, until, now), LiveLocationPolicy.isLive(payload, now))
                }
                1 -> {
                    val type = genLlType(rnd)
                    val content = genLlContent(rnd)
                    val now = genLlLong(rnd)
                    assertEquals(llIsLiveMessageOracle(type, content, now), LiveLocationPolicy.isLiveMessage(llMsg(type, content), now))
                }
                2 -> {
                    val until = genLlLong(rnd)
                    val now = genLlLong(rnd)
                    assertEquals(llRemainingMsOracle(until, now), LiveLocationPolicy.remainingMs(LocationPayload(0.0, 0.0, liveUntil = until), now))
                }
                3 -> {
                    val until = genLlUntil(rnd)
                    val now = genLlLong(rnd)
                    assertEquals(llRemainingMsOracle(until, now), LiveLocationPolicy.remainingFromUntil(until, now))
                }
                else -> {
                    val ms = genLlLong(rnd)
                    assertEquals(llFormatRemainingOracle(ms), LiveLocationPolicy.formatRemaining(ms))
                }
            }
        }
    }

    @Test
    fun sweepExhaustive() {
        // isLive over live x until x now; remainingMs / remainingFromUntil
        // over the same long pool (covers the overflow wrap pairs).
        for (live in listOf(false, true)) {
            for (until in LL_LONG_POOL) {
                for (now in LL_LONG_POOL) {
                    assertEquals(llIsLiveOracle(live, until, now), LiveLocationPolicy.isLive(LocationPayload(0.0, 0.0, live = live, liveUntil = until), now))
                    assertEquals(llRemainingMsOracle(until, now), LiveLocationPolicy.remainingMs(LocationPayload(0.0, 0.0, liveUntil = until), now))
                    assertEquals(llRemainingMsOracle(until, now), LiveLocationPolicy.remainingFromUntil(until, now))
                }
            }
        }
        assertEquals(llIsLiveOracle(true, null, 0L), LiveLocationPolicy.isLive(LocationPayload(0.0, 0.0, live = true), 0L))
        assertEquals(llRemainingMsOracle(null, 0L), LiveLocationPolicy.remainingFromUntil(null, 0L))
        for (ms in LL_LONG_POOL) {
            assertEquals(llFormatRemainingOracle(ms), LiveLocationPolicy.formatRemaining(ms))
        }
        for (ms in longArrayOf(-1500, -1001, -1000, -999, 999, 59_999, 60_001, 3_599_999, 3_600_001, 3_660_000, 3_660_001, 86_399_999, 86_400_000)) {
            assertEquals(llFormatRemainingOracle(ms), LiveLocationPolicy.formatRemaining(ms))
        }
        // isLiveMessage: every message type x the malformed pool, plus
        // the lat/long literal matrix at live=true.
        for (type in MessageType.entries) {
            for (content in LL_MALFORMED_POOL) {
                assertEquals(llIsLiveMessageOracle(type, content, 0L), LiveLocationPolicy.isLiveMessage(llMsg(type, content), 0L))
            }
        }
        val srnd = Random(129001)
        for (lat in LL_LAT_POOL) {
            for (lon in LL_LON_POOL) {
                val json = "{\"latitude\": $lat, \"longitude\": $lon, \"live\": true}"
                assertEquals(llIsLiveMessageOracle(MessageType.LOCATION, json, 0L), LiveLocationPolicy.isLiveMessage(llMsg(MessageType.LOCATION, json), 0L))
            }
        }
        // liveUntil boundary x now, and the entity / <meta> variants.
        for (until in longArrayOf(-1, 0, 1, 99, 100, 101, Long.MIN_VALUE, Long.MAX_VALUE)) {
            for (now in longArrayOf(-1, 0, 1, 99, 100, 101, Long.MIN_VALUE, Long.MAX_VALUE)) {
                val json = "{\"latitude\": 1, \"longitude\": 2, \"live\": true, \"liveUntil\": $until}"
                assertEquals(llIsLiveMessageOracle(MessageType.LOCATION, json, now), LiveLocationPolicy.isLiveMessage(llMsg(MessageType.LOCATION, json), now))
            }
        }
        for (variant in arrayOf(
            "{&quot;latitude&quot;: 1, &quot;longitude&quot;: 2, &quot;live&quot;: true}",
            "&#123;&quot;latitude&quot;: 1, &quot;longitude&quot;: 2, &quot;live&quot;: true&#125;",
            "{\"latitude\": 1, \"longitude\": 2, \"live\": true}&lt;meta&gt;",
            "{\"latitude\": 1, \"longitude\": 2, \"live\": true}<meta>{\"x\":1}</meta>",
            "{\"latitude\": 1, \"longitude\": 2, \"live\": true, \"label\": \"a&lt;b\"}",
            "{\"latitude\": 1, \"longitude\": 2, \"live\": true, \"label\": \"a&amp;lt;b\"}",
            "{\"latitude\": 1, \"longitude\": 2, \"live\": true, \"label\": \"&#38;lt;\"}",
            "{\"latitude\": 1, \"longitude\": 2, \"live\": true, \"label\": \"&#x3c;meta&gt;\"}",
            "{\"latitude\": 1, \"longitude\": 2, \"live\": true, \"label\": \"&#0;\"}",
            "{\"latitude\": 1, \"longitude\": 2, \"live\": true, \"label\": \"&#x110000;\"}",
            "{\"latitude\": 1, \"longitude\": 2, \"live\": true, \"label\": \"&#xD800;\"}"
        )) {
            assertEquals(llIsLiveMessageOracle(MessageType.LOCATION, variant, 0L), LiveLocationPolicy.isLiveMessage(llMsg(MessageType.LOCATION, variant), 0L))
        }
        // Random payload JSONs from the same seed, re-checked as a set.
        repeat(200) {
            val json = genLlPayloadJson(srnd)
            assertEquals(llIsLiveMessageOracle(MessageType.LOCATION, json, 0L), LiveLocationPolicy.isLiveMessage(llMsg(MessageType.LOCATION, json), 0L))
        }
    }
}
