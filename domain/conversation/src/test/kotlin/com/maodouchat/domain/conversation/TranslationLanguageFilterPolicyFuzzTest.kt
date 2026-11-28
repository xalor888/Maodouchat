// Fuzz / random-oracle tests for TranslationLanguageFilterPolicy (checklist
// line 931, 124th block). Test-only, zero production changes. The oracle is
// hand-written: trim with the probed 28-unit Kotlin whitespace set, and a
// case-insensitive contains that transcribes JDK 17's
// StringUTF16.compareToCIImpl exactly (probe124 + ProbeCase124 +
// ProbeAstral124: "ß" never folds to "SS", "ﬀ" never to "FF", while
// "ı" folds to "I" and "ſ" to "s" -- "İ" folds to "ı"/"i" because
// lower('İ') is 'i', and astral capitals fold to their small forms,
// e.g. Deseret U+1040F vs U+10437). No full-string lowercase() is
// ever called, so the oracle is exact for every input, not just the
// case-safe alphabet.
package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranslationLanguageFilterPolicyFuzzTest {

// ---------- shared oracle ----------

// The probed Kotlin blank set (28 UTF-16 code units). Hand-written,
// never calls Char.isWhitespace / String.isBlank / trim().
private val BLANK_UNITS_ORACLE = setOf(
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
    0xA0, 0x1680,
    0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
    0x2008, 0x2009, 0x200A,
    0x2028, 0x2029, 0x202F, 0x205F, 0x3000
)

// Hand-written trim: strip leading/trailing UTF-16 units in the probed
// 28-unit set. Never trim().
private fun trimOracle(s: String): String {
    var lo = 0
    var hi = s.length
    while (lo < hi && s[lo].code in BLANK_UNITS_ORACLE) lo++
    while (hi > lo && s[hi - 1].code in BLANK_UNITS_ORACLE) hi--
    return s.substring(lo, hi)
}

// Case-insensitive contains: a hand-written transcription of
// java.lang.String.regionMatches(ignoreCase = true) as implemented by
// JDK 17 (StringUTF16.compareToCIImpl -- read from the local JDK
// src.zip, verified by ProbeCase124/ProbeAstral124). Two subtleties
// the naive per-char rule misses:
//  1. The last step lowercases the *uppercased* chars, not the
//     originals -- that is the only way U+0130 'İ' matches U+0131
//     'ı' (lower('ı') is 'ı' but lower(upper('ı')) is 'i').
//  2. When two UTF-16 units differ, a surrogate pair is combined
//     into one code point before the case comparison -- so an astral
//     capital matches its astral small form (Deseret U+1040F vs
//     U+10437, Adlam U+1E900 vs U+1E922), while lone surrogates still
//     match unit-wise. Exact for every input.
private fun codePointEqualsIgnoreCaseOracle(cp1: Int, cp2: Int): Boolean {
    val up1 = Character.toUpperCase(cp1)
    val up2 = Character.toUpperCase(cp2)
    if (up1 == up2) return true
    return Character.toLowerCase(up1) == Character.toLowerCase(up2)
}

// Mirrors StringUTF16.codePointIncluding: returns the code point at
// index, negated when index points at a high surrogate paired with
// the next unit (the caller then skips that low surrogate).
private fun codePointIncludingOracle(
    s: String, cp: Int, index: Int, start: Int, end: Int
): Int {
    if (cp < 0xD800 || cp > 0xDFFF) return cp
    if (cp >= 0xDC00) { // low surrogate: pair with the previous unit
        if (index > start) {
            val c = s[index - 1]
            if (c in '\uD800'..'\uDBFF') return Character.toCodePoint(c, cp.toChar())
        }
    } else if (index + 1 < end) { // high surrogate: pair with the next unit
        val c = s[index + 1]
        if (c in '\uDC00'..'\uDFFF') return -Character.toCodePoint(cp.toChar(), c)
    }
    return cp
}

private fun regionMatchesCIOracle(
    s: String, thisOffset: Int, other: String, otherOffset: Int, length: Int
): Boolean {
    if (thisOffset < 0 || otherOffset < 0 ||
        thisOffset > s.length - length || otherOffset > other.length - length
    ) return false
    var k1 = thisOffset
    var k2 = otherOffset
    val tlast = thisOffset + length
    val olast = otherOffset + length
    while (k1 < tlast && k2 < olast) {
        val cp1 = s[k1].code
        val cp2 = other[k2].code
        if (cp1 == cp2 || codePointEqualsIgnoreCaseOracle(cp1, cp2)) {
            k1++
            k2++
            continue
        }
        var c1 = codePointIncludingOracle(s, cp1, k1, thisOffset, tlast)
        if (c1 < 0) {
            k1++
            c1 = -c1
        }
        var c2 = codePointIncludingOracle(other, cp2, k2, otherOffset, olast)
        if (c2 < 0) {
            k2++
            c2 = -c2
        }
        if (!codePointEqualsIgnoreCaseOracle(c1, c2)) return false
        k1++
        k2++
    }
    return true
}

private fun containsIgnoreCaseOracle(s: String, needle: String): Boolean {
    if (needle.isEmpty()) return true
    if (needle.length > s.length) return false
    for (i in 0..s.length - needle.length) {
        if (regionMatchesCIOracle(s, i, needle, 0, needle.length)) return true
    }
    return false
}

// ---------- filter oracle ----------

private fun matchesOracle(
    option: TranslationLanguageOption,
    label: String,
    query: String
): Boolean =
    containsIgnoreCaseOracle(label, query) ||
        containsIgnoreCaseOracle(option.wireValue, query)

private fun filterOracle(
    options: List<Pair<TranslationLanguageOption, String>>,
    query: String
): List<Pair<TranslationLanguageOption, String>> {
    val trimmed = trimOracle(query)
    if (trimmed.isEmpty()) return options
    return options.filter { (option, label) -> matchesOracle(option, label, trimmed) }
}

// ---------- GEN ----------

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

private fun randomString(rnd: Random, maxLen: Int = 24): String {
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

private val WIRE_POOL = arrayOf(
    "en", "zh-CN", "zh-TW", "es", "fr", "de", "ja", "ko", "pt-BR", "ru",
    "ar", "EN", "En", "en-US", "zh", "pt", "", " ", "e", "E", "in", "IN"
)

private val LABEL_POOL = arrayOf(
    "English", "English (US)", "中文", "繁體中文", "Español", "Français",
    "Deutsch", "日本語", "한국어", "Português", "Русский", "العربية",
    "ENGLISH", "english", "", " ", "in", "In", "IN"
)

private val QUERY_POOL = arrayOf(
    "", " ", "  ", "en", "EN", "En", "zh", "ZH", "an", "e", "E", "in", "IN",
    "中文", "ES", "es", "ñ", "ß", "ı", "ſ", "ﬀ", "İ", "eng", "ENG",
    "lish", "xyz", "  en  ", "\u00A0en\u00A0", "us", "US"
)

private fun genOption(rnd: Random, poolOnly: Boolean): Pair<TranslationLanguageOption, String> {
    val wire = if (poolOnly || rnd.nextInt(3) != 0) WIRE_POOL[rnd.nextInt(WIRE_POOL.size)]
    else randomString(rnd, 12)
    val label = if (poolOnly || rnd.nextInt(3) != 0) LABEL_POOL[rnd.nextInt(LABEL_POOL.size)]
    else randomString(rnd, 16)
    return TranslationLanguageOption(wire, 0) to label
}

private fun genOptions(rnd: Random, poolOnly: Boolean): List<Pair<TranslationLanguageOption, String>> {
    val n = when (rnd.nextInt(5)) {
        0 -> 0
        1 -> 1
        2 -> 3
        3 -> 5
        else -> rnd.nextInt(0, 8)
    }
    return List(n) { genOption(rnd, poolOnly) }
}

private fun genQuery(rnd: Random, poolOnly: Boolean): String =
    if (poolOnly || rnd.nextInt(3) != 0) QUERY_POOL[rnd.nextInt(QUERY_POOL.size)]
    else randomString(rnd, 10)

private fun checkMatches(
    option: TranslationLanguageOption,
    label: String,
    query: String
) {
    assertEquals(
        matchesOracle(option, label, query),
        TranslationLanguageFilterPolicy.matches(option, label, query),
        "wire='${option.wireValue}' label='$label' query='$query'"
    )
}

private fun checkFilter(
    options: List<Pair<TranslationLanguageOption, String>>,
    query: String
) {
    assertEquals(
        filterOracle(options, query),
        TranslationLanguageFilterPolicy.filter(options, query),
        "query='$query' options=${options.map { it.first.wireValue + "|" + it.second }}"
    )
}

    @Test
    fun translationLanguageMatchesRandom() {
        val rnd = Random(42)
        repeat(300) {
            val (option, label) = genOption(rnd, poolOnly = false)
            checkMatches(option, label, genQuery(rnd, poolOnly = false))
        }
    }

    @Test
    fun translationLanguageFilterRandom() {
        val rnd = Random(7)
        repeat(300) {
            checkFilter(genOptions(rnd, poolOnly = false), genQuery(rnd, poolOnly = false))
        }
    }

    @Test
    fun translationLanguageFilterPinned() {
        val en = TranslationLanguageOption("en", 0) to "English"
        val zh = TranslationLanguageOption("zh-CN", 0) to "中文"
        val es = TranslationLanguageOption("es", 0) to "Español"
        val options = listOf(en, zh, es)
        // Case-insensitive substring on the label.
        assertTrue(TranslationLanguageFilterPolicy.matches(en.first, en.second, "eng"))
        assertTrue(TranslationLanguageFilterPolicy.matches(en.first, en.second, "ENG"))
        assertTrue(TranslationLanguageFilterPolicy.matches(en.first, en.second, "lish"))
        assertFalse(TranslationLanguageFilterPolicy.matches(en.first, en.second, "xyz"))
        // A match on the wire value alone is enough.
        assertTrue(TranslationLanguageFilterPolicy.matches(zh.first, zh.second, "zh-cn"))
        assertTrue(TranslationLanguageFilterPolicy.matches(zh.first, zh.second, "CN"))
        assertFalse(TranslationLanguageFilterPolicy.matches(zh.first, zh.second, "en"))
        // The empty needle matches everything (contains("") is true).
        assertTrue(TranslationLanguageFilterPolicy.matches(en.first, en.second, ""))
        // Per-char folding, not full-string case mapping: "ß" never folds to
        // "SS", "ﬀ" never to "FF", but "ı" folds to "I" and "ſ" to "s" -- and
        // "İ" folds to "ı"/"i" because lower('İ') is 'i' (ProbeCase124: the
        // regionMatches rule lowercases the uppercased chars, so the naive
        // lower(a)==lower(b) oracle misses exactly the İ/ı pair over the
        // whole BMP).
        assertFalse(
            TranslationLanguageFilterPolicy.matches(
                TranslationLanguageOption("xx", 0), "Straße", "STRASSE"
            )
        )
        assertFalse(
            TranslationLanguageFilterPolicy.matches(
                TranslationLanguageOption("xx", 0), "ﬀ", "FF"
            )
        )
        assertTrue(
            TranslationLanguageFilterPolicy.matches(
                TranslationLanguageOption("xx", 0), "ı", "I"
            )
        )
        assertTrue(
            TranslationLanguageFilterPolicy.matches(
                TranslationLanguageOption("xx", 0), "ſ", "s"
            )
        )
        assertTrue(
            TranslationLanguageFilterPolicy.matches(
                TranslationLanguageOption("xx", 0), "ı", "İ"
            )
        )
        assertTrue(
            TranslationLanguageFilterPolicy.matches(
                TranslationLanguageOption("xx", 0), "i", "İ"
            )
        )
        assertTrue(
            TranslationLanguageFilterPolicy.matches(
                TranslationLanguageOption("xx", 0), "English", "İ"
            )
        )
        assertFalse(
            TranslationLanguageFilterPolicy.matches(
                TranslationLanguageOption("xx", 0), "Español", "İ"
            )
        )
        // Astral case pairs fold too (JDK combines the surrogate pair
        // into one code point before comparing): Deseret capital
        // U+1040F vs small U+10437, Adlam U+1E900 vs U+1E922.
        assertTrue(
            TranslationLanguageFilterPolicy.matches(
                TranslationLanguageOption("xx", 0), "𐐏", "𐐷"
            )
        )
        assertTrue(
            TranslationLanguageFilterPolicy.matches(
                TranslationLanguageOption("xx", 0), "𐐷", "𐐏"
            )
        )
        assertTrue(
            TranslationLanguageFilterPolicy.matches(
                TranslationLanguageOption("xx", 0), "𞤀", "𞤢"
            )
        )
        // A lone low surrogate still matches unit-wise, and a lone
        // high surrogate plus 'x' does not form the astral pair.
        assertTrue(
            TranslationLanguageFilterPolicy.matches(
                TranslationLanguageOption("xx", 0), "𐐏", "\uDC0F"
            )
        )
        assertFalse(
            TranslationLanguageFilterPolicy.matches(
                TranslationLanguageOption("xx", 0), "\uD801x", "𐐷"
            )
        )
        // A blank query returns the list untouched; padding is trimmed first.
        assertEquals(
            options,
            TranslationLanguageFilterPolicy.filter(options, "  ")
        )
        assertEquals(
            listOf(en),
            TranslationLanguageFilterPolicy.filter(options, "en")
        )
        assertEquals(
            listOf(en),
            TranslationLanguageFilterPolicy.filter(options, "  EN  ")
        )
        // "İ" matches "English" (via its 'i'), nothing else here.
        assertEquals(
            listOf(en),
            TranslationLanguageFilterPolicy.filter(options, "İ")
        )
        assertEquals(
            listOf(zh),
            TranslationLanguageFilterPolicy.filter(options, "中文")
        )
        assertEquals(
            emptyList(),
            TranslationLanguageFilterPolicy.filter(options, "xyz")
        )
        // U+00A0 padding trims away just like a space.
        assertEquals(
            listOf(en),
            TranslationLanguageFilterPolicy.filter(options, "\u00A0en\u00A0")
        )
        // Oracle cross-checks on the same pinned inputs.
        assertEquals(
            matchesOracle(en.first, en.second, "ENG"),
            TranslationLanguageFilterPolicy.matches(en.first, en.second, "ENG")
        )
        assertEquals(
            filterOracle(options, "  EN  "),
            TranslationLanguageFilterPolicy.filter(options, "  EN  ")
        )
        assertEquals(
            filterOracle(options, "xyz"),
            TranslationLanguageFilterPolicy.filter(options, "xyz")
        )
    }
}
