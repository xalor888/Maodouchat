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

class BotSendAlertParseFuzzTest {

    private companion object {
        /** sendAlert 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId", "text", "message", "level",
        )
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

    private fun fieldsOf(obj: JsonObject): BotSendAlertFields {
        val result = parseBotSendAlertFields(obj)
        assertTrue(result is BotSendAlertFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    private fun validBase(
        chatId: String = "c1",
        text: String = "系统维护通知",
        level: String = "warning",
    ): JsonObject = JsonObject(
        mapOf(
            "chatId" to JsonPrimitive(chatId),
            "text" to JsonPrimitive(text),
            "level" to JsonPrimitive(level),
        )
    )

    @Test
    fun `sendAlert parse survives seeded unknown-field fuzz`() {
        val random = Random(0xA1_E47)
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val text = "alert-$i"
            val level = "lv-$i"
            val base = JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive(chatId),
                    "text" to JsonPrimitive(text),
                    "level" to JsonPrimitive(level),
                )
            )
            val obj = injectUnknownFields(base, random)
            val fields = fieldsOf(obj)
            assertEquals(
                BotSendAlertFields(chatId, text, level),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
        }
    }

    @Test
    fun `sendAlert required fields are enforced`() {
        // chatId 缺失 → MissingRequired
        assertTrue(
            parseBotSendAlertFields(
                JsonObject(
                    mapOf(
                        "text" to JsonPrimitive("t"),
                        "level" to JsonPrimitive("info"),
                    )
                )
            ) is BotSendAlertFieldsResult.MissingRequired,
            "chatId 缺失必须判缺",
        )
        // chatId 空白 → MissingRequired
        assertTrue(
            parseBotSendAlertFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("  "),
                        "text" to JsonPrimitive("t"),
                    )
                )
            ) is BotSendAlertFieldsResult.MissingRequired,
            "chatId 空白必须判缺",
        )
        // text 缺失（且 message 别名也缺） → MissingRequired
        assertTrue(
            parseBotSendAlertFields(
                JsonObject(mapOf("chatId" to JsonPrimitive("c")))
            ) is BotSendAlertFieldsResult.MissingRequired,
            "text 缺失必须判缺",
        )
        // text 空白 → MissingRequired
        assertTrue(
            parseBotSendAlertFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "text" to JsonPrimitive("   "),
                    )
                )
            ) is BotSendAlertFieldsResult.MissingRequired,
            "text 空白必须判缺",
        )
        // message 别名满足必填
        assertTrue(
            parseBotSendAlertFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "message" to JsonPrimitive("via alias"),
                    )
                )
            ) is BotSendAlertFieldsResult.Ok,
            "message 别名必须能满足 text 必填",
        )
        // level 缺失/空白不判缺（回 "info"）
        assertEquals(
            "info",
            fieldsOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "text" to JsonPrimitive("t")))).level,
            "level 缺失必须回 info 且不判缺",
        )
        assertEquals(
            "info",
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "text" to JsonPrimitive("t"),
                        "level" to JsonPrimitive("  "),
                    )
                )
            ).level,
            "level 空白必须回 info 且不判缺",
        )
        // 合法请求 → Ok
        assertTrue(
            parseBotSendAlertFields(validBase()) is BotSendAlertFieldsResult.Ok,
            "合法请求必须通过",
        )
    }

    @Test
    fun `sendAlert text alias and truncation quirks`() {
        fun withText(vararg pairs: Pair<String, JsonElement>): BotSendAlertFields =
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                    ) + pairs.toMap()
                )
            )
        // text 不 trim：首尾空格原样保留
        assertEquals(
            "  前后空格  ",
            withText("text" to JsonPrimitive("  前后空格  ")).text,
            "text 必须不 trim，原样保留",
        )
        // take(300)：超长截断
        val long = "x".repeat(500)
        assertEquals(
            "x".repeat(300),
            withText("text" to JsonPrimitive(long)).text,
            "text 必须截断到 300 字符",
        )
        // text→message 别名：text 缺省时 message 生效
        assertEquals(
            "via alias",
            withText("message" to JsonPrimitive("via alias")).text,
            "text 缺省时 message 别名必须生效",
        )
        // text 存在即不穿透：哪怕显式 null（?: 接在存在性上）
        assertEquals(
            "null",
            withText("text" to JsonNull, "message" to JsonPrimitive("via alias")).text,
            "显式 null 的 text 不得穿透到 message，得字面 \"null\"",
        )
        // level 缺省回 info
        assertEquals("info", withText("text" to JsonPrimitive("t")).level, "level 缺省回 info")
        // 显式 JSON null 的 level 得字面 "null"（非空白，ifBlank 不触发）
        assertEquals(
            "null",
            withText("text" to JsonPrimitive("t"), "level" to JsonNull).level,
            "显式 null 的 level 是 JsonPrimitive、content 为 \"null\"，不得回 info",
        )
        // take(16)：超长 level 截断（发生在 ifBlank 之后）
        assertEquals(
            "l".repeat(16),
            withText("text" to JsonPrimitive("t"), "level" to JsonPrimitive("l".repeat(40))).level,
            "level 必须截断到 16 字符",
        )
    }

    @Test
    fun `sendAlert content assembly keeps production shape`() {
        assertEquals(
            "ALERT[warning]: 系统维护通知",
            buildBotAlertContent("系统维护通知", "warning"),
            "内容模板必须逐字一致：ALERT[level]: text",
        )
        assertEquals(
            "ALERT[info]: t",
            buildBotAlertContent("t", "info"),
            "默认 level 的内容模板必须逐字一致",
        )
    }

    @Test
    fun `sendAlert wrong-typed known fields fail loudly`() {
        // 对象型 chatId → IllegalArgumentException
        assertFailsWith<IllegalArgumentException>("对象型 chatId 必须抛 IllegalArgumentException") {
            parseBotSendAlertFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonObject(mapOf("nested" to JsonPrimitive("x"))),
                        "text" to JsonPrimitive("t"),
                    )
                )
            )
        }
        // 数组型 text → IllegalArgumentException
        assertFailsWith<IllegalArgumentException>("数组型 text 必须抛 IllegalArgumentException") {
            parseBotSendAlertFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "text" to JsonArray(emptyList()),
                    )
                )
            )
        }
        // 对象型 message 别名 → IllegalArgumentException（text 缺省时走 message 分支）
        assertFailsWith<IllegalArgumentException>("对象型 message 别名必须抛 IllegalArgumentException") {
            parseBotSendAlertFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "message" to JsonObject(mapOf("nested" to JsonPrimitive(1))),
                    )
                )
            )
        }
        // 对象型 level → IllegalArgumentException
        assertFailsWith<IllegalArgumentException>("对象型 level 必须抛 IllegalArgumentException") {
            parseBotSendAlertFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "text" to JsonPrimitive("t"),
                        "level" to JsonObject(mapOf("nested" to JsonPrimitive(1))),
                    )
                )
            )
        }
    }
}
