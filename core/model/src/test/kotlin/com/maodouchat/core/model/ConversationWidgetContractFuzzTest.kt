package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fuzz / random-oracle tests for [ConversationWidgetContract] row helpers
 * (checklist line 931, 54th block). Test-only, zero production changes.
 *
 * rowRequestCode is Int multiply-add with silent wrap-around; rowDataUri
 * is a plain template with no escaping. Random inputs use Random(42),
 * fresh per @Test.
 */
class ConversationWidgetContractFuzzTest {

// ---------- B4 ConversationWidgetContract oracle ----------

private fun rowRequestCodeOracle(widgetId: Int, rowIndex: Int, salt: Int): Int =
    widgetId * 1000 + rowIndex * 10 + salt

private fun rowDataUriOracle(kind: String, widgetId: Int, rowIndex: Int): String =
    "maodouchat-widget://$kind/$widgetId/$rowIndex"

// ---------- B4 generators (Random(42) in the CI tests) ----------

private val KIND_POOL = listOf(
    "", "open", "reply", "mark_read", "sync", "a/b", "x?y", "a b", "%20", "中文", "ab"
)

private val KIND_CH_POOL = listOf(
    "a", "b", "z", "A", "Z", "0", "9", " ", "/", "?", "%", "#", "_", "-", ".",
    "é", "中", "\u00a0"
)

private fun randomKind(rnd: Random): String = when (rnd.nextInt(7)) {
    0 -> KIND_POOL[rnd.nextInt(KIND_POOL.size)]
    else -> buildString {
        repeat(rnd.nextInt(0, 12)) { append(KIND_CH_POOL[rnd.nextInt(KIND_CH_POOL.size)]) }
    }
}

private fun randomWidgetId(rnd: Random): Int = when (rnd.nextInt(9)) {
    0 -> 0; 1 -> 1; 2 -> -1; 3 -> 2_147_483; 4 -> 2_147_484; 5 -> Int.MAX_VALUE
    6 -> Int.MIN_VALUE; 7 -> 999
    else -> rnd.nextInt()
}

private fun randomRowIndex(rnd: Random): Int = when (rnd.nextInt(7)) {
    0 -> 0; 1 -> 1; 2 -> 3; 3 -> 4; 4 -> 100; 5 -> 1000
    else -> rnd.nextInt(0, 1000)
}

private fun randomSalt(rnd: Random): Int = when (rnd.nextInt(7)) {
    0 -> 0; 1 -> 1; 2 -> -1; 3 -> 10; 4 -> Int.MAX_VALUE; 5 -> Int.MIN_VALUE
    else -> rnd.nextInt()
}

    @Test
    fun rowRequestCodeRandom() {
        val rnd = Random(42)
        repeat(300) {
            val widgetId = randomWidgetId(rnd)
            val rowIndex = randomRowIndex(rnd)
            val salt = randomSalt(rnd)
            assertEquals(
                rowRequestCodeOracle(widgetId, rowIndex, salt),
                ConversationWidgetContract.rowRequestCode(widgetId, rowIndex, salt)
            )
        }
    }

    @Test
    fun rowDataUriRandom() {
        val rnd = Random(42)
        repeat(300) {
            val kind = randomKind(rnd)
            val widgetId = randomWidgetId(rnd)
            val rowIndex = randomRowIndex(rnd)
            assertEquals(
                rowDataUriOracle(kind, widgetId, rowIndex),
                ConversationWidgetContract.rowDataUri(kind, widgetId, rowIndex)
            )
        }
    }

    @Test
    fun pinnedEdgeCases() {
        assertEquals(0, ConversationWidgetContract.rowRequestCode(0, 0, 0))
        assertEquals(1023, ConversationWidgetContract.rowRequestCode(1, 2, 3))
        assertEquals(2_147_483_000, ConversationWidgetContract.rowRequestCode(2_147_483, 0, 0))
        // Int.MAX_VALUE * 1000 wraps silently, +1 lands on -999.
        assertEquals(-999, ConversationWidgetContract.rowRequestCode(Int.MAX_VALUE, 0, 1))
        assertEquals(Int.MIN_VALUE, ConversationWidgetContract.rowRequestCode(0, 0, Int.MIN_VALUE))
        assertEquals(
            "maodouchat-widget:///1/2",
            ConversationWidgetContract.rowDataUri("", 1, 2)
        )
        assertEquals(
            "maodouchat-widget://open/7/3",
            ConversationWidgetContract.rowDataUri("open", 7, 3)
        )
        assertEquals(
            "maodouchat-widget://a/b/1/2",
            ConversationWidgetContract.rowDataUri("a/b", 1, 2)
        )
    }
}
