// Fuzz / random-oracle tests for AiStatusStripPolicy (checklist line 931,
// 76th block). Test-only, zero production changes. The oracle is
// hand-written: plain string building with the exact templates
// read from the production file. aiStatusStripVisible is (working && !draftStreaming) || suggestions non-empty || errorCode != null; aiStatusStripMode is a strict STREAMING > WORKING > SUGGESTIONS > ERROR priority chain.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class AiStatusStripPolicyFuzzTest {

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

// ---------- Kotlin trim() oracle (28-unit whitespace set) ----------

private fun trimKotlinOracle(s: String): String {
    var lo = 0
    var hi = s.length
    while (lo < hi && s[lo].code in BLANK_UNITS_ORACLE) lo++
    while (hi > lo && s[hi - 1].code in BLANK_UNITS_ORACLE) hi--
    return s.substring(lo, hi)
}

// ---------- AiStatusStripPolicy oracle ----------

private fun statusVisibleOracle(
    isAiWorking: Boolean,
    isAiDraftStreaming: Boolean,
    aiSuggestions: List<String>,
    aiReplyStreamErrorCode: String?,
): Boolean = (isAiWorking && !isAiDraftStreaming) ||
    aiSuggestions.isNotEmpty() ||
    aiReplyStreamErrorCode != null

private fun statusModeOracle(
    isAiReplyStreaming: Boolean,
    isAiWorking: Boolean,
    aiSuggestions: List<String>,
): String = when {
    isAiReplyStreaming -> "STREAMING"
    isAiWorking -> "WORKING"
    aiSuggestions.isNotEmpty() -> "SUGGESTIONS"
    else -> "ERROR"
}

private fun genStatusSuggestions(rnd: Random): List<String> =
    when (rnd.nextInt(4)) {
        0 -> emptyList()
        1 -> listOf("try this")
        2 -> listOf("a", "b")
        else -> List(rnd.nextInt(0, 4)) { randomWord(rnd) }
    }

private fun genStatusErrorCode(rnd: Random): String? =
    when (rnd.nextInt(4)) {
        0 -> null
        1 -> "ERR_X"
        2 -> ""
        else -> randomToken(rnd)
    }

    @Test
    fun statusRandom() {
        val rnd = Random(42)
        repeat(300) {
            val working = rnd.nextBoolean()
            val draftStreaming = rnd.nextBoolean()
            val replyStreaming = rnd.nextBoolean()
            val suggestions = genStatusSuggestions(rnd)
            val errorCode = genStatusErrorCode(rnd)
            assertEquals(
                statusVisibleOracle(working, draftStreaming, suggestions, errorCode),
                aiStatusStripVisible(working, draftStreaming, suggestions, errorCode)
            )
            assertEquals(
                statusModeOracle(replyStreaming, working, suggestions),
                aiStatusStripMode(replyStreaming, working, suggestions).name
            )
        }
    }

    @Test
    fun statusGrid() {
        // Exhaustive: every flag combination x empty/non-empty
        // suggestions x null/empty/non-empty error code.
        for (working in listOf(false, true)) {
            for (draftStreaming in listOf(false, true)) {
                for (replyStreaming in listOf(false, true)) {
                    for (suggestions in listOf(emptyList<String>(), listOf("s"))) {
                        for (errorCode in listOf(null, "", "ERR_X")) {
                            assertEquals(
                                statusVisibleOracle(working, draftStreaming, suggestions, errorCode),
                                aiStatusStripVisible(working, draftStreaming, suggestions, errorCode)
                            )
                            assertEquals(
                                statusModeOracle(replyStreaming, working, suggestions),
                                aiStatusStripMode(replyStreaming, working, suggestions).name
                            )
                        }
                    }
                }
            }
        }
    }
    @Test
    fun pinnedEdgeCases() {
        // Working alone shows the strip; draft streaming hides it.
        assertEquals(
            true,
            aiStatusStripVisible(
                isAiWorking = true, isAiDraftStreaming = false,
                aiSuggestions = emptyList(), aiReplyStreamErrorCode = null
            )
        )
        assertEquals(
            false,
            aiStatusStripVisible(
                isAiWorking = true, isAiDraftStreaming = true,
                aiSuggestions = emptyList(), aiReplyStreamErrorCode = null
            )
        )
        // Suggestions or an error code show it even when idle; an
        // EMPTY error string still counts (only null hides it).
        assertEquals(
            true,
            aiStatusStripVisible(
                isAiWorking = false, isAiDraftStreaming = false,
                aiSuggestions = listOf("try this"), aiReplyStreamErrorCode = null
            )
        )
        assertEquals(
            true,
            aiStatusStripVisible(
                isAiWorking = false, isAiDraftStreaming = false,
                aiSuggestions = emptyList(), aiReplyStreamErrorCode = ""
            )
        )
        assertEquals(
            false,
            aiStatusStripVisible(
                isAiWorking = false, isAiDraftStreaming = false,
                aiSuggestions = emptyList(), aiReplyStreamErrorCode = null
            )
        )
        // Mode priority: streaming beats working beats suggestions.
        assertEquals(
            AiStatusStripMode.STREAMING,
            aiStatusStripMode(
                isAiReplyStreaming = true, isAiWorking = true,
                aiSuggestions = listOf("s")
            )
        )
        assertEquals(
            AiStatusStripMode.WORKING,
            aiStatusStripMode(
                isAiReplyStreaming = false, isAiWorking = true,
                aiSuggestions = listOf("s")
            )
        )
        assertEquals(
            AiStatusStripMode.SUGGESTIONS,
            aiStatusStripMode(
                isAiReplyStreaming = false, isAiWorking = false,
                aiSuggestions = listOf("s")
            )
        )
        assertEquals(
            AiStatusStripMode.ERROR,
            aiStatusStripMode(
                isAiReplyStreaming = false, isAiWorking = false,
                aiSuggestions = emptyList()
            )
        )
    }
}
