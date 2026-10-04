package com.maodouchat.server.plugins

import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdminListQueryParseFuzzTest {

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
        if (random.nextBoolean()) pairs += "status" to listOf(randomParamValue(random))
        return parametersOf(*pairs.toTypedArray())
    }

    // 旧内联写法的逐字复刻，只供等价性比对。
    private fun oldLimitWay(params: Parameters): Int =
        (params["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)

    private fun oldOffsetWay(params: Parameters): Long =
        (params["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)

    private fun oldSearchWay(params: Parameters): String? =
        params["q"]?.trim()?.takeIf { it.isNotBlank() }

    private fun oldStatusWay(params: Parameters): String? =
        params["status"]?.trim()?.takeIf { it.isNotBlank() }

    private fun oldBotsLimitWay(params: Parameters): Int =
        params["limit"]?.toIntOrNull() ?: 50

    private fun oldBotsOffsetWay(params: Parameters): Int =
        params["offset"]?.toIntOrNull() ?: 0

    @Test
    fun `list params old and new agree on seeded random params`() {
        val random = Random(22041)
        repeat(ITERATIONS) { i ->
            val params = randomParams(random)
            assertEquals(oldLimitWay(params), parseAdminListLimit(params), "iteration " + i + ": limit must match")
            assertEquals(oldOffsetWay(params), parseAdminListOffset(params), "iteration " + i + ": offset must match")
            assertEquals(oldSearchWay(params), parseAdminListSearch(params), "iteration " + i + ": search must match")
            assertEquals(oldStatusWay(params), parseAdminListStatus(params), "iteration " + i + ": status must match")
            assertEquals(oldBotsLimitWay(params), parseAdminBotsListLimit(params), "iteration " + i + ": bots limit must match")
            assertEquals(oldBotsOffsetWay(params), parseAdminBotsListOffset(params), "iteration " + i + ": bots offset must match")
        }
    }

    @Test
    fun `limit clamping is pinned`() {
        assertEquals(1, parseAdminListLimit(parametersOf("limit" to listOf("0"))))
        assertEquals(200, parseAdminListLimit(parametersOf("limit" to listOf("201"))))
        assertEquals(50, parseAdminListLimit(parametersOf("limit" to listOf("abc"))))
        assertEquals(50, parseAdminListLimit(parametersOf()))
        assertEquals(1, parseAdminListLimit(parametersOf("limit" to listOf("-5"))))
        assertEquals(10000, parseAdminListLimit(parametersOf("limit" to listOf("999999")), defaultLimit = 2000, maxLimit = 10000))
        assertEquals(2000, parseAdminListLimit(parametersOf("limit" to listOf("abc")), defaultLimit = 2000, maxLimit = 10000))
    }

    @Test
    fun `offset negatives become zero`() {
        assertEquals(0L, parseAdminListOffset(parametersOf("offset" to listOf("-3"))))
        assertEquals(0L, parseAdminListOffset(parametersOf("offset" to listOf("xyz"))))
        assertEquals(0L, parseAdminListOffset(parametersOf()))
        assertEquals(7L, parseAdminListOffset(parametersOf("offset" to listOf("7"))))
    }

    @Test
    fun `search and status blank means no filter`() {
        assertNull(parseAdminListSearch(parametersOf("q" to listOf("   "))))
        assertNull(parseAdminListSearch(parametersOf()))
        assertEquals("hi", parseAdminListSearch(parametersOf("q" to listOf("  hi  "))))
        assertNull(parseAdminListStatus(parametersOf("status" to listOf(""))))
        assertEquals("banned", parseAdminListStatus(parametersOf("status" to listOf(" banned "))))
    }

    @Test
    fun `bots list params stay unclamped`() {
        assertEquals(99999, parseAdminBotsListLimit(parametersOf("limit" to listOf("99999"))))
        assertEquals(50, parseAdminBotsListLimit(parametersOf("limit" to listOf("abc"))))
        assertEquals(0, parseAdminBotsListOffset(parametersOf("offset" to listOf("xyz"))))
        assertEquals(3, parseAdminBotsListOffset(parametersOf("offset" to listOf("3"))))
    }
}
