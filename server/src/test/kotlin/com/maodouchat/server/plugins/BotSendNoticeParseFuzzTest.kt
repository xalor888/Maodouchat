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

class BotSendNoticeParseFuzzTest {

    private companion object {
        /** sendNotice 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId", "text", "message",
        )
        private const val ITERATIONS = 150
    }

    private fun randomFieldName(random: Random): String {
        val stems = listOf(
            "future", "x", "v9", "extra", "unknown", "meta", "debug", "tmp",
            "clientExt", "appExt", "exp", "flag",
        )
        var name = stems.random(random) + "_" + random.nextInt(10000)
        while (name in KNOWN_FIELD_NAMES) name = "z$name"
        return name
    }

    private fun randomJsonValue(random: Random, depth: Int): JsonElement {
        val leafKinds = 5 // bool / long / double / string / null
        return when (random.nextInt(if (depth <= 0) leafKinds else leafKinds + 2)) {
            0 -> JsonPrimitive(random.nextBoolean())
            1 -> JsonPrimitive(random.nextLong())
            2 -> JsonPrimitive(random.nextDouble())
            3 -> JsonPrimitive("str_" + random.nextInt(100000) + "_" + random.nextLong())
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

    private fun fieldsOf(obj: JsonObject): BotSendNoticeFields {
        val result = parseBotSendNoticeFields(obj)
        assertTrue(result is BotSendNoticeFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    private fun validBase(
        chatId: String = "c1",
        text: String = "系统通知",
    ): JsonObject = JsonObject(
        mapOf(
            "chatId" to JsonPrimitive(chatId),
            "text" to JsonPrimitive(text),
        )
    )

    @Test
    fun `sendNotice parse survives seeded unknown-field fuzz`() {
        val random = Random(0xB1_2026)
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val text = "notice-$i"
            val base = JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive(chatId),
                    "text" to JsonPrimitive(text),
                )
            )
            val obj = injectUnknownFields(base, random)
            val fields = fieldsOf(obj)
            assertEquals(
                BotSendNoticeFields(chatId, text),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
        }
    }

    @Test
    fun `sendNotice required fields are enforced`() {
        // chatId 缺失 → MissingRequired
        assertTrue(
            parseBotSendNoticeFields(
                JsonObject(
                    mapOf(
                        "text" to JsonPrimitive("t"),
                    )
                )
            ) is BotSendNoticeFieldsResult.MissingRequired,
            "chatId 缺失必须判缺",
        )
        // chatId 空白 → MissingRequired
        assertTrue(
            parseBotSendNoticeFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("   "),
                        "text" to JsonPrimitive("t"),
                    )
                )
            ) is BotSendNoticeFieldsResult.MissingRequired,
            "chatId 空白必须判缺",
        )
        // text 缺失 → 判缺（没有回退默认值，与 sendAlert 的 text 不同）
        assertTrue(
            parseBotSendNoticeFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                    )
                )
            ) is BotSendNoticeFieldsResult.MissingRequired,
            "text 缺省必须判缺",
        )
        // text 空白 → 判缺
        assertTrue(
            parseBotSendNoticeFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "text" to JsonPrimitive("  "),
                    )
                )
            ) is BotSendNoticeFieldsResult.MissingRequired,
            "text 空白必须判缺",
        )
        // message 别名满足必填
        assertEquals(
            BotSendNoticeFields("c1", "别名文本"),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "message" to JsonPrimitive("别名文本"),
                    )
                )
            ),
            "message 别名必须能满足 text 必填",
        )
        // text 显式 JSON null → 得字面 "null"（非空白），不判缺、不穿透到 message
        assertEquals(
            BotSendNoticeFields("c1", "null"),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "text" to JsonNull,
                        "message" to JsonPrimitive("不该被穿透"),
                    )
                )
            ),
            "text 显式 JSON null 必须得字面 \"null\"、不穿透到 message",
        )
    }

    @Test
    fun `sendNotice text legacy quirks are pinned`() {
        // 不 trim：前导空格原样保留并计入 300 上限
        // 注：repeat 提到模板外——Kotlin 2.4.0（K2）下模板内转义引号嵌套报 Syntax error。
        val xs = "x".repeat(340)
        val leading = "  系统通知 " + xs
        val expected = leading.take(300)
        assertEquals(
            BotSendNoticeFields("c1", expected),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "text" to JsonPrimitive(leading),
                    )
                )
            ),
            "text 不 trim、前导空格计入 take(300) 上限",
        )
        // 超长截 300
        val long = "y".repeat(500)
        assertEquals(
            "y".repeat(300),
            fieldsOf(validBase(text = long)).text,
            "text 超长截 300",
        )
        // text 键缺席 → 穿透到 message
        assertEquals(
            "穿透文本",
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "message" to JsonPrimitive("穿透文本"),
                    )
                )
            ).text,
            "text 缺席时 message 别名必须穿透",
        )
        // text 与 message 同时存在 → text 优先
        assertEquals(
            "主文本",
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "text" to JsonPrimitive("主文本"),
                        "message" to JsonPrimitive("别名文本"),
                    )
                )
            ).text,
            "text 与 message 并存时 text 优先",
        )
    }

    @Test
    fun `sendNotice content template is pinned`() {
        assertEquals(
            "NOTICE: 系统通知",
            buildBotNoticeContent("系统通知"),
            "内容模板必须与原处理器逐字一致",
        )
        assertEquals(
            "NOTICE: null",
            buildBotNoticeContent("null"),
            "字面 null 文本的内容模板",
        )
    }

    @Test
    fun `sendNotice wrong-typed known fields fail loudly`() {
        // 对象型 chatId → IllegalArgumentException（StatusPages 映射 400，不是 500）
        assertFailsWith<IllegalArgumentException> {
            parseBotSendNoticeFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonObject(mapOf("x" to JsonPrimitive(1))),
                        "text" to JsonPrimitive("t"),
                    )
                )
            )
        }
        // 数组型 text → IllegalArgumentException
        assertFailsWith<IllegalArgumentException> {
            parseBotSendNoticeFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "text" to JsonArray(listOf(JsonPrimitive("t"))),
                    )
                )
            )
        }
        // 对象型 message 别名 → IllegalArgumentException
        assertFailsWith<IllegalArgumentException> {
            parseBotSendNoticeFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "message" to JsonObject(mapOf("x" to JsonPrimitive(1))),
                    )
                )
            )
        }
        // 对象型 text → IllegalArgumentException（即使 message 别名合法也不穿透）
        assertFailsWith<IllegalArgumentException> {
            parseBotSendNoticeFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "text" to JsonObject(mapOf("x" to JsonPrimitive(1))),
                        "message" to JsonPrimitive("别名文本"),
                    )
                )
            )
        }
    }
}
