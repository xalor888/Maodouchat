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

class BotSendNudgeParseFuzzTest {

    private companion object {
        /** sendNudge 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("chatId", "text")
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

    private fun fieldsOf(obj: JsonObject): BotSendNudgeFields {
        val result = parseBotSendNudgeFields(obj)
        assertTrue(result is BotSendNudgeFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    @Test
    fun `sendNudge parse survives seeded unknown-field fuzz`() {
        val random = Random(0x9D6E_0E55)
        repeat(ITERATIONS) { i ->
            val chatId = "chat-$i"
            val note = "note-$i"
            val base = JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive(chatId),
                    "text" to JsonPrimitive(note),
                )
            )
            val obj = injectUnknownFields(base, random)
            val fields = fieldsOf(obj)
            assertEquals(
                BotSendNudgeFields(chatId, note),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
            assertEquals(
                "👋 $note",
                buildBotNudgeContent(fields.note),
                "fuzz 迭代 #$i：内容模板必须逐字一致",
            )
        }
    }

    @Test
    fun `sendNudge required field is enforced`() {
        // chatId 缺失 → MissingRequired
        assertTrue(
            parseBotSendNudgeFields(
                JsonObject(mapOf("text" to JsonPrimitive("hi")))
            ) is BotSendNudgeFieldsResult.MissingRequired,
            "chatId 缺失必须判缺",
        )
        // chatId 空白 → MissingRequired
        assertTrue(
            parseBotSendNudgeFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("   "),
                        "text" to JsonPrimitive("hi"),
                    )
                )
            ) is BotSendNudgeFieldsResult.MissingRequired,
            "空白 chatId 必须判缺",
        )
        // text 缺失不触发 400：note 回空串，内容模板走回退分支
        val noText = fieldsOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"))))
        assertEquals("", noText.note, "text 缺失时 note 必须为空串")
        assertEquals("👋 nudge", buildBotNudgeContent(noText.note), "空 note 必须组装出回退内容")
        // text 空白同样不触发 400：isNotBlank 判空 → 回退内容
        val blankText = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "text" to JsonPrimitive("   "),
                )
            )
        )
        assertEquals("👋 nudge", buildBotNudgeContent(blankText.note), "空白 text 必须组装出回退内容")
        // 近似字段名被忽略（不是别名）
        val approx = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "ChatID" to JsonPrimitive("wrong"),
                    "text" to JsonPrimitive("hi"),
                )
            )
        )
        assertEquals("c", approx.chatId, "近似字段名 ChatID 必须被忽略")
    }

    @Test
    fun `sendNudge text truncation is pinned without trimming`() {
        // 100 字符 → take(80)，不 trim：前导空格计入上限
        val long = "x".repeat(100)
        val truncated = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "text" to JsonPrimitive(long),
                )
            )
        )
        assertEquals("x".repeat(80), truncated.note, "text 超长必须截断到 80 字符")
        assertEquals("👋 ${"x".repeat(80)}", buildBotNudgeContent(truncated.note))
        val padded = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "text" to JsonPrimitive("   padded"),
                )
            )
        )
        assertEquals("   padded", padded.note, "text 不得被 trim")
    }

    @Test
    fun `sendNudge explicit null keeps legacy semantics`() {
        // 「怪」语义钉住：JsonNull.content 为 "null" 字符串，isBlank() 判不住——
        // 显式 null 的 chatId 被当成合法非空 id（零行为改动），显式 null 的 text
        // 组装出 "👋 null"。
        val nullChat = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonNull,
                    "text" to JsonPrimitive("hi"),
                )
            )
        )
        assertEquals("null", nullChat.chatId, "显式 null 的 chatId 必须得到字面 \"null\"")
        val nullText = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "text" to JsonNull,
                )
            )
        )
        assertEquals("null", nullText.note, "显式 null 的 text 必须得到字面 \"null\"")
        assertEquals("👋 null", buildBotNudgeContent(nullText.note), "显式 null 的 text 必须组装出 \"👋 null\"")
    }

    @Test
    fun `sendNudge wrong-typed known fields fail loudly`() {
        // 已知字段类型错 → IllegalArgumentException（路由层 StatusPages 映射 400，不是 500）
        assertFailsWith<IllegalArgumentException>(
            "chatId 收对象必须大声失败",
        ) {
            parseBotSendNudgeFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonObject(mapOf("id" to JsonPrimitive("c"))),
                        "text" to JsonPrimitive("hi"),
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException>(
            "text 收数组必须大声失败（不是静默回空串）",
        ) {
            parseBotSendNudgeFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "text" to JsonArray(listOf(JsonPrimitive("hi"))),
                    )
                )
            )
        }
    }
}
