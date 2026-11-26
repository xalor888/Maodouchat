package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun previewOracle161(text: String): String =
    if (text.length <= 60) text
    else text.substring(0, 60)

// ---------- generators ----------

internal val PREVIEW_POOL161 = listOf(
    "", " ", "hello", "a".repeat(59), "a".repeat(60), "a".repeat(61),
    "a".repeat(100), "中".repeat(59), "中".repeat(60), "中".repeat(61),
    "\uD83D\uDE00".repeat(29), "\uD83D\uDE00".repeat(30), "\uD83D\uDE00".repeat(31),
    "a".repeat(59) + "\uD83D\uDE00", "a".repeat(60) + "\uD83D\uDE00",
    "\uD83D\uDE00" + "a".repeat(59), "a b", "a\tb", "a\nb", "a\u00a0b",
    "  hello  ", "\u00a0", "\u0085", "ß", "İ",
)

internal val PREVIEW_ALPHABET161 =
    "abcXYZ019 \t\n\u00a0\u00e9\u4e2d\u0085\u2003x\u00df\u0130\u0131"

internal fun randomPreviewChar161(rnd: Random): String = when (rnd.nextInt(10)) {
    0 -> "\uD83D\uDE00"
    1 -> rnd.nextInt(0xD800, 0xE000).toChar().toString()
    else -> PREVIEW_ALPHABET161[rnd.nextInt(PREVIEW_ALPHABET161.length)].toString()
}

internal fun randomPreviewString161(rnd: Random, nearBoundary: Boolean = false): String {
    val lengthUnits = when {
        nearBoundary -> rnd.nextInt(50, 71)
        else -> when (rnd.nextInt(10)) {
            0 -> 0
            1 -> rnd.nextInt(1, 4)
            else -> rnd.nextInt(1, 13)
        }
    }
    val sb = StringBuilder()
    var units = 0
    while (units < lengthUnits) {
        val c = randomPreviewChar161(rnd)
        sb.append(c)
        units += c.length
    }
    return sb.toString()
}

internal data class PreviewCase161(
    val value: String,
)

internal fun randomPreviewCase161(rnd: Random): PreviewCase161 {
    val value = when (rnd.nextInt(10)) {
        0 -> ""
        1 -> "   "
        2 -> PREVIEW_POOL161[rnd.nextInt(PREVIEW_POOL161.size)]
        3 -> randomPreviewString161(rnd, nearBoundary = true)
        else -> randomPreviewString161(rnd)
    }
    return PreviewCase161(value)
}

internal fun previewJsonEscape161(s: String): String {
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

internal fun previewWireJson161(case: PreviewCase161): String =
    "{\"value\":\"" + previewJsonEscape161(case.value) + "\"}"

class ReplyPreviewPolicyFuzzTest {

    private fun check(value: String) {
        assertEquals(
            previewOracle161(value),
            ReplyPreviewPolicy.preview(value),
        )
    }

    @Test
    fun pinnedBoundaries() {
        assertEquals("hello", ReplyPreviewPolicy.preview("hello"))
        assertEquals("a".repeat(60), ReplyPreviewPolicy.preview("a".repeat(60)))
        assertEquals("a".repeat(60), ReplyPreviewPolicy.preview("a".repeat(61)))
        assertEquals("", ReplyPreviewPolicy.preview(""))
        assertEquals("a".repeat(60), previewOracle161("a".repeat(61)))
        // A surrogate pair straddling the cut leaves a lone high surrogate.
        val split = ReplyPreviewPolicy.preview("a".repeat(59) + "\uD83D\uDE00")
        assertEquals(60, split.length)
        assertEquals(0xD83D, split[59].code)
        for (value in PREVIEW_POOL161) {
            check(value)
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(161003)
        repeat(2000) {
            val case = randomPreviewCase161(rnd)
            check(case.value)
        }
    }

    @Test
    fun sweepExhaustive() {
        for (value in PREVIEW_POOL161) {
            check(value)
        }
        for (c in PREVIEW_ALPHABET161) {
            check(c.toString())
        }
        // Every pair of alphabet chars.
        for (a in PREVIEW_ALPHABET161) {
            for (b in PREVIEW_ALPHABET161) {
                check(a.toString() + b)
            }
        }
        // Lengths 0..120 in units, three fillers.
        for (n in 0..120) {
            check("a".repeat(n))
            check("中".repeat(n))
            check("\uD83D\uDE00".repeat(n))
        }
        // Boundary 55..65 with a surrogate pair at the cut.
        for (n in 55..65) {
            check("a".repeat(n) + "\uD83D\uDE00")
            check("\uD83D\uDE00" + "a".repeat(n))
            check("a".repeat(n) + "\uD800")
            check("\uD800" + "a".repeat(n))
        }
        // Lone surrogates on their own.
        for (u in 0xD800..0xDFFF step 0x101) {
            check(u.toChar().toString())
            check("a".repeat(59) + u.toChar())
        }
    }
}
