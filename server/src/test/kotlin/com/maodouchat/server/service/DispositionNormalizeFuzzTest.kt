package com.maodouchat.server.service

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ---------- oracle ----------

// Kotlin trim(): drops leading/trailing Char.isWhitespace() chars.
// Measured on kotlinc 2.4.0 (TrimProbe2, fuzz-188): 0x09-0x0D, 0x1C-0x20,
// 0x00A0, 0x1680, 0x2000-0x200A, 0x2028-0x2029, 0x202F, 0x205F, 0x3000.
// U+0085 and U+FEFF are NOT trimmed, NBSP IS.
private fun isTrimWs191(c: Char): Boolean =
    c in '\u0009'..'\u000D' || c in '\u001C'..'\u0020' || c == '\u00A0' ||
        c == '\u1680' || c in '\u2000'..'\u200A' || c == '\u2028' || c == '\u2029' ||
        c == '\u202F' || c == '\u205F' || c == '\u3000'

private fun ktTrim191(s: String): String {
    var i = 0
    var j = s.length
    while (i < j && isTrimWs191(s[i])) i++
    while (j > i && isTrimWs191(s[j - 1])) j--
    return s.substring(i, j)
}

private fun isBlank191(s: String): Boolean {
    for (c in s) {
        if (!isTrimWs191(c)) return false
    }
    return true
}

// Lowercase for the generated alphabet only (ASCII + pinned non-ASCII).
// Per-unit mappings verified against probe truth (fuzz-189 norm probe,
// 800 code points): İ -> i + U+0307, ẞ -> ß, Kelvin -> k, Angstrom -> å,
// Σ -> σ, then the final-sigma scan below may flip it to ς.
private fun lowerUnit191(c: Char): String = when (c) {
    in 'A'..'Z' -> (c + 32).toString()
    '\u00C9' -> "\u00E9"
    '\u00DC' -> "\u00FC"
    '\u0391' -> "\u03B1"
    '\u03A9' -> "\u03C9"
    '\u0130' -> "i\u0307"
    '\u1E9E' -> "\u00DF"
    '\u212A' -> "k"
    '\u212B' -> "\u00E5"
    '\u03A3' -> "\u03C3"
    else -> c.toString()
}

// Cased (Lu/Ll/Lt) over the generated alphabet; used by the sigma scan.
private fun isCased191(c: Char): Boolean =
    c in 'A'..'Z' || c in 'a'..'z' || c == '\u00C9' || c == '\u00E9' ||
        c == '\u00DC' || c == '\u00FC' || c == '\u00DF' || c == '\u0391' ||
        c == '\u03B1' || c == '\u03A9' || c == '\u03C9' || c == '\u03A3' ||
        c == '\u03C3' || c == '\u03C2' || c == '\u0130' || c == '\u1E9E' ||
        c == '\u212A' || c == '\u212B' || c == '\u00E5'

// Final-sigma scan (SigmaProbe3191/4191, kotlinc 2.4.0 / JDK 17): the
// JDK does NOT skip ignorables without limit. After a Σ it skips a run
// of digits (Nd: ASCII, Arabic-Indic, fullwidth) or a single '_'/'-';
// anything else stops the scan. Same backward. So "üΣ_ax" -> σ (the
// '_' is skipped, 'a' follows) but "üΣ__ax" -> ς, "Σ99a" -> σ,
// "Σ9_a" -> ς, "Σ a" -> ς. The 189 "skip all ignorables" model was
// wrong for chains; the drive's Python model implements the same rule.
private fun isDigit191(c: Char): Boolean =
    c in '0'..'9' || c in '\u0660'..'\u0669' || c in '\uFF10'..'\uFF19'

private fun isSingleSkip191(c: Char): Boolean = c == '_' || c == '-'

private fun sigmaBefore191(s: String, pos: Int): Boolean {
    var j = pos - 1
    if (j >= 0 && isDigit191(s[j])) {
        while (j >= 0 && isDigit191(s[j])) j--
    } else if (j >= 0 && isSingleSkip191(s[j])) {
        j--
    }
    return j >= 0 && isCased191(s[j])
}

private fun sigmaAfter191(s: String, pos: Int): Boolean {
    var j = pos + 1
    if (j < s.length && isDigit191(s[j])) {
        while (j < s.length && isDigit191(s[j])) j++
    } else if (j < s.length && isSingleSkip191(s[j])) {
        j++
    }
    return j < s.length && isCased191(s[j])
}

private fun lower191(s: String): String {
    val units = ArrayList<Int>()
    val src = ArrayList<Int>()
    for (i in s.indices) {
        val low = lowerUnit191(s[i])
        for (ch in low) {
            units.add(ch.code)
            src.add(i)
        }
    }
    for (pos in units.indices) {
        if (units[pos] != 0x03C3) continue
        if (s[src[pos]] != '\u03A3') continue
        if (sigmaBefore191(s, src[pos]) && !sigmaAfter191(s, src[pos])) units[pos] = 0x03C2
    }
    return String(units.map { it.toChar() }.toCharArray())
}

private fun normalizeReasonCodeOracle191(raw: String?): String? {
    val v = raw?.let { ktTrim191(lower191(it)).take(40) } ?: return null
    return v.takeIf { !isBlank191(it) }
}

private fun normalizeNoteOracle191(raw: String?): String? {
    val v = raw?.let { ktTrim191(it).take(500) } ?: return null
    return v.takeIf { !isBlank191(it) }
}

// Same set production builds from banReasonTemplates + unbanReasonCode.
private val KNOWN_CODES191 = setOf(
    "spam", "harassment", "scam", "illegal",
    "impersonation", "abuse_api", "other", "unban"
)

private fun isKnownReasonCodeOracle191(code: String?): Boolean {
    val normalized = normalizeReasonCodeOracle191(code) ?: return false
    return normalized in KNOWN_CODES191
}

private val CODE_POOL191 = listOf(
    "", " ", "spam", "SPAM", " Spam ", "harassment", "scam", "illegal",
    "impersonation", "abuse_api", "other", "OTHER", " other ", "unban",
    "UNBAN", " Unban ", "spam_chat", "unrestrict_messages", "unknown",
    "a".repeat(40), "a".repeat(41), "spam" + " ".repeat(50),
    "\u00A0spam\u00A0", "spam\n", "\tspam\t", "ΑΣ", "ΑΣ9Α", "A9Σ",
    "ΣA", "Α9Σ", "İ", "ẞ", "\u212A", "\u212B", "É", "spam\u0660",
    "spam\uFF19", "spam\u00A0chat"
)
private val NOTE_POOL191 = listOf(
    null, "", "   ", "note", "  note  ", "备注", "x".repeat(499),
    "x".repeat(500), "x".repeat(501), "\u00A0", "a b",
    "note\nwith newline", "İ note Σ", "😀 emoji", "a\uD800b"
)

private const val CODE_ALPHABET191 =
    "abcxyz_ -ABCXYZ019İẞΣσς\u212A\u212BÉéÜüßΑΩ\u0660\uFF19 \t\n\u00A0"
private const val NOTE_ALPHABET191 = "abcXYZ 019\n\t备注😀éİΣ\u00A0"

private fun randomCode191(r: Random): String? {
    if (r.nextInt(4) == 0) return CODE_POOL191.random(r)
    if (r.nextInt(12) == 0) return null
    val n = r.nextInt(0, 45)
    return buildString { repeat(n) { append(CODE_ALPHABET191.random(r)) } }
}

private fun randomNote191(r: Random): String? {
    if (r.nextInt(4) == 0) return NOTE_POOL191.random(r)
    val n = r.nextInt(0, 520)
    return buildString { repeat(n) { append(NOTE_ALPHABET191.random(r)) } }
}

// ---------- tests ----------

class DispositionNormalizeFuzzTest {

    @Test
    fun normalizeNoteDifferential191() {
        val r = Random(191005)
        repeat(300) {
            val note = randomNote191(r)
            assertEquals(normalizeNoteOracle191(note), DispositionService.normalizeNote(note))
        }
    }

    @Test
    fun isKnownReasonCodeDifferential191() {
        val r = Random(191006)
        repeat(300) {
            val code = randomCode191(r)
            assertEquals(isKnownReasonCodeOracle191(code), DispositionService.isKnownReasonCode(code))
            assertEquals(normalizeReasonCodeOracle191(code), DispositionService.normalizeReasonCode(code))
        }
    }

    @Test
    fun dispositionNormalizePinned191() {
        assertNull(DispositionService.normalizeNote(null))
        assertNull(DispositionService.normalizeNote(""))
        assertNull(DispositionService.normalizeNote("   "))
        assertNull(DispositionService.normalizeNote("\u00A0\u00A0"))
        assertEquals("x", DispositionService.normalizeNote("  x  "))
        assertEquals("x".repeat(500), DispositionService.normalizeNote("x".repeat(501)))
        assertEquals("x".repeat(500), DispositionService.normalizeNote("x".repeat(500)))
        assertEquals("a b", DispositionService.normalizeNote("a b"))
        assertEquals("note", DispositionService.normalizeNote("note\n"))
        assertTrue(DispositionService.isKnownReasonCode("spam"))
        assertTrue(DispositionService.isKnownReasonCode("SPAM"))
        assertTrue(DispositionService.isKnownReasonCode("  spam  "))
        assertTrue(DispositionService.isKnownReasonCode("unban"))
        assertTrue(DispositionService.isKnownReasonCode("UNBAN"))
        assertTrue(DispositionService.isKnownReasonCode("other"))
        assertTrue(DispositionService.isKnownReasonCode("abuse_api"))
        assertTrue(DispositionService.isKnownReasonCode("\u00A0spam\u00A0"))
        assertTrue(!DispositionService.isKnownReasonCode("spam_chat"))
        assertTrue(!DispositionService.isKnownReasonCode("unknown"))
        assertTrue(!DispositionService.isKnownReasonCode(null))
        assertTrue(!DispositionService.isKnownReasonCode(""))
        assertTrue(!DispositionService.isKnownReasonCode("  "))
        assertTrue(!DispositionService.isKnownReasonCode("a".repeat(41)))
        assertTrue(!DispositionService.isKnownReasonCode("spam\u00A0chat"))
        assertEquals("spam", DispositionService.normalizeReasonCode("SPAM"))
        assertNull(DispositionService.normalizeReasonCode("   "))
        assertEquals("a".repeat(40), DispositionService.normalizeReasonCode("a".repeat(41)))
    }
}
