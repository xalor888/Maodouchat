// Fuzz / random-oracle tests for UnreadSummaryCandidates (checklist line 931,
// 116th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. selectUnreadSummaryCandidates / buildUnreadSummaryCacheKey.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageType

class UnreadSummaryCandidatesFuzzTest {

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

// ---------- block-116 common oracle helpers ----------

// Per-code-point uppercase (Character.toUpperCase per code point),
// mirroring String.uppercase(): the alphabet audit proves full ==
// simple over the generator alphabet, so no expansion occurs.

// Local hex encoder (same codec as Parse116.kt's encStr); the spec
// builders below and the test files live in named packages and
// cannot see the root-package Parse116.kt, so the codec lives here.
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

// ---------- wire value parts (oracle-side, never production) ----------

private data class TargetParts(val userId: String, val deviceId: Int)

private data class CiphertextParts(
    val userId: String,
    val deviceId: Int,
    val ciphertextType: String,
    val ciphertext: String
)

private data class EnvelopeParts(
    val userId: String,
    val deviceId: Int,
    val ciphertextType: String,
    val ciphertext: String
)

private data class EventParts(
    val action: String,
    val targetMessageId: String,
    val status: String,
    val throughMessageId: String?
)

private data class ContentParts(
    val type: String,
    val attributes: List<Pair<String, String>>
)

private data class UscMessageParts(
    val id: String,
    val senderId: String,
    val content: String,
    val type: String,
    val timestamp: Long,
    val aiAssisted: Boolean
)

// ---------- spec encoders (GEN only; decoders live in EVAL_HELPERS) ----------

private fun targetSpecOf(targets: List<TargetParts>): String =
    targets.joinToString(";") { t -> encSpecStr(t.userId) + "|" + t.deviceId.toString() }

private fun cipherSpecOf(ciphertexts: List<CiphertextParts>): String =
    ciphertexts.joinToString(";") { c ->
        encSpecStr(c.userId) + "|" + c.deviceId.toString() + "|" +
            encSpecStr(c.ciphertextType) + "|" + encSpecStr(c.ciphertext)
    }

private fun envelopeSpecOf(envelopes: List<EnvelopeParts>): String =
    envelopes.joinToString(";") { e ->
        encSpecStr(e.userId) + "|" + e.deviceId.toString() + "|" +
            encSpecStr(e.ciphertextType) + "|" + encSpecStr(e.ciphertext)
    }

private fun uscMessageSpecOf(m: UscMessageParts): String =
    "M|" + encSpecStr(m.id) + "|" + encSpecStr(m.senderId) + "|" +
        encSpecStr(m.content) + "|" + m.type + "|" + m.timestamp.toString() + "|" +
        (if (m.aiAssisted) "1" else "0")

private fun uscMessageListSpecOf(messages: List<UscMessageParts>): String =
    messages.joinToString(";") { encSpecStr(uscMessageSpecOf(it)) }

// ---------- output encoders (eval only) ----------

private fun encTargetList(ids: List<String>): String =
    ids.joinToString(";") { encSpecStr(it) }

private fun encEnvelopeList(envelopes: List<EnvelopeParts>): String =
    envelopeSpecOf(envelopes)

private fun encEventParts(e: EventParts): String =
    encSpecStr(e.action) + "|" + encSpecStr(e.targetMessageId) + "|" +
        encSpecStr(e.status) + "|" +
        (e.throughMessageId?.let { "S" + encSpecStr(it) } ?: "N")

private fun encContentParts(c: ContentParts): String =
    encSpecStr(c.type) + "|" +
        c.attributes.joinToString(";") { (k, v) -> encSpecStr(k) + "=" + encSpecStr(v) }

private fun encIdList(ids: List<String>): String =
    ids.joinToString(";") { encSpecStr(it) }

// ---------- UnreadSummaryCandidates oracle ----------

// Hand-written reimplementation of ChatTextDisplayPolicy
// .unescapeHtmlEntities: numeric decimal, numeric hex, then the
// seven literal replaces in production order. Never Regex /
// .replace() with a lambda here -- the scan is explicit so the
// Python side mirrors it exactly.
private fun replaceLiteralOracle(s: String, old: String, new: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        if (s.startsWith(old, i)) { sb.append(new); i += old.length }
        else { sb.append(s[i]); i++ }
    }
    return sb.toString()
}

private fun replaceNumericDecimalOracle(s: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        var matched = false
        if (i + 2 < s.length && s[i] == '&' && s[i + 1] == '#') {
            var j = i + 2
            var digits = 0
            while (j < s.length && digits < 7 && s[j] in '0'..'9') { j++; digits++ }
            if (digits in 1..7 && j < s.length && s[j] == ';') {
                var code = 0
                for (k in i + 2 until j) code = code * 10 + (s[k].code - '0'.code)
                if (code in 1..0x10FFFF) sb.append(String(intArrayOf(code), 0, 1))
                else sb.append(s.substring(i, j + 1))
                i = j + 1
                matched = true
            }
        }
        if (!matched) { sb.append(s[i]); i++ }
    }
    return sb.toString()
}

private fun isHexDigitOracle(c: Char): Boolean =
    c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

private fun hexValueOracle(c: Char): Int =
    when (c) {
        in '0'..'9' -> c.code - '0'.code
        in 'a'..'f' -> c.code - 'a'.code + 10
        else -> c.code - 'A'.code + 10
    }

private fun replaceNumericHexOracle(s: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        var matched = false
        if (i + 3 < s.length && s[i] == '&' && s[i + 1] == '#' &&
            (s[i + 2] == 'x' || s[i + 2] == 'X')
        ) {
            var j = i + 3
            var digits = 0
            while (j < s.length && digits < 6 && isHexDigitOracle(s[j])) { j++; digits++ }
            if (digits in 1..6 && j < s.length && s[j] == ';') {
                var code = 0
                for (k in i + 3 until j) code = code * 16 + hexValueOracle(s[k])
                if (code in 1..0x10FFFF) sb.append(String(intArrayOf(code), 0, 1))
                else sb.append(s.substring(i, j + 1))
                i = j + 1
                matched = true
            }
        }
        if (!matched) { sb.append(s[i]); i++ }
    }
    return sb.toString()
}

private fun unescapeHtmlEntitiesOracle(raw: String): String {
    if (raw.isEmpty() || indexOfCharOracle(raw, '&') < 0) return raw
    var out = replaceNumericDecimalOracle(raw)
    out = replaceNumericHexOracle(out)
    out = replaceLiteralOracle(out, "&nbsp;", " ")
    out = replaceLiteralOracle(out, "&quot;", "\"")
    out = replaceLiteralOracle(out, "&#39;", "'")
    out = replaceLiteralOracle(out, "&apos;", "'")
    out = replaceLiteralOracle(out, "&lt;", "<")
    out = replaceLiteralOracle(out, "&gt;", ">")
    out = replaceLiteralOracle(out, "&amp;", "&")
    return out
}

private fun parsedContentOracle(content: String): String {
    val idx = content.lastIndexOf("<meta>")
    val body = if (idx < 0) content else content.substring(0, idx)
    return unescapeHtmlEntitiesOracle(body)
}

// Minimal JSON parser mirroring kotlinx.serialization's lenient
// parseToJsonElement (probed 2026-11-24): unquoted literals are
// raw runs up to a delimiter (so "tru", "01", "NaN", "+1", ".5"
// all parse as primitives); strings allow raw control units and
// only the eight JSON escapes plus \uXXXX; trailing content /
// trailing commas / bad escapes throw. Only the aiAssisted
// primitive's content string is ever read out of the tree.
private sealed interface UscJsonValue
private data class UscJsonObj(val entries: List<Pair<String, UscJsonValue>>) : UscJsonValue
private data class UscJsonArr(val items: List<UscJsonValue>) : UscJsonValue
private data class UscJsonStr(val value: String) : UscJsonValue
private data class UscJsonLit(val content: String) : UscJsonValue

private class UscJsonParser(val s: String) {
    var i = 0
    fun fail(): Nothing = throw IllegalArgumentException("bad json at $i")
    fun skipWs() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    }
    fun parseTop(): UscJsonValue {
        skipWs()
        val v = parseValue()
        skipWs()
        if (i != s.length) fail()
        return v
    }
    fun parseValue(): UscJsonValue {
        skipWs()
        if (i >= s.length) fail()
        return when (s[i]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> UscJsonStr(parseString())
            else -> parseLiteral()
        }
    }
    fun parseObject(): UscJsonValue {
        i++
        val entries = ArrayList<Pair<String, UscJsonValue>>()
        skipWs()
        if (i < s.length && s[i] == '}') { i++; return UscJsonObj(entries) }
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
            if (i < s.length && s[i] == '}') { i++; return UscJsonObj(entries) }
            fail()
        }
    }
    fun parseArray(): UscJsonValue {
        i++
        val items = ArrayList<UscJsonValue>()
        skipWs()
        if (i < s.length && s[i] == ']') { i++; return UscJsonArr(items) }
        while (true) {
            items.add(parseValue())
            skipWs()
            if (i < s.length && s[i] == ',') { i++; continue }
            if (i < s.length && s[i] == ']') { i++; return UscJsonArr(items) }
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
    fun parseLiteral(): UscJsonValue {
        val start = i
        while (i < s.length && !isLiteralTerminator(s[i])) i++
        if (i == start) fail()
        return UscJsonLit(s.substring(start, i))
    }
}

// Hand-written reimplementation of Message.parsedMeta()
// .aiAssisted: no "<meta>" -> the field; blank tag JSON -> false
// (fromJsonString returns a default MessageMeta for blank text,
// it does NOT fall back to the field -- only an unparsable tag
// does, via runCatching); a parsed object without aiAssisted ->
// false (the tag REPLACES the field); a primitive aiAssisted ->
// strict "true"/"false" ("True" is not true, "1" is not true,
// JsonNull is not true).
private fun parsedMetaAiAssistedOracle(content: String, fieldAi: Boolean): Boolean {
    val idx = content.lastIndexOf("<meta>")
    if (idx < 0) return fieldAi
    val after = content.substring(idx + "<meta>".length)
    val endIdx = after.indexOf("</meta>")
    val json = if (endIdx < 0) after else after.substring(0, endIdx)
    if (isBlankOracle(json)) return false
    val parsed = try { UscJsonParser(json).parseTop() } catch (e: Throwable) { null }
    if (parsed !is UscJsonObj) return fieldAi
    val raw = parsed.entries.lastOrNull { it.first == "aiAssisted" }?.second ?: return false
    val contentStr = when (raw) {
        is UscJsonStr -> raw.value
        is UscJsonLit -> if (raw.content == "null") null else raw.content
        else -> null
    } ?: return false
    return contentStr == "true"
}

private fun selectUnreadSummaryCandidatesOracle(
    messages: List<UscMessageParts>,
    currentUserId: String,
    unreadCount: Int
): List<String> {
    val limit = unreadCount.coerceIn(1, 48)
    return messages
        .filter { m ->
            m.senderId != currentUserId &&
                (m.type == "TEXT" || m.type == "MARKDOWN") &&
                !parsedMetaAiAssistedOracle(m.content, m.aiAssisted) &&
                !isBlankOracle(parsedContentOracle(m.content))
        }
        .sortedBy { it.timestamp }
        .takeLast(limit)
        .map { it.id }
}

private fun buildUnreadSummaryCacheKeyOracle(
    chatId: String,
    firstMessageId: String,
    lastMessageId: String,
    messageCount: Int
): String = "$chatId:$firstMessageId:$lastMessageId:$messageCount"

private fun genTextOf(rnd: Random, len: Int): String {
    val sb = StringBuilder()
    while (sb.length < len) sb.append(randomString(rnd, 64))
    return if (sb.length > len) sb.toString().substring(0, len) else sb.toString()
}

private val USC_ID_POOL116 = arrayOf(
    "m1", "m2", "m3", "", "m_1", "üser"
)

private val USC_SENDER_POOL116 = arrayOf(
    "u1", "u2", "owner", "", "  ", "üser"
)

private val USC_TS_POOL116 = arrayOf(
    0L, 1L, -1L, 1000L, 1700000000000L, 1750000000000L, Long.MAX_VALUE, Long.MIN_VALUE
)

private val USC_COUNT_POOL116 = arrayOf(
    0, 1, 2, 3, 48, 49, 100, -1, -100, 2147483647, -2147483648
)

private val USC_CONTENT_POOL116 = arrayOf(
    "", "hello", "plain text", "a &amp; b", "&#65; BC",
    "text<meta>{\"aiAssisted\":true}</meta>",
    "text<meta>{\"aiAssisted\":false}</meta>",
    "text<meta>{\"aiAssisted\":\"true\"}</meta>",
    "text<meta>{\"aiAssisted\":\"True\"}</meta>",
    "text<meta>{\"aiAssisted\":1}</meta>",
    "text<meta>{\"aiAssisted\":null}</meta>",
    "text<meta>{\"aiAssisted\":[true]}</meta>",
    "text<meta>{\"aiAssisted\":{\"x\":1}}</meta>",
    "text<meta>{}</meta>",
    "text<meta>{bad</meta>",
    "text<meta>not json</meta>",
    "text<meta>   </meta>",
    "text<meta>[]</meta>",
    "text<meta>\"x\"</meta>",
    "text<meta>123</meta>",
    "text<meta>{\"aiAssisted\":true} trailing</meta>",
    "text<meta>{\"aiAssisted\":true,\"aiAssisted\":false}</meta>",
    "text<meta>{\"aiAssisted\":NaN}</meta>",
    "text<meta>{\"aiAssisted\":01}</meta>",
    "text<meta>{\"aiAssisted\":tru}</meta>",
    "text<meta>{\"aiAssisted\":truex}</meta>",
    "text<meta>{\"aiAssisted\":\"tru\\u0065\"}</meta>",
    "text<meta>{\"a\":1,\"aiAssisted\":false}</meta>",
    "a<meta>b</meta>c<meta>{\"aiAssisted\":true}</meta>",
    "x<meta>y</meta>",
    "no meta <meta",
    "<meta>",
    "text<meta>{\"aiAssisted\":true}",
    "&#65; BC<meta>{\"aiAssisted\":true}</meta>",
    "   <meta>{\"aiAssisted\":true}</meta>   ",
    "text<meta>{\"aiAssisted\":-0}</meta>",
    "text<meta>{\"aiAssisted\":1.}</meta>",
    "text<meta>{\"aiAssisted\":+1}</meta>"
)

private fun genUscMessage116(rnd: Random, poolOnly: Boolean): UscMessageParts {
    val id = if (poolOnly) USC_ID_POOL116[rnd.nextInt(USC_ID_POOL116.size)]
        else if (rnd.nextBoolean()) randomToken(rnd) else genTextOf(rnd, rnd.nextInt(0, 8))
    val sender = if (poolOnly) USC_SENDER_POOL116[rnd.nextInt(USC_SENDER_POOL116.size)]
        else if (rnd.nextBoolean()) randomToken(rnd) else genTextOf(rnd, rnd.nextInt(0, 8))
    val content = if (poolOnly) USC_CONTENT_POOL116[rnd.nextInt(USC_CONTENT_POOL116.size)]
        else if (rnd.nextInt(4) == 0) randomString(rnd, 60)
        else genTextOf(rnd, rnd.nextInt(0, 60))
    val type = MessageType.entries[rnd.nextInt(MessageType.entries.size)].name
    val ts = if (poolOnly) USC_TS_POOL116[rnd.nextInt(USC_TS_POOL116.size)]
        else rnd.nextLong()
    val ai = rnd.nextBoolean()
    return UscMessageParts(id, sender, content, type, ts, ai)
}

private fun genUscMessages116(rnd: Random, poolOnly: Boolean): List<UscMessageParts> {
    val n = if (poolOnly) rnd.nextInt(0, 4) else rnd.nextInt(0, 7)
    return (0 until n).map { genUscMessage116(rnd, poolOnly) }
}

    @Test
    fun unreadSummaryCandidatesRandom() {
        val rnd = Random(42)
        repeat(300) {
            val messages = genUscMessages116(rnd, poolOnly = false)
            val current = if (rnd.nextBoolean()) "owner" else randomToken(rnd)
            val unread = if (rnd.nextBoolean()) USC_COUNT_POOL116[rnd.nextInt(USC_COUNT_POOL116.size)]
                else rnd.nextInt()
            val prodMessages = messages.map {
                Message(
                    id = it.id, chatId = "c", senderId = it.senderId, content = it.content,
                    type = MessageType.valueOf(it.type), timestamp = it.timestamp,
                    meta = MessageMeta(aiAssisted = it.aiAssisted)
                )
            }
            assertEquals(
                selectUnreadSummaryCandidatesOracle(messages, current, unread),
                selectUnreadSummaryCandidates(prodMessages, current, unread).map { it.id }
            )
            val chatId = if (rnd.nextBoolean()) "c1" else randomToken(rnd)
            val first = messages.firstOrNull()?.id ?: ""
            val last = messages.lastOrNull()?.id ?: ""
            val count = rnd.nextInt()
            assertEquals(
                buildUnreadSummaryCacheKeyOracle(chatId, first, last, count),
                buildUnreadSummaryCacheKey(chatId, first, last, count)
            )
        }
    }

    @Test
    fun unreadSummaryCandidatesEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            // Pool-only legs: meta-tag JSON variants and timestamp/count edges dominate.
            val messages = genUscMessages116(rnd, poolOnly = true)
            val current = USC_SENDER_POOL116[rnd.nextInt(USC_SENDER_POOL116.size)]
            val unread = USC_COUNT_POOL116[rnd.nextInt(USC_COUNT_POOL116.size)]
            val prodMessages = messages.map {
                Message(
                    id = it.id, chatId = "c", senderId = it.senderId, content = it.content,
                    type = MessageType.valueOf(it.type), timestamp = it.timestamp,
                    meta = MessageMeta(aiAssisted = it.aiAssisted)
                )
            }
            assertEquals(
                selectUnreadSummaryCandidatesOracle(messages, current, unread),
                selectUnreadSummaryCandidates(prodMessages, current, unread).map { it.id }
            )
            val chatId = USC_ID_POOL116[rnd.nextInt(USC_ID_POOL116.size)]
            val first = messages.firstOrNull()?.id ?: ""
            val last = messages.lastOrNull()?.id ?: ""
            val count = USC_COUNT_POOL116[rnd.nextInt(USC_COUNT_POOL116.size)]
            assertEquals(
                buildUnreadSummaryCacheKeyOracle(chatId, first, last, count),
                buildUnreadSummaryCacheKey(chatId, first, last, count)
            )
        }
    }

    @Test
    fun unreadSummaryCandidatesPinned() {
        val m1 = Message(id = "m1", chatId = "c", senderId = "u2", content = "hello", type = MessageType.TEXT, timestamp = 1000L)
        val m2 = Message(id = "m2", chatId = "c", senderId = "owner", content = "hi", type = MessageType.TEXT, timestamp = 2000L)
        val m3 = Message(id = "m3", chatId = "c", senderId = "u2", content = "pic", type = MessageType.IMAGE, timestamp = 3000L)
        val m4 = Message(id = "m4", chatId = "c", senderId = "u2", content = "", type = MessageType.TEXT, timestamp = 4000L)
        val m5 = Message(id = "m5", chatId = "c", senderId = "u2", content = "x<meta>{\"aiAssisted\":true}</meta>", type = MessageType.TEXT, timestamp = 5000L)
        val m6 = Message(id = "m6", chatId = "c", senderId = "u3", content = "y<meta>{\"aiAssisted\":false}</meta>", type = MessageType.MARKDOWN, timestamp = 500L)
        val m7 = Message(id = "m7", chatId = "c", senderId = "u2", content = "z<meta>{}</meta>", type = MessageType.TEXT, timestamp = 600L, meta = MessageMeta(aiAssisted = true))
        val m8 = Message(id = "m8", chatId = "c", senderId = "u2", content = "w<meta>not json</meta>", type = MessageType.TEXT, timestamp = 700L, meta = MessageMeta(aiAssisted = true))
        val m9 = Message(id = "m9", chatId = "c", senderId = "u2", content = "&#32;", type = MessageType.TEXT, timestamp = 800L)
        val m10 = Message(id = "m10", chatId = "c", senderId = "u2", content = "v<meta>not json</meta>", type = MessageType.TEXT, timestamp = 750L, meta = MessageMeta(aiAssisted = false))
        val all = listOf(m1, m2, m3, m4, m5, m6, m7, m8, m9, m10)
        // Included, oldest first: m6 (500), m7 (600), m10 (750), m1 (1000).
        assertEquals(listOf("m1"), selectUnreadSummaryCandidates(all, "owner", 1).map { it.id })
        assertEquals(listOf("m1"), selectUnreadSummaryCandidates(all, "owner", 0).map { it.id })
        assertEquals(listOf("m10", "m1"), selectUnreadSummaryCandidates(all, "owner", 2).map { it.id })
        assertEquals(
            listOf("m6", "m7", "m10", "m1"),
            selectUnreadSummaryCandidates(all, "owner", 100).map { it.id }
        )
        assertEquals(emptyList(), selectUnreadSummaryCandidates(emptyList(), "owner", 5).map { it.id })
        assertEquals("c1:m1:m5:3", buildUnreadSummaryCacheKey("c1", "m1", "m5", 3))
        assertEquals(":::0", buildUnreadSummaryCacheKey("", "", "", 0))
        // Oracle agrees with production on the meta-tag traps.
        assertEquals(true, parsedMetaAiAssistedOracle("x<meta>{\"aiAssisted\":true}</meta>", false))
        assertEquals(false, parsedMetaAiAssistedOracle("x<meta>{}</meta>", true))
        assertEquals(true, parsedMetaAiAssistedOracle("x<meta>not json</meta>", true))
        assertEquals(false, parsedMetaAiAssistedOracle("x<meta>not json</meta>", false))
        assertEquals(false, parsedMetaAiAssistedOracle("x<meta>{\"aiAssisted\":tru}</meta>", false))
        assertEquals(false, parsedMetaAiAssistedOracle("x<meta>{\"aiAssisted\":NaN}</meta>", true))
        assertEquals(true, parsedMetaAiAssistedOracle("plain", true))
        // Blank tag JSON does NOT fall back to the field: fromJsonString
        // returns a default MessageMeta (aiAssisted=false) for blank text.
        assertEquals(false, parsedMetaAiAssistedOracle("x<meta>   </meta>", true))
        assertEquals(false, parsedMetaAiAssistedOracle("x<meta></meta>", true))
        assertEquals(false, parsedMetaAiAssistedOracle("x<meta>{\"aiAssisted\":\"True\"}</meta>", false))
        assertEquals(false, parsedMetaAiAssistedOracle("x<meta>{\"aiAssisted\":1}</meta>", false))
        assertEquals("a <b", parsedContentOracle("a &lt;b<meta>x</meta>"))
        assertEquals("A", parsedContentOracle("&#65;<meta>x</meta>"))
        val parts = listOf(
            UscMessageParts("m1", "u2", "hello", "TEXT", 1000L, false),
            UscMessageParts("m6", "u3", "y<meta>{\"aiAssisted\":false}</meta>", "MARKDOWN", 500L, false),
            UscMessageParts("m7", "u2", "z<meta>{}</meta>", "TEXT", 600L, true),
            UscMessageParts("m10", "u2", "v<meta>not json</meta>", "TEXT", 750L, false)
        )
        assertEquals(listOf("m10", "m1"), selectUnreadSummaryCandidatesOracle(parts, "owner", 2))
    }
}
