package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// filterQuery reimplemented without take(): keep the whole string when
// it fits in 160 UTF-16 units, else cut it at unit 160. Cutting by
// unit index can split a surrogate pair; that is the production
// behaviour and the oracle keeps it.
internal fun filterQueryOracle166(value: String): String =
    if (value.length <= 160) value else value.substring(0, 160)

// ---------- generators ----------

internal data class StarCase166(val value: String)

internal val STAR_POOL166: List<String> = listOf(
    "", "x", "hello world", "x".repeat(159), "x".repeat(160),
    "x".repeat(161), "x".repeat(200), "x".repeat(400),
    "x".repeat(159) + "\uD83D\uDE00",
    "x".repeat(160) + "\uD83D\uDE00",
    "\uD83D\uDE00" + "x".repeat(159),
    "\uD83D", "\uDE00", "\uD800", "\uDFFF",
    "a\uD83Db", "\u00E9".repeat(161),
    " \t\n\u00A0\u2003\u001C",
)

internal const val STAR_ALPHABET166 = "abx \t\n\u00A0\u2003\u001C_\u00E9"

internal fun randomStarString166(rnd: Random): String {
    val n = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(1, 11)
        2 -> rnd.nextInt(150, 176)
        else -> rnd.nextInt(0, 320)
    }
    val sb = StringBuilder()
    repeat(n) {
        when (rnd.nextInt(20)) {
            0 -> sb.append("\uD83D\uDE00")
            1 -> sb.append("\uD83D")
            2 -> sb.append("\uDE00")
            3 -> sb.append("\uD800")
            else -> sb.append(STAR_ALPHABET166[rnd.nextInt(STAR_ALPHABET166.length)])
        }
    }
    return sb.toString()
}

internal fun randomStarCase166(rnd: Random): StarCase166 {
    return when (rnd.nextInt(10)) {
        0 -> StarCase166(STAR_POOL166[rnd.nextInt(STAR_POOL166.size)])
        else -> StarCase166(randomStarString166(rnd))
    }
}

internal fun starWireJson166(c: StarCase166): String {
    val sb = StringBuilder()
    sb.append("{\"value\":\"")
    for (ch in c.value) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            else -> {
                if (ch.code < 0x20) {
                    sb.append("\\u")
                    val v = ch.code
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append("\"}")
    return sb.toString()
}

class StarredSearchQueryPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val cases = listOf(
            "" to "",
            "x" to "x",
            "x".repeat(159) to "x".repeat(159),
            "x".repeat(160) to "x".repeat(160),
            "x".repeat(161) to "x".repeat(160),
            "x".repeat(400) to "x".repeat(160),
            "x".repeat(159) + "\uD83D\uDE00" to "x".repeat(159) + "\uD83D",
            "x".repeat(160) + "\uD83D\uDE00" to "x".repeat(160),
            "\uD83D\uDE00" + "x".repeat(159) to "\uD83D\uDE00" + "x".repeat(158),
            "\uD83D" to "\uD83D",
            "\uD800" to "\uD800",
            "\uDFFF" to "\uDFFF",
            "a\uD83Db" to "a\uD83Db",
            "\u00E9".repeat(161) to "\u00E9".repeat(160),
            " \t\n\u00A0\u2003\u001C" to " \t\n\u00A0\u2003\u001C",
            "hello world" to "hello world",
        )
        for ((input, expected) in cases) {
            assertEquals(expected, StarredSearchQueryPolicy.filterQuery(input), "filterQuery(len=${input.length})")
            assertEquals(expected, filterQueryOracle166(input), "oracle(len=${input.length})")
        }
        assertEquals(160, StarredSearchQueryPolicy.MAX_QUERY_LENGTH, "MAX_QUERY_LENGTH")
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(166003)
        repeat(4000) {
            val c = randomStarCase166(rnd)
            assertEquals(
                filterQueryOracle166(c.value),
                StarredSearchQueryPolicy.filterQuery(c.value),
                "filterQuery(len=${c.value.length})"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        val alphabet = listOf("a", "\uD83D", "\uDE00", "\u00E9")
        val all = ArrayList<String>()
        all.add("")
        for (a in alphabet) all.add(a)
        for (a in alphabet) for (b in alphabet) all.add(a + b)
        for (a in alphabet) for (b in alphabet) for (c in alphabet) all.add(a + b + c)
        for (s in all) {
            assertEquals(
                filterQueryOracle166(s),
                StarredSearchQueryPolicy.filterQuery(s),
                "filterQuery(len=${s.length})"
            )
        }
        for (n in 0..200) {
            val s = "x".repeat(n)
            assertEquals(
                filterQueryOracle166(s),
                StarredSearchQueryPolicy.filterQuery(s),
                "filterQuery(repeat=$n)"
            )
        }
        for (n in 0..170) {
            val before = "x".repeat(n) + "\uD83D\uDE00"
            assertEquals(
                filterQueryOracle166(before),
                StarredSearchQueryPolicy.filterQuery(before),
                "filterQuery(astralAfter=$n)"
            )
            val after = "\uD83D\uDE00" + "x".repeat(n)
            assertEquals(
                filterQueryOracle166(after),
                StarredSearchQueryPolicy.filterQuery(after),
                "filterQuery(astralBefore=$n)"
            )
        }
        for (p in 155..165) {
            val s = "x".repeat(p) + "\uD83D\uDE00" + "y".repeat(5)
            assertEquals(
                filterQueryOracle166(s),
                StarredSearchQueryPolicy.filterQuery(s),
                "filterQuery(boundary=$p)"
            )
        }
    }
}
