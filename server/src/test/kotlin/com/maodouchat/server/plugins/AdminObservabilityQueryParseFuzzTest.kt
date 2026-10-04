package com.maodouchat.server.plugins

import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdminObservabilityQueryParseFuzzTest {

    private companion object {
        private const val ITERATIONS = 150
    }

    private fun randomString(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?~\t\n"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomParamValue(random: Random): String = when (random.nextInt(8)) {
        0 -> ""
        1 -> "   "
        2 -> random.nextInt(-500, 5000).toString()
        3 -> (random.nextDouble(-50.0, 25000.0)).toString()
        4 -> randomString(random, random.nextInt(1, 30))
        5 -> randomString(random, random.nextInt(90, 160))
        6 -> "abc"
        else -> random.nextLong(-5, 5).toString() + ".5"
    }

    private fun randomParams(random: Random): Parameters {
        val pairs = mutableListOf<Pair<String, List<String>>>()
        if (random.nextBoolean()) pairs += "limit" to listOf(randomParamValue(random))
        if (random.nextBoolean()) pairs += "offset" to listOf(randomParamValue(random))
        if (random.nextBoolean()) pairs += "q" to listOf(randomParamValue(random))
        if (random.nextBoolean()) pairs += "action" to listOf(randomParamValue(random))
        return parametersOf(*pairs.toTypedArray())
    }

    // 旧内联写法的逐字复刻，只供等价性比对。
    private fun oldLimitWay(params: Parameters, default: Int, max: Int): Int =
        (params["limit"]?.toIntOrNull() ?: default).coerceIn(1, max)

    private fun oldOffsetLongWay(params: Parameters): Long =
        (params["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)

    private fun oldOffsetIntWay(params: Parameters): Int =
        (params["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

    private fun oldActionWay(params: Parameters): String? =
        params["action"]?.trim()?.takeIf { it.isNotBlank() }

    private fun oldSearchWay(params: Parameters): String? =
        params["q"]?.trim()?.take(80)?.takeIf { it.isNotBlank() }

    @Test
    fun `observability params old and new agree on seeded random params`() {
        val random = Random(33017)
        repeat(ITERATIONS) { i ->
            val params = randomParams(random)
            assertEquals(oldLimitWay(params, 100, 500), parseObservabilityLimit(params), "iteration " + i + ": limit must match")
            assertEquals(oldLimitWay(params, 20, 100), parseObservabilityLimit(params, defaultLimit = 20, maxLimit = 100), "iteration " + i + ": topN must match")
            assertEquals(oldLimitWay(params, 5000, 10000), parseObservabilityLimit(params, defaultLimit = 5000, maxLimit = 10000), "iteration " + i + ": export limit must match")
            assertEquals(oldOffsetLongWay(params), parseObservabilityOffsetLong(params), "iteration " + i + ": offset long must match")
            assertEquals(oldOffsetIntWay(params), parseObservabilityOffsetInt(params), "iteration " + i + ": offset int must match")
            assertEquals(oldActionWay(params), parseObservabilityAction(params), "iteration " + i + ": action must match")
            assertEquals(oldSearchWay(params), parseObservabilitySearch(params), "iteration " + i + ": search must match")
        }
    }

    @Test
    fun `limit clamping is pinned`() {
        assertEquals(1, parseObservabilityLimit(parametersOf("limit" to listOf("0"))))
        assertEquals(500, parseObservabilityLimit(parametersOf("limit" to listOf("501"))))
        assertEquals(100, parseObservabilityLimit(parametersOf("limit" to listOf("abc"))))
        assertEquals(100, parseObservabilityLimit(parametersOf()))
        // 可解析的值走钳制分支：回的是上限，不是默认。
        assertEquals(10000, parseObservabilityLimit(parametersOf("limit" to listOf("999999")), defaultLimit = 5000, maxLimit = 10000))
        assertEquals(100, parseObservabilityLimit(parametersOf("limit" to listOf("150")), defaultLimit = 20, maxLimit = 100))
    }

    @Test
    fun `offset negatives become zero`() {
        assertEquals(0L, parseObservabilityOffsetLong(parametersOf("offset" to listOf("-3"))))
        assertEquals(0L, parseObservabilityOffsetLong(parametersOf("offset" to listOf("xyz"))))
        assertEquals(7L, parseObservabilityOffsetLong(parametersOf("offset" to listOf("7"))))
        assertEquals(0, parseObservabilityOffsetInt(parametersOf("offset" to listOf("-3"))))
        assertEquals(0, parseObservabilityOffsetInt(parametersOf("offset" to listOf("xyz"))))
        assertEquals(3, parseObservabilityOffsetInt(parametersOf("offset" to listOf("3"))))
    }

    @Test
    fun `action and search blank means no filter`() {
        assertNull(parseObservabilityAction(parametersOf("action" to listOf("   "))))
        assertNull(parseObservabilityAction(parametersOf()))
        assertEquals("LOGIN", parseObservabilityAction(parametersOf("action" to listOf(" LOGIN "))))
        assertNull(parseObservabilitySearch(parametersOf("q" to listOf("   "))))
        assertNull(parseObservabilitySearch(parametersOf()))
        assertEquals("hi", parseObservabilitySearch(parametersOf("q" to listOf("  hi  "))))
        assertEquals(80, parseObservabilitySearch(parametersOf("q" to listOf("x".repeat(200))))!!.length)
    }
}
