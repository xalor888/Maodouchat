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

class BotSetChatDescriptionParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId", "description", "announcement"
        )
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotSetChatDescriptionFieldsResult =
        parseBotSetChatDescriptionFields(obj)

    private fun okOf(obj: JsonObject): BotSetChatDescriptionFields =
        (parseOf(obj) as BotSetChatDescriptionFieldsResult.Ok).fields

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
        val random = Random(2026100268)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            // 必填字段确定性合法（第四十一块 CI 教训）：chatId 恒为 "c<i>"（无空白），
            // description 恒为 "d<i>"。
            base["chatId"] = JsonPrimitive("c" + i)
            base["description"] = JsonPrimitive("d" + i)
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val fields = okOf(JsonObject(base))
            assertEquals("c" + i, fields.chatId, "未知键不得污染 chatId，迭代 " + i)
            assertEquals("d" + i, fields.description, "未知键不得污染 description，迭代 " + i)
        }
    }

    @Test
    fun requiredSemantics() {
        // 每个 okOf 用例都携带全部必填字段（第五十七块 CI 教训）。
        // chatId 缺 / 空 / 纯空白 → Invalid（无 trim，纯空白直接判空）。
        assertTrue(parseOf(JsonObject(emptyMap())) is BotSetChatDescriptionFieldsResult.Invalid)
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive(""),
                "description" to JsonPrimitive("d1"))))
                is BotSetChatDescriptionFieldsResult.Invalid
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("   "),
                "description" to JsonPrimitive("d1"))))
                is BotSetChatDescriptionFieldsResult.Invalid
        )
        // description 缺席 → Ok("")（下游 takeIf 非空→null，清公告语义在本函数之外）。
        assertEquals("", okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1")))).description)
        // 双合法 → Ok。
        val fields = okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "description" to JsonPrimitive("hello"))))
        assertEquals("c1", fields.chatId)
        assertEquals("hello", fields.description)
    }

    @Test
    fun descriptionAliasAndTrim() {
        // description 优先于 announcement。
        assertEquals("d1", okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "description" to JsonPrimitive("d1"),
            "announcement" to JsonPrimitive("a1")))).description)
        // announcement 兜底：description 缺席时生效。
        assertEquals("a1", okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "announcement" to JsonPrimitive("a1")))).description)
        // trim：前后空白被去掉。
        assertEquals("hi", okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "description" to JsonPrimitive("  hi  ")))).description)
        // 全空白 → trim 后 "" → Ok("")（不走 Invalid）。
        assertEquals("", okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "description" to JsonPrimitive("   ")))).description)
        // 显式 null 不回退：字面量 "null"（JsonNull 是非空实例，?: 不生效）。
        assertEquals("null", okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "description" to JsonNull))).description)
        // 显式 null description 不回退到 announcement。
        assertEquals("null", okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "description" to JsonNull,
            "announcement" to JsonPrimitive("a1")))).description)
    }

    @Test
    fun descriptionLengthLimit() {
        // 1200 字符恰好通过。
        val ok1200 = "x".repeat(1200)
        assertEquals(ok1200, okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "description" to JsonPrimitive(ok1200)))).description)
        // 1201 字符 → TooLong（处理器侧 400 "description too long"）。
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
                "description" to JsonPrimitive("x".repeat(1201)))))
                is BotSetChatDescriptionFieldsResult.TooLong
        )
        // 夹界判的是 trim 之后：1201 个空格 → trim 后 "" → Ok，不判超长。
        assertEquals("", okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "description" to JsonPrimitive(" ".repeat(1201))))).description)
        // 1200 个 x 加前后空格 → trim 后 1200 → Ok。
        assertEquals(ok1200, okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "description" to JsonPrimitive("  " + ok1200 + "  ")))).description)
    }

    @Test
    fun extractionBeforeValidationOrder() {
        // 两处抽取都在必填判空之前：description 对象型 + chatId 空白时先抛，不走 Invalid。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("   "),
                "description" to JsonObject(mapOf("x" to JsonPrimitive(1))))))
        }
        // chatId 数组型先抛（即使 description 合法）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonArray(listOf(JsonPrimitive(1))),
                "description" to JsonPrimitive("d1"))))
        }
    }

    @Test
    fun loudFailureCounterProof() {
        // announcement 数组型在 ?.jsonPrimitive 处大声失败（description 缺席时走别名抽取）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
                "announcement" to JsonArray(listOf(JsonPrimitive(1))))))
        }
        // 显式 null 不抛的反证：chatId 得字面量 "null"→非空→Ok（原处理器逐字怪语义）。
        val fields = okOf(JsonObject(mapOf("chatId" to JsonNull,
            "description" to JsonPrimitive("d1"))))
        assertEquals("null", fields.chatId)
    }

    @Test
    fun approximateFieldNamesIgnored() {
        // 近似字段名按未知键忽略：真字段缺席 → Invalid / 缺省。
        assertTrue(
            parseOf(JsonObject(mapOf("ChatId" to JsonPrimitive("c1"),
                "description" to JsonPrimitive("d1"))))
                is BotSetChatDescriptionFieldsResult.Invalid
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
                "Description" to JsonPrimitive("d1")))).let {
                (it as BotSetChatDescriptionFieldsResult.Ok).fields.description == ""
            }
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
                "description" to JsonPrimitive("d1"),
                "Announcement" to JsonPrimitive("a1")))).let {
                (it as BotSetChatDescriptionFieldsResult.Ok).fields.description == "d1"
            }
        )
    }

    @Test
    fun chatIdNoTrim() {
        // chatId 无 trim：原样保留仍通过必填。
        val fields = okOf(JsonObject(mapOf("chatId" to JsonPrimitive(" c1 "),
            "description" to JsonPrimitive("d1"))))
        assertEquals(" c1 ", fields.chatId)
    }
}
