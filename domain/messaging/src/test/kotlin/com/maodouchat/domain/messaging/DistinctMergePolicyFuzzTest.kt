package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// mergeById reimplemented by hand: walk existing then incoming in
// order, keep the first item of each id, drop later repeats. The
// kept item is the first occurrence, so its payload value wins.
internal data class MergeItem166(val id: String, val v: Int)

internal fun mergeByIdOracle166(
    existing: List<MergeItem166>,
    incoming: List<MergeItem166>
): List<MergeItem166> {
    val seen = LinkedHashSet<String>()
    val out = ArrayList<MergeItem166>()
    for (item in existing + incoming) {
        if (seen.add(item.id)) out.add(item)
    }
    return out
}

// ---------- generators ----------

internal data class MergeCase166(
    val existing: List<MergeItem166>,
    val incoming: List<MergeItem166>
)

internal val MERGE_ID_POOL166: List<String> = listOf(
    "a", "b", "c", "", "A", "a ", " a", "ab", "m1", "m2",
    "\u00A0", "\u2003", "a\u00A0", "\uD83D\uDE00",
)

internal fun randomMergeItem166(rnd: Random): MergeItem166 {
    val id = when (rnd.nextInt(10)) {
        0 -> MERGE_ID_POOL166[rnd.nextInt(MERGE_ID_POOL166.size)]
        1 -> "id" + rnd.nextInt(4)
        else -> MERGE_ID_POOL166[rnd.nextInt(MERGE_ID_POOL166.size)]
    }
    val v = when (rnd.nextInt(10)) {
        0 -> Int.MIN_VALUE
        1 -> Int.MAX_VALUE
        else -> rnd.nextInt(-2, 3)
    }
    return MergeItem166(id, v)
}

internal fun randomMergeList166(rnd: Random): List<MergeItem166> {
    val n = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(1, 3)
        else -> rnd.nextInt(0, 7)
    }
    return List(n) { randomMergeItem166(rnd) }
}

internal fun randomMergeCase166(rnd: Random): MergeCase166 =
    MergeCase166(randomMergeList166(rnd), randomMergeList166(rnd))

internal fun mergeWireJson166(c: MergeCase166): String {
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
    fun listJson(items: List<MergeItem166>): String {
        val sb = StringBuilder()
        sb.append("[")
        for ((i, it) in items.withIndex()) {
            if (i > 0) sb.append(",")
            sb.append("[${esc(it.id)},${it.v}]")
        }
        sb.append("]")
        return sb.toString()
    }
    return "{\"e\":${listJson(c.existing)},\"i\":${listJson(c.incoming)}}"
}

class DistinctMergePolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val a1 = MergeItem166("a", 1)
        val a2 = MergeItem166("a", 2)
        val b1 = MergeItem166("b", 1)
        val empty = MergeItem166("", 0)
        val cases = listOf(
            Triple(emptyList(), emptyList(), emptyList()),
            Triple(listOf(a1), emptyList(), listOf(a1)),
            Triple(emptyList(), listOf(a1), listOf(a1)),
            Triple(listOf(a1), listOf(a2), listOf(a1)),
            Triple(listOf(a1, a2), emptyList(), listOf(a1)),
            Triple(listOf(a1, b1), listOf(a2), listOf(a1, b1)),
            Triple(listOf(b1), listOf(a1, a2, b1), listOf(b1, a1)),
            Triple(
                listOf(MergeItem166("x", 1), MergeItem166("y", 2)),
                listOf(MergeItem166("y", 3), MergeItem166("z", 4), MergeItem166("x", 5)),
                listOf(
                    MergeItem166("x", 1), MergeItem166("y", 2),
                    MergeItem166("z", 4)
                )
            ),
            Triple(listOf(empty), listOf(empty), listOf(empty)),
            Triple(
                listOf(MergeItem166("A", 1)),
                listOf(MergeItem166("a", 2)),
                listOf(MergeItem166("A", 1), MergeItem166("a", 2))
            ),
            Triple(
                listOf(MergeItem166("a ", 1)),
                listOf(MergeItem166("a", 2)),
                listOf(MergeItem166("a ", 1), MergeItem166("a", 2))
            ),
            Triple(
                listOf(MergeItem166("a", Int.MIN_VALUE)),
                listOf(MergeItem166("a", Int.MAX_VALUE)),
                listOf(MergeItem166("a", Int.MIN_VALUE))
            ),
        )
        for ((existing, incoming, expected) in cases) {
            assertEquals(
                expected,
                DistinctMergePolicy.mergeById(existing, incoming) { it.id },
                "mergeById($existing,$incoming)"
            )
            assertEquals(
                expected,
                mergeByIdOracle166(existing, incoming),
                "oracle($existing,$incoming)"
            )
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(166002)
        repeat(4000) {
            val c = randomMergeCase166(rnd)
            assertEquals(
                mergeByIdOracle166(c.existing, c.incoming),
                DistinctMergePolicy.mergeById(c.existing, c.incoming) { it.id },
                "mergeById(${c.existing},${c.incoming})"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        val ids = listOf("a", "b", "")
        val lists = ArrayList<List<MergeItem166>>()
        lists.add(emptyList())
        for (a in ids) lists.add(listOf(MergeItem166(a, 0)))
        for (a in ids) for (b in ids) {
            lists.add(listOf(MergeItem166(a, 0), MergeItem166(b, 1)))
        }
        for (a in ids) for (b in ids) for (c in ids) {
            lists.add(listOf(MergeItem166(a, 0), MergeItem166(b, 1), MergeItem166(c, 2)))
        }
        for (existing in lists) {
            for (incoming in lists) {
                assertEquals(
                    mergeByIdOracle166(existing, incoming),
                    DistinctMergePolicy.mergeById(existing, incoming) { it.id },
                    "mergeById($existing,$incoming)"
                )
            }
        }
        val values = listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)
        for (v1 in values) {
            for (v2 in values) {
                val existing = listOf(MergeItem166("a", v1))
                val incoming = listOf(MergeItem166("a", v2))
                assertEquals(
                    listOf(MergeItem166("a", v1)),
                    DistinctMergePolicy.mergeById(existing, incoming) { it.id },
                    "mergeById(v=$v1,$v2)"
                )
            }
        }
    }
}
