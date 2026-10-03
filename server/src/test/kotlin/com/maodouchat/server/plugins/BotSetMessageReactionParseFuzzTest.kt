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

class BotSetMessageReactionParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("messageId", "emoji")
        private const val ITERATIONS = 150
        /** 白名单之外的 emoji（\uD83D\uDE00），逐字不在 ALLOWED_REACTION_EMOJIS 里。 */
        private const val OFF_LIST_EMOJI = "😀"
    }

    private fun parseOf(obj: JsonObject): BotReactionFieldsResult =
        parseBotReactionFields(obj)

    private fun okOf(obj: JsonObject): BotReactionFields =
        (parseOf(obj) as BotReactionFieldsResult.Ok).fields

    /** 白名单成员的确定性取样（setOf 迭代顺序稳定）。 */
    private fun allowedEmoji(i: Int): String =
        ALLOWED_REACTION_EMOJIS.toList()[i % ALLOWED_REACTION_EMOJIS.size]

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
        val random = Random(20261003)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            // messageId：五分之一用整数钉住 content→toString，其余用字符串。
            val expectedMessageId = if (i % 5 == 4) {
                base["messageId"] = JsonPrimitive(9000 + i)
                (9000 + i).toString()
            } else {
                base["messageId"] = JsonPrimitive("msg-" + i)
                "msg-" + i
            }
            // mode 0：白名单成员逐字通过；mode 1：两端空白→trim 后通过；
            // mode 2/3：白名单外/乱串 → UnsupportedEmoji。
            when (i % 4) {
                0 -> base["emoji"] = JsonPrimitive(allowedEmoji(i))
                1 -> base["emoji"] = JsonPrimitive("  " + allowedEmoji(i) + "\t\n ")
                2 -> base["emoji"] = JsonPrimitive(OFF_LIST_EMOJI)
                else -> base["emoji"] = JsonPrimitive("not-an-emoji-" + i)
            }
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            when (i % 4) {
                0 -> {
                    val fields = okOf(JsonObject(base))
                    assertEquals(expectedMessageId, fields.messageId, "未知键不得污染 messageId，迭代 " + i)
                    assertEquals(allowedEmoji(i), fields.emoji, "未知键不得污染 emoji，迭代 " + i)
                }
                1 -> {
                    val fields = okOf(JsonObject(base))
                    assertEquals(allowedEmoji(i), fields.emoji, "emoji 两端空白必须 trim 后再判白名单，迭代 " + i)
                }
                else -> assertTrue(
                    parseOf(JsonObject(base)) is BotReactionFieldsResult.UnsupportedEmoji,
                    "白名单外 emoji 必须 UnsupportedEmoji，迭代 " + i,
                )
            }
        }
    }

    @Test
    fun missingRequiredSemantics() {
        // messageId 缺 / 空 / 纯空白 → MissingRequired（emoji 合法）。
        assertTrue(parseOf(JsonObject(mapOf("emoji" to JsonPrimitive(allowedEmoji(0))))) is BotReactionFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(mapOf("messageId" to JsonPrimitive(""), "emoji" to JsonPrimitive(allowedEmoji(0))))) is BotReactionFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(mapOf("messageId" to JsonPrimitive("   "), "emoji" to JsonPrimitive(allowedEmoji(0))))) is BotReactionFieldsResult.MissingRequired)
        // emoji 缺 / 空 / 纯空白 → MissingRequired（messageId 合法）；判的是 trim() 之后的空白性。
        assertTrue(parseOf(JsonObject(mapOf("messageId" to JsonPrimitive("m1")))) is BotReactionFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(mapOf("messageId" to JsonPrimitive("m1"), "emoji" to JsonPrimitive("")))) is BotReactionFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(mapOf("messageId" to JsonPrimitive("m1"), "emoji" to JsonPrimitive(" \t\n ")))) is BotReactionFieldsResult.MissingRequired)
        // 双合法 → Ok。
        val ok = okOf(JsonObject(mapOf("messageId" to JsonPrimitive("m1"), "emoji" to JsonPrimitive(allowedEmoji(2)))))
        assertEquals("m1", ok.messageId)
        assertEquals(allowedEmoji(2), ok.emoji)
    }

    @Test
    fun allowlistSemantics() {
        // 白名单全体成员逐字通过。
        for (e in ALLOWED_REACTION_EMOJIS) {
            val fields = okOf(JsonObject(mapOf("messageId" to JsonPrimitive("m"), "emoji" to JsonPrimitive(e))))
            assertEquals(e, fields.emoji, "白名单成员必须逐字通过")
        }
        // 白名单外 → UnsupportedEmoji。
        assertTrue(parseOf(JsonObject(mapOf("messageId" to JsonPrimitive("m"), "emoji" to JsonPrimitive(OFF_LIST_EMOJI)))) is BotReactionFieldsResult.UnsupportedEmoji)
        assertTrue(parseOf(JsonObject(mapOf("messageId" to JsonPrimitive("m"), "emoji" to JsonPrimitive("x")))) is BotReactionFieldsResult.UnsupportedEmoji)
        // 两端空白的白名单成员 → trim 后通过（判的是 trim 后的串）。
        val ok = okOf(JsonObject(mapOf("messageId" to JsonPrimitive("m"), "emoji" to JsonPrimitive("\n " + allowedEmoji(3) + "  "))))
        assertEquals(allowedEmoji(3), ok.emoji)
        // emoji 显式 null → 字面 "null"（不抛），不在白名单 → UnsupportedEmoji，特意钉住。
        assertTrue(parseOf(JsonObject(mapOf("messageId" to JsonPrimitive("m"), "emoji" to JsonNull))) is BotReactionFieldsResult.UnsupportedEmoji)
        // emoji 数字经 content 取 toString，不抛，但不在白名单。
        assertTrue(parseOf(JsonObject(mapOf("messageId" to JsonPrimitive("m"), "emoji" to JsonPrimitive(123)))) is BotReactionFieldsResult.UnsupportedEmoji)
    }

    @Test
    fun nullAndScalarQuirks() {
        // messageId 显式 null → 字面 "null"（不抛、非空），双必填通过，emoji 合法 → Ok。
        val ok = okOf(JsonObject(mapOf("messageId" to JsonNull, "emoji" to JsonPrimitive(allowedEmoji(0)))))
        assertEquals("null", ok.messageId)
        // JSON 布尔经 content 取 toString，不抛。
        val ok2 = okOf(JsonObject(mapOf("messageId" to JsonPrimitive(true), "emoji" to JsonPrimitive(allowedEmoji(1)))))
        assertEquals("true", ok2.messageId)
        // 对象 / 数组型 messageId / emoji 在 ?.jsonPrimitive 处抛 IllegalArgumentException。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("messageId" to JsonObject(mapOf()), "emoji" to JsonPrimitive(allowedEmoji(0)))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("messageId" to JsonPrimitive("m"), "emoji" to JsonArray(emptyList()))))
        }
    }

    @Test
    fun wrongTypedKnownFieldsFailLoudly() {
        // messageId 对象型 → 抛（大声失败，路由层 StatusPages 映射 400，不是 500）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("messageId" to JsonObject(mapOf("x" to JsonPrimitive(1))), "emoji" to JsonPrimitive(allowedEmoji(0)))))
        }
        // emoji 数组型 → 抛。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("messageId" to JsonPrimitive("m"), "emoji" to JsonArray(listOf(JsonPrimitive(1))))))
        }
        // 反证：显式 null 不抛（是 JsonPrimitive 的一种），messageId 得字面 "null"。
        val ok = okOf(JsonObject(mapOf("messageId" to JsonNull, "emoji" to JsonPrimitive(allowedEmoji(2)))))
        assertEquals("null", ok.messageId)
    }

    @Test
    fun extractionOrder() {
        // messageId 坏类型 + emoji 乱值 → 抛错（messageId 先抽取）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("messageId" to JsonArray(emptyList()), "emoji" to JsonPrimitive("whatever"))))
        }
        // messageId 缺席 + emoji 坏类型 → 抛错，而非 MissingRequired（抽取抛在必填判断之前）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("emoji" to JsonArray(emptyList()))))
        }
        // messageId 纯空白 + emoji 坏类型 → 仍抛错（抽取先于必填判断）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("messageId" to JsonPrimitive("   "), "emoji" to JsonObject(mapOf()))))
        }
        // 反证：emoji 合法时 messageId 纯空白 → MissingRequired（抽取不抛，轮到必填判断）。
        assertTrue(parseOf(JsonObject(mapOf("messageId" to JsonPrimitive("  "), "emoji" to JsonPrimitive(allowedEmoji(0))))) is BotReactionFieldsResult.MissingRequired)
    }
}
