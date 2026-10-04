package com.maodouchat.server.plugins

import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentCallKeyQueryParseFuzzTest {

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
    private fun oldOffsetWay(params: Parameters): Long? =
        params["offset"]?.toLongOrNull()

    private fun oldChatIdWay(params: Parameters): String =
        params["chatId"].orEmpty()

    private fun oldMessageIdWay(params: Parameters): String =
        params["messageId"].orEmpty()

    private fun oldCallIdWay(params: Parameters): String =
        params["callId"].orEmpty()

    private fun oldOffersOnlyWay(params: Parameters): Boolean =
        params["offersOnly"]?.toBooleanStrictOrNull() == true

    private fun oldCurrentDeviceIdWay(params: Parameters): Int? =
        params["currentDeviceId"]?.toIntOrNull()

    private fun oldDeviceIdWay(params: Parameters): Int =
        params["deviceId"]?.toIntOrNull() ?: 1

    @Test
    fun `attachment call signaling signal key query parse old and new agree on seeded random params`() {
        val random = Random(35210)
        repeat(ITERATIONS) { i ->
            val offsetParams = randomParams(random, "offset")
            assertEquals(oldOffsetWay(offsetParams), parseOptionalLong(offsetParams, "offset"), "iteration " + i + ": offset must match")
            val chatParams = randomParams(random, "chatId")
            assertEquals(oldChatIdWay(chatParams), parseRawOrEmpty(chatParams, "chatId"), "iteration " + i + ": chatId must match")
            val msgParams = randomParams(random, "messageId")
            assertEquals(oldMessageIdWay(msgParams), parseRawOrEmpty(msgParams, "messageId"), "iteration " + i + ": messageId must match")
            val callParams = randomParams(random, "callId")
            assertEquals(oldCallIdWay(callParams), parseRawOrEmpty(callParams, "callId"), "iteration " + i + ": callId must match")
            val offersParams = randomParams(random, "offersOnly")
            assertEquals(oldOffersOnlyWay(offersParams), parseStrictBooleanFlag(offersParams, "offersOnly"), "iteration " + i + ": offersOnly must match")
            val deviceParams = randomParams(random, "currentDeviceId")
            assertEquals(oldCurrentDeviceIdWay(deviceParams), parseOptionalInt(deviceParams, "currentDeviceId"), "iteration " + i + ": currentDeviceId must match")
            val sealedParams = randomParams(random, "deviceId")
            assertEquals(oldDeviceIdWay(sealedParams), parseIntOrDefault(sealedParams, "deviceId", 1), "iteration " + i + ": deviceId must match")
        }
    }

    @Test
    fun `attachment call signaling signal key query parse boundaries are pinned`() {
        // raw orEmpty：不 trim，原样透出；缺省回空串。
        assertEquals("", parseRawOrEmpty(parametersOf(), "chatId"))
        assertEquals("  ", parseRawOrEmpty(parametersOf("chatId", "  "), "chatId"))
        assertEquals("  abc  ", parseRawOrEmpty(parametersOf("chatId", "  abc  "), "chatId"))

        // offersOnly：只有字面量 "true" 算开。
        assertTrue(parseStrictBooleanFlag(parametersOf("offersOnly", "true"), "offersOnly"))
        assertFalse(parseStrictBooleanFlag(parametersOf("offersOnly", "True"), "offersOnly"))
        assertFalse(parseStrictBooleanFlag(parametersOf("offersOnly", "1"), "offersOnly"))
        assertFalse(parseStrictBooleanFlag(parametersOf(), "offersOnly"))

        // offset：缺省/非法回 null（上传会话偏移由仓库层按“未指定”处理）。
        assertNull(parseOptionalLong(parametersOf(), "offset"))
        assertNull(parseOptionalLong(parametersOf("offset", "abc"), "offset"))
        assertNull(parseOptionalLong(parametersOf("offset", "99999999999999999999"), "offset"))
        assertEquals(0L, parseOptionalLong(parametersOf("offset", "0"), "offset"))
        assertEquals(-5L, parseOptionalLong(parametersOf("offset", "-5"), "offset"))

        // currentDeviceId：缺省/非法回 null；群管理路由的 1..255 范围校验仍在处理器里。
        assertNull(parseOptionalInt(parametersOf(), "currentDeviceId"))
        assertNull(parseOptionalInt(parametersOf("currentDeviceId", "abc"), "currentDeviceId"))
        assertNull(parseOptionalInt(parametersOf("currentDeviceId", "2147483648"), "currentDeviceId"))
        assertEquals(255, parseOptionalInt(parametersOf("currentDeviceId", "255"), "currentDeviceId"))
        assertEquals(-1, parseOptionalInt(parametersOf("currentDeviceId", "-1"), "currentDeviceId"))

        // sealed-sender deviceId：缺省/非法回 1，负数原样透出。
        assertEquals(1, parseIntOrDefault(parametersOf(), "deviceId", 1))
        assertEquals(1, parseIntOrDefault(parametersOf("deviceId", "abc"), "deviceId", 1))
        assertEquals(1, parseIntOrDefault(parametersOf("deviceId", "2147483648"), "deviceId", 1))
        assertEquals(7, parseIntOrDefault(parametersOf("deviceId", "7"), "deviceId", 1))
        assertEquals(-3, parseIntOrDefault(parametersOf("deviceId", "-3"), "deviceId", 1))
    }
}
