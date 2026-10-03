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

class BotSendContactCardParseFuzzTest {

    private companion object {
        /** sendContactCard 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("chatId", "name")
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

    private fun fieldsOf(obj: JsonObject): BotSendContactCardFields {
        val result = parseBotSendContactCardFields(obj)
        assertTrue(result is BotSendContactCardFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    @Test
    fun `sendContactCard parse survives seeded unknown-field fuzz`() {
        val random = Random(0xC0A7CA_2D1D)
        repeat(ITERATIONS) { i ->
            val chatId = "chat-$i"
            val name = "name-$i"
            val base = JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive(chatId),
                    "name" to JsonPrimitive(name),
                )
            )
            val obj = injectUnknownFields(base, random)
            val fields = fieldsOf(obj)
            assertEquals(
                BotSendContactCardFields(chatId, name),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
            assertEquals(
                "> ~card:$name~",
                buildBotContactCardContent(fields.name),
                "fuzz 迭代 #$i：内容模板必须逐字一致",
            )
        }
    }

    @Test
    fun `sendContactCard required field is enforced`() {
        // chatId 缺失 → MissingRequired
        assertTrue(
            parseBotSendContactCardFields(
                JsonObject(mapOf("name" to JsonPrimitive("Alice")))
            ) is BotSendContactCardFieldsResult.MissingRequired,
            "chatId 缺失必须判缺",
        )
        // chatId 空白 → MissingRequired
        assertTrue(
            parseBotSendContactCardFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("   "),
                        "name" to JsonPrimitive("Alice"),
                    )
                )
            ) is BotSendContactCardFieldsResult.MissingRequired,
            "空白 chatId 必须判缺",
        )
        // name 缺失不触发 400：回 "contact"，内容模板逐字组装
        val noName = fieldsOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"))))
        assertEquals("contact", noName.name, "name 缺失时必须回 \"contact\"")
        assertEquals("> ~card:contact~", buildBotContactCardContent(noName.name), "缺省 name 必须组装出默认名片内容")
        // 近似字段名被忽略（不是别名）
        val approx = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "Name" to JsonPrimitive("wrong"),
                    "name" to JsonPrimitive("Alice"),
                )
            )
        )
        assertEquals("Alice", approx.name, "近似字段名 Name 必须被忽略")
    }

    @Test
    fun `sendContactCard name truncation is pinned without trimming`() {
        // 100 字符 → take(80)，不 trim：前导空格计入上限
        val long = "x".repeat(100)
        val truncated = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "name" to JsonPrimitive(long),
                )
            )
        )
        assertEquals("x".repeat(80), truncated.name, "name 超长必须截断到 80 字符")
        assertEquals("> ~card:${"x".repeat(80)}~", buildBotContactCardContent(truncated.name))
        val padded = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "name" to JsonPrimitive("   padded"),
                )
            )
        )
        assertEquals("   padded", padded.name, "name 不得被 trim")
    }

    @Test
    fun `sendContactCard explicit null keeps legacy semantics`() {
        // 「怪」语义钉住：JsonNull.content 为 "null" 字符串，isBlank() 判不住——
        // 显式 null 的 chatId 被当成合法非空 id（零行为改动），显式 null 的 name
        // 组装出 "> ~card:null~"（注意：不是缺省 "contact"）。
        val nullChat = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonNull,
                    "name" to JsonPrimitive("Alice"),
                )
            )
        )
        assertEquals("null", nullChat.chatId, "显式 null 的 chatId 必须得到字面 \"null\"")
        val nullName = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "name" to JsonNull,
                )
            )
        )
        assertEquals("null", nullName.name, "显式 null 的 name 必须得到字面 \"null\"（不是 \"contact\"）")
        assertEquals("> ~card:null~", buildBotContactCardContent(nullName.name), "显式 null 的 name 必须组装出 \"> ~card:null~\"")
    }

    @Test
    fun `sendContactCard wrong-typed known fields fail loudly`() {
        // 已知字段类型错 → IllegalArgumentException（路由层 StatusPages 映射 400，不是 500）
        assertFailsWith<IllegalArgumentException>(
            "chatId 收对象必须大声失败",
        ) {
            parseBotSendContactCardFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonObject(mapOf("id" to JsonPrimitive("c"))),
                        "name" to JsonPrimitive("Alice"),
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException>(
            "name 收数组必须大声失败（不是静默回 \"contact\"）",
        ) {
            parseBotSendContactCardFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "name" to JsonArray(listOf(JsonPrimitive("Alice"))),
                    )
                )
            )
        }
    }
}
