package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 通话记录搜索框输入截断 fuzz（清单 931 行第二十二块）。
 * 只加测试、零生产改动：`CallHistoryQueryPolicy.kt` 的 `filterQuery`（`take(100)`）。
 * 固定种子，同一份输入每次跑出同一批用例。
 */
class CallHistoryQueryPolicyFuzzTest {

    private val textAlphabet = "abcXYZ019 _-.\"\\\n\t中"

    private fun randomText(r: Random, length: Int): String =
        buildString { repeat(length) { append(textAlphabet[r.nextInt(textAlphabet.length)]) } }

    // 与生产侧不同的写法：显式长度分支 + substring，不走 take。
    private fun oracleFilterQuery(value: String): String =
        if (value.length <= CallHistoryQueryPolicy.MAX_QUERY_LENGTH) value
        else value.substring(0, CallHistoryQueryPolicy.MAX_QUERY_LENGTH)

    @Test
    fun `filterQuery matches oracle on random strings`() {
        val r = Random(0x9E52_0622L)
        repeat(200) {
            val length = when (r.nextInt(5)) {
                0 -> r.nextInt(0, 100)
                1 -> r.nextInt(100, 105)
                else -> r.nextInt(0, 160)
            }
            val value = randomText(r, length)
            assertEquals(oracleFilterQuery(value), CallHistoryQueryPolicy.filterQuery(value))
        }
    }

    @Test
    fun `boundary lengths pin at 99 100 101`() {
        assertEquals(99, CallHistoryQueryPolicy.filterQuery("x".repeat(99)).length)
        assertEquals("x".repeat(100), CallHistoryQueryPolicy.filterQuery("x".repeat(100)))
        assertEquals("x".repeat(100), CallHistoryQueryPolicy.filterQuery("x".repeat(101)))
        assertEquals("", CallHistoryQueryPolicy.filterQuery(""))
        assertEquals("中".repeat(100), CallHistoryQueryPolicy.filterQuery("中".repeat(105)))
    }

    @Test
    fun `truncation counts chars not bytes or code points`() {
        // 100 个中文字符恰好满格，105 个截到 100。
        assertEquals(100, CallHistoryQueryPolicy.filterQuery("中".repeat(105)).length)
        // 50 个 emoji 正好 100 个 UTF-16 单元：不切半个字符。
        assertEquals("😀".repeat(50), CallHistoryQueryPolicy.filterQuery("😀".repeat(51)))
        // "a" + 50 个 emoji 共 101 个单元：切在第 100 个单元，正好劈开最后一个 emoji 的代理对。
        val split = CallHistoryQueryPolicy.filterQuery("a" + "😀".repeat(50))
        assertEquals(100, split.length)
        assertTrue(split.startsWith("a" + "😀".repeat(49)))
        assertTrue(split.last().isHighSurrogate())
    }
}
