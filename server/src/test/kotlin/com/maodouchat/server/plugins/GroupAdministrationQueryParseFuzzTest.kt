package com.maodouchat.server.plugins

import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupAdministrationQueryParseFuzzTest {

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
        6 -> "-5"
        7 -> "99999"
        8 -> "9223372036854775807"
        else -> randomString(random, random.nextInt(1, 20))
    }

    private fun randomParams(random: Random, name: String): Parameters {
        val pairs = mutableListOf<Pair<String, List<String>>>()
        if (random.nextBoolean()) pairs += name to listOf(randomParamValue(random))
        return parametersOf(*pairs.toTypedArray())
    }

    // 旧内联写法的逐字复刻，只供等价性比对。
    private fun oldRotateWay(params: Parameters): Boolean =
        params["rotate"] == "1"

    private fun oldAuditLimitWay(params: Parameters): Int =
        (params["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)

    private fun oldAuditOffsetWay(params: Parameters): Int =
        (params["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

    private fun oldEpochWay(params: Parameters): Long? =
        params["epoch"]?.toLongOrNull()

    @Test
    fun `group administration query parse old and new agree on seeded random params`() {
        val random = Random(34410)
        repeat(ITERATIONS) { i ->
            val rotateParams = randomParams(random, "rotate")
            assertEquals(oldRotateWay(rotateParams), parseQueryFlagOne(rotateParams, "rotate"), "iteration $i: rotate must match")
            val limitParams = randomParams(random, "limit")
            assertEquals(oldAuditLimitWay(limitParams), parseAdminListLimit(limitParams, maxLimit = 100), "iteration $i: audit limit must match")
            val offsetParams = randomParams(random, "offset")
            assertEquals(oldAuditOffsetWay(offsetParams), parseAdminListIntOffset(offsetParams), "iteration $i: audit offset must match")
            val epochParams = randomParams(random, "epoch")
            assertEquals(oldEpochWay(epochParams), parseOptionalLong(epochParams, "epoch"), "iteration $i: epoch must match")
        }
    }

    @Test
    fun `group administration query parse boundaries are pinned`() {
        // rotate：只有字面量 "1" 算开（邀请链接轮换）。
        assertTrue(parseQueryFlagOne(parametersOf("rotate", "1"), "rotate"))
        assertFalse(parseQueryFlagOne(parametersOf("rotate", "true"), "rotate"))
        assertFalse(parseQueryFlagOne(parametersOf("rotate", "0"), "rotate"))
        assertFalse(parseQueryFlagOne(parametersOf(), "rotate"))

        // audit 列表：limit 默认 50、上限 100；offset 非法/负数回 0。
        assertEquals(50, parseAdminListLimit(parametersOf(), maxLimit = 100))
        assertEquals(100, parseAdminListLimit(parametersOf("limit", "9999"), maxLimit = 100))
        assertEquals(1, parseAdminListLimit(parametersOf("limit", "-3"), maxLimit = 100))
        assertEquals(0, parseAdminListIntOffset(parametersOf("offset", "abc")))
        assertEquals(0, parseAdminListIntOffset(parametersOf()))

        // epoch：缺省/非法回 null（未指定），溢出也算非法。
        assertNull(parseOptionalLong(parametersOf(), "epoch"))
        assertNull(parseOptionalLong(parametersOf("epoch", "abc"), "epoch"))
        assertNull(parseOptionalLong(parametersOf("epoch", "99999999999999999999"), "epoch"))
        assertEquals(0L, parseOptionalLong(parametersOf("epoch", "0"), "epoch"))
        assertEquals(-5L, parseOptionalLong(parametersOf("epoch", "-5"), "epoch"))
        assertEquals(Long.MAX_VALUE, parseOptionalLong(parametersOf("epoch", "9223372036854775807"), "epoch"))
    }
}
