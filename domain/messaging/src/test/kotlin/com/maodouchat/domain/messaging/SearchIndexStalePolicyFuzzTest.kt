// Fuzz / random-oracle tests for SearchIndexStalePolicy (checklist line 931,
// 79th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. both counts zero -> false without calling the lambda; docCount > 0 and drift < max(msg*0.1 truncated, 50) -> the lambda decides; anything else -> true without calling it. Int arithmetic wraps.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class SearchIndexStalePolicyFuzzTest {

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


// ---------- extra helpers, block 79 ----------

private fun endsWithOracle(s: String, suffix: String): Boolean {
    if (suffix.length > s.length) return false
    for (i in suffix.indices) {
        if (s[s.length - suffix.length + i] != suffix[i]) return false
    }
    return true
}

// Literal (non-regex) replace-all, as String.replace(old, new).
private fun replaceLiteralOracle(s: String, old: String, new: String): String {
    if (old.isEmpty()) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i <= s.length - old.length) {
        var ok = true
        for (j in old.indices) {
            if (s[i + j] != old[j]) { ok = false; break }
        }
        if (ok) {
            sb.append(new)
            i += old.length
        } else {
            sb.append(s[i])
            i++
        }
    }
    while (i < s.length) {
        sb.append(s[i])
        i++
    }
    return sb.toString()
}

// Order-preserving dedupe, as List.distinct().
private fun distinctOracle(xs: List<String>): List<String> {
    val out = mutableListOf<String>()
    for (x in xs) {
        var seen = false
        for (y in out) {
            if (equalsOracle(x, y)) { seen = true; break }
        }
        if (!seen) out.add(x)
    }
    return out
}

// Float clamp with the exact coerceIn semantics: NaN passes through
// (both comparisons false), -0.0f stays -0.0f.
private fun clamp01Oracle(v: Float): Float =
    if (v < 0f) 0f else if (v > 1f) 1f else v

// SHA-256 hex, lowercase, two digits per byte (hand-rolled nibbles,
// never String.format).
private fun sha256HexOracle(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.update(bytes)
    val hash = digest.digest()
    val hex = "0123456789abcdef"
    val sb = StringBuilder(hash.size * 2)
    for (b in hash) {
        sb.append(hex[(b.toInt() shr 4) and 0xF])
        sb.append(hex[b.toInt() and 0xF])
    }
    return sb.toString()
}


// ---------- SearchIndexStalePolicy oracle ----------

private fun shouldRefreshIndexOracle(docCount: Int, msgCount: Int, mismatch: Boolean): Pair<Boolean, Boolean> {
    if (msgCount == 0 && docCount == 0) return Pair(false, false)
    var called = false
    if (docCount > 0) {
        val drift = kotlin.math.abs(msgCount - docCount)
        if (drift < (msgCount.toDouble() * 0.1).toInt().coerceAtLeast(50)) {
            called = true
            if (!mismatch) return Pair(false, true)
        }
    }
    return Pair(true, called)
}

private fun genSearchCount(rnd: Random): Int =
    when (rnd.nextInt(10)) {
        0 -> 0
        1 -> 1
        2 -> -1
        3 -> 49
        4 -> 50
        5 -> 51
        6 -> 99
        7 -> 100
        8 -> 101
        9 -> 1000
        else -> rnd.nextInt()
    }

private fun genSearchPair(rnd: Random): Pair<Int, Int> {
    // Often near a threshold boundary: msg fixed, doc = msg +/- drift.
    return when (rnd.nextInt(10)) {
        0, 1, 2, 3 -> {
            val msg = when (rnd.nextInt(6)) {
                0 -> 0
                1 -> 100
                2 -> 1000
                3 -> 10000
                4 -> rnd.nextInt(0, 200)
                else -> rnd.nextInt()
            }
            val threshold = maxOf((msg.toDouble() * 0.1).toInt(), 50)
            val drift = when (rnd.nextInt(6)) {
                0 -> threshold - 1
                1 -> threshold
                2 -> threshold + 1
                3 -> 0
                4 -> rnd.nextInt(0, threshold + 1)
                else -> rnd.nextInt(0, 200)
            }
            val doc = if (rnd.nextBoolean()) msg + drift else msg - drift
            doc to msg
        }
        else -> genSearchCount(rnd) to genSearchCount(rnd)
    }
}

    @Test
    fun searchRandom() {
        val rnd = Random(42)
        repeat(300) {
            val (doc, msg) = genSearchPair(rnd)
            val mismatch = rnd.nextBoolean()
            val (want, wantCalled) = shouldRefreshIndexOracle(doc, msg, mismatch)
            var calls = 0
            val got = runBlocking {
                SearchIndexStalePolicy.shouldRefreshIndex(doc, msg) { calls++; mismatch }
            }
            assertEquals(want, got)
            assertEquals(if (wantCalled) 1 else 0, calls)
        }
    }

    @Test
    fun searchCountRandom() {
        val rnd = Random(7)
        repeat(300) {
            val doc = genSearchCount(rnd)
            val msg = genSearchCount(rnd)
            val mismatch = rnd.nextBoolean()
            val (want, wantCalled) = shouldRefreshIndexOracle(doc, msg, mismatch)
            var calls = 0
            val got = runBlocking {
                SearchIndexStalePolicy.shouldRefreshIndex(doc, msg) { calls++; mismatch }
            }
            assertEquals(want, got)
            assertEquals(if (wantCalled) 1 else 0, calls)
        }
    }
    @Test
    fun pinnedEdgeCases() {
        // Both zero skips without calling the lambda.
        var calls = 0
        assertEquals(false, runBlocking { SearchIndexStalePolicy.shouldRefreshIndex(0, 0) { calls++; true } })
        assertEquals(0, calls)
        // Empty index with messages refreshes without calling it.
        assertEquals(true, runBlocking { SearchIndexStalePolicy.shouldRefreshIndex(0, 100) { error("must not be called") } })
        // Drift within threshold, no mismatch -> skip (lambda called once).
        calls = 0
        assertEquals(false, runBlocking { SearchIndexStalePolicy.shouldRefreshIndex(100, 140) { calls++; false } })
        assertEquals(1, calls)
        // Drift within threshold, mismatch -> refresh.
        assertEquals(true, runBlocking { SearchIndexStalePolicy.shouldRefreshIndex(100, 140) { true } })
        // Drift over threshold refreshes without calling the lambda.
        calls = 0
        assertEquals(true, runBlocking { SearchIndexStalePolicy.shouldRefreshIndex(100, 200) { calls++; false } })
        assertEquals(0, calls)
        // Drift exactly at the threshold refreshes (strict <).
        assertEquals(true, runBlocking { SearchIndexStalePolicy.shouldRefreshIndex(900, 1000) { false } })
        // Small library uses the 50 floor: msg=100 -> threshold 50.
        assertEquals(false, runBlocking { SearchIndexStalePolicy.shouldRefreshIndex(100, 140) { false } })
        assertEquals(true, runBlocking { SearchIndexStalePolicy.shouldRefreshIndex(100, 151) { false } })
        // Zero messages with documents refreshes.
        assertEquals(true, runBlocking { SearchIndexStalePolicy.shouldRefreshIndex(50, 0) { false } })
        // Negative docCount never enters the drift branch.
        assertEquals(true, runBlocking { SearchIndexStalePolicy.shouldRefreshIndex(-5, 100) { error("must not be called") } })
        // Constants, pinned literally.
        assertEquals(0.1, SearchIndexStalePolicy.STALE_DRIFT_RATIO)
        assertEquals(50, SearchIndexStalePolicy.MIN_STALE_DRIFT)
    }
}
