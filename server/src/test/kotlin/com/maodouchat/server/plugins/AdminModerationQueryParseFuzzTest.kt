package com.maodouchat.server.plugins

import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminModerationQueryParseFuzzTest {

    private companion object {
        private const val ITERATIONS = 150
    }

    private fun randomString(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomParamValue(random: Random): String = when (random.nextInt(8)) {
        0 -> ""
        1 -> "   "
        2 -> "ALL"
        3 -> "  ALL  "
        4 -> "all"
        5 -> randomString(random, random.nextInt(1, 20))
        6 -> listOf("true", "false", "TRUE", "1", "yes").random(random)
        else -> "PENDING"
    }

    private fun randomParams(random: Random, name: String): Parameters {
        val pairs = mutableListOf<Pair<String, List<String>>>()
        if (random.nextBoolean()) pairs += name to listOf(randomParamValue(random))
        return parametersOf(*pairs.toTypedArray())
    }

    // 旧内联写法的逐字复刻，只供等价性比对。
    private fun oldStatusFilterWay(params: Parameters): String? =
        params["status"]?.trim()?.takeIf { it.isNotBlank() && it != "ALL" }

    private fun oldQueryFlagWay(params: Parameters, name: String): Boolean =
        params[name] == "true"

    @Test
    fun `moderation query parse old and new agree on seeded random params`() {
        val random = Random(34210)
        repeat(ITERATIONS) { i ->
            val statusParams = randomParams(random, "status")
            assertEquals(oldStatusFilterWay(statusParams), parseModerationStatusFilter(statusParams), "iteration $i: status filter must match")
            val pendingParams = randomParams(random, "pending")
            assertEquals(oldQueryFlagWay(pendingParams, "pending"), parseQueryFlag(pendingParams, "pending"), "iteration $i: query flag must match")
        }
    }

    @Test
    fun `moderation query parse boundaries are pinned`() {
        // status：空白或哨兵值 ALL 等于不筛选；大小写敏感，all 小写照常透出。
        assertNull(parseModerationStatusFilter(parametersOf()))
        assertNull(parseModerationStatusFilter(parametersOf("status" to listOf("   "))))
        assertNull(parseModerationStatusFilter(parametersOf("status" to listOf("ALL"))))
        assertNull(parseModerationStatusFilter(parametersOf("status" to listOf("  ALL  "))))
        assertEquals("PENDING", parseModerationStatusFilter(parametersOf("status" to listOf("  PENDING "))))
        assertEquals("all", parseModerationStatusFilter(parametersOf("status" to listOf("all"))))
        // pending：只有字面量 "true" 算开。
        assertTrue(parseQueryFlag(parametersOf("pending" to listOf("true")), "pending"))
        assertFalse(parseQueryFlag(parametersOf(), "pending"))
        assertFalse(parseQueryFlag(parametersOf("pending" to listOf("TRUE")), "pending"))
        assertFalse(parseQueryFlag(parametersOf("pending" to listOf("1")), "pending"))
        assertFalse(parseQueryFlag(parametersOf("pending" to listOf("")), "pending"))
    }
}
