// Fuzz / random-oracle tests for PublishPostValidationPolicy.validate
// (checklist line 931, 124th block). Test-only, zero production changes.
// The oracle is hand-written: trim with the probed 28-unit Kotlin whitespace
// set (probe124: String.trim() strips exactly those units -- U+00A0/U+2007/
// U+202F yes, U+0085/U+200B/U+FEFF no), then the empty-content and 5000-char
// rules. The spec string encodes the sealed result so the comparison never
// depends on equals() of the production type.
package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublishPostValidationPolicyFuzzTest {

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

// ---------- validate oracle ----------

private fun validateSpecOf(v: PublishPostValidation): String =
    when (v) {
        PublishPostValidation.Valid -> "V"
        is PublishPostValidation.Invalid -> "I:" + v.reason
    }

private fun validateOracle(content: String, imageUrls: List<String>): String {
    val trimmed = trimOracle(content)
    if (trimmed.isEmpty() && imageUrls.isEmpty()) {
        return "I:Post content or image cannot be empty"
    }
    if (trimmed.length > 5000) {
        return "I:Post content exceeds 5000 characters"
    }
    return "V"
}

// ---------- GEN ----------

// Case-safe alphabet: every code point maps 1:1 under the per-code-point
// case mapping and never counts as whitespace. Same ranges as block 123.
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

private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

// Non-whitespace core characters only (no space, no WS set members), so
// the trimmed length is exactly the core length by construction.
private fun randomCoreCodePoint(rnd: Random): Int {
    while (true) {
        val (lo, hi) = CASE_SAFE_RANGES[rnd.nextInt(CASE_SAFE_RANGES.size)]
        val cp = rnd.nextInt(lo, hi + 1)
        if (cp !in BLANK_UNITS_ORACLE) return cp
    }
}

private fun randomCore(rnd: Random, len: Int): String {
    val sb = StringBuilder()
    var n = 0
    while (n < len) {
        val cp = randomCoreCodePoint(rnd)
        sb.append(Character.toChars(cp))
        n += if (cp >= 0x10000) 2 else 1
    }
    return sb.toString()
}

private fun randomWsPad(rnd: Random, maxLen: Int): String {
    val len = rnd.nextInt(0, maxLen + 1)
    val sb = StringBuilder()
    repeat(len) { sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)]) }
    return sb.toString()
}

private val CORE_LEN_POOL = intArrayOf(
    0, 1, 2, 3, 10, 100, 4998, 4999, 5000, 5001, 5002, 5100, 6000
)

private fun genContent(rnd: Random, poolOnly: Boolean): String {
    val coreLen = if (poolOnly) CORE_LEN_POOL[rnd.nextInt(CORE_LEN_POOL.size)]
    else when (rnd.nextInt(5)) {
        0 -> CORE_LEN_POOL[rnd.nextInt(CORE_LEN_POOL.size)]
        1 -> rnd.nextInt(0, 100)
        2 -> rnd.nextInt(4995, 5006)
        else -> rnd.nextInt(0, 5101)
    }
    val core = randomCore(rnd, coreLen)
    return randomWsPad(rnd, 4) + core + randomWsPad(rnd, 4)
}

private fun genImageUrls(rnd: Random): List<String> {
    val n = when (rnd.nextInt(5)) {
        0 -> 0
        1 -> 1
        2 -> 2
        3 -> 3
        else -> rnd.nextInt(0, 5)
    }
    return List(n) { "https://example.com/img${rnd.nextInt(100)}.jpg" }
}

private fun checkValidate(content: String, imageUrls: List<String>) {
    assertEquals(
        validateOracle(content, imageUrls),
        validateSpecOf(PublishPostValidationPolicy.validate(content, imageUrls)),
        "contentLen=${content.length} trimmedLen=${trimOracle(content).length} images=${imageUrls.size}"
    )
}

    @Test
    fun publishPostValidationRandom() {
        val rnd = Random(42)
        repeat(300) {
            checkValidate(genContent(rnd, poolOnly = false), genImageUrls(rnd))
        }
    }

    @Test
    fun publishPostValidationEdgeRandom() {
        val rnd = Random(7)
        repeat(300) {
            checkValidate(genContent(rnd, poolOnly = true), genImageUrls(rnd))
        }
    }

    @Test
    fun publishPostValidationPinned() {
        // Empty / whitespace-only content with no images is invalid.
        assertEquals(
            "I:Post content or image cannot be empty",
            validateSpecOf(PublishPostValidationPolicy.validate("", emptyList()))
        )
        assertEquals(
            "I:Post content or image cannot be empty",
            validateSpecOf(PublishPostValidationPolicy.validate("   ", emptyList()))
        )
        // U+00A0 / U+2007 trim away, so the content is empty.
        assertEquals(
            "I:Post content or image cannot be empty",
            validateSpecOf(
                PublishPostValidationPolicy.validate("\u00A0\u2007\u202F", emptyList())
            )
        )
        // U+0085 / U+200B / U+FEFF do NOT trim: single such char is content.
        assertEquals(
            "V",
            validateSpecOf(PublishPostValidationPolicy.validate("\u0085", emptyList()))
        )
        assertEquals(
            "V",
            validateSpecOf(PublishPostValidationPolicy.validate("\u200B", emptyList()))
        )
        assertEquals(
            "V",
            validateSpecOf(PublishPostValidationPolicy.validate("\uFEFF", emptyList()))
        )
        // Any image saves empty content; it does not save overlong content.
        assertEquals(
            "V",
            validateSpecOf(
                PublishPostValidationPolicy.validate(
                    "   ", listOf("https://example.com/img.jpg")
                )
            )
        )
        assertEquals(
            "I:Post content exceeds 5000 characters",
            validateSpecOf(
                PublishPostValidationPolicy.validate(
                    "x".repeat(5001), listOf("https://example.com/img.jpg")
                )
            )
        )
        // The 5000 boundary is on the trimmed length.
        assertEquals(
            "V",
            validateSpecOf(
                PublishPostValidationPolicy.validate("x".repeat(5000), emptyList())
            )
        )
        assertEquals(
            "I:Post content exceeds 5000 characters",
            validateSpecOf(
                PublishPostValidationPolicy.validate("x".repeat(5001), emptyList())
            )
        )
        assertEquals(
            "V",
            validateSpecOf(
                PublishPostValidationPolicy.validate(
                    "  " + "x".repeat(5000) + "  ", emptyList()
                )
            )
        )
        assertEquals(
            "I:Post content exceeds 5000 characters",
            validateSpecOf(
                PublishPostValidationPolicy.validate(
                    "  " + "x".repeat(5001) + "  ", emptyList()
                )
            )
        )
        // Plain content is valid; the reason strings are exact.
        assertEquals(
            "V",
            validateSpecOf(
                PublishPostValidationPolicy.validate("  hello  ", emptyList())
            )
        )
        val empty = PublishPostValidationPolicy.validate("", emptyList())
        assertTrue(empty is PublishPostValidation.Invalid)
        assertEquals(
            "Post content or image cannot be empty",
            (empty as PublishPostValidation.Invalid).reason
        )
        val overlong = PublishPostValidationPolicy.validate("x".repeat(5001), emptyList())
        assertTrue(overlong is PublishPostValidation.Invalid)
        assertEquals(
            "Post content exceeds 5000 characters",
            (overlong as PublishPostValidation.Invalid).reason
        )
        // Oracle cross-checks on the same pinned inputs.
        assertEquals(
            validateOracle("", emptyList()),
            validateSpecOf(PublishPostValidationPolicy.validate("", emptyList()))
        )
        assertEquals(
            validateOracle("x".repeat(5001), emptyList()),
            validateSpecOf(
                PublishPostValidationPolicy.validate("x".repeat(5001), emptyList())
            )
        )
        assertEquals(
            validateOracle("\u00A0\u2007", emptyList()),
            validateSpecOf(
                PublishPostValidationPolicy.validate("\u00A0\u2007", emptyList())
            )
        )
    }
}
