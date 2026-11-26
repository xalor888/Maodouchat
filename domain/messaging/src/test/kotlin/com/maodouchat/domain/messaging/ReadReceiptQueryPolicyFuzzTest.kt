package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// filterQuery restated by hand: keep the first 100 UTF-16 units.
internal fun filterQueryOracle170(value: String): String = value.take(100)

// Canonical JSON string escaping, same as Python json.dumps with
// ensure_ascii=False, so wire JSON survives quotes and control chars.
internal fun escJson170(s: String): String {
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
internal fun filterQueryProduction170(value: String): String =
    ReadReceiptQueryPolicy.filterQuery(value)

// ---------- generators ----------

internal data class QueryCase170(val value: String)

internal val QUERY_POOL170: List<String> = listOf(
    "",
    "a",
    "hello world",
    "x".repeat(99),
    "x".repeat(100),
    "x".repeat(101),
    "x".repeat(150),
    "x".repeat(200),
    "\uD83D\uDE00".repeat(49),
    "\uD83D\uDE00".repeat(50),
    "\uD83D\uDE00".repeat(51),
    "a".repeat(99) + "\uD83D\uDE00",
    "a".repeat(98) + "\uD83D\uDE00" + "b",
    "a\nb\"c\\d",
    "  ",
    "\u00A0".repeat(150),
    "\u200B".repeat(150),
    "搜索词",
    "a".repeat(50) + "b".repeat(50) + "c".repeat(50)
)

internal fun randomQueryCase170(rnd: Random): QueryCase170 {
    val n = rnd.nextInt(5)
    val parts = List(n) { QUERY_POOL170[rnd.nextInt(QUERY_POOL170.size)] }
    return QueryCase170(parts.joinToString(""))
}

internal fun queryWireJson170(c: QueryCase170): String =
    "{\"value\":${escJson170(c.value)}}"

class ReadReceiptQueryPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // The limit itself is a fixed constant.
        assertEquals(100, ReadReceiptQueryPolicy.MAX_QUERY_LENGTH)
        // Lengths around the 100 cut, plain and astral.
        val cases = listOf(
            "" to "",
            "a" to "a",
            "x".repeat(99) to "x".repeat(99),
            "x".repeat(100) to "x".repeat(100),
            "x".repeat(101) to "x".repeat(100),
            "x".repeat(200) to "x".repeat(100),
            "\uD83D\uDE00".repeat(49) to "\uD83D\uDE00".repeat(49),
            "\uD83D\uDE00".repeat(50) to "\uD83D\uDE00".repeat(50),
            "\uD83D\uDE00".repeat(51) to "\uD83D\uDE00".repeat(50),
            "a".repeat(99) + "\uD83D\uDE00" to "a".repeat(99) + "\uD83D\uDE00".substring(0, 1),
            "a\nb\"c\\d" to "a\nb\"c\\d",
            "\u00A0".repeat(150) to "\u00A0".repeat(100)
        )
        for ((value, expected) in cases) {
            assertEquals(
                expected,
                filterQueryProduction170(value),
                "filterQuery(len=${value.length})"
            )
            assertEquals(
                expected,
                filterQueryOracle170(value),
                "oracle(len=${value.length})"
            )
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(170002)
        repeat(4000) {
            val c = randomQueryCase170(rnd)
            assertEquals(
                filterQueryOracle170(c.value),
                filterQueryProduction170(c.value),
                "filterQuery(random)"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every length from 0 to 110, plain and astral.
        for (n in 0..110) {
            val value = "x".repeat(n)
            assertEquals(
                filterQueryOracle170(value),
                filterQueryProduction170(value),
                "sweep-plain-len(n=$n)"
            )
        }
        for (n in 45..55) {
            val value = "\uD83D\uDE00".repeat(n)
            assertEquals(
                filterQueryOracle170(value),
                filterQueryProduction170(value),
                "sweep-astral-count(n=$n)"
            )
        }
        // A lone high surrogate at the cut: take(100) keeps the
        // first 100 units even if the last one is half a pair.
        val split = "a".repeat(99) + "\uD83D\uDE00"
        assertEquals(100, filterQueryProduction170(split).length)
        assertEquals(
            filterQueryOracle170(split),
            filterQueryProduction170(split),
            "sweep-split-surrogate"
        )
        // Every whitespace unit repeated past the cut.
        for (u in listOf(9, 10, 11, 12, 13, 28, 29, 30, 31, 32, 160, 5760, 8192, 8193, 8194, 8195, 8196, 8197, 8198, 8199, 8200, 8201, 8202, 8232, 8233, 8239, 8287, 12288)) {
            val value = u.toChar().toString().repeat(150)
            assertEquals(
                filterQueryOracle170(value),
                filterQueryProduction170(value),
                "sweep-ws(u=$u)"
            )
        }
    }
}
