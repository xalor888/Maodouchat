package com.maodouchat.server.plugins

import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminManagementQueryParseFuzzTest {

    private companion object {
        private const val ITERATIONS = 150
    }

    private fun randomString(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomParamValue(random: Random): String = when (random.nextInt(10)) {
        0 -> ""
        1 -> "   "
        2 -> "  abc  "
        3 -> "1"
        4 -> "0"
        5 -> "true"
        6 -> "TRUE"
        7 -> "-5"
        8 -> "99999"
        else -> randomString(random, random.nextInt(1, 20))
    }

    private fun randomParams(random: Random, name: String): Parameters {
        val pairs = mutableListOf<Pair<String, List<String>>>()
        if (random.nextBoolean()) pairs += name to listOf(randomParamValue(random))
        return parametersOf(*pairs.toTypedArray())
    }

    // 旧内联写法的逐字复刻，只供等价性比对。
    private fun oldFlagOneWay(params: Parameters, name: String): Boolean =
        params[name] == "1"

    private fun oldTrimmedOrEmptyWay(params: Parameters, name: String): String =
        params[name]?.trim().orEmpty()

    private fun oldIntOffsetWay(params: Parameters): Int =
        (params["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

    private fun oldLimit200Way(params: Parameters): Int =
        (params["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)

    private fun oldLimit500Way(params: Parameters): Int =
        (params["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 500)

    @Test
    fun `management query parse old and new agree on seeded random params`() {
        val random = Random(34310)
        repeat(ITERATIONS) { i ->
            val flagParams = randomParams(random, "includeRevoked")
            assertEquals(oldFlagOneWay(flagParams, "includeRevoked"), parseQueryFlagOne(flagParams, "includeRevoked"), "iteration $i: flag one must match")
            val qParams = randomParams(random, "q")
            assertEquals(oldTrimmedOrEmptyWay(qParams, "q"), parseTrimmedOrEmpty(qParams, "q"), "iteration $i: trimmed or empty must match")
            val offsetParams = randomParams(random, "offset")
            assertEquals(oldIntOffsetWay(offsetParams), parseAdminListIntOffset(offsetParams), "iteration $i: int offset must match")
            val limitParams = randomParams(random, "limit")
            assertEquals(oldLimit200Way(limitParams), parseAdminListLimit(limitParams), "iteration $i: limit 200 must match")
            assertEquals(oldLimit500Way(limitParams), parseAdminListLimit(limitParams, maxLimit = 500), "iteration $i: limit 500 must match")
        }
    }

    @Test
    fun `management query parse boundaries are pinned`() {
        // "1" 开关：只有字面量 "1" 算开。
        assertTrue(parseQueryFlagOne(parametersOf("includeRevoked", "1"), "includeRevoked"))
        assertFalse(parseQueryFlagOne(parametersOf("includeRevoked", "true"), "includeRevoked"))
        assertFalse(parseQueryFlagOne(parametersOf("includeRevoked", "TRUE"), "includeRevoked"))
        assertFalse(parseQueryFlagOne(parametersOf("includeRevoked", "0"), "includeRevoked"))
        assertFalse(parseQueryFlagOne(parametersOf(), "includeRevoked"))

        // trim：前后空格去掉，空串/缺省都回空串。
        assertEquals("x", parseTrimmedOrEmpty(parametersOf("q", "  x  "), "q"))
        assertEquals("", parseTrimmedOrEmpty(parametersOf("q", "   "), "q"))
        assertEquals("", parseTrimmedOrEmpty(parametersOf(), "q"))

        // Int offset：非法/负数回 0。
        assertEquals(15, parseAdminListIntOffset(parametersOf("offset", "15")))
        assertEquals(0, parseAdminListIntOffset(parametersOf("offset", "-3")))
        assertEquals(0, parseAdminListIntOffset(parametersOf("offset", "abc")))
        assertEquals(0, parseAdminListIntOffset(parametersOf()))

        // limit 上限：默认 200，bot 命令日志 500。
        assertEquals(200, parseAdminListLimit(parametersOf("limit", "9999")))
        assertEquals(1, parseAdminListLimit(parametersOf("limit", "0")))
        assertEquals(50, parseAdminListLimit(parametersOf()))
        assertEquals(500, parseAdminListLimit(parametersOf("limit", "9999"), maxLimit = 500))
        assertEquals(300, parseAdminListLimit(parametersOf("limit", "300"), maxLimit = 500))
    }
}
