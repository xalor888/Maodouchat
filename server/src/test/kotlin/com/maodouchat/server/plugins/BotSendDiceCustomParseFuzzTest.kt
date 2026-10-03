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

class BotSendDiceCustomParseFuzzTest {

    private companion object {
        /** sendDiceCustom 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("chatId", "sides")
        private const val ITERATIONS = 150
    }

    private fun randomFieldName(random: Random): String {
        val stems = listOf(
            "future", "x", "v9", "extra", "unknown", "meta", "debug", "tmp",
            "clientExt", "appExt", "exp", "flag",
        )
        var name = "${stems.random(random)}_${random.nextInt(10000)}"
        while (name in KNOWN_FIELD_NAMES) name = "z$name"
        return name
    }

    private fun randomJsonValue(random: Random, depth: Int): JsonElement {
        val leafKinds = 5 // bool / long / double / string / null
        return when (random.nextInt(if (depth <= 0) leafKinds else leafKinds + 2)) {
            0 -> JsonPrimitive(random.nextBoolean())
            1 -> JsonPrimitive(random.nextLong())
            2 -> JsonPrimitive(random.nextDouble())
            3 -> JsonPrimitive("str_${random.nextInt(100000)}_${random.nextLong()}")
            4 -> JsonNull
            5 -> JsonArray(List(random.nextInt(1, 4)) { randomJsonValue(random, depth - 1) })
            else -> JsonObject(
                (0 until random.nextInt(1, 4))
                    .associate { randomFieldName(random) to randomJsonValue(random, depth - 1) }
            )
        }
    }

    /** 顶层注入 1–5 个未知字段。 */
    private fun injectUnknownFields(base: JsonObject, random: Random): JsonObject {
        val fields = base.toMutableMap()
        repeat(random.nextInt(1, 6)) {
            fields[randomFieldName(random)] = randomJsonValue(random, 2)
        }
        return JsonObject(fields)
    }

    private fun fieldsOf(obj: JsonObject): BotSendDiceCustomFields {
        val result = parseBotSendDiceCustomFields(obj)
        assertTrue(result is BotSendDiceCustomFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    @Test
    fun `sendDiceCustom parse survives seeded unknown-field fuzz`() {
        val random = Random(0xD1CEC5)
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val rawSides = random.nextInt(-50, 500)
            // 整数或其字符串形式：toIntOrNull 对两者都成立
            val sidesElement = if (random.nextBoolean()) JsonPrimitive(rawSides) else JsonPrimitive(rawSides.toString())
            val baseMap = mutableMapOf<String, JsonElement>(
                "chatId" to JsonPrimitive(chatId),
                "sides" to sidesElement,
            )
            val obj = injectUnknownFields(JsonObject(baseMap), random)
            val fields = fieldsOf(obj)
            val expectedSides = (rawSides.toString().toIntOrNull() ?: 6).coerceIn(2, 100)
            assertEquals(
                BotSendDiceCustomFields(chatId, expectedSides),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
        }
    }

    @Test
    fun `sendDiceCustom chatId is required`() {
        // chatId 缺失 → MissingRequired
        assertTrue(
            parseBotSendDiceCustomFields(JsonObject(mapOf("sides" to JsonPrimitive(6))))
                is BotSendDiceCustomFieldsResult.MissingRequired,
            "chatId 缺失必须判缺",
        )
        // chatId 空白 → MissingRequired
        assertTrue(
            parseBotSendDiceCustomFields(
                JsonObject(mapOf("chatId" to JsonPrimitive("   "), "sides" to JsonPrimitive(6)))
            ) is BotSendDiceCustomFieldsResult.MissingRequired,
            "chatId 空白必须判缺",
        )
        // chatId 合法 → Ok
        assertTrue(
            parseBotSendDiceCustomFields(JsonObject(mapOf("chatId" to JsonPrimitive("c"))))
                is BotSendDiceCustomFieldsResult.Ok,
            "合法 chatId 必须通过",
        )
    }

    @Test
    fun `sendDiceCustom chatId is not trimmed`() {
        // chatId 不 trim：带空格的 chatId 原样保留，只有 isBlank() 判缺
        val fields = fieldsOf(
            JsonObject(mapOf("chatId" to JsonPrimitive("  c1  "), "sides" to JsonPrimitive(6)))
        )
        assertEquals("  c1  ", fields.chatId, "chatId 必须不 trim，原样保留")
    }

    @Test
    fun `sendDiceCustom sides default to 6 and are clamped`() {
        fun sidesOf(sides: JsonElement?): Int {
            val map = mutableMapOf<String, JsonElement>("chatId" to JsonPrimitive("c"))
            if (sides != null) map["sides"] = sides
            return fieldsOf(JsonObject(map)).sides
        }
        // 缺省 → 6
        assertEquals(6, sidesOf(null))
        // 显式值
        assertEquals(20, sidesOf(JsonPrimitive(20)))
        // 字符串数字同样有效
        assertEquals(12, sidesOf(JsonPrimitive("12")))
        // 钳制：1→2、0→2、负数→2、1000→100、边界值合法
        assertEquals(2, sidesOf(JsonPrimitive(1)))
        assertEquals(2, sidesOf(JsonPrimitive(0)))
        assertEquals(2, sidesOf(JsonPrimitive(-42)))
        assertEquals(100, sidesOf(JsonPrimitive(1000)))
        assertEquals(2, sidesOf(JsonPrimitive(2)))
        assertEquals(100, sidesOf(JsonPrimitive(100)))
    }

    @Test
    fun `sendDiceCustom non-numeric sides fall back to 6`() {
        fun sidesOf(sides: JsonElement): Int = fieldsOf(
            JsonObject(mapOf("chatId" to JsonPrimitive("c"), "sides" to sides))
        ).sides
        // 非数字字符串 → toIntOrNull 为 null → 直接回 6（与 sendDice 穿透到 emoji 映射默认故意不同）
        assertEquals(6, sidesOf(JsonPrimitive("abc")))
        // 浮点：content 为 "5.5"，toIntOrNull 为 null → 回 6
        assertEquals(6, sidesOf(JsonPrimitive(5.5)))
        // 显式 JSON null：content 为 "null" → 回 6
        assertEquals(6, sidesOf(JsonNull))
        // 超出 Int 范围的长整数：toIntOrNull 为 null → 回 6
        assertEquals(6, sidesOf(JsonPrimitive(Long.MAX_VALUE)))
    }

    @Test
    fun `sendDiceCustom wrong-typed known fields fail loudly`() {
        val badChatId = JsonObject(
            mapOf(
                "chatId" to JsonObject(mapOf("nested" to JsonPrimitive("x"))),
                "sides" to JsonPrimitive(6),
            )
        )
        assertFailsWith<IllegalArgumentException>("对象型 chatId 必须抛 IllegalArgumentException") {
            parseBotSendDiceCustomFields(badChatId)
        }
        val badChatIdArray = JsonObject(
            mapOf(
                "chatId" to JsonArray(listOf(JsonPrimitive("c"))),
                "sides" to JsonPrimitive(6),
            )
        )
        assertFailsWith<IllegalArgumentException>("数组型 chatId 必须抛 IllegalArgumentException") {
            parseBotSendDiceCustomFields(badChatIdArray)
        }
        val badSides = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "sides" to JsonObject(emptyMap()),
            )
        )
        assertFailsWith<IllegalArgumentException>("对象型 sides 必须抛 IllegalArgumentException") {
            parseBotSendDiceCustomFields(badSides)
        }
        val badSidesArray = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "sides" to JsonArray(listOf(JsonPrimitive(6))),
            )
        )
        assertFailsWith<IllegalArgumentException>("数组型 sides 必须抛 IllegalArgumentException") {
            parseBotSendDiceCustomFields(badSidesArray)
        }
    }

    @Test
    fun `sendDiceCustom near-miss field names are ignored as unknown`() {
        val obj = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "sides" to JsonPrimitive(20),
                // 近似字段名：大小写/前后缀之差，必须按未知键忽略
                "ChatId" to JsonPrimitive("WRONG"),
                "chatid" to JsonPrimitive("WRONG"),
                "chat_id" to JsonPrimitive("WRONG"),
                "side" to JsonPrimitive(5),
                "sidesCount" to JsonPrimitive(5),
                "diceSides" to JsonPrimitive(5),
            )
        )
        val fields = fieldsOf(obj)
        assertEquals("c", fields.chatId, "近似字段名不得覆盖 chatId")
        assertEquals(20, fields.sides, "近似字段名不得覆盖 sides")
    }

    @Test
    fun `sendDiceCustom content template is pinned`() {
        assertEquals("DICE:6|4|bot dice roll", buildBotDiceCustomContent(6, 4), "默认 6 面模板必须逐字一致")
        assertEquals("DICE:20|17|bot dice roll", buildBotDiceCustomContent(20, 17), "显式面数模板必须逐字一致")
        assertEquals("DICE:2|1|bot dice roll", buildBotDiceCustomContent(2, 1), "钳制下限模板必须逐字一致")
        assertEquals("DICE:100|99|bot dice roll", buildBotDiceCustomContent(100, 99), "钳制上限模板必须逐字一致")
    }
}
