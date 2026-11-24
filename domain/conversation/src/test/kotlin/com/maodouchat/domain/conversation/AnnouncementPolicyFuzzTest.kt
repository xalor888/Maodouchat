// Fuzz / random-oracle tests for AnnouncementPolicy (checklist line 931,
// 117th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. filterForDisplay / shouldNotifyNow / parseAndFilterActivePayload.
package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnnouncementPolicyFuzzTest {

// ---------- shared oracle (all three clusters) ----------

// The probed Kotlin blank set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as blank, U+0085 NEL / U+200B / U+FEFF do not.
// Hand-written, never calls Char.isWhitespace / String.isBlank.
private val BLANK_UNITS_ORACLE = setOf(
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
    0xA0, 0x1680,
    0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
    0x2008, 0x2009, 0x200A,
    0x2028, 0x2029, 0x202F, 0x205F, 0x3000
)

private fun isBlankOracle(s: String): Boolean {
    for (c in s) {
        if (c.code !in BLANK_UNITS_ORACLE) return false
    }
    return true
}

// Hand-written trim: strip leading/trailing UTF-16 units in the
// probed 28-unit Kotlin whitespace set. Never trim().
private fun trimOracle(s: String): String {
    var lo = 0
    var hi = s.length
    while (lo < hi && s[lo].code in BLANK_UNITS_ORACLE) lo++
    while (hi > lo && s[hi - 1].code in BLANK_UNITS_ORACLE) hi--
    return s.substring(lo, hi)
}

// Unit-by-unit equality. Never == on strings.
private fun equalsOracle(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    for (i in a.indices) {
        if (a[i] != b[i]) return false
    }
    return true
}

// Per-code-point simple case mapping. Agrees with String.lowercase()
// (full mapping) on the case-safe alphabet; never calls lowercase().
// Diverges on purpose outside the alphabet: U+0130 İ (full mapping
// expands to i + U+0307), word-final sigma, U+0587 -- those are
// pinned with probe-verified literals, never generated.
private fun lowercaseOracle(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        sb.append(Character.toChars(Character.toLowerCase(cp)))
        i += Character.charCount(cp)
    }
    return sb.toString()
}

// Hand-written substring search over UTF-16 units. Never contains().
private fun containsOracle(s: String, needle: String): Boolean {
    if (needle.isEmpty()) return true
    if (needle.length > s.length) return false
    for (i in 0..s.length - needle.length) {
        var ok = true
        for (j in needle.indices) {
            if (s[i + j] != needle[j]) { ok = false; break }
        }
        if (ok) return true
    }
    return false
}

// Case-insensitive contains: per-char fold, exactly as
// regionMatches(ignoreCase = true) does it -- equal, else equal after
// toUpperCase, else equal after toLowerCase. Exact for every input;
// no alphabet restriction applies to this function.
private fun charsEqualIgnoreCaseOracle(a: Char, b: Char): Boolean {
    if (a == b) return true
    if (Character.toUpperCase(a) == Character.toUpperCase(b)) return true
    return Character.toLowerCase(a) == Character.toLowerCase(b)
}

private fun containsIgnoreCaseOracle(s: String, needle: String): Boolean {
    if (needle.isEmpty()) return true
    if (needle.length > s.length) return false
    for (i in 0..s.length - needle.length) {
        var ok = true
        for (j in needle.indices) {
            if (!charsEqualIgnoreCaseOracle(s[i + j], needle[j])) { ok = false; break }
        }
        if (ok) return true
    }
    return false
}

private fun replaceCharOracle(s: String, from: Char, to: Char): String {
    val chars = s.toCharArray()
    for (i in chars.indices) {
        if (chars[i] == from) chars[i] = to
    }
    return String(chars)
}

// Lexicographic order over UTF-16 units, as String.compareTo.
private fun compareOracle(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    for (i in 0 until n) {
        val d = a[i].code - b[i].code
        if (d != 0) return d
    }
    return a.length - b.length
}

// Hand-written take: first n UTF-16 units. Never take().
private fun takeOracle(s: String, n: Int): String {
    if (n <= 0) return ""
    if (n >= s.length) return s
    return s.substring(0, n)
}

private fun coerceAtLeastOracle(v: Int, lo: Int): Int = if (v < lo) lo else v

// ---------- shared generators ----------

// Every code point here maps 1:1 under BOTH the per-code-point simple
// mapping (Character.toLowerCase, what the oracle uses) and the full
// string mapping (String.lowercase(), what production uses), and folds
// the same under contains(ignoreCase = true). Excluded on purpose:
// Greek (word-final sigma), Armenian, ligatures U+FB00-FB17, U+0130
// (İ), U+00DF (ß, conservative), U+0131 (ı: upper folds to I,
// simple lowercase keeps ı), U+0149, U+017F (ſ: ignoreCase folds
// it to s, simple lowercase does not), U+01F0, U+1E9B (ṡ: upper folds
// to Ṡ, simple lowercase does not), Cherokee, Georgian --
// all special-casing code points. Same ranges as block 66, minus
// ſ / ı / U+1E9B.
private val CASE_SAFE_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00C0 to 0x00DE, 0x00E0 to 0x00FE,
    0x0100 to 0x012F, 0x0132 to 0x0148, 0x014A to 0x017E, 0x0180 to 0x01EF,
    0x0300 to 0x036F,
    0x1E00 to 0x1E95, 0x1E9C to 0x1EFF,
    0x2000 to 0x206F,
    0x3000 to 0x30FF, 0x4E00 to 0x4FFF,
    0xFF21 to 0xFF3A, 0xFF41 to 0xFF5A,
    0x10400 to 0x1044F
)

// Astral emoji: no case at all, lowercase is identity on both sides.
private val EMOJI_RANGES = arrayOf(
    0x1F300 to 0x1F5FF, 0x1F600 to 0x1F64F, 0x1F680 to 0x1F6FF, 0x1F900 to 0x1F9FF
)

private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

private fun randomCaseSafeCodePoint(rnd: Random): Int {
    val (lo, hi) = CASE_SAFE_RANGES[rnd.nextInt(CASE_SAFE_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomEmojiCodePoint(rnd: Random): Int {
    val (lo, hi) = EMOJI_RANGES[rnd.nextInt(EMOJI_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomString(rnd: Random, maxLen: Int = 40): String {
    val len = when (rnd.nextInt(8)) {
        0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 8; 5 -> 16; 6 -> 32
        else -> rnd.nextInt(0, maxLen + 1)
    }
    val sb = StringBuilder()
    var n = 0
    while (n < len) {
        when (rnd.nextInt(10)) {
            0 -> { sb.append(' '); n++ }
            1 -> { sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)]); n++ }
            2 -> { sb.append(if (rnd.nextBoolean()) '\uD800' else '\uDC00'); n++ }
            3 -> { sb.append(Character.toChars(randomEmojiCodePoint(rnd))); n += 2 }
            else -> {
                val cp = randomCaseSafeCodePoint(rnd)
                sb.append(Character.toChars(cp))
                n += if (cp >= 0x10000) 2 else 1
            }
        }
    }
    return sb.toString()
}

// Short tokens for ids / pack ids / sticker names: pool words (so the
// interesting collisions actually happen) mixed with random strings.
private val TOKEN_POOL = arrayOf(
    "a", "b", "c", "A", "B", "x", "y", "z", "ab", "AB", "a_b", "a-b",
    "mood", "gesture", "party", "cat", "CAT", "Cat", "happy", "HAPPY",
    "ok", "fire", "pet", "love", "hi", "no", "win", "star", "", " ",
    "  ", "😀", "🎉", "🔥", "👍", "🥳", "a b", "funny_cat", "funny-cat"
)

private fun randomToken(rnd: Random): String =
    if (rnd.nextInt(4) == 0) randomString(rnd, 6) else TOKEN_POOL[rnd.nextInt(TOKEN_POOL.size)]

private fun randomWord(rnd: Random): String {
    return when (rnd.nextInt(5)) {
        0 -> "cat"
        1 -> "CAT"
        2 -> "funny cat"
        3 -> "happy"
        else -> randomString(rnd, 12)
    }
}

// ---------- extra hand-written helpers ----------

private fun isWhitespaceOracle(c: Char): Boolean = c.code in BLANK_UNITS_ORACLE

private fun isLetterOrDigitOracle(c: Char): Boolean =
    Character.isLetterOrDigit(c.code)

private fun startsWithOracle(s: String, prefix: String): Boolean {
    if (prefix.length > s.length) return false
    for (i in prefix.indices) {
        if (s[i] != prefix[i]) return false
    }
    return true
}

private fun startsWithIgnoreCaseOracle(s: String, prefix: String): Boolean {
    if (prefix.length > s.length) return false
    for (i in prefix.indices) {
        if (!charsEqualIgnoreCaseOracle(s[i], prefix[i])) return false
    }
    return true
}

private fun dropOracle(s: String, n: Int): String {
    if (n <= 0) return s
    if (n >= s.length) return ""
    return s.substring(n)
}

private fun removePrefixOracle(s: String, prefix: String): String =
    if (startsWithOracle(s, prefix)) dropOracle(s, prefix.length) else s

// trimStart() with no args: strip leading Kotlin-whitespace units.
private fun trimStartBlankOracle(s: String): String {
    var i = 0
    while (i < s.length && s[i].code in BLANK_UNITS_ORACLE) i++
    return s.substring(i)
}

// lineSequence(): split on "\r\n" | "\n" | "\r", terminators dropped,
// a trailing terminator leaves a final empty line.
private fun splitLinesOracle(s: String): List<String> {
    val out = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\n') {
            out.add(s.substring(start, i))
            i++
            start = i
        } else if (c == '\r') {
            out.add(s.substring(start, i))
            i++
            if (i < s.length && s[i] == '\n') i++
            start = i
        } else {
            i++
        }
    }
    out.add(s.substring(start, s.length))
    return out
}

private fun indexOfCharOracle(s: String, ch: Char): Int {
    for (i in s.indices) {
        if (s[i] == ch) return i
    }
    return -1
}

// Java String.hashCode(): h = 31*h + unit, Int arithmetic wraps.
private fun hashCodeOracle(s: String): Int {
    var h = 0
    for (c in s) h = 31 * h + c.code
    return h
}

// java.lang.Long.parseLong(s, radix), reimplemented with the same
// JDK primitive (Character.digit) and the same negative-accumulator
// overflow algorithm, so the exact Long.MIN_VALUE edge parses.
private fun parseLongRadixOracle(s: String, radix: Int): Long? {
    if (s.isEmpty()) return null
    var i = 0
    var neg = false
    if (s[0] == '+' || s[0] == '-') {
        neg = s[0] == '-'
        i = 1
        if (i >= s.length) return null
    }
    var acc = 0L
    val limit = if (neg) Long.MIN_VALUE else -Long.MAX_VALUE
    val multmin = limit / radix
    while (i < s.length) {
        val d = Character.digit(s[i].code, radix)
        if (d < 0) return null
        if (acc < multmin) return null
        acc *= radix
        if (acc < limit + d) return null
        acc -= d
        i++
    }
    return if (neg) acc else -acc
}

// java.lang.Integer.parseInt(s), same algorithm on Int.
private fun parseIntOracle(s: String): Int? {
    if (s.isEmpty()) return null
    var i = 0
    var neg = false
    if (s[0] == '+' || s[0] == '-') {
        neg = s[0] == '-'
        i = 1
        if (i >= s.length) return null
    }
    var acc = 0
    val limit = if (neg) Int.MIN_VALUE else -Int.MAX_VALUE
    val multmin = limit / 10
    while (i < s.length) {
        val d = Character.digit(s[i].code, 10)
        if (d < 0) return null
        if (acc < multmin) return null
        acc *= 10
        if (acc < limit + d) return null
        acc -= d
        i++
    }
    return if (neg) acc else -acc
}

private fun isDigitOracle(c: Char): Boolean = Character.isDigit(c.code)


// ---------- extra helpers, block 80 ----------

// Per-code-point simple case mapping, mirroring lowercaseOracle.
// Agrees with String.uppercase() (full mapping) on the case-safe
// alphabet; never calls uppercase().
private fun uppercaseOracle(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        sb.append(Character.toChars(Character.toUpperCase(cp)))
        i += Character.charCount(cp)
    }
    return sb.toString()
}

// String.substringBefore(delimiter): up to the first occurrence,
// or the whole string when absent.
private fun substringBeforeOracle(s: String, delim: Char): String {
    val idx = indexOfCharOracle(s, delim)
    return if (idx < 0) s else s.substring(0, idx)
}

// ---------- block-117 common oracle helpers ----------

// Per-code-point uppercase (Character.toUpperCase per code point),
// mirroring String.uppercase(): the alphabet audit proves full ==
// simple over the generator alphabet, so no expansion occurs.

// Local hex encoder (same codec as Parse117.kt's encStr); the spec
// builders below and the test files live in named packages and
// cannot see the root-package Parse117.kt, so the codec lives here.
private fun encSpecStr(s: String): String {
    val sb = StringBuilder(s.length * 4)
    for (c in s) {
        val v = c.code
        sb.append("0123456789abcdef"[v shr 12])
        sb.append("0123456789abcdef"[(v shr 8) and 0xF])
        sb.append("0123456789abcdef"[(v shr 4) and 0xF])
        sb.append("0123456789abcdef"[v and 0xF])
    }
    return sb.toString()
}

private fun encNullableSpecStr(s: String?): String =
    if (s == null) "N" else "S" + encSpecStr(s)

// ---------- wire value parts (oracle-side, never production) ----------

// kind: HTTP | TIMEOUT | NETWORK | INVALID_RESPONSE | UNEXPECTED | OTHER.
// For OTHER, serverMessage carries the RuntimeException message;
// statusCode / serverCode / reached / retryAfterSeconds are ignored.
private data class ErrParts(
    val kind: String,
    val statusCode: Int?,
    val serverCode: String?,
    val serverMessage: String?,
    val reached: Boolean,
    val retryAfterSeconds: Long?
)

private data class StatsParts(
    val messageCount: Int,
    val activeDays: Int,
    val morning: Int,
    val afternoon: Int,
    val evening: Int,
    val night: Int,
    val emojiCount: Int,
    val topTerms: List<String>
)

private data class AnnParts(
    val id: String,
    val title: String,
    val content: String,
    val level: String,
    val startsAt: Long,
    val expiresAt: Long,
    val status: String,
    val acked: Boolean
)

private data class IoParts(val cls: String, val cause: IoParts?)

// ---------- spec encoders (GEN only; decoders live in EVAL_HELPERS) ----------

private fun errSpecOf(e: ErrParts): String =
    e.kind + "|" +
        (e.statusCode?.toString() ?: "N") + "|" +
        encNullableSpecStr(e.serverCode) + "|" +
        encNullableSpecStr(e.serverMessage) + "|" +
        (if (e.reached) "1" else "0") + "|" +
        (e.retryAfterSeconds?.toString() ?: "N")

private fun statsSpecOf(s: StatsParts): String =
    listOf(
        s.messageCount, s.activeDays, s.morning, s.afternoon, s.evening,
        s.night, s.emojiCount
    ).joinToString("|") + "|" +
        s.topTerms.joinToString(";") { encSpecStr(it) }

private fun annItemSpecOf(a: AnnParts): String =
    encSpecStr(a.id) + "|" + encSpecStr(a.title) + "|" +
        encSpecStr(a.content) + "|" + encSpecStr(a.level) + "|" +
        a.startsAt.toString() + "|" + a.expiresAt.toString() + "|" +
        encSpecStr(a.status) + "|" + (if (a.acked) "1" else "0")

private fun annListSpecOf(list: List<AnnParts>): String =
    list.joinToString(";") { encSpecStr(annItemSpecOf(it)) }

private fun ioSpecOf(e: IoParts): String =
    e.cls + "|" + (e.cause?.let { ioSpecOf(it) } ?: "N")

// ---------- output encoders (eval only) ----------

private fun encErrDetailed(code: String, retry: Long?, persisted: String): String =
    encSpecStr(code) + "|" + (retry?.toString() ?: "N") + "|" + encSpecStr(persisted)

private fun encAnnList(list: List<AnnouncementData>): String =
    annListSpecOf(list.map {
        AnnParts(it.id, it.title, it.content, it.level, it.startsAt, it.expiresAt, it.status, it.acked)
    })

private fun encIoResult(kind: String, reached: Boolean): String =
    kind + "|" + (if (reached) "B1" else "B0")

// ---------- AnnouncementPolicy oracle ----------

// Hand-written reimplementation. Level must be exactly one of the
// four keys; sort is (priority asc, startsAt asc), stable; the
// window check is inclusive on both ends; acked is ignored only
// when forceShowAcked.
private fun levelPriorityOracle(level: String): Int = when (level) {
    "EMERGENCY" -> 0
    "MAINTENANCE" -> 1
    "WARNING" -> 2
    "INFO" -> 3
    else -> 99
}

private fun filterForDisplayOracle(
    announcements: List<AnnouncementData>,
    nowMs: Long,
    forceShowAcked: Boolean
): List<AnnouncementData> =
    announcements
        .filter { a ->
            (forceShowAcked || !a.acked) &&
                a.startsAt <= nowMs &&
                a.expiresAt >= nowMs &&
                a.status == "ACTIVE" &&
                levelPriorityOracle(a.level) != 99
        }
        .sortedWith(compareBy({ levelPriorityOracle(it.level) }, { it.startsAt }))

private fun shouldNotifyNowOracle(
    announcements: List<AnnouncementData>,
    nowMs: Long
): Boolean =
    filterForDisplayOracle(announcements, nowMs, false).any { a ->
        a.level == "EMERGENCY" || a.level == "MAINTENANCE"
    }

// Minimal JSON parser mirroring kotlinx.serialization's lenient
// parseToJsonElement (probed 2026-11-24, block 116): unquoted
// literals are raw runs up to a delimiter (so "tru", "01", "NaN",
// "+1", ".5" all parse as primitives); strings allow raw control
// units and only the eight JSON escapes plus \uXXXX; trailing
// content / trailing commas / bad escapes throw. Duplicate object
// keys keep the LAST value at FIRST-seen order (LinkedHashMap).
private sealed interface AnnJsonValue
private data class AnnJsonObj(val entries: List<Pair<String, AnnJsonValue>>) : AnnJsonValue
private data class AnnJsonArr(val items: List<AnnJsonValue>) : AnnJsonValue
private data class AnnJsonStr(val value: String) : AnnJsonValue
private data class AnnJsonLit(val content: String) : AnnJsonValue
private object AnnJsonNull : AnnJsonValue

private class AnnJsonParser(val s: String) {
    var i = 0
    fun fail(): Nothing = throw IllegalArgumentException("bad json at $i")
    fun skipWs() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    }
    fun parseTop(): AnnJsonValue {
        skipWs()
        val v = parseValue()
        skipWs()
        if (i != s.length) fail()
        return v
    }
    fun parseValue(): AnnJsonValue {
        skipWs()
        if (i >= s.length) fail()
        return when (s[i]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> AnnJsonStr(parseString())
            else -> parseLiteral()
        }
    }
    fun parseObject(): AnnJsonValue {
        i++
        val entries = ArrayList<Pair<String, AnnJsonValue>>()
        skipWs()
        if (i < s.length && s[i] == '}') { i++; return AnnJsonObj(entries) }
        while (true) {
            skipWs()
            if (i >= s.length || s[i] != '"') fail()
            val key = parseString()
            skipWs()
            if (i >= s.length || s[i] != ':') fail()
            i++
            val v = parseValue()
            entries.add(key to v)
            skipWs()
            if (i < s.length && s[i] == ',') { i++; continue }
            if (i < s.length && s[i] == '}') { i++; return AnnJsonObj(entries) }
            fail()
        }
    }
    fun parseArray(): AnnJsonValue {
        i++
        val items = ArrayList<AnnJsonValue>()
        skipWs()
        if (i < s.length && s[i] == ']') { i++; return AnnJsonArr(items) }
        while (true) {
            items.add(parseValue())
            skipWs()
            if (i < s.length && s[i] == ',') { i++; continue }
            if (i < s.length && s[i] == ']') { i++; return AnnJsonArr(items) }
            fail()
        }
    }
    fun parseString(): String {
        i++
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) fail()
            val c = s[i]
            if (c == '"') { i++; return sb.toString() }
            if (c == '\\') {
                i++
                if (i >= s.length) fail()
                when (s[i]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\u0008')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 4 >= s.length) fail()
                        var v = 0
                        for (k in 1..4) {
                            val h = s[i + k]
                            val d = when (h) {
                                in '0'..'9' -> h.code - '0'.code
                                in 'a'..'f' -> h.code - 'a'.code + 10
                                in 'A'..'F' -> h.code - 'A'.code + 10
                                else -> fail()
                            }
                            v = v * 16 + d
                        }
                        sb.append(v.toChar())
                        i += 4
                    }
                    else -> fail()
                }
                i++
            } else {
                sb.append(c)
                i++
            }
        }
    }
    fun isLiteralTerminator(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\r' ||
            c == ',' || c == '}' || c == ']' || c == ':' ||
            c == '"' || c == '[' || c == '{' || c == '\\' || c.code < 0x20
    fun parseLiteral(): AnnJsonValue {
        val start = i
        while (i < s.length && !isLiteralTerminator(s[i])) i++
        if (i == start) fail()
        val content = s.substring(start, i)
        return if (content == "null") AnnJsonNull else AnnJsonLit(content)
    }
}

// Compact JSON rendering of a parsed value, mirroring kotlinx
// JsonElement.toString() (probed 2026-11-24): no spaces; strings
// escape only " \ b f n r t and other units < 0x20 as \uXXXX with
// LOWERCASE hex; U+2028/U+2029, U+007F and lone surrogates pass
// through raw; duplicate object keys render once (last value).
private fun annJsonEscapeOracle(s: String): String {
    val sb = StringBuilder()
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) {
                sb.append("\\u")
                sb.append("0123456789abcdef"[c.code shr 12])
                sb.append("0123456789abcdef"[(c.code shr 8) and 0xF])
                sb.append("0123456789abcdef"[(c.code shr 4) and 0xF])
                sb.append("0123456789abcdef"[c.code and 0xF])
            } else sb.append(c)
        }
    }
    return sb.toString()
}

private fun annJsonToStringOracle(v: AnnJsonValue): String = when (v) {
    is AnnJsonNull -> "null"
    is AnnJsonStr -> "\"" + annJsonEscapeOracle(v.value) + "\""
    is AnnJsonLit -> v.content
    is AnnJsonArr -> v.items.joinToString(",", "[", "]") { annJsonToStringOracle(it) }
    is AnnJsonObj -> {
        val deduped = ArrayList<Pair<String, AnnJsonValue>>()
        for ((k, value) in v.entries) {
            val idx = deduped.indexOfFirst { it.first == k }
            if (idx >= 0) deduped[idx] = k to value else deduped.add(k to value)
        }
        deduped.joinToString(",", "{", "}") { (k, value) ->
            "\"" + annJsonEscapeOracle(k) + "\":" + annJsonToStringOracle(value)
        }
    }
}

private fun annLookup(obj: AnnJsonObj, key: String): AnnJsonValue? =
    obj.entries.lastOrNull { it.first == key }?.second

private fun safeStringOracle(obj: AnnJsonObj, key: String): String {
    val element = annLookup(obj, key) ?: return ""
    if (element is AnnJsonNull) return ""
    if (element is AnnJsonStr) return element.value.takeIf { it != "null" }.orEmpty()
    if (element is AnnJsonLit) return element.content.takeIf { it != "null" }.orEmpty()
    return annJsonToStringOracle(element)
}

// kotlinx JsonPrimitive.longOrNull: StringJsonLexer
// .consumeNumericLiteral() (decompiled 2026-11-24). Faithful port:
// skip ASCII JSON whitespace; an optional leading '"' switches to
// quoted mode; optional leading '-'; digits accumulate into a
// negative Long (overflow fails); an optional exponent e/E with an
// optional sign and zero-or-more digits (so "1e" is 1); '.' or any
// other non-delimiter fails; trailing delimiters end the literal
// (a letter after the digits fails, whitespace/comma/brace ends
// it); the exponent path multiplies by 10^exp as a double and
// requires |value| <= 9.223372036854776E18 and floor(value) == value.
// In quoted mode a missing closing quote at EOF throws
// StringIndexOutOfBoundsException -- reproduced here by indexing
// s[i] unguarded, exactly like the library (the production
// runCatching turns it into an empty list).
private fun tokenClassOracle(c: Char): Int {
    val u = c.code
    if (u >= 126) return 0
    return when (u) {
        9, 10, 13, 32 -> 3
        34 -> 1
        44 -> 4
        58 -> 5
        91 -> 8
        92 -> 2
        93 -> 9
        123 -> 6
        125 -> 7
        0, 1, 2, 3, 4, 5, 6, 7, 8, 11, 12, 14, 15, 16, 17, 18, 19,
        20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31 -> 127
        else -> 0
    }
}

private fun calculateExponentOracle(exp: Long, positive: Boolean): Double =
    if (positive) Math.pow(10.0, exp.toDouble()) else Math.pow(10.0, -exp.toDouble())

private fun longOrNullOracle(content: String): Long? {
    val s = content
    var i = 0
    while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    if (i >= s.length) return null
    var isString = false
    if (s[i] == '"') {
        i++
        if (i >= s.length) return null
        isString = true
    }
    val start = i
    var result = 0L
    var exp = 0L
    var negative = false
    var expPositive = true
    var hasExp = false
    while (i < s.length) {
        val c = s[i]
        if (c == 'e' || c == 'E') {
            if (hasExp) return null
            if (i == start) return null
            hasExp = true
            expPositive = true
            i++
            continue
        }
        if (c == '-' && hasExp) {
            if (i == start) return null
            expPositive = false
            i++
            continue
        }
        if (c == '+' && hasExp) {
            if (i == start) return null
            expPositive = true
            i++
            continue
        }
        if (c == '-' && !hasExp) {
            if (i != start) return null
            negative = true
            i++
            continue
        }
        if (tokenClassOracle(c) != 0) break
        i++
        val d = c.code - '0'.code
        if (d < 0 || d > 9) return null
        if (hasExp) {
            exp = exp * 10 + d
        } else {
            result = result * 10 - d
            if (result > 0) return null
        }
    }
    if (i == start || (negative && i == start + 1)) return null
    if (isString) {
        if (s[i] != '"') return null
        i++
    }
    if (hasExp) {
        val value = result.toDouble() * calculateExponentOracle(exp, expPositive)
        if (value > 9.223372036854776E18 || value < -9.223372036854776E18) return null
        if (Math.floor(value) != value) return null
        result = value.toLong()
    }
    if (negative) return result
    if (result == Long.MIN_VALUE) return null
    return -result
}

private fun safeLongOracle(obj: AnnJsonObj, key: String): Long {
    val element = annLookup(obj, key) ?: return 0L
    val content = when (element) {
        is AnnJsonStr -> element.value
        is AnnJsonLit -> element.content
        else -> return 0L
    }
    return longOrNullOracle(content) ?: 0L
}

private fun equalsIgnoreCaseOracle(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    for (i in a.indices) {
        if (!charsEqualIgnoreCaseOracle(a[i], b[i])) return false
    }
    return true
}

private fun safeBooleanOracle(obj: AnnJsonObj, key: String): Boolean {
    val element = annLookup(obj, key) ?: return false
    val content = when (element) {
        is AnnJsonStr -> element.value
        is AnnJsonLit -> element.content
        else -> return false
    }
    return when {
        equalsIgnoreCaseOracle(content, "true") -> true
        equalsIgnoreCaseOracle(content, "false") -> false
        else -> false
    }
}

private fun parseAndFilterActivePayloadOracle(raw: String, nowMs: Long): List<AnnouncementData> {
    if (isBlankOracle(raw)) return emptyList()
    // The try wraps the whole body, like the production runCatching:
    // a throw inside safeLong (the quoted-EOF StringIndexOutOfBounds
    // case) must yield [], not escape.
    return try {
        val parsed = AnnJsonParser(raw).parseTop()
        if (parsed !is AnnJsonObj) return emptyList()
        val arr = annLookup(parsed, "announcements") ?: return emptyList()
        if (arr !is AnnJsonArr) return emptyList()
        val items = arr.items.mapNotNull { element ->
            if (element !is AnnJsonObj) return@mapNotNull null
            val id = safeStringOracle(element, "id")
            if (isBlankOracle(id)) return@mapNotNull null
            AnnouncementData(
                id = id,
                title = safeStringOracle(element, "title"),
                content = safeStringOracle(element, "content"),
                level = safeStringOracle(element, "level"),
                startsAt = safeLongOracle(element, "startsAt"),
                expiresAt = safeLongOracle(element, "expiresAt"),
                status = safeStringOracle(element, "status"),
                acked = safeBooleanOracle(element, "acked")
            )
        }
        filterForDisplayOracle(items, nowMs, false)
    } catch (e: Throwable) {
        emptyList()
    }
}

private fun genTextOf(rnd: Random, len: Int): String {
    val sb = StringBuilder()
    while (sb.length < len) sb.append(randomString(rnd, 64))
    return if (sb.length > len) sb.toString().substring(0, len) else sb.toString()
}

private val ANN_ID_POOL117 = arrayOf(
    "", "a1", "a2", "ann-1", "üser", "  ", "id:1", "a"
)

private val ANN_LEVEL_POOL117 = arrayOf(
    "EMERGENCY", "MAINTENANCE", "WARNING", "INFO", "emergency",
    "Emergency", "CRITICAL", "", "INFO ", " INFO", "DEBUG", "ALERT"
)

private val ANN_STATUS_POOL117 = arrayOf(
    "ACTIVE", "active", "Active", "EXPIRED", "DRAFT", "", "ACTIVE "
)

private val ANN_TIME_POOL117 = arrayOf(
    0L, 1L, 1000L, 1700000000000L, 1750000000000L, Long.MAX_VALUE,
    Long.MIN_VALUE, -1L, -1000L
)

private val ANN_NOW_POOL117 = arrayOf(
    0L, 1000L, 1700000000000L, 1750000000000L, Long.MAX_VALUE, -1L
)

private fun genAnnParts117(rnd: Random, poolOnly: Boolean): AnnParts {
    val id = if (poolOnly) ANN_ID_POOL117[rnd.nextInt(ANN_ID_POOL117.size)]
        else if (rnd.nextBoolean()) randomToken(rnd) else genTextOf(rnd, rnd.nextInt(0, 8))
    val title = if (poolOnly) ANN_ID_POOL117[rnd.nextInt(ANN_ID_POOL117.size)]
        else if (rnd.nextBoolean()) randomToken(rnd) else genTextOf(rnd, rnd.nextInt(0, 12))
    val content = if (poolOnly) ANN_ID_POOL117[rnd.nextInt(ANN_ID_POOL117.size)]
        else if (rnd.nextBoolean()) randomToken(rnd) else genTextOf(rnd, rnd.nextInt(0, 16))
    val level = if (poolOnly) ANN_LEVEL_POOL117[rnd.nextInt(ANN_LEVEL_POOL117.size)]
        else if (rnd.nextBoolean()) ANN_LEVEL_POOL117[rnd.nextInt(ANN_LEVEL_POOL117.size)]
        else randomToken(rnd)
    val startsAt = if (poolOnly) ANN_TIME_POOL117[rnd.nextInt(ANN_TIME_POOL117.size)]
        else rnd.nextLong()
    val expiresAt = if (poolOnly) ANN_TIME_POOL117[rnd.nextInt(ANN_TIME_POOL117.size)]
        else rnd.nextLong()
    val status = if (poolOnly) ANN_STATUS_POOL117[rnd.nextInt(ANN_STATUS_POOL117.size)]
        else if (rnd.nextBoolean()) ANN_STATUS_POOL117[rnd.nextInt(ANN_STATUS_POOL117.size)]
        else randomToken(rnd)
    val acked = rnd.nextBoolean()
    return AnnParts(id, title, content, level, startsAt, expiresAt, status, acked)
}

private fun genAnnList117(rnd: Random, poolOnly: Boolean): List<AnnParts> {
    val n = if (poolOnly) rnd.nextInt(0, 5) else rnd.nextInt(0, 7)
    return (0 until n).map { genAnnParts117(rnd, poolOnly) }
}

private fun annDataOfParts(p: AnnParts): AnnouncementData = AnnouncementData(
    id = p.id, title = p.title, content = p.content, level = p.level,
    startsAt = p.startsAt, expiresAt = p.expiresAt, status = p.status, acked = p.acked
)

// Raw JSON payloads for the parse leg: hand-built strings exercising
// the lenient parser, the safe* readers and the toString fallback.
// The Kotlin string literals below use explicit \u escapes so the
// file stays ASCII-clean.
private val ANN_RAW_POOL117 = arrayOf(
    "",
    "   ",
    "{}",
    "[]",
    "\"x\"",
    "123",
    "null",
    "{\"announcements\":[]}",
    "{\"announcements\":null}",
    "{\"announcements\":{}}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"EMERGENCY\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\",\"acked\":false}]}",
    "{\"announcements\":[{\"id\":\"\",\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"  \",\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":\" 42 \",\"expiresAt\":\"1e3\",\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":\"+12\",\"expiresAt\":\"12.0\",\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":\"1e\",\"expiresAt\":\"-0\",\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":\"\\\"12\",\"expiresAt\":9999999999999,\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":12,\"expiresAt\":\"12x\",\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":\"12 34\",\"expiresAt\":\"1e-3\",\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":\"9223372036854775807\",\"expiresAt\":\"9223372036854775808\",\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":\"-9223372036854775808\",\"expiresAt\":\"1e18\",\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":\"1e19\",\"expiresAt\":\"1e300\",\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":tru,\"expiresAt\":NaN,\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":01,\"expiresAt\":.5,\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":1.,\"expiresAt\":1e,\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":0x10,\"expiresAt\":TRUE,\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":\"\\u0031\\u0032\",\"expiresAt\":9999999999999,\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"title\":{\"a\":\"xy\"},\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"title\":[\"a\",\"b\"],\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"title\":null,\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"title\":\"null\",\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\",\"acked\":\"TRUE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\",\"acked\":\"yes\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\",\"acked\":1}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\",\"acked\":true}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\"},{\"id\":\"a2\",\"level\":\"EMERGENCY\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\"},\"junk\",42,null,{\"id\":\"a2\",\"level\":\"WARNING\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"id\":\"a2\",\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\",}]}",
    "{\"announcements\":[{'id':'a1'}]}",
    "{announcements:[{\"id\":\"a1\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\"}]} trailing",
    " {\"announcements\":[]} ",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\",\"extra\":{\"x\":1},\"n\":[1,2]}]}",
    "{\"announcements\":[{\"id\":\"\\u0061\\u0031\",\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a\\x1\",\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a\\u12\",\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\"}]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\"}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\"},]}",
    "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":9999999999999,\"status\":\"ACTIVE\"}],\"announcements\":[]}"
)

private fun genAnnRaw117(rnd: Random, poolOnly: Boolean): String =
    if (poolOnly) ANN_RAW_POOL117[rnd.nextInt(ANN_RAW_POOL117.size)]
        else if (rnd.nextBoolean()) ANN_RAW_POOL117[rnd.nextInt(ANN_RAW_POOL117.size)]
        else randomToken(rnd)

    @Test
    fun announcementPolicyRandom() {
        val rnd = Random(42)
        repeat(300) {
            val parts = genAnnList117(rnd, poolOnly = false)
            val list = parts.map { annDataOfParts(it) }
            val now = if (rnd.nextBoolean()) 1700000000000L else rnd.nextLong()
            val force = rnd.nextBoolean()
            assertEquals(
                filterForDisplayOracle(list, now, force),
                AnnouncementPolicy.filterForDisplay(list, now, force)
            )
            assertEquals(
                shouldNotifyNowOracle(list, now),
                AnnouncementPolicy.shouldNotifyNow(list, now)
            )
            val raw = genAnnRaw117(rnd, poolOnly = false)
            assertEquals(
                parseAndFilterActivePayloadOracle(raw, now),
                AnnouncementPolicy.parseAndFilterActivePayload(raw, now)
            )
        }
    }

    @Test
    fun announcementPolicyEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            // Pool-only legs: level/status/time pools and the raw JSON pool dominate.
            val parts = genAnnList117(rnd, poolOnly = true)
            val list = parts.map { annDataOfParts(it) }
            val now = ANN_NOW_POOL117[rnd.nextInt(ANN_NOW_POOL117.size)]
            val force = rnd.nextBoolean()
            assertEquals(
                filterForDisplayOracle(list, now, force),
                AnnouncementPolicy.filterForDisplay(list, now, force)
            )
            assertEquals(
                shouldNotifyNowOracle(list, now),
                AnnouncementPolicy.shouldNotifyNow(list, now)
            )
            val raw = genAnnRaw117(rnd, poolOnly = true)
            assertEquals(
                parseAndFilterActivePayloadOracle(raw, now),
                AnnouncementPolicy.parseAndFilterActivePayload(raw, now)
            )
        }
    }

    @Test
    fun announcementPolicyPinned() {
        val now = 1700000000000L
        val info = AnnouncementData("a1", "t", "c", "INFO", 0L, 2000000000000L, "ACTIVE")
        val emergency = AnnouncementData("a2", "t", "c", "EMERGENCY", 0L, 2000000000000L, "ACTIVE")
        val maintenance = AnnouncementData("a3", "t", "c", "MAINTENANCE", 0L, 2000000000000L, "ACTIVE")
        val warning = AnnouncementData("a4", "t", "c", "WARNING", 0L, 2000000000000L, "ACTIVE")
        val acked = AnnouncementData("a5", "t", "c", "EMERGENCY", 0L, 2000000000000L, "ACTIVE", acked = true)
        val expired = AnnouncementData("a6", "t", "c", "EMERGENCY", 0L, 1000L, "ACTIVE")
        val future = AnnouncementData("a7", "t", "c", "EMERGENCY", 1800000000000L, 2000000000000L, "ACTIVE")
        val draft = AnnouncementData("a8", "t", "c", "EMERGENCY", 0L, 2000000000000L, "DRAFT")
        val badLevel = AnnouncementData("a9", "t", "c", "CRITICAL", 0L, 2000000000000L, "ACTIVE")
        val mixed = listOf(info, emergency, maintenance, warning, acked, expired, future, draft, badLevel)
        // Priority order wins over input order; acked/expired/future/draft/bad-level drop out.
        assertEquals(
            listOf(emergency, maintenance, warning, info),
            AnnouncementPolicy.filterForDisplay(mixed, now)
        )
        assertEquals(
            listOf(emergency, maintenance, warning, info),
            filterForDisplayOracle(mixed, now, false)
        )
        // forceShowAcked brings the acked one back (still priority-sorted).
        assertEquals(
            listOf(emergency, acked, maintenance, warning, info),
            AnnouncementPolicy.filterForDisplay(mixed, now, forceShowAcked = true)
        )
        // Window edges are inclusive on both ends.
        val edgeStart = AnnouncementData("e1", "t", "c", "INFO", now, 2000000000000L, "ACTIVE")
        val edgeEnd = AnnouncementData("e2", "t", "c", "INFO", 0L, now, "ACTIVE")
        assertEquals(listOf(edgeStart), AnnouncementPolicy.filterForDisplay(listOf(edgeStart), now))
        assertEquals(listOf(edgeEnd), AnnouncementPolicy.filterForDisplay(listOf(edgeEnd), now))
        // Same priority sorts by startsAt, stable for ties.
        val s1 = AnnouncementData("s1", "t", "c", "INFO", 3000L, 2000000000000L, "ACTIVE")
        val s2 = AnnouncementData("s2", "t", "c", "INFO", 1000L, 2000000000000L, "ACTIVE")
        val s3 = AnnouncementData("s3", "t", "c", "INFO", 1000L, 2000000000000L, "ACTIVE")
        assertEquals(
            listOf(s2, s3, s1),
            AnnouncementPolicy.filterForDisplay(listOf(s1, s2, s3), now)
        )
        // Notify only on unacked, in-window EMERGENCY / MAINTENANCE.
        assertEquals(true, AnnouncementPolicy.shouldNotifyNow(listOf(emergency), now))
        assertEquals(true, AnnouncementPolicy.shouldNotifyNow(listOf(maintenance), now))
        assertEquals(false, AnnouncementPolicy.shouldNotifyNow(listOf(info), now))
        assertEquals(false, AnnouncementPolicy.shouldNotifyNow(listOf(warning), now))
        assertEquals(false, AnnouncementPolicy.shouldNotifyNow(listOf(acked), now))
        assertEquals(false, AnnouncementPolicy.shouldNotifyNow(listOf(expired), now))
        assertEquals(false, AnnouncementPolicy.shouldNotifyNow(emptyList(), now))
        // Parse: blank / non-object / missing array -> [].
        assertEquals(emptyList(), AnnouncementPolicy.parseAndFilterActivePayload("", now))
        assertEquals(emptyList(), AnnouncementPolicy.parseAndFilterActivePayload("   ", now))
        assertEquals(emptyList(), AnnouncementPolicy.parseAndFilterActivePayload("[]", now))
        assertEquals(emptyList(), AnnouncementPolicy.parseAndFilterActivePayload("{}", now))
        assertEquals(emptyList(), AnnouncementPolicy.parseAndFilterActivePayload("{\"announcements\":null}", now))
        // Parse: a full item survives; blank id drops the item.
        val one = "{\"announcements\":[{\"id\":\"a1\",\"title\":\"T\",\"content\":\"C\",\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":2000000000000,\"status\":\"ACTIVE\"}]}"
        assertEquals(
            listOf(AnnouncementData("a1", "T", "C", "INFO", 0L, 2000000000000L, "ACTIVE")),
            AnnouncementPolicy.parseAndFilterActivePayload(one, now)
        )
        assertEquals(
            listOf(AnnouncementData("a1", "T", "C", "INFO", 0L, 2000000000000L, "ACTIVE")),
            parseAndFilterActivePayloadOracle(one, now)
        )
        // safeLong: " 42 " trims to 42, "1e13" is 10^13.
        val nums = "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":\" 42 \",\"expiresAt\":\"1e13\",\"status\":\"ACTIVE\"}]}"
        assertEquals(
            listOf(AnnouncementData("a1", "", "", "INFO", 42L, 10000000000000L, "ACTIVE")),
            AnnouncementPolicy.parseAndFilterActivePayload(nums, now)
        )
        // "+12" has no leading plus in the lexer, "12.0" has a dot: both 0.
        val plus = "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":\"+12\",\"expiresAt\":\"1e13\",\"status\":\"ACTIVE\"}]}"
        assertEquals(
            listOf(AnnouncementData("a1", "", "", "INFO", 0L, 10000000000000L, "ACTIVE")),
            AnnouncementPolicy.parseAndFilterActivePayload(plus, now)
        )
        val dot = "{\"announcements\":[{\"id\":\"a1\",\"level\":\"INFO\",\"startsAt\":\"12.0\",\"expiresAt\":\"1e13\",\"status\":\"ACTIVE\"}]}"
        assertEquals(
            listOf(AnnouncementData("a1", "", "", "INFO", 0L, 10000000000000L, "ACTIVE")),
            AnnouncementPolicy.parseAndFilterActivePayload(dot, now)
        )
        // safeString: object/array values render as compact JSON.
        val objTitle = "{\"announcements\":[{\"id\":\"a1\",\"title\":{\"a\":\"x\"},\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":2000000000000,\"status\":\"ACTIVE\"}]}"
        assertEquals(
            "{\"a\":\"x\"}",
            AnnouncementPolicy.parseAndFilterActivePayload(objTitle, now).first().title
        )
        // safeBoolean: "TRUE" is true, "yes" is false.
        val ackTrue = "{\"announcements\":[{\"id\":\"a1\",\"level\":\"EMERGENCY\",\"startsAt\":0,\"expiresAt\":2000000000000,\"status\":\"ACTIVE\",\"acked\":\"TRUE\"}]}"
        assertEquals(
            emptyList(),
            AnnouncementPolicy.parseAndFilterActivePayload(ackTrue, now)
        )
        // Duplicate id keys: the last one wins.
        val dup = "{\"announcements\":[{\"id\":\"a1\",\"id\":\"a2\",\"level\":\"INFO\",\"startsAt\":0,\"expiresAt\":2000000000000,\"status\":\"ACTIVE\"}]}"
        assertEquals(
            "a2",
            AnnouncementPolicy.parseAndFilterActivePayload(dup, now).first().id
        )
        // Oracle agrees with production on the parse edges.
        assertEquals(
            emptyList(),
            parseAndFilterActivePayloadOracle("{\"announcements\":[{\"id\":\"a1\"}]}", now)
        )
    }
}
