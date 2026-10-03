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

class BotSendMessageSilentParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES =
            setOf(
                "chatId", "text", "parseMode",
            )
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotSendMessageSilentFieldsResult =
        parseBotSendMessageSilentFields(obj)

    private fun okOf(obj: JsonObject): BotSendMessageSilentFields =
        (parseOf(obj) as BotSendMessageSilentFieldsResult.Ok).fields

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

    private fun basePayload(random: Random, i: Int): Pair<MutableMap<String, JsonElement>, BotSendMessageSilentFields> {
        val base = mutableMapOf<String, JsonElement>()
        // 必填字段确定性合法（第四十一块 CI 教训）：chatId 恒为 "c<i>"、text 恒为
        // 非空确定性字符串——未知键注入永不触达必填。
        base["chatId"] = JsonPrimitive("c" + i)
        // text 无 trim：首尾空格原样保留；取 " t<i> " 钉住该语义。
        // i % 3 == 0 时塞一个超长文本（5000 字符→截 4000），钉住截断。
        val longText = "x".repeat(5000)
        val text = if (i % 3 == 0) longText else " t" + i + " "
        base["text"] = JsonPrimitive(text)
        // parseMode 轮换映射变体，钉住映射表。
        val mode = when (i % 4) {
            0 -> "markdown"
            1 -> "MD"
            2 -> "text"
            else -> null
        }
        if (mode != null) base["parseMode"] = JsonPrimitive(mode)
        // 程序化注入未知字段：永不破坏 JSON 语法。
        repeat(random.nextInt(0, 6)) {
            base[randomName(random)] = randomScalar(random)
        }
        val expectedType = if (mode == "markdown" || mode == "MD") "MARKDOWN" else "TEXT"
        return base to BotSendMessageSilentFields("c" + i, text.take(4000), expectedType)
    }

    @Test
    fun fuzzUnknownKeysIgnoredAndKnownSemanticsHold() {
        val random = Random(20261005)
        repeat(ITERATIONS) { i ->
            val (base, expected) = basePayload(random, i)
            val fields = okOf(JsonObject(base))
            assertEquals(expected.chatId, fields.chatId, "未知键不得污染 chatId，迭代 " + i)
            assertEquals(expected.text, fields.text, "未知键不得污染 text，迭代 " + i)
            assertEquals(expected.msgType, fields.msgType, "未知键不得污染 parseMode 映射，迭代 " + i)
        }
    }

    @Test
    fun missingRequiredSemantics() {
        val text = mapOf("text" to JsonPrimitive("x"))
        val chatId = mapOf("chatId" to JsonPrimitive("c1"))
        // chatId 缺 / 空 / 纯空白 → MissingRequired（text 合法时）。
        assertTrue(parseOf(JsonObject(text)) is BotSendMessageSilentFieldsResult.MissingRequired)
        assertTrue(
            parseOf(JsonObject(text + ("chatId" to JsonPrimitive("")))) is BotSendMessageSilentFieldsResult.MissingRequired
        )
        assertTrue(
            parseOf(JsonObject(text + ("chatId" to JsonPrimitive("   ")))) is BotSendMessageSilentFieldsResult.MissingRequired
        )
        // text 缺 / 空 / 纯空白 → MissingRequired（chatId 合法时）。
        assertTrue(parseOf(JsonObject(chatId)) is BotSendMessageSilentFieldsResult.MissingRequired)
        assertTrue(
            parseOf(JsonObject(chatId + ("text" to JsonPrimitive("")))) is BotSendMessageSilentFieldsResult.MissingRequired
        )
        assertTrue(
            parseOf(JsonObject(chatId + ("text" to JsonPrimitive("   ")))) is BotSendMessageSilentFieldsResult.MissingRequired
        )
        // 显式 null 得字面量 "null"→非空→Ok 的逐字怪语义。
        val nullChatId = okOf(JsonObject(mapOf("chatId" to JsonNull, "text" to JsonPrimitive("x"))))
        assertEquals("null", nullChatId.chatId)
        val nullText = okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "text" to JsonNull)))
        assertEquals("null", nullText.text)
    }

    @Test
    fun parseModeMapping() {
        fun typeOf(mode: JsonElement?): String {
            val base = mutableMapOf<String, JsonElement>("chatId" to JsonPrimitive("c1"), "text" to JsonPrimitive("x"))
            if (mode != null) base["parseMode"] = mode
            return okOf(JsonObject(base)).msgType
        }
        // 大小写不敏感的两种合法值。
        assertEquals("MARKDOWN", typeOf(JsonPrimitive("markdown")))
        assertEquals("MARKDOWN", typeOf(JsonPrimitive("MARKDOWN")))
        assertEquals("MARKDOWN", typeOf(JsonPrimitive("Markdown")))
        assertEquals("MARKDOWN", typeOf(JsonPrimitive("md")))
        assertEquals("MARKDOWN", typeOf(JsonPrimitive("MD")))
        assertEquals("MARKDOWN", typeOf(JsonPrimitive("mD")))
        // 其余一律 TEXT：缺席 / 空串 / 其他值 / 带空格（无 trim 的逐字语义）/ 显式 null。
        assertEquals("TEXT", typeOf(null))
        assertEquals("TEXT", typeOf(JsonPrimitive("")))
        assertEquals("TEXT", typeOf(JsonPrimitive("TEXT")))
        assertEquals("TEXT", typeOf(JsonPrimitive("text")))
        assertEquals("TEXT", typeOf(JsonPrimitive("HTML")))
        assertEquals("TEXT", typeOf(JsonPrimitive(" md ")))
        assertEquals("TEXT", typeOf(JsonPrimitive("MARKDOWN ")))
        assertEquals("TEXT", typeOf(JsonNull))
    }

    @Test
    fun truncationAndNoTrim() {
        // text 5000 字符→截 4000（先取后截）。
        val long = okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "text" to JsonPrimitive("x".repeat(5000)))))
        assertEquals(4000, long.text.length)
        assertEquals("x".repeat(4000), long.text)
        // 无 trim：首尾空白原样保留。
        val spaced = okOf(JsonObject(mapOf("chatId" to JsonPrimitive(" c1 "), "text" to JsonPrimitive(" t "))))
        assertEquals(" c1 ", spaced.chatId)
        assertEquals(" t ", spaced.text)
    }

    @Test
    fun loudFailureCounterexamples() {
        // 对象 / 数组型已知字段在 ?.jsonPrimitive 处大声失败（路由层映射 400，不是 500）。
        val badChatIdObj = JsonObject(mapOf("chatId" to JsonObject(mapOf("a" to JsonPrimitive(1))), "text" to JsonPrimitive("x")))
        assertFailsWith<IllegalArgumentException> { parseOf(badChatIdObj) }
        val badChatIdArr = JsonObject(mapOf("chatId" to JsonArray(listOf(JsonPrimitive("c"))), "text" to JsonPrimitive("x")))
        assertFailsWith<IllegalArgumentException> { parseOf(badChatIdArr) }
        val badTextObj = JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "text" to JsonObject(mapOf("a" to JsonPrimitive(1)))))
        assertFailsWith<IllegalArgumentException> { parseOf(badTextObj) }
        val badModeArr = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c1"),
                "text" to JsonPrimitive("x"),
                "parseMode" to JsonArray(listOf(JsonPrimitive("MD"))),
            )
        )
        assertFailsWith<IllegalArgumentException> { parseOf(badModeArr) }
        // 反证：显式 null 不抛（得字面量 "null"）。
        okOf(JsonObject(mapOf("chatId" to JsonNull, "text" to JsonPrimitive("x"))))
        okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "text" to JsonNull)))
        okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "text" to JsonPrimitive("x"), "parseMode" to JsonNull)))
    }

    @Test
    fun nearMissFieldNamesAreIgnored() {
        // 近似字段名按未知键忽略：真字段缺席→MissingRequired。
        val nearMiss = JsonObject(
            mapOf(
                "ChatId" to JsonPrimitive("c1"),
                "chatid2" to JsonPrimitive("c1"),
                "Text" to JsonPrimitive("x"),
                "parsemode" to JsonPrimitive("MD"),
            )
        )
        assertTrue(parseOf(nearMiss) is BotSendMessageSilentFieldsResult.MissingRequired)
    }
}
