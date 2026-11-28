package com.maodouchat.domain.contacts

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Fuzz / random-oracle tests for [AccountDeviceFingerprintPolicy] (checklist
 * line 931, 59th block). Test-only, zero production changes.
 *
 * The oracle re-implements the policy by hand: UTF-16 take/takeLast, space
 * stripping, then Locale.US uppercasing via a 102-entry full-mapping table
 * (probed over every code point on JDK 17) plus the single-code-point
 * mapping as fallback. Random inputs use Random(42), fresh per @Test.
 */
class AccountDeviceFingerprintPolicyFuzzTest {

// ---------- C1 AccountDeviceFingerprintPolicy oracle ----------

// Full uppercase mappings: code points where String.toUpperCase(Locale.US)
// differs from the single-code-point Character.toUpperCase (Probe59e
// full-code-point scan, JDK 17). Everything else falls back to the
// simple mapping, which is exactly the full mapping there.
private fun fullUpper(cp: Int): String? = when (cp) {
        0x00DF -> "\u0053\u0053"
        0x0149 -> "\u02bc\u004e"
        0x01F0 -> "\u004a\u030c"
        0x0390 -> "\u0399\u0308\u0301"
        0x03B0 -> "\u03a5\u0308\u0301"
        0x0587 -> "\u0535\u0552"
        0x1E96 -> "\u0048\u0331"
        0x1E97 -> "\u0054\u0308"
        0x1E98 -> "\u0057\u030a"
        0x1E99 -> "\u0059\u030a"
        0x1E9A -> "\u0041\u02be"
        0x1F50 -> "\u03a5\u0313"
        0x1F52 -> "\u03a5\u0313\u0300"
        0x1F54 -> "\u03a5\u0313\u0301"
        0x1F56 -> "\u03a5\u0313\u0342"
        0x1F80 -> "\u1f08\u0399"
        0x1F81 -> "\u1f09\u0399"
        0x1F82 -> "\u1f0a\u0399"
        0x1F83 -> "\u1f0b\u0399"
        0x1F84 -> "\u1f0c\u0399"
        0x1F85 -> "\u1f0d\u0399"
        0x1F86 -> "\u1f0e\u0399"
        0x1F87 -> "\u1f0f\u0399"
        0x1F88 -> "\u1f08\u0399"
        0x1F89 -> "\u1f09\u0399"
        0x1F8A -> "\u1f0a\u0399"
        0x1F8B -> "\u1f0b\u0399"
        0x1F8C -> "\u1f0c\u0399"
        0x1F8D -> "\u1f0d\u0399"
        0x1F8E -> "\u1f0e\u0399"
        0x1F8F -> "\u1f0f\u0399"
        0x1F90 -> "\u1f28\u0399"
        0x1F91 -> "\u1f29\u0399"
        0x1F92 -> "\u1f2a\u0399"
        0x1F93 -> "\u1f2b\u0399"
        0x1F94 -> "\u1f2c\u0399"
        0x1F95 -> "\u1f2d\u0399"
        0x1F96 -> "\u1f2e\u0399"
        0x1F97 -> "\u1f2f\u0399"
        0x1F98 -> "\u1f28\u0399"
        0x1F99 -> "\u1f29\u0399"
        0x1F9A -> "\u1f2a\u0399"
        0x1F9B -> "\u1f2b\u0399"
        0x1F9C -> "\u1f2c\u0399"
        0x1F9D -> "\u1f2d\u0399"
        0x1F9E -> "\u1f2e\u0399"
        0x1F9F -> "\u1f2f\u0399"
        0x1FA0 -> "\u1f68\u0399"
        0x1FA1 -> "\u1f69\u0399"
        0x1FA2 -> "\u1f6a\u0399"
        0x1FA3 -> "\u1f6b\u0399"
        0x1FA4 -> "\u1f6c\u0399"
        0x1FA5 -> "\u1f6d\u0399"
        0x1FA6 -> "\u1f6e\u0399"
        0x1FA7 -> "\u1f6f\u0399"
        0x1FA8 -> "\u1f68\u0399"
        0x1FA9 -> "\u1f69\u0399"
        0x1FAA -> "\u1f6a\u0399"
        0x1FAB -> "\u1f6b\u0399"
        0x1FAC -> "\u1f6c\u0399"
        0x1FAD -> "\u1f6d\u0399"
        0x1FAE -> "\u1f6e\u0399"
        0x1FAF -> "\u1f6f\u0399"
        0x1FB2 -> "\u1fba\u0399"
        0x1FB3 -> "\u0391\u0399"
        0x1FB4 -> "\u0386\u0399"
        0x1FB6 -> "\u0391\u0342"
        0x1FB7 -> "\u0391\u0342\u0399"
        0x1FBC -> "\u0391\u0399"
        0x1FC2 -> "\u1fca\u0399"
        0x1FC3 -> "\u0397\u0399"
        0x1FC4 -> "\u0389\u0399"
        0x1FC6 -> "\u0397\u0342"
        0x1FC7 -> "\u0397\u0342\u0399"
        0x1FCC -> "\u0397\u0399"
        0x1FD2 -> "\u0399\u0308\u0300"
        0x1FD3 -> "\u0399\u0308\u0301"
        0x1FD6 -> "\u0399\u0342"
        0x1FD7 -> "\u0399\u0308\u0342"
        0x1FE2 -> "\u03a5\u0308\u0300"
        0x1FE3 -> "\u03a5\u0308\u0301"
        0x1FE4 -> "\u03a1\u0313"
        0x1FE6 -> "\u03a5\u0342"
        0x1FE7 -> "\u03a5\u0308\u0342"
        0x1FF2 -> "\u1ffa\u0399"
        0x1FF3 -> "\u03a9\u0399"
        0x1FF4 -> "\u038f\u0399"
        0x1FF6 -> "\u03a9\u0342"
        0x1FF7 -> "\u03a9\u0342\u0399"
        0x1FFC -> "\u03a9\u0399"
        0xFB00 -> "\u0046\u0046"
        0xFB01 -> "\u0046\u0049"
        0xFB02 -> "\u0046\u004c"
        0xFB03 -> "\u0046\u0046\u0049"
        0xFB04 -> "\u0046\u0046\u004c"
        0xFB05 -> "\u0053\u0054"
        0xFB06 -> "\u0053\u0054"
        0xFB13 -> "\u0544\u0546"
        0xFB14 -> "\u0544\u0535"
        0xFB15 -> "\u0544\u053b"
        0xFB16 -> "\u054e\u0546"
        0xFB17 -> "\u0544\u053d"
    else -> null
}

// Hand-written mirror of String.uppercase(Locale.US): walk UTF-16 code
// points (a valid pair maps as one code point, a lone surrogate maps as
// itself), apply the full-mapping table first, then the simple mapping.
private fun uppercaseOracle(s: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c1 = s[i]
        val cp: Int
        val adv: Int
        if (c1.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            cp = Character.toCodePoint(c1, s[i + 1]); adv = 2
        } else {
            cp = c1.code; adv = 1
        }
        val full = fullUpper(cp)
        if (full != null) sb.append(full)
        else sb.append(Character.toChars(Character.toUpperCase(cp)))
        i += adv
    }
    return sb.toString()
}

private fun takeOracle(s: String, n: Int): String =
    if (s.length <= n) s else s.substring(0, n)

private fun takeLastOracle(s: String, n: Int): String =
    if (s.length <= n) s else s.substring(s.length - n)

private fun removeSpacesOracle(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) if (c != ' ') sb.append(c)
    return sb.toString()
}

private fun headOracle(key: String): String = takeOracle(key, 8)

private fun tailOracle(key: String): String = takeLastOracle(key, 6)

private fun fingerprintOracle(fingerprint: String?): String? {
    if (fingerprint == null) return null
    // Order matters: strip spaces, then truncate to 8 units, then uppercase
    // (an eszett past the cut never expands).
    return uppercaseOracle(takeOracle(removeSpacesOracle(fingerprint), 8))
}

// ---------- shared generators (Random(42) in the CI tests) ----------

// The probed Kotlin whitespace set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as whitespace, U+0085 NEL does not.
private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

// Code-point ranges the random strings draw from. Covers ASCII, Latin-1,
// Latin/Greek/Cyrillic/Armenian/Georgian scripts, Greek Extended (with the
// multi-code-unit uppercase expansions), ligatures, fullwidth forms,
// Deseret / Adlam / Medefaidrin / Vithkuqi astral letters, CJK, combining
// marks, digits of several scripts, and general punctuation. Osage small
// letters are excluded: JDK 17 leaves them unchanged on uppercase while
// newer Unicode data maps them (pinned separately).
private val CP_RANGES = arrayOf(
    0x20 to 0x7E, 0x09 to 0x0D, 0x1C to 0x1F, 0x0085 to 0x0085,
    0x00A0 to 0x00FF, 0x0100 to 0x024F, 0x0300 to 0x036F,
    0x0370 to 0x03FF, 0x0400 to 0x052F, 0x0530 to 0x058F,
    0x0660 to 0x0660, 0x06F0 to 0x06F0, 0x0966 to 0x0966,
    0x10D0 to 0x10FF, 0x1680 to 0x1680, 0x1E00 to 0x1EFF,
    0x1F00 to 0x1FFF, 0x2000 to 0x206F, 0x2C00 to 0x2CFF,
    0x3000 to 0x30FF, 0x4E00 to 0x4FFF, 0xA640 to 0xA69F,
    0xA7C0 to 0xA7FF, 0xAB53 to 0xAB53, 0xAB70 to 0xAB70,
    0xFB00 to 0xFB17, 0xFEFF to 0xFEFF, 0xFF10 to 0xFF10,
    0xFF21 to 0xFF5A, 0x10400 to 0x1044F, 0x104A0 to 0x104A0,
    0x10570 to 0x105BC, 0x16E60 to 0x16E7F, 0x1E922 to 0x1E943,
    0x1F600 to 0x1F600
)

private fun randomCodePoint(rnd: Random): Int {
    val (lo, hi) = CP_RANGES[rnd.nextInt(CP_RANGES.size)]
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
            2 -> {
                // Lone surrogate (high or low): survives take/uppercase
                // untouched, encodes as 0x3F in UTF-8.
                sb.append(if (rnd.nextBoolean()) '\uD800' else '\uDC00'); n++
            }
            else -> {
                val cp = randomCodePoint(rnd)
                sb.append(Character.toChars(cp))
                n += if (cp >= 0x10000) 2 else 1
            }
        }
    }
    return sb.toString()
}

private fun randomInt(rnd: Random): Int = when (rnd.nextInt(8)) {
    0 -> 0; 1 -> 1; 2 -> -1; 3 -> 42; 4 -> -42
    5 -> Int.MIN_VALUE; 6 -> Int.MAX_VALUE
    else -> rnd.nextInt()
}

    @Test
    fun headRandom() {
        val rnd = Random(42)
        repeat(300) {
            val key = randomString(rnd)
            assertEquals(headOracle(key), AccountDeviceFingerprintPolicy.identityKeyHead(key))
        }
    }

    @Test
    fun tailRandom() {
        val rnd = Random(42)
        repeat(300) {
            val key = randomString(rnd)
            assertEquals(tailOracle(key), AccountDeviceFingerprintPolicy.identityKeyTail(key))
        }
    }

    @Test
    fun fingerprintRandom() {
        val rnd = Random(42)
        repeat(300) {
            val fp = if (rnd.nextBoolean()) null else randomString(rnd)
            assertEquals(fingerprintOracle(fp), AccountDeviceFingerprintPolicy.shortFingerprint(fp))
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // head: first 8 UTF-16 units, short strings pass through.
        assertEquals("", AccountDeviceFingerprintPolicy.identityKeyHead(""))
        assertEquals("abc", AccountDeviceFingerprintPolicy.identityKeyHead("abc"))
        assertEquals("abcdef12", AccountDeviceFingerprintPolicy.identityKeyHead("abcdef12"))
        assertEquals("abcdef12", AccountDeviceFingerprintPolicy.identityKeyHead("abcdef123"))
        assertEquals("abcdef12", AccountDeviceFingerprintPolicy.identityKeyHead("abcdef123456"))
        assertEquals("\ud83d\ude00", AccountDeviceFingerprintPolicy.identityKeyHead("\ud83d\ude00"))
        assertEquals("\ud83d\ude00\ud83d\ude00\ud83d\ude00\ud83d\ude00", AccountDeviceFingerprintPolicy.identityKeyHead("\ud83d\ude00\ud83d\ude00\ud83d\ude00\ud83d\ude00"))
        assertEquals("\ud83d\ude00\ud83d\ude00\ud83d\ude00\ud83d\ude00", AccountDeviceFingerprintPolicy.identityKeyHead("\ud83d\ude00\ud83d\ude00\ud83d\ude00\ud83d\ude00\ud83d\ude00"))
        assertEquals("\ud800", AccountDeviceFingerprintPolicy.identityKeyHead("\ud800"))
        assertEquals("\ud800abcdefg", AccountDeviceFingerprintPolicy.identityKeyHead("\ud800abcdefg"))
        assertEquals("a\ud800bcdefg", AccountDeviceFingerprintPolicy.identityKeyHead("a\ud800bcdefgh"))
        assertEquals("\u00df\u00df\u00df\u00df\u00df\u00df\u00df\u00df", AccountDeviceFingerprintPolicy.identityKeyHead("\u00df\u00df\u00df\u00df\u00df\u00df\u00df\u00df"))
        assertEquals("12345678", AccountDeviceFingerprintPolicy.identityKeyHead("12345678"))
        assertEquals("12345678", AccountDeviceFingerprintPolicy.identityKeyHead("123456789"))
        // tail: last 6 UTF-16 units.
        assertEquals("", AccountDeviceFingerprintPolicy.identityKeyTail(""))
        assertEquals("abc", AccountDeviceFingerprintPolicy.identityKeyTail("abc"))
        assertEquals("123456", AccountDeviceFingerprintPolicy.identityKeyTail("123456"))
        assertEquals("234567", AccountDeviceFingerprintPolicy.identityKeyTail("1234567"))
        assertEquals("123456", AccountDeviceFingerprintPolicy.identityKeyTail("abcdef123456"))
        assertEquals("\ud83d\ude00\ud83d\ude00\ud83d\ude00", AccountDeviceFingerprintPolicy.identityKeyTail("\ud83d\ude00\ud83d\ude00\ud83d\ude00"))
        assertEquals("\ud83d\ude00\ud83d\ude00\ud83d\ude00", AccountDeviceFingerprintPolicy.identityKeyTail("\ud83d\ude00\ud83d\ude00\ud83d\ude00\ud83d\ude00"))
        assertEquals("\ud800", AccountDeviceFingerprintPolicy.identityKeyTail("\ud800"))
        assertEquals("cdefg\ud800", AccountDeviceFingerprintPolicy.identityKeyTail("abcdefg\ud800"))
        assertEquals("\u00df\u00df\u00df\u00df\u00df\u00df", AccountDeviceFingerprintPolicy.identityKeyTail("\u00df\u00df\u00df\u00df\u00df\u00df"))
        // fingerprint: null stays null; only U+0020 is stripped,
        // then truncated to 8 units, then uppercased (Locale.US).
        assertNull(AccountDeviceFingerprintPolicy.shortFingerprint(null))
        assertEquals("", AccountDeviceFingerprintPolicy.shortFingerprint(""))
        assertEquals("ABCD", AccountDeviceFingerprintPolicy.shortFingerprint("abcd"))
        assertEquals("ABCDEF12", AccountDeviceFingerprintPolicy.shortFingerprint("ab cd ef12 34"))
        assertEquals("SS", AccountDeviceFingerprintPolicy.shortFingerprint("\u00df"))
        assertEquals("SSSSSSSSSSSSSSSS", AccountDeviceFingerprintPolicy.shortFingerprint("\u00df\u00df\u00df\u00df\u00df\u00df\u00df\u00df"))
        assertEquals("SSSSSSSSSSSSSSSS", AccountDeviceFingerprintPolicy.shortFingerprint("\u00df\u00df\u00df\u00df\u00df\u00df\u00df\u00df\u00df"))
        assertEquals("FFFIFL", AccountDeviceFingerprintPolicy.shortFingerprint("\ufb00\ufb01\ufb02"))
        assertEquals("FF", AccountDeviceFingerprintPolicy.shortFingerprint("\ufb00"))
        assertEquals("ABCDEFGH", AccountDeviceFingerprintPolicy.shortFingerprint("a b c d e f g h i"))
        assertEquals("AB", AccountDeviceFingerprintPolicy.shortFingerprint("  ab  "))
        assertEquals("\u00a0AB\u00a0", AccountDeviceFingerprintPolicy.shortFingerprint("\u00a0ab\u00a0"))
        assertEquals("\u1f08\u0399", AccountDeviceFingerprintPolicy.shortFingerprint("\u1f80"))
        assertEquals("\u0391\u0399", AccountDeviceFingerprintPolicy.shortFingerprint("\u1fb3"))
        assertEquals("\u0399", AccountDeviceFingerprintPolicy.shortFingerprint("\u1fbe"))
        assertEquals("\u03a5\u0313", AccountDeviceFingerprintPolicy.shortFingerprint("\u1f50"))
        assertEquals("J\u030c", AccountDeviceFingerprintPolicy.shortFingerprint("\u01f0"))
        assertEquals("\u02bcN", AccountDeviceFingerprintPolicy.shortFingerprint("\u0149"))
        assertEquals("\u0535\u0552", AccountDeviceFingerprintPolicy.shortFingerprint("\u0587"))
        assertEquals("H\u0331", AccountDeviceFingerprintPolicy.shortFingerprint("h\u0331"))
        assertEquals("T\u0308", AccountDeviceFingerprintPolicy.shortFingerprint("\u1e97"))
        assertEquals("\ud801\udc00", AccountDeviceFingerprintPolicy.shortFingerprint("\ud801\udc28"))
        assertEquals("\u13a0", AccountDeviceFingerprintPolicy.shortFingerprint("\uab70"))
        assertEquals("\ud801\udcb0", AccountDeviceFingerprintPolicy.shortFingerprint("\ud801\udcb0"))
        // uppercase(Locale.US) 走 JDK 21 的 Unicode 16 大小写表：
        // U+2C5F -> U+2C2F，U+A7C1 -> U+A7C0，U+10597 -> U+10570。
        assertEquals("\u2c2f", AccountDeviceFingerprintPolicy.shortFingerprint("\u2c5f"))
        assertEquals("\ua7c0", AccountDeviceFingerprintPolicy.shortFingerprint("\ua7c1"))
        assertEquals("\ud801\udd70", AccountDeviceFingerprintPolicy.shortFingerprint("\ud801\udd97"))
        assertEquals("\ud800", AccountDeviceFingerprintPolicy.shortFingerprint("\ud800"))
        assertEquals("A\ud800", AccountDeviceFingerprintPolicy.shortFingerprint("a\ud800"))
        assertEquals("\ud800\udc00", AccountDeviceFingerprintPolicy.shortFingerprint("\ud800\udc00"))
        assertEquals("\ud83d\ude00X", AccountDeviceFingerprintPolicy.shortFingerprint("\ud83d\ude00x"))
        assertEquals("S", AccountDeviceFingerprintPolicy.shortFingerprint("\u017f"))
        assertEquals("I", AccountDeviceFingerprintPolicy.shortFingerprint("\u0131"))
        assertEquals("\u01c4", AccountDeviceFingerprintPolicy.shortFingerprint("\u01c6"))
        assertEquals("A\u0307", AccountDeviceFingerprintPolicy.shortFingerprint("a\u0307"))
        assertEquals("\u039f\u03a3", AccountDeviceFingerprintPolicy.shortFingerprint("\u039f\u03a3"))
        assertEquals("FISH", AccountDeviceFingerprintPolicy.shortFingerprint("\ufb01sh"))
        assertEquals("FIFIFIFIFI", AccountDeviceFingerprintPolicy.shortFingerprint("\ufb01\ufb01\ufb01\ufb01\ufb01"))
    }
}
