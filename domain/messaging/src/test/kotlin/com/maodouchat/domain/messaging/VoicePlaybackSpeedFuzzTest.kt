// Fuzz / random-oracle tests for VoicePlaybackSpeed (checklist line 931,
// 78th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. nextSpeed walks SPEED_STEPS [1, 1.5, 2, 0.5] cyclically, unknown current (incl. NaN) restarts at index 0 -> 1.5f; formatSpeedLabel is a when on Float equality; waveHeightsFor is a 64-bit LCG seeded by messageId.hashCode() in float32 arithmetic.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class VoicePlaybackSpeedFuzzTest {

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


// ---------- VoicePlaybackSpeed / VoiceWaveform oracle ----------

private val VOICE_SPEED_STEPS_ORACLE = floatArrayOf(1f, 1.5f, 2f, 0.5f)

private val WAVE_HEIGHTS_ORACLE = listOf(0.3f, 0.7f, 1f, 0.5f, 0.9f, 0.4f, 0.6f, 0.2f)

private fun nextSpeedOracle(current: Float): Float {
    var idx = -1
    for (i in VOICE_SPEED_STEPS_ORACLE.indices) {
        if (VOICE_SPEED_STEPS_ORACLE[i] == current) {
            idx = i
            break
        }
    }
    if (idx < 0) idx = 0
    return VOICE_SPEED_STEPS_ORACLE[(idx + 1) % VOICE_SPEED_STEPS_ORACLE.size]
}

private fun formatSpeedLabelOracle(speed: Float): String = when (speed) {
    0.5f -> "0.5x"
    1.5f -> "1.5x"
    2f -> "2x"
    else -> "1x"
}

private fun waveHeightsOracle(messageId: String): List<Float> {
    var seed = hashCodeOracle(messageId).toLong()
    val out = mutableListOf<Float>()
    repeat(WAVE_HEIGHTS_ORACLE.size) {
        seed = seed * 6364136223846793005L + 1442695040888963407L
        out.add(0.25f + ((seed ushr 33) % 76) / 100f)
    }
    return out
}

private val VOICE_BIT_POOL = listOf(
    0x3F800000, // 1f
    0x3FC00000, // 1.5f
    0x40000000, // 2f
    0x3F000000, // 0.5f
    0x00000000, // +0f
    0x80000000.toInt(), // -0f
    0x7F800000, // +Inf
    0xFF800000.toInt(), // -Inf
    0x7FC00000, // NaN (quiet)
    0x7F800001, // NaN (signalling payload)
    0xFFC00000.toInt(), // -NaN
    0x00000001, // smallest denormal
    0x007FFFFF, // largest denormal
    0x00800000, // smallest normal
    0x7F7FFFFF, // largest finite
    0x3F800001, // 1f + 1 ulp
    0x3F7FFFFF, // 1f - 1 ulp
    0x3FC00001, // 1.5f + 1 ulp
    0x40000001, // 2f + 1 ulp
    0x3F000001, // 0.5f + 1 ulp
    0xBF800000.toInt(), // -1f
    0xC0000000.toInt(), // -2f
    0x42C80000, // 100f
    0x447A0000, // 1000f
    0x3DCCCCCD // 0.1f
)

private fun genSpeedBits(rnd: Random): Int =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> VOICE_BIT_POOL[rnd.nextInt(VOICE_BIT_POOL.size)]
        6 -> rnd.nextInt()
        7 -> rnd.nextInt(0x00800000, 0x7F800000)
        8 -> rnd.nextInt() and 0x7FFFFFFF
        else -> rnd.nextInt()
    }

private fun genSpeedFloat(rnd: Random): Float = Float.fromBits(genSpeedBits(rnd))

    @Test
    fun voiceRandom() {
        val rnd = Random(42)
        repeat(300) {
            val speed = genSpeedFloat(rnd)
            val id = when (rnd.nextInt(10)) {
                0, 1, 2, 3 -> randomToken(rnd)
                4, 5 -> randomWord(rnd)
                6 -> "msg-" + rnd.nextInt(1000)
                7 -> "a".repeat(rnd.nextInt(0, 32))
                else -> randomString(rnd, 24)
            }
            assertEquals(nextSpeedOracle(speed), VoicePlaybackSpeed.nextSpeed(speed))
            assertEquals(formatSpeedLabelOracle(speed), VoicePlaybackSpeed.formatSpeedLabel(speed))
            assertEquals(waveHeightsOracle(id), waveHeightsFor(id))
        }
    }

    @Test
    fun voiceBitsRandom() {
        val rnd = Random(7)
        repeat(300) {
            // Float-bit-heavy: NaN / infinities / denormals / ulp
            // neighbours of the four steps decide indexOfFirst.
            val speed = genSpeedFloat(rnd)
            assertEquals(nextSpeedOracle(speed), VoicePlaybackSpeed.nextSpeed(speed))
            assertEquals(formatSpeedLabelOracle(speed), VoicePlaybackSpeed.formatSpeedLabel(speed))
        }
    }
    @Test
    fun pinnedEdgeCases() {
        // Speed cycle: 1 -> 1.5 -> 2 -> 0.5 -> 1.
        assertEquals(1.5f, VoicePlaybackSpeed.nextSpeed(1f))
        assertEquals(2f, VoicePlaybackSpeed.nextSpeed(1.5f))
        assertEquals(0.5f, VoicePlaybackSpeed.nextSpeed(2f))
        assertEquals(1f, VoicePlaybackSpeed.nextSpeed(0.5f))
        // Unknown current restarts at index 0 -> 1.5f (NaN never
        // equals a step; -0f equals +0f but +0f is not a step either).
        assertEquals(1.5f, VoicePlaybackSpeed.nextSpeed(3f))
        assertEquals(1.5f, VoicePlaybackSpeed.nextSpeed(0f))
        assertEquals(1.5f, VoicePlaybackSpeed.nextSpeed(-0f))
        assertEquals(1.5f, VoicePlaybackSpeed.nextSpeed(Float.NaN))
        assertEquals(1.5f, VoicePlaybackSpeed.nextSpeed(Float.POSITIVE_INFINITY))
        assertEquals(1.5f, VoicePlaybackSpeed.nextSpeed(1f + 1.1920929E-7f))
        // Labels: only the three non-1 steps have their own label.
        assertEquals("1x", VoicePlaybackSpeed.formatSpeedLabel(1f))
        assertEquals("1.5x", VoicePlaybackSpeed.formatSpeedLabel(1.5f))
        assertEquals("2x", VoicePlaybackSpeed.formatSpeedLabel(2f))
        assertEquals("0.5x", VoicePlaybackSpeed.formatSpeedLabel(0.5f))
        assertEquals("1x", VoicePlaybackSpeed.formatSpeedLabel(0f))
        assertEquals("1x", VoicePlaybackSpeed.formatSpeedLabel(Float.NaN))
        assertEquals("1x", VoicePlaybackSpeed.formatSpeedLabel(3f))
        // Steps pinned literally.
        assertEquals(
            listOf(1f, 1.5f, 2f, 0.5f),
            VoicePlaybackSpeed.SPEED_STEPS.toList()
        )
        assertEquals(
            listOf(0.3f, 0.7f, 1f, 0.5f, 0.9f, 0.4f, 0.6f, 0.2f),
            WaveHeights
        )
        // Wave heights: 8 values, every one in [0.25, 1.0].
        val heights = waveHeightsFor("msg-1")
        assertEquals(8, heights.size)
        for (h in heights) {
            assertEquals(true, h >= 0.25f && h <= 1.0f)
        }
        // Same id -> same wave; different id -> (almost surely) a
        // different wave; empty id is deterministic too.
        assertEquals(waveHeightsFor("msg-1"), waveHeightsFor("msg-1"))
        assertEquals(8, waveHeightsFor("").size)
        // Pinned bit patterns for the empty id (probe-verified).
        assertEquals(
            listOf(
                Float.fromBits(0x3ECCCCCD),
                Float.fromBits(0x3EBD70A4),
                Float.fromBits(0x3E851EB8),
                Float.fromBits(0x3E800000),
                Float.fromBits(0x3E99999A),
                Float.fromBits(0x3F6147AE),
                Float.fromBits(0x3F63D70A),
                Float.fromBits(0x3F6B851F)
            ),
            waveHeightsFor("")
        )
    }
}
