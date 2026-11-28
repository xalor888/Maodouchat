// Fuzz / random-oracle tests for DisappearingMessageLabel (checklist line 931,
// 86th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. disappearDurationLabel maps exactly fourteen second values to their labels; every other value falls to Off through the else branch, the same Off as 0.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class DisappearingMessageLabelFuzzTest {

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

// ---------- block 86 shared (no shared helper needed) ----------

// ---------- DisappearingMessageLabel oracle ----------

private fun disappearLabelOracle(seconds: Int): DisappearDurationLabel =
    when (seconds) {
        0 -> DisappearDurationLabel.Off
        30 -> DisappearDurationLabel.Seconds30
        60 -> DisappearDurationLabel.Minute1
        2 * 60 -> DisappearDurationLabel.Minute2
        5 * 60 -> DisappearDurationLabel.Minute5
        15 * 60 -> DisappearDurationLabel.Minute15
        60 * 60 -> DisappearDurationLabel.Hour1
        2 * 60 * 60 -> DisappearDurationLabel.Hour2
        4 * 60 * 60 -> DisappearDurationLabel.Hour4
        8 * 60 * 60 -> DisappearDurationLabel.Hour8
        12 * 60 * 60 -> DisappearDurationLabel.Hour12
        24 * 60 * 60 -> DisappearDurationLabel.Hour24
        7 * 24 * 60 * 60 -> DisappearDurationLabel.Days7
        30 * 24 * 60 * 60 -> DisappearDurationLabel.Days30
        else -> DisappearDurationLabel.Off
    }

private fun genDisappearSeconds(rnd: Random): Int =
    when (rnd.nextInt(10)) {
        0 -> 0
        1 -> 30
        2 -> 60
        3 -> 2 * 60
        4 -> 5 * 60
        5 -> 15 * 60
        6 -> 60 * 60
        7 -> 24 * 60 * 60
        8 -> 7 * 24 * 60 * 60
        else -> rnd.nextInt(-1000, 30 * 24 * 60 * 60)
    }

private fun genDisappearSecondsEdge(rnd: Random): Int =
    when (rnd.nextInt(12)) {
        0 -> 1
        1 -> 29
        2 -> 31
        3 -> 59
        4 -> 61
        5 -> -1
        6 -> -5
        7 -> Int.MAX_VALUE
        8 -> Int.MIN_VALUE
        9 -> 30 * 24 * 60 * 60 + 1
        10 -> 7 * 24 * 60 * 60 - 1
        else -> genDisappearSeconds(rnd)
    }

    @Test
    fun disappearRandom() {
        val rnd = Random(42)
        repeat(300) {
            val seconds = genDisappearSeconds(rnd)
            assertEquals(
                disappearLabelOracle(seconds),
                DisappearingMessageLabel.disappearDurationLabel(seconds)
            )
        }
    }

    @Test
    fun disappearEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            // Off-by-one seconds, negatives, and the Int extremes:
            // only the fourteen exact values take a known branch,
            // everything else falls to Off through the else.
            val seconds = genDisappearSecondsEdge(rnd)
            assertEquals(
                disappearLabelOracle(seconds),
                DisappearingMessageLabel.disappearDurationLabel(seconds)
            )
        }
    }
    @Test
    fun pinnedEdgeCases() {
        // Each known duration maps to its own label.
        assertEquals(DisappearDurationLabel.Off, DisappearingMessageLabel.disappearDurationLabel(0))
        assertEquals(DisappearDurationLabel.Seconds30, DisappearingMessageLabel.disappearDurationLabel(30))
        assertEquals(DisappearDurationLabel.Minute1, DisappearingMessageLabel.disappearDurationLabel(60))
        assertEquals(DisappearDurationLabel.Minute2, DisappearingMessageLabel.disappearDurationLabel(2 * 60))
        assertEquals(DisappearDurationLabel.Minute5, DisappearingMessageLabel.disappearDurationLabel(5 * 60))
        assertEquals(DisappearDurationLabel.Minute15, DisappearingMessageLabel.disappearDurationLabel(15 * 60))
        assertEquals(DisappearDurationLabel.Hour1, DisappearingMessageLabel.disappearDurationLabel(60 * 60))
        assertEquals(DisappearDurationLabel.Hour2, DisappearingMessageLabel.disappearDurationLabel(2 * 60 * 60))
        assertEquals(DisappearDurationLabel.Hour4, DisappearingMessageLabel.disappearDurationLabel(4 * 60 * 60))
        assertEquals(DisappearDurationLabel.Hour8, DisappearingMessageLabel.disappearDurationLabel(8 * 60 * 60))
        assertEquals(DisappearDurationLabel.Hour12, DisappearingMessageLabel.disappearDurationLabel(12 * 60 * 60))
        assertEquals(DisappearDurationLabel.Hour24, DisappearingMessageLabel.disappearDurationLabel(24 * 60 * 60))
        assertEquals(DisappearDurationLabel.Days7, DisappearingMessageLabel.disappearDurationLabel(7 * 24 * 60 * 60))
        assertEquals(DisappearDurationLabel.Days30, DisappearingMessageLabel.disappearDurationLabel(30 * 24 * 60 * 60))
        // Unknown seconds fall to Off -- the same Off as 0, not a
        // distinct "unknown" label.
        assertEquals(DisappearDurationLabel.Off, DisappearingMessageLabel.disappearDurationLabel(1))
        assertEquals(DisappearDurationLabel.Off, DisappearingMessageLabel.disappearDurationLabel(45))
        assertEquals(DisappearDurationLabel.Off, DisappearingMessageLabel.disappearDurationLabel(90))
        assertEquals(DisappearDurationLabel.Off, DisappearingMessageLabel.disappearDurationLabel(-5))
        assertEquals(DisappearDurationLabel.Off, DisappearingMessageLabel.disappearDurationLabel(31 * 24 * 60 * 60))
        assertEquals(DisappearDurationLabel.Off, DisappearingMessageLabel.disappearDurationLabel(Int.MAX_VALUE))
        assertEquals(DisappearDurationLabel.Off, DisappearingMessageLabel.disappearDurationLabel(Int.MIN_VALUE))
        // Off-by-one on either side of a known value never matches.
        assertEquals(DisappearDurationLabel.Off, DisappearingMessageLabel.disappearDurationLabel(29))
        assertEquals(DisappearDurationLabel.Off, DisappearingMessageLabel.disappearDurationLabel(31))
        assertEquals(DisappearDurationLabel.Off, DisappearingMessageLabel.disappearDurationLabel(7 * 24 * 60 * 60 - 1))
        assertEquals(DisappearDurationLabel.Off, DisappearingMessageLabel.disappearDurationLabel(30 * 24 * 60 * 60 + 1))
        // All fourteen labels are reachable.
        assertEquals(14, DisappearDurationLabel.entries.size)
    }
}
