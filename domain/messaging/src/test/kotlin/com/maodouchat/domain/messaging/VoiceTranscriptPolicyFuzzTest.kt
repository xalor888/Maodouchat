// Fuzz / random-oracle tests for VoiceTranscriptPolicy (checklist line 931,
// 69th block). Test-only, zero production changes. The oracle is
// hand-written: trim / trimEnd over the probed 28-unit Kotlin
// whitespace set, ASCII-whitespace-run collapse, per-code-point
// lowercase, hand-written take (UTF-16 units), explicit membership
// loops. normalize trims + takes 6000; hasTranscript is normalize non-empty; shouldShowInlineEntry and canRequest share the same guard table; needsExpandToggle is normalize length > 360; displayText takes 360, trims the end, appends an ellipsis (expanded or <= 360 returns the full text).
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceTranscriptPolicyFuzzTest {

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
    0x1E00 to 0x1E9A, 0x1E9C to 0x1EFF,
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

// Hand-written trimEnd: strip trailing UTF-16 units in the probed
// 28-unit Kotlin whitespace set. Never trimEnd().
private fun trimEndOracle(s: String): String {
    var hi = s.length
    while (hi > 0 && s[hi - 1].code in BLANK_UNITS_ORACLE) hi--
    return s.substring(0, hi)
}

// ASCII whitespace for java.util.regex \s (what Kotlin Regex("\\s+")
// matches on the JVM): tab, LF, VT, FF, CR, space. NOT Unicode
// whitespace: NBSP / U+2007 / U+2028 / U+202F / U+0085 never match.
private val ASCII_WS_ORACLE = setOf(0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20)

// Hand-written \s+ -> " " collapse: each maximal run of ASCII
// whitespace becomes exactly one space. Never Regex.replace.
private fun collapseWhitespaceOracle(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c.code in ASCII_WS_ORACLE) {
            sb.append(' ')
            while (i < s.length && s[i].code in ASCII_WS_ORACLE) i++
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

// ---------- VoiceTranscriptPolicy oracle ----------

private fun voiceNormalizeOracle(raw: String?): String =
    takeOracle(trimOracle(raw ?: ""), 6000)

private fun voiceHasTranscriptOracle(raw: String?): Boolean =
    voiceNormalizeOracle(raw).length > 0

private fun voiceShouldShowInlineEntryOracle(
    isVoiceMessage: Boolean,
    transcript: String?,
    isTranscribing: Boolean
): Boolean {
    if (!isVoiceMessage) return false
    if (isTranscribing) return false
    return !voiceHasTranscriptOracle(transcript)
}

private fun voiceCanRequestOracle(
    isVoiceMessage: Boolean,
    transcript: String?,
    isTranscribing: Boolean
): Boolean {
    if (!isVoiceMessage) return false
    if (isTranscribing) return false
    return !voiceHasTranscriptOracle(transcript)
}

private fun voiceNeedsExpandToggleOracle(transcript: String?): Boolean =
    voiceNormalizeOracle(transcript).length > 360

private fun voiceDisplayTextOracle(transcript: String?, expanded: Boolean): String {
    val full = voiceNormalizeOracle(transcript)
    if (full.length == 0) return ""
    if (expanded || full.length <= 360) return full
    return trimEndOracle(takeOracle(full, 360)) + "…"
}

private fun genVoiceRaw(rnd: Random): String? {
    return when (rnd.nextInt(10)) {
        0 -> null
        1 -> randomWord(rnd)
        2 -> "  " + randomWord(rnd) + "  "
        3 -> randomString(rnd, 120)
        4 -> "a".repeat(rnd.nextInt(5900, 6101))
        5 -> "😀".repeat(rnd.nextInt(2990, 3011))
        6 -> randomString(rnd, 7000)
        7 -> "a".repeat(rnd.nextInt(355, 366))
        8 -> "a".repeat(350) + " ".repeat(rnd.nextInt(0, 21)) + "b"
        else -> randomString(rnd, 400)
    }
}

private fun genVoiceGates(rnd: Random): Triple<Boolean, String?, Boolean> {
    val transcript = when (rnd.nextInt(5)) {
        0 -> null
        1 -> ""
        2 -> "   "
        3 -> randomWord(rnd)
        else -> randomString(rnd, 20)
    }
    return Triple(rnd.nextBoolean(), transcript, rnd.nextBoolean())
}

private fun genVoiceDisplay(rnd: Random): Pair<String?, Boolean> {
    val transcript = when (rnd.nextInt(6)) {
        0 -> null
        1 -> ""
        2 -> "a".repeat(rnd.nextInt(355, 366))
        3 -> "a".repeat(350) + " ".repeat(rnd.nextInt(0, 21)) + "b"
        4 -> "😀".repeat(rnd.nextInt(175, 186))
        else -> genVoiceRaw(rnd)
    }
    return transcript to rnd.nextBoolean()
}

    @Test
    fun normalizeRandom() {
        val rnd = Random(42)
        repeat(300) {
            val raw = genVoiceRaw(rnd)
            assertEquals(voiceNormalizeOracle(raw), VoiceTranscriptPolicy.normalize(raw))
            assertEquals(voiceHasTranscriptOracle(raw), VoiceTranscriptPolicy.hasTranscript(raw))
        }
    }

    @Test
    fun gatesRandom() {
        val rnd = Random(42)
        repeat(300) {
            val (isVoice, transcript, isTranscribing) = genVoiceGates(rnd)
            assertEquals(
                voiceShouldShowInlineEntryOracle(isVoice, transcript, isTranscribing),
                VoiceTranscriptPolicy.shouldShowInlineEntry(isVoice, transcript, isTranscribing)
            )
            assertEquals(
                voiceCanRequestOracle(isVoice, transcript, isTranscribing),
                VoiceTranscriptPolicy.canRequest(isVoice, transcript, isTranscribing)
            )
            assertEquals(
                voiceNeedsExpandToggleOracle(transcript),
                VoiceTranscriptPolicy.needsExpandToggle(transcript)
            )
        }
    }

    @Test
    fun displayTextRandom() {
        val rnd = Random(42)
        repeat(300) {
            val (transcript, expanded) = genVoiceDisplay(rnd)
            assertEquals(
                voiceDisplayTextOracle(transcript, expanded),
                VoiceTranscriptPolicy.displayText(transcript, expanded)
            )
        }
    }
    @Test
    fun pinnedEdgeCases() {
        // normalize pinned
        assertEquals("", VoiceTranscriptPolicy.normalize(null))
        assertEquals("", VoiceTranscriptPolicy.normalize(""))
        assertEquals("hi", VoiceTranscriptPolicy.normalize("  hi  "))
        assertEquals(6000, VoiceTranscriptPolicy.normalize("a".repeat(6001)).length)
        assertEquals("a".repeat(6000), VoiceTranscriptPolicy.normalize("a".repeat(6001)))
        // 3001 emoji = 6002 UTF-16 units, truncated to 3000 emoji.
        assertEquals("😀".repeat(3000), VoiceTranscriptPolicy.normalize("😀".repeat(3001)))
        assertEquals("cat", VoiceTranscriptPolicy.normalize("\u00A0 cat \u2007"))
        assertEquals("\u0085cat\u0085", VoiceTranscriptPolicy.normalize("\u0085cat\u0085"))
        assertEquals("\u200Bcat\u200B", VoiceTranscriptPolicy.normalize("\u200Bcat\u200B"))
        assertEquals("a  b", VoiceTranscriptPolicy.normalize("  a  b  "))

        // hasTranscript pinned
        assertFalse(VoiceTranscriptPolicy.hasTranscript(null))
        assertFalse(VoiceTranscriptPolicy.hasTranscript(""))
        assertFalse(VoiceTranscriptPolicy.hasTranscript("   "))
        assertTrue(VoiceTranscriptPolicy.hasTranscript("x"))
        assertTrue(VoiceTranscriptPolicy.hasTranscript("  x  "))

        // shouldShowInlineEntry / canRequest share the guard table.
        assertFalse(VoiceTranscriptPolicy.shouldShowInlineEntry(false, null, false))
        assertFalse(VoiceTranscriptPolicy.canRequest(false, null, false))
        assertTrue(VoiceTranscriptPolicy.shouldShowInlineEntry(true, null, false))
        assertTrue(VoiceTranscriptPolicy.canRequest(true, null, false))
        assertFalse(VoiceTranscriptPolicy.shouldShowInlineEntry(true, "x", false))
        assertFalse(VoiceTranscriptPolicy.canRequest(true, "x", false))
        assertFalse(VoiceTranscriptPolicy.shouldShowInlineEntry(true, "   ", true))
        assertFalse(VoiceTranscriptPolicy.canRequest(true, null, true))
        assertTrue(VoiceTranscriptPolicy.shouldShowInlineEntry(true, "  ", false))

        // needsExpandToggle pinned (boundary at 360).
        assertFalse(VoiceTranscriptPolicy.needsExpandToggle(null))
        assertFalse(VoiceTranscriptPolicy.needsExpandToggle("a".repeat(360)))
        assertTrue(VoiceTranscriptPolicy.needsExpandToggle("a".repeat(361)))
        assertFalse(VoiceTranscriptPolicy.needsExpandToggle("a".repeat(359) + " "))
        assertTrue(VoiceTranscriptPolicy.needsExpandToggle("😀".repeat(181)))

        // displayText pinned
        assertEquals("", VoiceTranscriptPolicy.displayText(null, false))
        assertEquals("", VoiceTranscriptPolicy.displayText("", true))
        assertEquals("a".repeat(360), VoiceTranscriptPolicy.displayText("a".repeat(360), false))
        assertEquals("a".repeat(360) + "…", VoiceTranscriptPolicy.displayText("a".repeat(361), false))
        assertEquals("a".repeat(361), VoiceTranscriptPolicy.displayText("a".repeat(361), true))
        // Spaces straddling the cut are trimmed before the ellipsis.
        assertEquals("a".repeat(350) + "…", VoiceTranscriptPolicy.displayText("a".repeat(350) + " ".repeat(20) + "b", false))
        // The cut lands inside the space run: trimEnd eats the spaces.
        assertEquals("a".repeat(350) + "…", VoiceTranscriptPolicy.displayText("a".repeat(350) + " ".repeat(10) + "b", false))
        // Exactly 360 chars returns the full text, interior spaces kept.
        assertEquals("a".repeat(350) + " ".repeat(9) + "b", VoiceTranscriptPolicy.displayText("a".repeat(350) + " ".repeat(9) + "b", false))
        // Interior whitespace survives; trailing whitespace is trimmed by normalize first.
        assertEquals("a  b", VoiceTranscriptPolicy.displayText("  a  b  ", false))
        assertEquals("😀".repeat(180) + "…", VoiceTranscriptPolicy.displayText("😀".repeat(181), false))
        assertEquals(361, VoiceTranscriptPolicy.displayText("a".repeat(500), false).length)
    }
}
