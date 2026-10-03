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

class BotDeleteMessageParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("chatId", "messageId")
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotDeleteMessageFieldsResult =
        parseBotDeleteMessageFields(obj)

    private fun okOf(obj: JsonObject): BotDeleteMessageFields =
        (parseOf(obj) as BotDeleteMessageFieldsResult.Ok).fields

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
        val random = Random(2026100258)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            // 必填字段确定性合法（第四十一块 CI 教训）：chatId 恒为 "c<i>"、
            // messageId 恒为 "m<i>"（均无空白），未知键注入永不触达必填。
            base["chatId"] = JsonPrimitive("c" + i)
            base["messageId"] = JsonPrimitive("m" + i)
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val fields = okOf(JsonObject(base))
            assertEquals("c" + i, fields.chatId, "未知键不得污染 chatId，迭代 " + i)
            assertEquals("m" + i, fields.messageId, "未知键不得污染 messageId，迭代 " + i)
        }
    }

    @Test
    fun requiredSemantics() {
        // 双字段端点的每个 okOf 用例都携带全部必填字段（第五十七块 CI 教训）。
        // chatId 缺 / 空 / 纯空白 → Invalid（无 trim，纯空白直接判空）。
        assertTrue(parseOf(JsonObject(emptyMap())) is BotDeleteMessageFieldsResult.Invalid)
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive(""),
                "messageId" to JsonPrimitive("m1")))) is BotDeleteMessageFieldsResult.Invalid
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("   "),
                "messageId" to JsonPrimitive("m1")))) is BotDeleteMessageFieldsResult.Invalid
        )
        // messageId 缺 / 空 / 纯空白 → Invalid。
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1")))) is BotDeleteMessageFieldsResult.Invalid
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
                "messageId" to JsonPrimitive("")))) is BotDeleteMessageFieldsResult.Invalid
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
                "messageId" to JsonPrimitive("\t \n")))) is BotDeleteMessageFieldsResult.Invalid
        )
        // 双字段都合法 → Ok。
        val ok = okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "messageId" to JsonPrimitive("m1"))))
        assertEquals("c1", ok.chatId)
        assertEquals("m1", ok.messageId)
    }

    @Test
    fun explicitNullLiteral() {
        // 显式 null 不判空（逐字怪语义）：得字面量 "null" → Ok。
        val nullChatId = okOf(JsonObject(mapOf("chatId" to JsonNull,
            "messageId" to JsonPrimitive("m1"))))
        assertEquals("null", nullChatId.chatId)
        val nullMessageId = okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "messageId" to JsonNull)))
        assertEquals("null", nullMessageId.messageId)
        // 两个都显式 null → 双 "null" → Ok。
        val bothNull = okOf(JsonObject(mapOf("chatId" to JsonNull, "messageId" to JsonNull)))
        assertEquals("null", bothNull.chatId)
        assertEquals("null", bothNull.messageId)
    }

    @Test
    fun whitespaceNotTrimmed() {
        // 两字段均无 trim：" c1 "/" m1 " 原样保留（原处理器逐字如此），但仍通过必填（非空）。
        val fields = okOf(JsonObject(mapOf("chatId" to JsonPrimitive(" c1 "),
            "messageId" to JsonPrimitive(" m1 "))))
        assertEquals(" c1 ", fields.chatId)
        assertEquals(" m1 ", fields.messageId)
    }

    @Test
    fun loudFailureOnBadTypes() {
        // 对象 / 数组型在 ?.jsonPrimitive 处大声失败（路由层 StatusPages → 400，不是 500）。
        // 抽取顺序 chatId 先、messageId 后。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonObject(mapOf("a" to JsonPrimitive(1))),
                "messageId" to JsonPrimitive("m1"))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonArray(listOf(JsonPrimitive(1))),
                "messageId" to JsonPrimitive("m1"))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
                "messageId" to JsonObject(emptyMap()))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
                "messageId" to JsonArray(listOf(JsonPrimitive(1))))))
        }
        // 显式 null 不抛的反证：JsonNull 是 JsonPrimitive。
        assertEquals("null", okOf(JsonObject(mapOf("chatId" to JsonNull,
            "messageId" to JsonPrimitive("m1")))).chatId)
    }

    @Test
    fun nearMissNamesIgnored() {
        // 近似字段名按未知键忽略，真字段缺席 → Invalid。
        val obj = JsonObject(
            mapOf(
                "ChatId" to JsonPrimitive("c1"),
                "chatid2" to JsonPrimitive("c1"),
                "chat_id" to JsonPrimitive("c1"),
                "MessageId" to JsonPrimitive("m1"),
                "message_id" to JsonPrimitive("m1"),
                "messageid" to JsonPrimitive("m1")
            )
        )
        assertTrue(parseOf(obj) is BotDeleteMessageFieldsResult.Invalid)
    }
}
