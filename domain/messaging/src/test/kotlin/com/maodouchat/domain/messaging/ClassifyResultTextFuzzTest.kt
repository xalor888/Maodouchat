// Fuzz / random-oracle tests for classifyResultText
// (checklist line 931, 125th block). Test-only, zero production changes.
// The oracle is hand-written: exact-match kind mapping (unknown -> OTHER),
// rows "<LABEL>: <N>条\n" then the disclaimer verbatim. The label/count
// lambdas are fixed by the test (kind.name / "$count条"), so the oracle
// checks production's assembly and kind mapping, not the lambdas.
package com.maodouchat.domain.messaging

import com.maodouchat.core.model.CategoryCount
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class ClassifyResultTextFuzzTest {

    private val labelText: (AiClassifyCategoryKind) -> String = { kind -> kind.name }
    private val countText: (Int) -> String = { count -> "${count}条" }

// ---------- shared oracle ----------

    private fun kindOracle(category: String): String = when (category) {
        "notice" -> "NOTICE"
        "todo" -> "TODO"
        "finance" -> "FINANCE"
        "study" -> "STUDY"
        "tech" -> "TECH"
        "social" -> "SOCIAL"
        else -> "OTHER"
    }

    private fun classifyResultTextOracle(categories: List<CategoryCount>, disclaimer: String): String = buildString {
        categories.forEach { row ->
            append(kindOracle(row.category)).append(": ").append("${row.count}条").append('\n')
        }
        append(disclaimer)
    }

// ---------- GEN ----------

    private val CATEGORY_POOL = arrayOf(
        "notice", "todo", "finance", "study", "tech", "social",
        "", "NOTICE", "Notice", "notice ", " notice", "noticex", "todo2",
        "finance1", "x", "mystery", "NOTICE ", "todo\n", "study\t"
    )

    private val COUNT_POOL = intArrayOf(0, 1, -1, 2, 99, Int.MAX_VALUE, Int.MIN_VALUE)

    private val DISCLAIMER_POOL = arrayOf(
        "", "d", "免责声明", "a:b\nc", "line1\nline2\n", "x".repeat(500)
    )

    private val TEXT_RANGES = arrayOf(
        0x20 to 0x7E,
        0x00A0 to 0x00FF, 0x0100 to 0x017F, 0x0370 to 0x03FF, 0x0400 to 0x04FF,
        0x2000 to 0x206F, 0x3000 to 0x30FF, 0x4E00 to 0x4FFF,
        0x10000 to 0x1FFFF, 0x20000 to 0x2A6DF
    )

    private fun randomText(rnd: Random, maxLen: Int): String {
        val len = rnd.nextInt(0, maxLen + 1)
        val sb = StringBuilder()
        repeat(len) {
            val (lo, hi) = TEXT_RANGES[rnd.nextInt(TEXT_RANGES.size)]
            sb.append(Character.toChars(rnd.nextInt(lo, hi + 1)))
        }
        return sb.toString()
    }

    private fun genCategories(rnd: Random): List<CategoryCount> {
        val n = when (rnd.nextInt(10)) {
            0 -> 0
            1, 2, 3 -> rnd.nextInt(1, 3)
            4, 5, 6 -> rnd.nextInt(3, 6)
            else -> rnd.nextInt(6, 11)
        }
        return List(n) {
            val category = if (rnd.nextInt(4) == 0) randomText(rnd, 12) else CATEGORY_POOL[rnd.nextInt(CATEGORY_POOL.size)]
            val count = if (rnd.nextInt(3) == 0) rnd.nextInt() else COUNT_POOL[rnd.nextInt(COUNT_POOL.size)]
            CategoryCount(category, count, rnd.nextDouble())
        }
    }

    private fun genDisclaimer(rnd: Random): String =
        if (rnd.nextInt(3) == 0) randomText(rnd, 60) else DISCLAIMER_POOL[rnd.nextInt(DISCLAIMER_POOL.size)]

// ---------- tests ----------

    @Test
    fun pinnedRows() {
        val allKinds = listOf("notice", "todo", "finance", "study", "tech", "social")
            .map { CategoryCount(it, 1, 0.5) }
        assertEquals(
            "NOTICE: 1条\nTODO: 1条\nFINANCE: 1条\nSTUDY: 1条\nTECH: 1条\nSOCIAL: 1条\nend",
            classifyResultText(allKinds, labelText, countText, "end")
        )
        assertEquals("only", classifyResultText(emptyList(), labelText, countText, "only"))
        assertEquals(
            "OTHER: 2条\nend",
            classifyResultText(listOf(CategoryCount("mystery", 2, 0.5)), labelText, countText, "end")
        )
        assertEquals(
            "NOTICE: 2147483647条\nTODO: -2147483648条\nend",
            classifyResultText(
                listOf(CategoryCount("notice", Int.MAX_VALUE, 0.0), CategoryCount("todo", Int.MIN_VALUE, 0.0)),
                labelText, countText, "end"
            )
        )
        // Disclaimer is appended verbatim, even with newlines and colons.
        assertEquals(
            "NOTICE: 0条\na:b\nc",
            classifyResultText(listOf(CategoryCount("notice", 0, 0.0)), labelText, countText, "a:b\nc")
        )
        // Same kind twice keeps both rows, in order.
        assertEquals(
            "TODO: 1条\nTODO: 2条\nend",
            classifyResultText(
                listOf(CategoryCount("todo", 1, 0.0), CategoryCount("todo", 2, 0.0)),
                labelText, countText, "end"
            )
        )
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(125002)
        repeat(1200) {
            val categories = genCategories(rnd)
            val disclaimer = genDisclaimer(rnd)
            assertEquals(
                classifyResultTextOracle(categories, disclaimer),
                classifyResultText(categories, labelText, countText, disclaimer)
            )
        }
    }

    @Test
    fun kindAndCountSweep() {
        // Every kind (plus unknown) as a single row, across the count pool.
        val kinds = listOf("notice", "todo", "finance", "study", "tech", "social", "mystery", "", "NOTICE")
        for (kind in kinds) {
            for (count in COUNT_POOL) {
                val rows = listOf(CategoryCount(kind, count, 0.0))
                assertEquals(
                    classifyResultTextOracle(rows, "d"),
                    classifyResultText(rows, labelText, countText, "d")
                )
            }
        }
        // Empty list across the disclaimer pool.
        for (disclaimer in DISCLAIMER_POOL) {
            assertEquals(
                classifyResultTextOracle(emptyList(), disclaimer),
                classifyResultText(emptyList(), labelText, countText, disclaimer)
            )
        }
    }
}
