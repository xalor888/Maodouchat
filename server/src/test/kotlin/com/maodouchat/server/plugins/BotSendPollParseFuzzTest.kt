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

class BotSendPollParseFuzzTest {

    private companion object {
        /** sendPoll 解析涉及的全部已知字段名（含别名）：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId", "question", "options",
            "multi", "allowsMultipleAnswers",
            "anonymous", "isAnonymous",
            "closesAt", "closeDate",
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

    private fun fieldsOf(obj: JsonObject): BotSendPollFields {
        val result = parseBotSendPollFields(obj)
        assertTrue(result is BotSendPollFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    private fun validBase(
        chatId: String = "c1",
        question: String = "今晚吃什么？",
        options: List<String> = listOf("火锅", "烧烤"),
        multi: Boolean = false,
        anonymous: Boolean = true,
        closesAt: Long = 1_700_000_000L,
    ): JsonObject = JsonObject(
        mapOf(
            "chatId" to JsonPrimitive(chatId),
            "question" to JsonPrimitive(question),
            "options" to JsonArray(options.map { JsonPrimitive(it) }),
            "multi" to JsonPrimitive(multi),
            "anonymous" to JsonPrimitive(anonymous),
            "closesAt" to JsonPrimitive(closesAt),
        )
    )

    @Test
    fun `sendPoll parse survives seeded unknown-field fuzz`() {
        val random = Random(0x50_11)
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val question = "q-$i"
            val options = listOf("opt-a-$i", "opt-b-$i", "opt-c-$i")
            val multi = random.nextBoolean()
            val anonymous = random.nextBoolean()
            val closesAt = random.nextLong(1_000_000_000L, 9_999_999_999L)
            val base = JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive(chatId),
                    "question" to JsonPrimitive(question),
                    "options" to JsonArray(options.map { JsonPrimitive(it) }),
                    "multi" to JsonPrimitive(multi),
                    "anonymous" to JsonPrimitive(anonymous),
                    "closesAt" to JsonPrimitive(closesAt),
                )
            )
            val obj = injectUnknownFields(base, random)
            val fields = fieldsOf(obj)
            assertEquals(
                BotSendPollFields(chatId, question, options, multi, anonymous, closesAt),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
        }
    }

    @Test
    fun `sendPoll required fields are enforced`() {
        // chatId 缺失 → MissingRequired
        assertTrue(
            parseBotSendPollFields(
                JsonObject(
                    mapOf(
                        "question" to JsonPrimitive("q"),
                        "options" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
                    )
                )
            ) is BotSendPollFieldsResult.MissingRequired,
            "chatId 缺失必须判缺",
        )
        // question 缺失 → MissingRequired
        assertTrue(
            parseBotSendPollFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "options" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
                    )
                )
            ) is BotSendPollFieldsResult.MissingRequired,
            "question 缺失必须判缺",
        )
        // 空白 question → MissingRequired（question 本身不 trim，空白判缺靠 isBlank）
        assertTrue(
            parseBotSendPollFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "question" to JsonPrimitive("   "),
                        "options" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
                    )
                )
            ) is BotSendPollFieldsResult.MissingRequired,
            "空白 question 必须判缺",
        )
        // options 缺失 → MissingRequired
        assertTrue(
            parseBotSendPollFields(
                JsonObject(mapOf("chatId" to JsonPrimitive("c"), "question" to JsonPrimitive("q")))
            ) is BotSendPollFieldsResult.MissingRequired,
            "options 缺失必须判缺",
        )
        // options 只有 1 个 → MissingRequired
        assertTrue(
            parseBotSendPollFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "question" to JsonPrimitive("q"),
                        "options" to JsonArray(listOf(JsonPrimitive("a"))),
                    )
                )
            ) is BotSendPollFieldsResult.MissingRequired,
            "options 不足 2 个必须判缺",
        )
        // options 恰 2 个 → Ok
        assertTrue(
            parseBotSendPollFields(validBase()) is BotSendPollFieldsResult.Ok,
            "options 恰 2 个必须通过",
        )
    }

    @Test
    fun `sendPoll option filtering keeps production quirks`() {
        // 元素 trim + 空白剔除
        val trimmed = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "question" to JsonPrimitive("q"),
                    "options" to JsonArray(
                        listOf(
                            JsonPrimitive("  火锅  "),
                            JsonPrimitive("   "),
                            JsonPrimitive("烧烤"),
                        )
                    ),
                )
            )
        )
        assertEquals(listOf("火锅", "烧烤"), trimmed.options, "选项元素必须 trim 并剔除空白")

        // 显式 JSON null 是 JsonPrimitive：content 为 "null" 字符串，保留为字面选项
        val withNull = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "question" to JsonPrimitive("q"),
                    "options" to JsonArray(listOf(JsonPrimitive("a"), JsonNull, JsonPrimitive("b"))),
                )
            )
        )
        assertEquals(listOf("a", "null", "b"), withNull.options, "显式 null 必须保留为字面 \"null\" 选项")

        // 非原始类型元素（对象/数组）被 runCatching 静默丢弃
        val withJunk = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "question" to JsonPrimitive("q"),
                    "options" to JsonArray(
                        listOf(
                            JsonPrimitive("a"),
                            JsonObject(mapOf("k" to JsonPrimitive("v"))),
                            JsonArray(listOf(JsonPrimitive("x"))),
                            JsonPrimitive("b"),
                        )
                    ),
                )
            )
        )
        assertEquals(listOf("a", "b"), withJunk.options, "非原始类型元素必须被静默丢弃")

        // options 非数组不是错误：emptyList → 判缺（不是抛错）
        assertTrue(
            parseBotSendPollFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "question" to JsonPrimitive("q"),
                        "options" to JsonPrimitive("not-an-array"),
                    )
                )
            ) is BotSendPollFieldsResult.MissingRequired,
            "options 非数组必须走 emptyList → 判缺，而不是抛错",
        )
    }

    @Test
    fun `sendPoll boolean aliases resolve in order with penetration`() {
        // multi 主字段优先（false 也是有效值，不穿透）
        assertEquals(
            false,
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "question" to JsonPrimitive("q"),
                        "options" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
                        "multi" to JsonPrimitive(false),
                        "allowsMultipleAnswers" to JsonPrimitive(true),
                    )
                )
            ).multi,
            "multi=false 必须优先于别名（false 是有效值，不穿透）",
        )
        // multi 非布尔字符串 → booleanOrNull 为 null → 穿透到别名
        assertEquals(
            true,
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "question" to JsonPrimitive("q"),
                        "options" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
                        "multi" to JsonPrimitive("yes"),
                        "allowsMultipleAnswers" to JsonPrimitive(true),
                    )
                )
            ).multi,
            "multi 非布尔值必须穿透到 allowsMultipleAnswers",
        )
        // 缺省 multi → false；缺省 anonymous → true
        val defaults = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "question" to JsonPrimitive("q"),
                    "options" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
                )
            )
        )
        assertEquals(false, defaults.multi, "multi 缺省必须为 false")
        assertEquals(true, defaults.anonymous, "anonymous 缺省必须为 true（投票默认匿名）")
        assertEquals(null, defaults.closesAt, "closesAt 缺省必须为 null")
        // anonymous 别名 isAnonymous
        assertEquals(
            false,
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "question" to JsonPrimitive("q"),
                        "options" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
                        "isAnonymous" to JsonPrimitive(false),
                    )
                )
            ).anonymous,
            "anonymous 缺省时 isAnonymous=false 必须生效",
        )
    }

    @Test
    fun `sendPoll closesAt aliases resolve in order with penetration`() {
        // 主字段 closesAt 优先
        assertEquals(
            1_700_000_000L,
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "question" to JsonPrimitive("q"),
                        "options" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
                        "closesAt" to JsonPrimitive(1_700_000_000L),
                        "closeDate" to JsonPrimitive(1_800_000_000L),
                    )
                )
            ).closesAt,
            "closesAt 必须优先于 closeDate",
        )
        // 主字段非数字字符串 → toLongOrNull 为 null → 穿透到 closeDate
        assertEquals(
            1_800_000_000L,
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "question" to JsonPrimitive("q"),
                        "options" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
                        "closesAt" to JsonPrimitive("not-a-number"),
                        "closeDate" to JsonPrimitive("1800000000"),
                    )
                )
            ).closesAt,
            "closesAt 非数字必须穿透到 closeDate",
        )
    }

    @Test
    fun `sendPoll wrong-typed known fields fail loudly`() {
        val badChatId = JsonObject(
            mapOf(
                "chatId" to JsonObject(mapOf("nested" to JsonPrimitive("x"))),
                "question" to JsonPrimitive("q"),
                "options" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
            )
        )
        assertFailsWith<IllegalArgumentException>("对象型 chatId 必须抛 IllegalArgumentException") {
            parseBotSendPollFields(badChatId)
        }
        val badQuestion = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "question" to JsonArray(emptyList()),
                "options" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
            )
        )
        assertFailsWith<IllegalArgumentException>("数组型 question 必须抛 IllegalArgumentException") {
            parseBotSendPollFields(badQuestion)
        }
        val badMulti = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "question" to JsonPrimitive("q"),
                "options" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
                "multi" to JsonObject(emptyMap()),
            )
        )
        assertFailsWith<IllegalArgumentException>("对象型 multi 必须抛 IllegalArgumentException") {
            parseBotSendPollFields(badMulti)
        }
        val badClosesAt = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "question" to JsonPrimitive("q"),
                "options" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
                "closesAt" to JsonArray(listOf(JsonPrimitive(1L))),
            )
        )
        assertFailsWith<IllegalArgumentException>("数组型 closesAt 必须抛 IllegalArgumentException") {
            parseBotSendPollFields(badClosesAt)
        }
    }

    @Test
    fun `sendPoll near-miss field names are ignored as unknown`() {
        val obj = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "question" to JsonPrimitive("q"),
                "options" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
                // 近似字段名：大小写/前后缀之差，必须按未知键忽略
                "ChatId" to JsonPrimitive("WRONG"),
                "chatid" to JsonPrimitive("WRONG"),
                "Questions" to JsonPrimitive("WRONG"),
                "Multi" to JsonPrimitive(true),
                "multi2" to JsonPrimitive(true),
                "allowMultipleAnswers" to JsonPrimitive(true),
                "close_date" to JsonPrimitive(1_800_000_000L),
                "ClosesAt" to JsonPrimitive(1_800_000_000L),
            )
        )
        val fields = fieldsOf(obj)
        assertEquals("c", fields.chatId, "近似字段名不得覆盖 chatId")
        assertEquals("q", fields.question, "近似字段名不得覆盖 question")
        assertEquals(false, fields.multi, "近似字段名不得覆盖 multi（保持缺省 false）")
        assertEquals(true, fields.anonymous, "近似字段名不得覆盖 anonymous（保持缺省 true）")
        assertEquals(null, fields.closesAt, "近似字段名不得覆盖 closesAt（保持缺省 null）")
    }

    @Test
    fun `sendPoll summary template is pinned`() {
        assertEquals(
            "📊 今晚吃什么？\n1. 火锅\n2. 烧烤\n[poll:poll-1]",
            buildBotPollSummary("今晚吃什么？", listOf("火锅", "烧烤"), "poll-1"),
            "摘要模板必须逐字一致",
        )
        assertEquals(
            "📊 q\n1. a\n2. b\n3. c\n[poll:x]",
            buildBotPollSummary("q", listOf("a", "b", "c"), "x"),
            "三选项摘要模板必须逐字一致",
        )
    }
}
