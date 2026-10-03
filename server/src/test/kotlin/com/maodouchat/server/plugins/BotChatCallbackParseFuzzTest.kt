package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BotChatCallbackParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "messageId",
            "botUserId",
            "callbackData",
        )
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotChatCallbackFieldsResult =
        parseBotChatCallbackFields(obj)

    private fun okOf(messageId: String, botUserId: String, callbackData: String): BotChatCallbackFields =
        (parseOf(JsonObject(mapOf(
            "messageId" to JsonPrimitive(messageId),
            "botUserId" to JsonPrimitive(botUserId),
            "callbackData" to JsonPrimitive(callbackData),
        ))) as BotChatCallbackFieldsResult.Ok).fields

    private fun randomScalar(random: Random): JsonElement = when (random.nextInt(7)) {
        0 -> JsonPrimitive(random.nextBoolean())
        1 -> JsonPrimitive(random.nextInt(-1000, 1000))
        2 -> JsonPrimitive(random.nextDouble(-1000.0, 1000.0))
        3 -> JsonPrimitive(randomString(random, random.nextInt(0, 40)))
        4 -> JsonNull
        5 -> JsonArray(List(random.nextInt(0, 4)) { randomScalar(random) })
        else -> JsonObject(mapOf(randomName(random) to randomScalar(random)))
    }

    private fun randomString(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?~"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomName(random: Random): String {
        var name: String
        do {
            name = "fuzz_" + randomString(random, random.nextInt(3, 12)).replace(" ", "_")
        } while (name in KNOWN_FIELD_NAMES)
        return name
    }

    @Test
    fun fuzzUnknownKeysIgnored() {
        val random = Random(2026100378)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            // 基 payload：三字段恒为 "m<i>" / "b<i>" / "d<i>"（恒等断言只看不被未知键污染）。
            val expectedMessageId = "m" + i
            val expectedBotUserId = "b" + i
            val expectedCallbackData = "d" + i
            base["messageId"] = JsonPrimitive(expectedMessageId)
            base["botUserId"] = JsonPrimitive(expectedBotUserId)
            base["callbackData"] = JsonPrimitive(expectedCallbackData)
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val viaParse = parseOf(JsonObject(base))
            assertTrue(viaParse is BotChatCallbackFieldsResult.Ok, "注入未知键后仍应为 Ok，迭代 " + i)
            assertEquals(expectedMessageId, viaParse.fields.messageId, "未知键不得污染 messageId，迭代 " + i)
            assertEquals(expectedBotUserId, viaParse.fields.botUserId, "未知键不得污染 botUserId，迭代 " + i)
            assertEquals(expectedCallbackData, viaParse.fields.callbackData, "未知键不得污染 callbackData，迭代 " + i)
        }
    }

    @Test
    fun requiredSemantics() {
        // 缺席 → Invalid。
        assertTrue(
            parseOf(JsonObject(emptyMap())) is BotChatCallbackFieldsResult.Invalid,
            "全缺席应为 Invalid",
        )
        // 单个缺席 → Invalid（逐个字段）。
        assertTrue(
            parseOf(JsonObject(mapOf(
                "botUserId" to JsonPrimitive("b1"),
                "callbackData" to JsonPrimitive("d1"),
            ))) is BotChatCallbackFieldsResult.Invalid,
            "messageId 缺席应为 Invalid",
        )
        assertTrue(
            parseOf(JsonObject(mapOf(
                "messageId" to JsonPrimitive("m1"),
                "callbackData" to JsonPrimitive("d1"),
            ))) is BotChatCallbackFieldsResult.Invalid,
            "botUserId 缺席应为 Invalid",
        )
        assertTrue(
            parseOf(JsonObject(mapOf(
                "messageId" to JsonPrimitive("m1"),
                "botUserId" to JsonPrimitive("b1"),
            ))) is BotChatCallbackFieldsResult.Invalid,
            "callbackData 缺席应为 Invalid",
        )
        // 空字符串 → Invalid。
        assertTrue(
            parseOf(JsonObject(mapOf(
                "messageId" to JsonPrimitive(""),
                "botUserId" to JsonPrimitive("b1"),
                "callbackData" to JsonPrimitive("d1"),
            ))) is BotChatCallbackFieldsResult.Invalid,
            "messageId 为空应为 Invalid",
        )
        // 纯空白 → Invalid（无 trim，全空白直接判空）。
        assertTrue(
            parseOf(JsonObject(mapOf(
                "messageId" to JsonPrimitive("m1"),
                "botUserId" to JsonPrimitive("   "),
                "callbackData" to JsonPrimitive("d1"),
            ))) is BotChatCallbackFieldsResult.Invalid,
            "botUserId 纯空白应为 Invalid",
        )
        // 合法 → Ok。
        val fields = okOf("m1", "b1", "d1")
        assertEquals("m1", fields.messageId)
        assertEquals("b1", fields.botUserId)
        assertEquals("d1", fields.callbackData)
    }

    @Test
    fun explicitNullIsLiteral() {
        // 三键显式 null → 字面量 "null"，非空 → Ok（原处理器逐字语义）。
        val viaParse = parseOf(JsonObject(mapOf(
            "messageId" to JsonNull,
            "botUserId" to JsonNull,
            "callbackData" to JsonNull,
        )))
        assertTrue(viaParse is BotChatCallbackFieldsResult.Ok, "显式 null 应得字面量而为 Ok")
        assertEquals("null", viaParse.fields.messageId)
        assertEquals("null", viaParse.fields.botUserId)
        assertEquals("null", viaParse.fields.callbackData)
    }

    @Test
    fun lengthCaps() {
        val at80 = "x".repeat(80)
        val over80 = "x".repeat(81)
        val at128 = "y".repeat(128)
        val over129 = "y".repeat(129)
        // 边界恒等：上限以内（含上限）通过。
        assertTrue(
            parseOf(JsonObject(mapOf(
                "messageId" to JsonPrimitive(at80),
                "botUserId" to JsonPrimitive(at80),
                "callbackData" to JsonPrimitive(at128),
            ))) is BotChatCallbackFieldsResult.Ok,
            "上限以内应为 Ok",
        )
        // messageId 超 80 → Invalid。
        assertTrue(
            parseOf(JsonObject(mapOf(
                "messageId" to JsonPrimitive(over80),
                "botUserId" to JsonPrimitive("b1"),
                "callbackData" to JsonPrimitive("d1"),
            ))) is BotChatCallbackFieldsResult.Invalid,
            "messageId 超 80 应为 Invalid",
        )
        // botUserId 超 80 → Invalid。
        assertTrue(
            parseOf(JsonObject(mapOf(
                "messageId" to JsonPrimitive("m1"),
                "botUserId" to JsonPrimitive(over80),
                "callbackData" to JsonPrimitive("d1"),
            ))) is BotChatCallbackFieldsResult.Invalid,
            "botUserId 超 80 应为 Invalid",
        )
        // callbackData 超 128 → Invalid。
        assertTrue(
            parseOf(JsonObject(mapOf(
                "messageId" to JsonPrimitive("m1"),
                "botUserId" to JsonPrimitive("b1"),
                "callbackData" to JsonPrimitive(over129),
            ))) is BotChatCallbackFieldsResult.Invalid,
            "callbackData 超 128 应为 Invalid",
        )
    }

    @Test
    fun noTrim() {
        // 前后空格原样保留仍通过（isBlank 只判空，无 trim）。
        val fields = okOf(" m1 ", " b1 ", " d1 ")
        assertEquals(" m1 ", fields.messageId)
        assertEquals(" b1 ", fields.botUserId)
        assertEquals(" d1 ", fields.callbackData)
    }

    @Test
    fun nonStringPrimitiveContent() {
        // 非字符串 primitive 走 .content：数字/布尔原样通过（原处理器逐字语义）。
        val viaParse = parseOf(JsonObject(mapOf(
            "messageId" to JsonPrimitive(123),
            "botUserId" to JsonPrimitive(true),
            "callbackData" to JsonPrimitive(45.6),
        )))
        assertTrue(viaParse is BotChatCallbackFieldsResult.Ok, "非字符串 primitive 应走 .content 为 Ok")
        assertEquals("123", viaParse.fields.messageId)
        assertEquals("true", viaParse.fields.botUserId)
        assertEquals("45.6", viaParse.fields.callbackData)
    }

    @Test
    fun badTypeLoudFail() {
        // 对象 / 数组型在 ?.jsonPrimitive 处抛 IllegalArgumentException（逐字语义）。
        listOf(
            "messageId" to JsonObject(mapOf("x" to JsonPrimitive(1))),
            "botUserId" to JsonArray(listOf(JsonPrimitive(1))),
            "callbackData" to JsonObject(emptyMap()),
        ).forEach { (badKey, badValue) ->
            val obj = JsonObject(mapOf(
                "messageId" to JsonPrimitive("m1"),
                "botUserId" to JsonPrimitive("b1"),
                "callbackData" to JsonPrimitive("d1"),
                badKey to badValue,
            ))
            assertFailsWith<IllegalArgumentException>("坏类型字段 " + badKey + " 应大声失败") {
                parseOf(obj)
            }
        }
    }

    @Test
    fun nearMissFieldNamesIgnored() {
        // 近似字段名仍按未知键忽略：真实字段缺席 → Invalid。
        assertTrue(
            parseOf(JsonObject(mapOf(
                "MessageId" to JsonPrimitive("m1"),
                "MESSAGEID" to JsonPrimitive("m1"),
                "message_id" to JsonPrimitive("m1"),
                "messageId2" to JsonPrimitive("m1"),
                "BotUserId" to JsonPrimitive("b1"),
                "bot_user_id" to JsonPrimitive("b1"),
                "callbackdata" to JsonPrimitive("d1"),
                "CallbackData" to JsonPrimitive("d1"),
                "callbackData2" to JsonPrimitive("d1"),
            ))) is BotChatCallbackFieldsResult.Invalid,
            "近似字段名应按未知键忽略，真实字段缺席应为 Invalid",
        )
    }
}
