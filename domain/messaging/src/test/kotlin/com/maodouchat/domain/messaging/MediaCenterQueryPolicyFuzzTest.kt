package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// filterQuery restated by hand: keep the first 200 UTF-16 units.
internal fun filterQueryOracle169(value: String): String = value.take(200)

// Canonical JSON string escaping, same as Python json.dumps with
// ensure_ascii=False, so wire JSON survives quotes and control chars.
internal fun escJson169(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
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
    sb.append("\"")
    return sb.toString()
}

// Production filterQuery returns the truncated string directly.
internal fun filterQueryProduction169(value: String): String =
    MediaCenterQueryPolicy.filterQuery(value)

// ---------- generators ----------

internal data class QueryCase169(val value: String)

internal val QUERY_POOL169: List<String> = listOf(
    "",
    "a",
    "hello world",
    "x".repeat(199),
    "x".repeat(200),
    "x".repeat(201),
    "x".repeat(250),
    "x".repeat(400),
    "\uD83D\uDE00".repeat(99),
    "\uD83D\uDE00".repeat(100),
    "\uD83D\uDE00".repeat(101),
    "a".repeat(199) + "\uD83D\uDE00",
    "a".repeat(198) + "\uD83D\uDE00" + "b",
    "a\nb\"c\\d",
    "  ",
    "\u00A0".repeat(250),
    "\u200B".repeat(250),
    "搜索词",
    "a".repeat(100) + "b".repeat(100) + "c".repeat(100)
)

internal fun randomQueryCase169(rnd: Random): QueryCase169 {
    val n = rnd.nextInt(5)
    val parts = List(n) { QUERY_POOL169[rnd.nextInt(QUERY_POOL169.size)] }
    return QueryCase169(parts.joinToString(""))
}

internal fun queryWireJson169(c: QueryCase169): String =
    "{\"value\":${escJson169(c.value)}}"

class MediaCenterQueryPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // The limit itself is a fixed constant.
        assertEquals(200, MediaCenterQueryPolicy.MAX_QUERY_LENGTH)
        // Lengths around the 200 cut, plain and astral.
        val cases = listOf(
            "" to "",
            "a" to "a",
            "x".repeat(199) to "x".repeat(199),
            "x".repeat(200) to "x".repeat(200),
            "x".repeat(201) to "x".repeat(200),
            "x".repeat(400) to "x".repeat(200),
            "\uD83D\uDE00".repeat(99) to "\uD83D\uDE00".repeat(99),
            "\uD83D\uDE00".repeat(100) to "\uD83D\uDE00".repeat(100),
            "\uD83D\uDE00".repeat(101) to "\uD83D\uDE00".repeat(100),
            "a".repeat(199) + "\uD83D\uDE00" to "a".repeat(199) + "\uD83D\uDE00".substring(0, 1),
            "a\nb\"c\\d" to "a\nb\"c\\d",
            "\u00A0".repeat(250) to "\u00A0".repeat(200)
        )
        for ((value, expected) in cases) {
            assertEquals(
                expected,
                filterQueryProduction169(value),
                "filterQuery(len=${value.length})"
            )
            assertEquals(
                expected,
                filterQueryOracle169(value),
                "oracle(len=${value.length})"
            )
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(169003)
        repeat(4000) {
            val c = randomQueryCase169(rnd)
            assertEquals(
                filterQueryOracle169(c.value),
                filterQueryProduction169(c.value),
                "filterQuery(random)"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every length from 0 to 210, plain and astral.
        for (n in 0..210) {
            val value = "x".repeat(n)
            assertEquals(
                filterQueryOracle169(value),
                filterQueryProduction169(value),
                "sweep-plain-len(n=$n)"
            )
        }
        for (n in 95..105) {
            val value = "\uD83D\uDE00".repeat(n)
            assertEquals(
                filterQueryOracle169(value),
                filterQueryProduction169(value),
                "sweep-astral-count(n=$n)"
            )
        }
        // A lone high surrogate at the cut: take(200) keeps the
        // first 200 units even if the last one is half a pair.
        val split = "a".repeat(199) + "\uD83D\uDE00"
        assertEquals(200, filterQueryProduction169(split).length)
        assertEquals(
            filterQueryOracle169(split),
            filterQueryProduction169(split),
            "sweep-split-surrogate"
        )
        // Every whitespace unit repeated past the cut.
        for (u in listOf(9, 10, 11, 12, 13, 28, 29, 30, 31, 32, 160, 5760, 8192, 8193, 8194, 8195, 8196, 8197, 8198, 8199, 8200, 8201, 8202, 8232, 8233, 8239, 8287, 12288)) {
            val value = u.toChar().toString().repeat(250)
            assertEquals(
                filterQueryOracle169(value),
                filterQueryProduction169(value),
                "sweep-ws(u=$u)"
            )
        }
    }
}
