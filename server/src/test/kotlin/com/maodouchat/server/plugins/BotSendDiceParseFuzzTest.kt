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

class BotSendDiceParseFuzzTest {

    private companion object {
        /** sendDice 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("chatId", "emoji", "sides")
        private const val ITERATIONS = 150
        private val EMOJIS = listOf("🎲", "🎯", "🎳", "🏀", "⚽", "🎰")
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

    private fun fieldsOf(obj: JsonObject): BotSendDiceFields {
        val result = parseBotSendDiceFields(obj)
        assertTrue(result is BotSendDiceFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    /** fuzz 迭代期望的 emoji→sides 默认映射（生产逻辑的镜像）。 */
    private fun expectedDefaultSides(emoji: String?): Int = when (emoji) {
        "🏀", "⚽" -> 5
        "🎰" -> 64
        else -> 6
    }

    @Test
    fun `sendDice parse survives seeded unknown-field fuzz`() {
        val random = Random(0xD1CE)
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val emoji = if (random.nextBoolean()) EMOJIS.random(random) else null
            val explicitSides = if (random.nextBoolean()) random.nextInt(-50, 500) else null
            val baseMap = mutableMapOf("chatId" to JsonPrimitive(chatId))
            if (emoji != null) baseMap["emoji"] = JsonPrimitive(emoji)
            if (explicitSides != null) baseMap["sides"] = JsonPrimitive(explicitSides)
            val obj = injectUnknownFields(JsonObject(baseMap), random)
            val fields = fieldsOf(obj)
            val expectedSides = (explicitSides ?: expectedDefaultSides(emoji)).coerceIn(2, 100)
            assertEquals(
                BotSendDiceFields(chatId, emoji, expectedSides),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
        }
    }

    @Test
    fun `sendDice chatId is required`() {
        // chatId 缺失 → MissingRequired
        assertTrue(
            parseBotSendDiceFields(JsonObject(mapOf("emoji" to JsonPrimitive("🎲"))))
                is BotSendDiceFieldsResult.MissingRequired,
            "chatId 缺失必须判缺",
        )
        // chatId 空白 → MissingRequired
        assertTrue(
            parseBotSendDiceFields(
                JsonObject(mapOf("chatId" to JsonPrimitive("   "), "sides" to JsonPrimitive(6)))
            ) is BotSendDiceFieldsResult.MissingRequired,
            "chatId 空白必须判缺",
        )
        // chatId 合法 → Ok
        assertTrue(
            parseBotSendDiceFields(JsonObject(mapOf("chatId" to JsonPrimitive("c"))))
                is BotSendDiceFieldsResult.Ok,
            "合法 chatId 必须通过",
        )
    }

    @Test
    fun `sendDice emoji to sides mapping is pinned`() {
        // 🏀/⚽ → 5 面
        assertEquals(5, fieldsOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "emoji" to JsonPrimitive("🏀")))).sides)
        assertEquals(5, fieldsOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "emoji" to JsonPrimitive("⚽")))).sides)
        // 🎰 → 64 面
        assertEquals(64, fieldsOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "emoji" to JsonPrimitive("🎰")))).sides)
        // 其余 emoji / 缺省 → 6 面
        assertEquals(6, fieldsOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "emoji" to JsonPrimitive("🎲")))).sides)
        assertEquals(6, fieldsOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "emoji" to JsonPrimitive("🎯")))).sides)
        assertEquals(6, fieldsOf(JsonObject(mapOf("chatId" to JsonPrimitive("c")))).sides)
        // 显式 JSON null：jsonPrimitive 是 JsonPrimitive、content 为 "null"，isBlank 判不住→emoji="null"，映射兜底 6
        val explicitNull = fieldsOf(
            JsonObject(mapOf("chatId" to JsonPrimitive("c"), "emoji" to JsonNull))
        )
        assertEquals("null", explicitNull.diceEmoji, "显式 JSON null 的 content \"null\" 必须逐字保留")
        assertEquals(6, explicitNull.sides, "显式 null emoji 映射兜底必须为 6")
        // 纯空格 emoji → isBlank → null → 兜底 6
        val blankEmoji = fieldsOf(
            JsonObject(mapOf("chatId" to JsonPrimitive("c"), "emoji" to JsonPrimitive("  ")))
        )
        assertEquals(null, blankEmoji.diceEmoji, "纯空格 emoji 必须判为无 emoji")
        assertEquals(6, blankEmoji.sides)
    }

    @Test
    fun `sendDice emoji is not trimmed and when is exact match`() {
        // emoji 不 trim：带前导空格的 "  🏀" 原样保留，when 全字串比较不命中 → 兜底 6 面
        val spaced = fieldsOf(
            JsonObject(mapOf("chatId" to JsonPrimitive("c"), "emoji" to JsonPrimitive("  🏀")))
        )
        assertEquals("  🏀", spaced.diceEmoji, "emoji 必须不 trim，原样保留")
        assertEquals(6, spaced.sides, "带空格的 🏀 全字串比较不命中，兜底必须为 6")
        assertEquals("  🏀 4/6", buildBotDiceContent(spaced.diceEmoji, 4, spaced.sides), "组装必须用原样 emoji")
    }

    @Test
    fun `sendDice explicit sides override and are clamped`() {
        fun sidesOf(sides: JsonElement): Int = fieldsOf(
            JsonObject(mapOf("chatId" to JsonPrimitive("c"), "sides" to sides))
        ).sides
        // 显式 sides 优先于 emoji 映射
        assertEquals(20, sidesOf(JsonPrimitive(20)))
        assertEquals("🎰 2/20", buildBotDiceContent("🎰", 2, sidesOf(JsonPrimitive(20))), "显式 sides 必须覆盖 🎰 的 64 默认")
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
    fun `sendDice non-numeric sides penetrate to emoji default`() {
        fun sidesOf(sides: JsonElement, emoji: JsonElement? = null): Int {
            val map = mutableMapOf("chatId" to JsonPrimitive("c"), "sides" to sides)
            if (emoji != null) map["emoji"] = emoji
            return fieldsOf(JsonObject(map)).sides
        }
        // 非数字字符串 → toIntOrNull 为 null → 穿透到 emoji 映射默认
        assertEquals(6, sidesOf(JsonPrimitive("abc")))
        assertEquals(5, sidesOf(JsonPrimitive("abc"), JsonPrimitive("🏀")))
        // 浮点：content 为 "5.5"，toIntOrNull 为 null → 穿透
        assertEquals(6, sidesOf(JsonPrimitive(5.5)))
        assertEquals(64, sidesOf(JsonPrimitive(5.5), JsonPrimitive("🎰")))
        // 显式 JSON null：content 为 "null" → 穿透
        assertEquals(6, sidesOf(JsonNull))
    }

    @Test
    fun `sendDice wrong-typed known fields fail loudly`() {
        val badChatId = JsonObject(
            mapOf(
                "chatId" to JsonObject(mapOf("nested" to JsonPrimitive("x"))),
                "emoji" to JsonPrimitive("🎲"),
            )
        )
        assertFailsWith<IllegalArgumentException>("对象型 chatId 必须抛 IllegalArgumentException") {
            parseBotSendDiceFields(badChatId)
        }
        val badEmoji = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "emoji" to JsonArray(listOf(JsonPrimitive("🎲"))),
            )
        )
        assertFailsWith<IllegalArgumentException>("数组型 emoji 必须抛 IllegalArgumentException") {
            parseBotSendDiceFields(badEmoji)
        }
        val badSides = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "sides" to JsonObject(emptyMap()),
            )
        )
        assertFailsWith<IllegalArgumentException>("对象型 sides 必须抛 IllegalArgumentException") {
            parseBotSendDiceFields(badSides)
        }
        val badSidesArray = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "sides" to JsonArray(listOf(JsonPrimitive(6))),
            )
        )
        assertFailsWith<IllegalArgumentException>("数组型 sides 必须抛 IllegalArgumentException") {
            parseBotSendDiceFields(badSidesArray)
        }
    }

    @Test
    fun `sendDice near-miss field names are ignored as unknown`() {
        val obj = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "emoji" to JsonPrimitive("🎲"),
                "sides" to JsonPrimitive(20),
                // 近似字段名：大小写/前后缀之差，必须按未知键忽略
                "ChatId" to JsonPrimitive("WRONG"),
                "chatid" to JsonPrimitive("WRONG"),
                "Emoji" to JsonPrimitive("🏀"),
                "diceEmoji" to JsonPrimitive("🏀"),
                "side" to JsonPrimitive(5),
                "sidesCount" to JsonPrimitive(5),
            )
        )
        val fields = fieldsOf(obj)
        assertEquals("c", fields.chatId, "近似字段名不得覆盖 chatId")
        assertEquals("🎲", fields.diceEmoji, "近似字段名不得覆盖 emoji")
        assertEquals(20, fields.sides, "近似字段名不得覆盖 sides")
    }

    @Test
    fun `sendDice content template is pinned`() {
        assertEquals("🎲 4/6", buildBotDiceContent("🎲", 4, 6), "显式 emoji 模板必须逐字一致")
        assertEquals("🎲 1/6", buildBotDiceContent(null, 1, 6), "emoji 缺省必须回退 🎲")
        assertEquals("🎰 64/64", buildBotDiceContent("🎰", 64, 64), "🎰 64 面模板必须逐字一致")
        assertEquals("🏀 5/5", buildBotDiceContent("🏀", 5, 5), "🏀 5 面模板必须逐字一致")
    }
}
