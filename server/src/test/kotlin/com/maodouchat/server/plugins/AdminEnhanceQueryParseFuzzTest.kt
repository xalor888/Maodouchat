package com.maodouchat.server.plugins

import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdminEnhanceQueryParseFuzzTest {

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
        2 -> random.nextLong(-5000, 5000).toString()
        3 -> random.nextDouble(-50.0, 25000.0).toString()
        4 -> randomString(random, random.nextInt(1, 40))
        5 -> "abc"
        6 -> "0x" + random.nextInt(1, 9999).toString()
        7 -> listOf("1h", "24h", "7d", "5h", "24H", " 1h ").random(random)
        else -> "x"
    }

    private fun randomParams(random: Random, name: String): Parameters {
        val pairs = mutableListOf<Pair<String, List<String>>>()
        if (random.nextBoolean()) pairs += name to listOf(randomParamValue(random))
        return parametersOf(*pairs.toTypedArray())
    }

    // 旧内联写法的逐字复刻，只供等价性比对。
    private fun oldOptionalTrimmedWay(params: Parameters, name: String): String? =
        params[name]?.trim()?.takeIf { it.isNotBlank() }

    private fun oldUpperTokenWay(params: Parameters, name: String, maxLength: Int): String? =
        params[name]?.trim()?.uppercase()?.take(maxLength)

    private fun oldRequiredLongWay(params: Parameters, name: String): Long? =
        params[name]?.toLongOrNull()

    private fun oldRateLimitRangeWay(params: Parameters): String? {
        val range = params["range"]?.trim()?.lowercase() ?: return "24h"
        return range.takeIf { it == "1h" || it == "24h" || it == "7d" }
    }

    @Test
    fun `enhance query parse old and new agree on seeded random params`() {
        val random = Random(34177)
        repeat(ITERATIONS) { i ->
            val userIdParams = randomParams(random, "userId")
            assertEquals(oldOptionalTrimmedWay(userIdParams, "userId"), parseOptionalTrimmed(userIdParams, "userId"), "iteration $i: optional trimmed must match")
            val scopeParams = randomParams(random, "scope")
            assertEquals(oldUpperTokenWay(scopeParams, "scope", 30), parseUpperToken(scopeParams, "scope", 30), "iteration $i: upper token 30 must match")
            val statusParams = randomParams(random, "status")
            assertEquals(oldUpperTokenWay(statusParams, "status", 20), parseUpperToken(statusParams, "status", 20), "iteration $i: upper token 20 must match")
            val fromMsParams = randomParams(random, "fromMs")
            assertEquals(oldRequiredLongWay(fromMsParams, "fromMs"), parseRequiredLong(fromMsParams, "fromMs"), "iteration $i: required long must match")
            val rangeParams = randomParams(random, "range")
            assertEquals(oldRateLimitRangeWay(rangeParams), parseRateLimitRange(rangeParams), "iteration $i: rate limit range must match")
        }
    }

    @Test
    fun `enhance query parse boundaries are pinned`() {
        // userId：trim 后空白即无筛选
        assertEquals("x", parseOptionalTrimmed(parametersOf("userId" to listOf("  x ")), "userId"))
        assertNull(parseOptionalTrimmed(parametersOf("userId" to listOf("   ")), "userId"))
        assertNull(parseOptionalTrimmed(parametersOf(), "userId"))
        // scope/status：转大写并截断
        assertEquals("ADMIN_AUDIT", parseUpperToken(parametersOf("scope" to listOf("admin_audit")), "scope", 30))
        assertEquals(30, parseUpperToken(parametersOf("scope" to listOf("a".repeat(40))), "scope", 30)!!.length)
        assertEquals("", parseUpperToken(parametersOf("status" to listOf("   ")), "status", 20))
        assertNull(parseUpperToken(parametersOf(), "status", 20))
        // fromMs/toMs：缺参或非法回 null
        assertEquals(123L, parseRequiredLong(parametersOf("fromMs" to listOf("123")), "fromMs"))
        assertNull(parseRequiredLong(parametersOf("fromMs" to listOf("abc")), "fromMs"))
        assertNull(parseRequiredLong(parametersOf(), "fromMs"))
        // range：缺省 24h，非法回 null
        assertEquals("24h", parseRateLimitRange(parametersOf()))
        assertEquals("1h", parseRateLimitRange(parametersOf("range" to listOf(" 1H "))))
        assertEquals("7d", parseRateLimitRange(parametersOf("range" to listOf("7d"))))
        assertNull(parseRateLimitRange(parametersOf("range" to listOf("5h"))))
        assertNull(parseRateLimitRange(parametersOf("range" to listOf(""))))
    }
}
