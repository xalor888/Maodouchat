package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// restore reimplemented by hand: clamp the index into [0, size]
// (a negative index lands at the front, an index past the end
// appends) and insert the item there; the input list is never
// mutated. Generic over the element type, like production.
internal fun <T> restoreOracle167(items: List<T>, item: T, originalIndex: Int): List<T> {
    val out = items.toMutableList()
    out.add(originalIndex.coerceIn(0, out.size), item)
    return out
}

// ---------- generators ----------

internal data class DelCase167(
    val items: List<String>,
    val item: String,
    val index: Int
)

internal val DEL_ITEM_POOL167: List<String> = listOf(
    "", "a", "b", "x", " ", "\u00A0", "ab", "a b", "A", "a\u00A0",
    "\uD83D\uDE00", "\uD800", "post", "comment",
)

internal val DEL_INDEX_POOL167: List<Int> = listOf(
    Int.MIN_VALUE, -2, -1, 0, 1, 2, 3, 5, 6, 7, Int.MAX_VALUE,
)

internal fun randomDelCase167(rnd: Random): DelCase167 {
    val n = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(1, 3)
        else -> rnd.nextInt(0, 7)
    }
    val items = List(n) { DEL_ITEM_POOL167[rnd.nextInt(DEL_ITEM_POOL167.size)] }
    return DelCase167(
        items,
        DEL_ITEM_POOL167[rnd.nextInt(DEL_ITEM_POOL167.size)],
        DEL_INDEX_POOL167[rnd.nextInt(DEL_INDEX_POOL167.size)]
    )
}

internal fun delWireJson167(c: DelCase167): String {
    fun esc(s: String): String {
        val sb = StringBuilder()
        sb.append("\"")
        for (ch in s) {
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
        sb.append("\"")
        return sb.toString()
    }
    val sb = StringBuilder()
    sb.append("{\"items\":[")
    for ((i, s) in c.items.withIndex()) {
        if (i > 0) sb.append(",")
        sb.append(esc(s))
    }
    sb.append("],\"item\":${esc(c.item)},\"index\":${c.index}}")
    return sb.toString()
}

class ExploreDeleteRollbackPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        fun check(items: List<String>, item: String, index: Int, expected: List<String>) {
            assertEquals(
                expected, ExploreDeleteRollbackPolicy.restore(items, item, index),
                "restore(items=$items,item=$item,index=$index)"
            )
            assertEquals(
                expected, restoreOracle167(items, item, index),
                "oracle(items=$items,item=$item,index=$index)"
            )
        }
        // Empty list: every index lands at the only slot.
        check(emptyList(), "x", 0, listOf("x"))
        check(emptyList(), "x", 5, listOf("x"))
        check(emptyList(), "x", -3, listOf("x"))
        check(emptyList(), "x", Int.MAX_VALUE, listOf("x"))
        check(emptyList(), "x", Int.MIN_VALUE, listOf("x"))
        // Index at each slot of a 3-list, plus one past the end.
        check(listOf("a", "b", "c"), "x", 0, listOf("x", "a", "b", "c"))
        check(listOf("a", "b", "c"), "x", 1, listOf("a", "x", "b", "c"))
        check(listOf("a", "b", "c"), "x", 2, listOf("a", "b", "x", "c"))
        check(listOf("a", "b", "c"), "x", 3, listOf("a", "b", "c", "x"))
        check(listOf("a", "b", "c"), "x", 4, listOf("a", "b", "c", "x"))
        // Negative index clamps to the front (-1 is not "before last").
        check(listOf("a", "b", "c"), "x", -1, listOf("x", "a", "b", "c"))
        check(listOf("a", "b", "c"), "x", Int.MIN_VALUE, listOf("x", "a", "b", "c"))
        check(listOf("a", "b", "c"), "x", Int.MAX_VALUE, listOf("a", "b", "c", "x"))
        // Duplicates are inert: insertion is positional, not by value.
        check(listOf("a", "a", "b"), "a", 2, listOf("a", "a", "a", "b"))
        check(listOf("a", "a", "b"), "b", 0, listOf("b", "a", "a", "b"))
        // The input list is not mutated.
        val input = listOf("a", "b")
        ExploreDeleteRollbackPolicy.restore(input, "x", 1)
        assertEquals(listOf("a", "b"), input, "input untouched")
        // Genericity: the same oracle covers Int elements.
        assertEquals(
            listOf(1, 9, 2, 3),
            ExploreDeleteRollbackPolicy.restore(listOf(1, 2, 3), 9, 1),
            "restore ints"
        )
        assertEquals(
            listOf(1, 9, 2, 3),
            restoreOracle167(listOf(1, 2, 3), 9, 1),
            "oracle ints"
        )
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(167002)
        repeat(4000) {
            val c = randomDelCase167(rnd)
            assertEquals(
                restoreOracle167(c.items, c.item, c.index),
                ExploreDeleteRollbackPolicy.restore(c.items, c.item, c.index),
                "restore(items=${c.items},item=${c.item},index=${c.index})"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every short list over {a, b} x every item x every near index.
        val alphabet = listOf("a", "b")
        val all = ArrayList<List<String>>()
        all.add(emptyList())
        for (a in alphabet) all.add(listOf(a))
        for (a in alphabet) for (b in alphabet) all.add(listOf(a, b))
        for (a in alphabet) for (b in alphabet) for (c in alphabet) {
            all.add(listOf(a, b, c))
        }
        for (items in all) {
            for (item in listOf("a", "b", "x")) {
                for (index in -1..4) {
                    assertEquals(
                        restoreOracle167(items, item, index),
                        ExploreDeleteRollbackPolicy.restore(items, item, index),
                        "sweep(items=$items,item=$item,index=$index)"
                    )
                }
            }
        }
        // Index sweep for each length 0..10 (clamp edges at -1 / size / size+1).
        for (n in 0..10) {
            val items = List(n) { "e$it" }
            for (index in listOf(-2, -1, 0, 1, n - 1, n, n + 1, Int.MAX_VALUE, Int.MIN_VALUE)) {
                assertEquals(
                    restoreOracle167(items, "x", index),
                    ExploreDeleteRollbackPolicy.restore(items, "x", index),
                    "sweep-len(n=$n,index=$index)"
                )
            }
        }
    }
}
