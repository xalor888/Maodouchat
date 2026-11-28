package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun filterQueryOracle161(value: String): String =
    if (value.length <= 160) value
    else value.substring(0, 160)

// ---------- generators ----------

internal val QUERY_POOL161 = listOf(
    "", " ", "  ", "abc", "x", "x".repeat(159), "x".repeat(160), "x".repeat(161),
    "x".repeat(200), "中".repeat(159), "中".repeat(160), "中".repeat(161),
    "\uD83D\uDE00".repeat(79), "\uD83D\uDE00".repeat(80), "\uD83D\uDE00".repeat(81),
    "x".repeat(159) + "\uD83D\uDE00", "x".repeat(160) + "\uD83D\uDE00",
    "\uD83D\uDE00" + "x".repeat(159), "a b", "a\tb", "a\nb", "a\u00a0b",
    "  abc  ", "\u00a0", "\u0085", "ß", "İ",
)

internal val QUERY_ALPHABET161 =
    "abcXYZ019 \t\n\u00a0\u00e9\u4e2d\u0085\u2003x\u00df\u0130\u0131"

internal fun randomQueryChar161(rnd: Random): String = when (rnd.nextInt(10)) {
    0 -> "\uD83D\uDE00"
    1 -> rnd.nextInt(0xD800, 0xE000).toChar().toString()
    else -> QUERY_ALPHABET161[rnd.nextInt(QUERY_ALPHABET161.length)].toString()
}

internal fun randomQueryString161(rnd: Random, nearBoundary: Boolean = false): String {
    val lengthUnits = when {
        nearBoundary -> rnd.nextInt(150, 171)
        else -> when (rnd.nextInt(10)) {
            0 -> 0
            1 -> rnd.nextInt(1, 4)
            else -> rnd.nextInt(1, 13)
        }
    }
    val sb = StringBuilder()
    var units = 0
    while (units < lengthUnits) {
        val c = randomQueryChar161(rnd)
        sb.append(c)
        units += c.length
    }
    return sb.toString()
}

internal data class QueryCase161(
    val value: String,
)

internal fun randomQueryCase161(rnd: Random): QueryCase161 {
    val value = when (rnd.nextInt(10)) {
        0 -> ""
        1 -> "   "
        2 -> QUERY_POOL161[rnd.nextInt(QUERY_POOL161.size)]
        3 -> randomQueryString161(rnd, nearBoundary = true)
        else -> randomQueryString161(rnd)
    }
    return QueryCase161(value)
}

internal fun queryJsonEscape161(s: String): String {
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

internal fun filterQueryWireJson161(case: QueryCase161): String =
    "{\"value\":\"" + queryJsonEscape161(case.value) + "\"}"

class AiTaskQueryPolicyFuzzTest {

    private fun check(value: String) {
        assertEquals(
            filterQueryOracle161(value),
            AiTaskQueryPolicy.filterQuery(value),
        )
    }

    @Test
    fun pinnedBoundaries() {
        assertEquals("", AiTaskQueryPolicy.filterQuery(""))
        assertEquals("abc", AiTaskQueryPolicy.filterQuery("abc"))
        assertEquals("x".repeat(160), AiTaskQueryPolicy.filterQuery("x".repeat(160)))
        assertEquals("x".repeat(160), AiTaskQueryPolicy.filterQuery("x".repeat(161)))
        assertEquals("中".repeat(160), AiTaskQueryPolicy.filterQuery("中".repeat(165)))
        assertEquals("x".repeat(160), filterQueryOracle161("x".repeat(161)))
        // A surrogate pair straddling the cut leaves a lone high surrogate.
        val split = AiTaskQueryPolicy.filterQuery("x".repeat(159) + "\uD83D\uDE00")
        assertEquals(160, split.length)
        assertEquals(0xD83D, split[159].code)
        for (value in QUERY_POOL161) {
            check(value)
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(161001)
        repeat(2000) {
            val case = randomQueryCase161(rnd)
            check(case.value)
        }
    }

    @Test
    fun sweepExhaustive() {
        for (value in QUERY_POOL161) {
            check(value)
        }
        for (c in QUERY_ALPHABET161) {
            check(c.toString())
        }
        // Every pair of alphabet chars.
        for (a in QUERY_ALPHABET161) {
            for (b in QUERY_ALPHABET161) {
                check(a.toString() + b)
            }
        }
        // Lengths 0..200 in units, three fillers.
        for (n in 0..200) {
            check("x".repeat(n))
            check("中".repeat(n))
            check("\uD83D\uDE00".repeat(n))
        }
        // Boundary 159..161 with a surrogate pair at the cut.
        for (n in 155..165) {
            check("x".repeat(n) + "\uD83D\uDE00")
            check("\uD83D\uDE00" + "x".repeat(n))
            check("x".repeat(n) + "\uD800")
            check("\uD800" + "x".repeat(n))
        }
        // Lone surrogates on their own.
        for (u in 0xD800..0xDFFF step 0x101) {
            check(u.toChar().toString())
            check("x".repeat(159) + u.toChar())
        }
    }
}
