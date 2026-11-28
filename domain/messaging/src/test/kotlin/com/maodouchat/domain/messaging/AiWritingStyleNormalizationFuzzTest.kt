// Fuzz / random-oracle tests for AiWritingStyleNormalization (checklist line 931,
// 69th block). Test-only, zero production changes. The oracle is
// hand-written: trim / trimEnd over the probed 28-unit Kotlin
// whitespace set, ASCII-whitespace-run collapse, per-code-point
// lowercase, hand-written take (UTF-16 units), explicit membership
// loops. normalizeCustomNote trims, folds ASCII whitespace runs to one space, then takes 320; Preset.fromId trims, takes 40, lowercases, then matches 11 ids (unknown falls back to NONE); the disabled branch returns Snapshot(false, NONE, "") no matter the inputs.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import com.maodouchat.core.model.Preset

class AiWritingStyleNormalizationFuzzTest {

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

// ---------- AiWritingStyleNormalization oracle ----------

private val PRESET_IDS_ORACLE = arrayOf(
    "none", "concise", "formal", "warm", "professional", "casual",
    "witty", "empathetic", "direct", "enthusiastic", "diplomatic"
)

private fun stylePresetFromIdOracle(raw: String?): String {
    val id = lowercaseOracle(takeOracle(trimOracle(raw ?: ""), 40))
    for (candidate in PRESET_IDS_ORACLE) {
        if (equalsOracle(candidate, id)) return candidate
    }
    return "none"
}

private fun styleNormalizeCustomNoteOracle(raw: String?): String =
    takeOracle(collapseWhitespaceOracle(trimOracle(raw ?: "")), 320)

private fun styleNormalizeOracle(
    enabled: Boolean,
    presetId: String?,
    customNote: String?
): Snapshot {
    val preset = stylePresetFromIdOracle(presetId)
    val note = styleNormalizeCustomNoteOracle(customNote)
    if (!enabled) return Snapshot(enabled = false, preset = Preset.NONE, customNote = "")
    // The enabled branch stores the preset id resolved above; map it
    // back to the enum by the same membership loop.
    var resolved = Preset.NONE
    for (p in Preset.entries) {
        if (equalsOracle(p.id, preset)) { resolved = p; break }
    }
    return Snapshot(enabled = true, preset = resolved, customNote = note)
}

private fun styleClearOracle(): Snapshot =
    Snapshot(enabled = false, preset = Preset.NONE, customNote = "")

private fun genStyleNote(rnd: Random): String? {
    return when (rnd.nextInt(10)) {
        0 -> null
        1 -> ""
        2 -> "   "
        3 -> randomWord(rnd)
        4 -> "  " + randomWord(rnd) + "   " + randomWord(rnd) + "  "
        5 -> randomString(rnd, 60)
        6 -> (0 until rnd.nextInt(1, 8)).joinToString(
            when (rnd.nextInt(6)) {
                0 -> " "
                1 -> "   "
                2 -> "\t"
                3 -> "\n"
                4 -> "\r\n"
                else -> " \t "
            }
        ) { randomWord(rnd) }
        7 -> "a".repeat(rnd.nextInt(310, 331))
        8 -> "word ".repeat(rnd.nextInt(60, 71))
        else -> randomString(rnd, 400)
    }
}

private fun genStylePresetId(rnd: Random): String? {
    val valid = arrayOf(
        "none", "concise", "formal", "warm", "professional", "casual",
        "witty", "empathetic", "direct", "enthusiastic", "diplomatic"
    )
    return when (rnd.nextInt(10)) {
        0 -> null
        1 -> ""
        2 -> "   "
        3 -> valid[rnd.nextInt(valid.size)]
        4 -> valid[rnd.nextInt(valid.size)].uppercase()
        5 -> "  " + valid[rnd.nextInt(valid.size)] + "  "
        6 -> randomWord(rnd)
        7 -> valid[rnd.nextInt(valid.size)] + "x".repeat(rnd.nextInt(30, 45))
        8 -> "concise".substring(0, rnd.nextInt(1, 8))
        else -> randomString(rnd, 50)
    }
}

private fun genStyleNormalize(rnd: Random): Triple<Boolean, String?, String?> =
    Triple(rnd.nextBoolean(), genStylePresetId(rnd), genStyleNote(rnd))

    @Test
    fun customNoteRandom() {
        val rnd = Random(42)
        repeat(300) {
            val raw = genStyleNote(rnd)
            assertEquals(
                styleNormalizeCustomNoteOracle(raw),
                AiWritingStyleNormalization.normalizeCustomNote(raw)
            )
        }
    }

    @Test
    fun normalizeRandom() {
        val rnd = Random(42)
        repeat(300) {
            val (enabled, presetId, customNote) = genStyleNormalize(rnd)
            assertEquals(
                styleNormalizeOracle(enabled, presetId, customNote),
                AiWritingStyleNormalization.normalize(enabled, presetId, customNote)
            )
        }
    }
    @Test
    fun pinnedEdgeCases() {
        // normalizeCustomNote pinned: trim, ASCII-run fold, take 320.
        assertEquals("", AiWritingStyleNormalization.normalizeCustomNote(null))
        assertEquals("", AiWritingStyleNormalization.normalizeCustomNote(""))
        assertEquals("", AiWritingStyleNormalization.normalizeCustomNote("   "))
        assertEquals("a b c d", AiWritingStyleNormalization.normalizeCustomNote("  a   b\tc\n\nd  "))
        assertEquals("a b", AiWritingStyleNormalization.normalizeCustomNote("a\r\nb"))
        assertEquals("a b", AiWritingStyleNormalization.normalizeCustomNote("a\u000Bb"))
        assertEquals("a b", AiWritingStyleNormalization.normalizeCustomNote("a\u000Cb"))
        // NBSP / U+2007 / U+2028 are NOT \s: they survive the fold.
        assertEquals("a\u00A0\u00A0b", AiWritingStyleNormalization.normalizeCustomNote("a\u00A0\u00A0b"))
        assertEquals("a\u2007b", AiWritingStyleNormalization.normalizeCustomNote("a\u2007b"))
        assertEquals("a\u2028b", AiWritingStyleNormalization.normalizeCustomNote("a\u2028b"))
        assertEquals("a\u200Bb", AiWritingStyleNormalization.normalizeCustomNote("a\u200Bb"))
        // Fold happens before take: "word " * 70 folds to 350 chars, then takes 320.
        assertEquals(320, AiWritingStyleNormalization.normalizeCustomNote("word ".repeat(70)).length)
        assertEquals("word ".repeat(64), AiWritingStyleNormalization.normalizeCustomNote("word ".repeat(70)))
        assertEquals("a".repeat(320), AiWritingStyleNormalization.normalizeCustomNote("a".repeat(321)))
        // Trailing ASCII whitespace is trimmed before the fold.
        assertEquals("a b", AiWritingStyleNormalization.normalizeCustomNote("  a   b  \t\n "))

        // normalize pinned (Preset.fromId: trim + take 40 + lowercase).
        assertEquals(Snapshot(false, Preset.NONE, ""), AiWritingStyleNormalization.normalize(false, "concise", " note "))
        assertEquals(Snapshot(true, Preset.WARM, "hi there"), AiWritingStyleNormalization.normalize(true, "warm", "  hi   there "))
        assertEquals(Snapshot(true, Preset.CONCISE, ""), AiWritingStyleNormalization.normalize(true, "  CONCISE ", null))
        assertEquals(Snapshot(true, Preset.FORMAL, ""), AiWritingStyleNormalization.normalize(true, "formal", ""))
        assertEquals(Snapshot(true, Preset.NONE, ""), AiWritingStyleNormalization.normalize(true, "unknown", ""))
        assertEquals(Snapshot(true, Preset.NONE, ""), AiWritingStyleNormalization.normalize(true, null, ""))
        assertEquals(Snapshot(true, Preset.NONE, ""), AiWritingStyleNormalization.normalize(true, "", ""))
        // 41 chars: take(40) keeps "concise" + 33 x's, no match -> NONE.
        assertEquals(Snapshot(true, Preset.NONE, ""), AiWritingStyleNormalization.normalize(true, "concise" + "x".repeat(34), ""))
        // Every valid preset id round-trips (upper + padded).
        assertEquals(Snapshot(true, Preset.PROFESSIONAL, ""), AiWritingStyleNormalization.normalize(true, "  PROFESSIONAL ", ""))
        assertEquals(Snapshot(true, Preset.DIPLOMATIC, ""), AiWritingStyleNormalization.normalize(true, "Diplomatic", ""))
        assertEquals(Snapshot(true, Preset.ENTHUSIASTIC, ""), AiWritingStyleNormalization.normalize(true, "enthusiastic", ""))
        // "concise" is a Preset id, but the note is folded independently.
        assertEquals(Snapshot(true, Preset.CASUAL, "a b"), AiWritingStyleNormalization.normalize(true, "casual", "a   b"))

        // clear pinned
        assertEquals(Snapshot(false, Preset.NONE, ""), AiWritingStyleNormalization.clear())
        assertEquals(styleClearOracle(), AiWritingStyleNormalization.clear())
    }
}
