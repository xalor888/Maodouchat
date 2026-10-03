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

class BotStarMessageParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("messageId")
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotStarMessageFieldsResult =
        parseBotStarMessageFields(obj)

    private fun okOf(obj: JsonObject): BotStarMessageFields =
        (parseOf(obj) as BotStarMessageFieldsResult.Ok).fields

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
    fun fuzzUnknownKeysIgnoredAndKnownSemanticsHold() {
        val random = Random(20261001)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            // 五分之一用整数钉住 content→toString，其余用字符串。
            val expectedMessageId = if (i % 5 == 4) {
                base["messageId"] = JsonPrimitive(9000 + i)
                (9000 + i).toString()
            } else {
                base["messageId"] = JsonPrimitive("msg-" + i)
                "msg-" + i
            }
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val fields = okOf(JsonObject(base))
            assertEquals(expectedMessageId, fields.messageId, "未知键不得污染 messageId，迭代 " + i)
        }
    }

    @Test
    fun missingRequiredSemantics() {
        // messageId 缺 / 空 / 纯空白 → MissingRequired。
        assertTrue(parseOf(JsonObject(mapOf())) is BotStarMessageFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(mapOf("messageId" to JsonPrimitive("")))) is BotStarMessageFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(mapOf("messageId" to JsonPrimitive("   ")))) is BotStarMessageFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(mapOf("messageId" to JsonPrimitive(" \t\n ")))) is BotStarMessageFieldsResult.MissingRequired)
        // 合法 → Ok。
        assertEquals("m1", okOf(JsonObject(mapOf("messageId" to JsonPrimitive("m1")))).messageId)
    }

    @Test
    fun noTrimSemantics() {
        // 钉住无 trim：两端空白原样通过必填检查、原样进下游（不裁剪）。
        val ok = okOf(JsonObject(mapOf("messageId" to JsonPrimitive(" m1 "))))
        assertEquals(" m1 ", ok.messageId)
        val ok2 = okOf(JsonObject(mapOf("messageId" to JsonPrimitive("\tmsg-2\n"))))
        assertEquals("\tmsg-2\n", ok2.messageId)
    }

    @Test
    fun nullAndScalarQuirks() {
        // messageId 显式 null → 字面 "null"（不抛、非空）→ Ok。
        val ok = okOf(JsonObject(mapOf("messageId" to JsonNull)))
        assertEquals("null", ok.messageId)
        // JSON 布尔经 content 取 toString，不抛。
        val ok2 = okOf(JsonObject(mapOf("messageId" to JsonPrimitive(true))))
        assertEquals("true", ok2.messageId)
        // JSON 整数经 content 取 toString，不抛。
        val ok3 = okOf(JsonObject(mapOf("messageId" to JsonPrimitive(42))))
        assertEquals("42", ok3.messageId)
        // 对象 / 数组型 messageId 在 ?.jsonPrimitive 处抛 IllegalArgumentException。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("messageId" to JsonObject(mapOf()))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("messageId" to JsonArray(emptyList()))))
        }
    }

    @Test
    fun wrongTypedKnownFieldsFailLoudly() {
        // messageId 对象型 → 抛（大声失败，路由层 StatusPages 映射 400，不是 500）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("messageId" to JsonObject(mapOf("x" to JsonPrimitive(1))))))
        }
        // messageId 数组型 → 抛。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("messageId" to JsonArray(listOf(JsonPrimitive(1))))))
        }
        // 反证：显式 null 不抛（是 JsonPrimitive 的一种），messageId 得字面 "null"。
        val ok = okOf(JsonObject(mapOf("messageId" to JsonNull)))
        assertEquals("null", ok.messageId)
    }
}
