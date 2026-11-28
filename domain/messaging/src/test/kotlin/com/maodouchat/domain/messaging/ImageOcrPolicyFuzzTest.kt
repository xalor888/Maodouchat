package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun truncateOcrResultOracle161(text: String): String {
    var start = 0
    while (start < text.length && text[start].isWhitespace()) start++
    var end = text.length
    while (end > start && text[end - 1].isWhitespace()) end--
    val trimmed = text.substring(start, end)
    return if (trimmed.length <= 6000) trimmed
    else trimmed.substring(0, 6000)
}

// ---------- generators ----------

internal val OCR_POOL161 = listOf(
    "", " ", "   ", "\t\n", "hello", "  hello  ", "\t hello \n",
    "\u00a0hello\u00a0", "\u0085hello\u0085", "\u3000hello\u3000",
    "\u2028hello\u2029", "x".repeat(5999), "x".repeat(6000), "x".repeat(6001),
    "x".repeat(7000), "  " + "x".repeat(6000) + "  ",
    "  " + "x".repeat(5998) + "  ", "\u00a0" + "x".repeat(6000) + "\u00a0",
    "中".repeat(6000), "中".repeat(6001),
    "\uD83D\uDE00".repeat(3000), "\uD83D\uDE00".repeat(3001),
    "x".repeat(5999) + "\uD83D\uDE00", "x".repeat(6000) + "\uD83D\uDE00",
    " \t\n\u00a0\u3000", "a\u00a0b", "a\u0085b",
)

internal val OCR_ALPHABET161 =
    " \t\n\u000B\u000C\r\u00A0\u0085\u1680\u2000\u2003\u2028\u2029\u202F\u205F\u3000ax\u4E2D"

internal fun randomOcrChar161(rnd: Random): String = when (rnd.nextInt(10)) {
    0 -> "\uD83D\uDE00"
    1 -> rnd.nextInt(0xD800, 0xE000).toChar().toString()
    else -> OCR_ALPHABET161[rnd.nextInt(OCR_ALPHABET161.length)].toString()
}

internal fun randomOcrString161(rnd: Random, nearBoundary: Boolean = false): String {
    val lengthUnits = when {
        nearBoundary -> rnd.nextInt(5990, 6011)
        else -> when (rnd.nextInt(10)) {
            0 -> 0
            1 -> rnd.nextInt(1, 4)
            else -> rnd.nextInt(1, 41)
        }
    }
    val sb = StringBuilder()
    var units = 0
    while (units < lengthUnits) {
        val c = randomOcrChar161(rnd)
        sb.append(c)
        units += c.length
    }
    return sb.toString()
}

internal data class OcrCase161(
    val value: String,
)

internal fun randomOcrCase161(rnd: Random): OcrCase161 {
    val value = when (rnd.nextInt(10)) {
        0 -> ""
        1 -> "   "
        2 -> OCR_POOL161[rnd.nextInt(OCR_POOL161.size)]
        3 -> randomOcrString161(rnd, nearBoundary = true)
        4 -> {
            // Whitespace padding around a near-boundary body.
            val pad = randomOcrString161(rnd).filter { it.isWhitespace() }
            pad + randomOcrString161(rnd, nearBoundary = true) + pad
        }
        else -> randomOcrString161(rnd)
    }
    return OcrCase161(value)
}

internal fun ocrJsonEscape161(s: String): String {
    val sb = StringBuilder()
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            else -> {
                if (c.code < 0x20) {
                    val v = c.code
                    sb.append("\\u")
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(c)
                }
            }
        }
    }
    return sb.toString()
}

internal fun truncateOcrWireJson161(case: OcrCase161): String =
    "{\"value\":\"" + ocrJsonEscape161(case.value) + "\"}"

class ImageOcrPolicyFuzzTest {

    private fun check(value: String) {
        assertEquals(
            truncateOcrResultOracle161(value),
            ImageOcrPolicy.truncateOcrResult(value),
        )
    }

    @Test
    fun pinnedBoundaries() {
        assertEquals("", ImageOcrPolicy.truncateOcrResult("   "))
        assertEquals("hello", ImageOcrPolicy.truncateOcrResult("  hello  "))
        assertEquals("x".repeat(6000), ImageOcrPolicy.truncateOcrResult("x".repeat(6000)))
        assertEquals("x".repeat(6000), ImageOcrPolicy.truncateOcrResult("x".repeat(6001)))
        assertEquals("", ImageOcrPolicy.truncateOcrResult(""))
        // NBSP trims, NEL does not: the trim set is Kotlin whitespace,
        // not just chars <= ' ' and not Java Character.isWhitespace.
        assertEquals("hello", ImageOcrPolicy.truncateOcrResult("\u00a0hello\u00a0"))
        assertEquals("\u0085hello\u0085", ImageOcrPolicy.truncateOcrResult("\u0085hello\u0085"))
        assertEquals("x".repeat(6000), truncateOcrResultOracle161("x".repeat(6001)))
        // A surrogate pair straddling the cut leaves a lone high surrogate.
        val split = ImageOcrPolicy.truncateOcrResult("x".repeat(5999) + "\uD83D\uDE00")
        assertEquals(6000, split.length)
        assertEquals(0xD83D, split[5999].code)
        for (value in OCR_POOL161) {
            check(value)
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(161002)
        repeat(2000) {
            val case = randomOcrCase161(rnd)
            check(case.value)
        }
    }

    @Test
    fun sweepExhaustive() {
        for (value in OCR_POOL161) {
            check(value)
        }
        for (c in OCR_ALPHABET161) {
            check(c.toString())
            check(c.toString() + "a")
            check("a" + c.toString())
        }
        // Every pair of alphabet chars.
        for (a in OCR_ALPHABET161) {
            for (b in OCR_ALPHABET161) {
                check(a.toString() + b)
            }
        }
        // Lengths 0..200 in units, three fillers.
        for (n in 0..200) {
            check("x".repeat(n))
            check(" ".repeat(n) + "x")
            check("x" + " ".repeat(n))
        }
        // Dense neighbourhood of the 6000 cut.
        for (n in 5990..6010) {
            check("x".repeat(n))
            check(" " + "x".repeat(n) + " ")
            check("\u00a0" + "x".repeat(n) + "\u00a0")
            check("x".repeat(n) + "\uD83D\uDE00")
        }
        // Lone surrogates on their own.
        for (u in 0xD800..0xDFFF step 0x101) {
            check(u.toChar().toString())
        }
    }
}
