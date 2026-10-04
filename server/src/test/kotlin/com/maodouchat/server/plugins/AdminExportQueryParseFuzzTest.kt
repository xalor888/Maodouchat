package com.maodouchat.server.plugins

import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class AdminExportQueryParseFuzzTest {

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
        2 -> random.nextInt(-500, 25000).toString()
        3 -> (random.nextDouble(-50.0, 25000.0)).toString()
        4 -> randomString(random, random.nextInt(1, 30))
        5 -> "abc"
        6 -> "0x" + random.nextInt(1, 9999).toString()
        else -> random.nextLong(-5, 5).toString() + ".5"
    }

    private fun randomParams(random: Random): Parameters {
        val pairs = mutableListOf<Pair<String, List<String>>>()
        if (random.nextBoolean()) pairs += "limit" to listOf(randomParamValue(random))
        return parametersOf(*pairs.toTypedArray())
    }

    // 旧内联写法的逐字复刻，只供等价性比对。
    private fun oldLargeLimitWay(params: Parameters): Int =
        (params["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)

    private fun oldBotsLimitWay(params: Parameters): Int =
        (params["limit"]?.toIntOrNull() ?: 2000).coerceIn(1, 20000)

    private fun oldMetaLimitWay(params: Parameters): Int =
        (params["limit"]?.toIntOrNull() ?: 2000).coerceIn(1, 10000)

    @Test
    fun `export limit old and new agree on seeded random params`() {
        val random = Random(33977)
        repeat(ITERATIONS) { i ->
            val params = randomParams(random)
            assertEquals(oldLargeLimitWay(params), parseExportLimit(params, 5000, 20000), "iteration " + i + ": large limit must match")
            assertEquals(oldBotsLimitWay(params), parseExportLimit(params, 2000, 20000), "iteration " + i + ": bots limit must match")
            assertEquals(oldMetaLimitWay(params), parseExportLimit(params, 2000, 10000), "iteration " + i + ": meta limit must match")
        }
    }

    @Test
    fun `export limit boundaries are pinned`() {
        assertEquals(1, parseExportLimit(parametersOf("limit" to listOf("-3")), 5000, 20000))
        assertEquals(20000, parseExportLimit(parametersOf("limit" to listOf("99999")), 5000, 20000))
        assertEquals(5000, parseExportLimit(parametersOf("limit" to listOf("notanumber")), 5000, 20000))
        assertEquals(5000, parseExportLimit(parametersOf(), 5000, 20000))
        assertEquals(10000, parseExportLimit(parametersOf("limit" to listOf("100000")), 2000, 10000))
        assertEquals(2000, parseExportLimit(parametersOf(), 2000, 10000))
    }
}
