package com.maodouchat.server.plugins

import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminFilterQueryParseFuzzTest {

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
        6 -> "false"
        7 -> "-5"
        8 -> "9223372036854775807"
        else -> randomString(random, random.nextInt(1, 20))
    }

    private fun randomParams(random: Random, vararg names: String): Parameters {
        val pairs = mutableListOf<Pair<String, List<String>>>()
        names.forEach { name ->
            if (random.nextBoolean()) pairs += name to listOf(randomParamValue(random))
        }
        return parametersOf(*pairs.toTypedArray())
    }

    // 旧内联写法的逐字复刻，只供等价性比对。
    private fun oldLimitWay(params: Parameters): Int? =
        params["limit"]?.toIntOrNull()

    private fun oldOffsetWay(params: Parameters): Long? =
        params["offset"]?.toLongOrNull()

    private fun oldFeatureWay(params: Parameters): String? =
        params["feature"]

    private fun oldUserWay(params: Parameters): String? =
        params["userId"] ?: params["q"]

    private fun oldGroupOnlyWay(params: Parameters): Boolean =
        params["groupOnly"] != "false"

    private fun oldStatusWay(params: Parameters): String? =
        params["status"]?.takeIf { it.isNotBlank() }

    @Test
    fun `admin ai-usage chats and posts query parse old and new agree on seeded random params`() {
        val random = Random(35311)
        repeat(ITERATIONS) { i ->
            val auditParams = randomParams(random, "limit", "offset", "feature", "userId", "q")
            assertEquals(
                oldLimitWay(auditParams),
                parseOptionalInt(auditParams, "limit"),
                "iteration " + i + ": limit must match",
            )
            assertEquals(
                oldOffsetWay(auditParams),
                parseOptionalLong(auditParams, "offset"),
                "iteration " + i + ": offset must match",
            )
            assertEquals(
                oldFeatureWay(auditParams),
                parseRawOrNull(auditParams, "feature"),
                "iteration " + i + ": feature must match",
            )
            assertEquals(
                oldUserWay(auditParams),
                parseFirstPresent(auditParams, "userId", "q"),
                "iteration " + i + ": user filter must match",
            )
            val flagParams = randomParams(random, "groupOnly")
            assertEquals(
                oldGroupOnlyWay(flagParams),
                parseDefaultTrueFlag(flagParams, "groupOnly"),
                "iteration " + i + ": groupOnly must match",
            )
            val statusParams = randomParams(random, "status")
            assertEquals(
                oldStatusWay(statusParams),
                parseNonBlankOrNull(statusParams, "status"),
                "iteration " + i + ": status must match",
            )
        }
    }

    @Test
    fun `admin filter query parse boundaries are pinned`() {
        // userId 优先于 q，两者都给时取 userId。
        val both = parametersOf("userId" to listOf("u1"), "q" to listOf("search"))
        assertEquals("u1", parseFirstPresent(both, "userId", "q"))
        // groupOnly 只有字面量 "false" 关闭；"FALSE"、空串、缺省都是开。
        assertFalse(parseDefaultTrueFlag(parametersOf("groupOnly" to listOf("false")), "groupOnly"))
        assertTrue(parseDefaultTrueFlag(parametersOf("groupOnly" to listOf("FALSE")), "groupOnly"))
        assertTrue(parseDefaultTrueFlag(parametersOf("groupOnly" to listOf("")), "groupOnly"))
        assertTrue(parseDefaultTrueFlag(parametersOf(), "groupOnly"))
        // status 不 trim，原样透出。
        assertEquals(" x ", parseNonBlankOrNull(parametersOf("status" to listOf(" x ")), "status"))
        assertNull(parseNonBlankOrNull(parametersOf("status" to listOf("   ")), "status"))
        assertNull(parseNonBlankOrNull(parametersOf(), "status"))
        // feature 缺省回 null，下游区分 null/字符串。
        assertNull(parseRawOrNull(parametersOf(), "feature"))
    }
}
