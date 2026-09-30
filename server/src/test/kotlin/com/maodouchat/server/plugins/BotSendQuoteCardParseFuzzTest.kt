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

/**
 * Bot `sendQuoteCard` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，G355
 * `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、`sendPhoto`、
 * `sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、`sendVenue`、
 * `sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、
 * `sendChecklist`、`sendAlert`、`sendCountdown`、`sendNotice`、`sendBadge`、
 * `sendToast`、`sendHr`、`sendDivider`、`sendProgress`、`send*Hint`、
 * `sendMentionCard`/`sendNudgeCard`、`sendMetric`/`sendCompare`、`sendKeyValue`
 * 之后第三十二块）。
 *
 * 本测试直接钉住纯函数 ([parseBotQuoteCardFields] / [buildBotQuoteCardContent])
 * 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 钉住 `quote` / `by` 的逐字抽取顺序：两者都**无默认值**（缺席得 `""`），
 *   `.take(200)` / `.take(40)`；空白原样保留；显式 JSON null 得字面 `"null"`
 *   （不被 `orEmpty` 吞掉）。
 * - 钉住**双必填**（`chatId` 或 `quote` 缺/空白 → 400），`by` 非必填——
 *   缺省/空白都不判缺，空白 `by` 组装时吞掉署名行。
 * - 钉住内容模板逐字一致（`"> "` + quote + 署名行，与处理器源码逐字对过）。
 * - 反证 `wrong-typed known fields still fail loudly`：手写解析里 `?.jsonPrimitive`
 *   在类型错时抛 [IllegalArgumentException]，路由层 `StatusPages` 把它映射为 400
 *   「参数无效」（不是 500）——坏数据必须大声失败，不能悄悄吞掉。
 * - 反证抽取顺序：`chatId` 空白 + 后续字段坏类型 → 仍抛错而非回 `MissingRequired`
 *   （抽取在必填校验之前，原处理器逐字如此）。
 */
class BotSendQuoteCardParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("chatId", "quote", "by")
        private const val ITERATIONS = 150
        private const val QUOTE_CAP = 200
        private const val BY_CAP = 40
    }

    private fun parseOf(obj: JsonObject): BotQuoteCardFieldsResult =
        parseBotQuoteCardFields(obj)

    private fun fieldsOf(obj: JsonObject): BotQuoteCardFields {
        val result = parseOf(obj)
        assertTrue(result is BotQuoteCardFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
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

    @Test
    fun `quoteCard survives seeded unknown-field fuzz`() {
        val random = Random(0x9E0A_2032)
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val quote = "q-$i some quote text"
            val entries = mutableMapOf<String, JsonElement>(
                "chatId" to JsonPrimitive(chatId),
                "quote" to JsonPrimitive(quote),
            )
            val expectedBy: String
            if (i % 3 == 0) {
                // 三分之一 by 缺省：钉住 by 回 ""
                expectedBy = ""
            } else {
                val b = "b-$i"
                entries["by"] = JsonPrimitive(b)
                expectedBy = b.take(BY_CAP)
            }
            val payload = injectUnknownFields(JsonObject(entries), random)
            val fields = fieldsOf(payload)
            assertEquals(chatId, fields.chatId, "chatId 必须原样保留")
            assertEquals(quote.take(QUOTE_CAP), fields.quote, "quote 逐字一致")
            assertEquals(expectedBy, fields.by, "by 逐字一致")
        }
    }

    @Test
    fun `dual required chatId and quote semantics`() {
        // chatId 缺 / 空 / 纯空白 → MissingRequired
        assertTrue(
            parseOf(JsonObject(emptyMap())) is BotQuoteCardFieldsResult.MissingRequired,
            "chatId 缺席必须判缺",
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive(""))))
                is BotQuoteCardFieldsResult.MissingRequired,
            "chatId 空字符串必须判缺",
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("   "))))
                is BotQuoteCardFieldsResult.MissingRequired,
            "chatId 纯空白必须判缺",
        )
        // quote 缺 / 空 / 纯空白 → MissingRequired（chatId 合法）
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"))))
                is BotQuoteCardFieldsResult.MissingRequired,
            "quote 缺席必须判缺",
        )
        assertTrue(
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "quote" to JsonPrimitive(""),
                    ),
                ),
            ) is BotQuoteCardFieldsResult.MissingRequired,
            "quote 空字符串必须判缺",
        )
        assertTrue(
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "quote" to JsonPrimitive("   "),
                    ),
                ),
            ) is BotQuoteCardFieldsResult.MissingRequired,
            "quote 纯空白必须判缺",
        )
        // by 缺省 / 空白都不判缺
        val blankBy = mapOf(
            "chatId" to JsonPrimitive("c"),
            "quote" to JsonPrimitive("q"),
            "by" to JsonPrimitive(""),
        )
        val fields = fieldsOf(JsonObject(blankBy))
        assertEquals("c", fields.chatId)
        assertEquals("q", fields.quote)
        assertEquals("", fields.by, "by 空白原样保留，不判缺")
    }

    @Test
    fun `caps and null semantics are pinned`() {
        // quote 超长截 200
        val longQuote = "y".repeat(250)
        val overQuote = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "quote" to JsonPrimitive(longQuote),
            ),
        )
        assertEquals(longQuote.take(QUOTE_CAP), fieldsOf(overQuote).quote, "quote 超长截 200")
        // by 超长截 40
        val longBy = "z".repeat(50)
        val overBy = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "quote" to JsonPrimitive("q"),
                "by" to JsonPrimitive(longBy),
            ),
        )
        assertEquals(longBy.take(BY_CAP), fieldsOf(overBy).by, "by 超长截 40")
        // 显式 JSON null → 字面 "null"，不被 orEmpty 吞掉（quote 非空故仍判合法）
        val nullQuote = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "quote" to JsonNull,
            ),
        )
        assertEquals("null", fieldsOf(nullQuote).quote, "quote 显式 null 得字面 null")
        val nullBy = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "quote" to JsonPrimitive("q"),
                "by" to JsonNull,
            ),
        )
        assertEquals("null", fieldsOf(nullBy).by, "by 显式 null 得字面 null")
        // JSON 数字经 .content 照样解析
        val numQuote = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "quote" to JsonPrimitive(42),
            ),
        )
        assertEquals("42", fieldsOf(numQuote).quote, "数字 quote 经 content 解析")
        // 前导空格计入上限（quote 不触发任何默认）
        val padded = "  " + "w".repeat(250)
        val paddedQuote = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "quote" to JsonPrimitive(padded),
            ),
        )
        assertEquals(padded.take(QUOTE_CAP), fieldsOf(paddedQuote).quote, "quote 前导空格计入 200 上限")
    }

    @Test
    fun `content template is pinned verbatim`() {
        assertEquals("> hello\n— *bob*", buildBotQuoteCardContent("hello", "bob"))
        // by 缺省 / 空白 → 无署名行
        assertEquals("> hello", buildBotQuoteCardContent("hello", ""))
        assertEquals("> hello", buildBotQuoteCardContent("hello", "   "))
        // 与纯函数解析链路打通：markdown 特殊字符原样进模板
        val parsed = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "quote" to JsonPrimitive("a*b"),
                    "by" to JsonPrimitive("x_y"),
                ),
            ),
        )
        assertEquals("> a*b\n— *x_y*", buildBotQuoteCardContent(parsed.quote, parsed.by))
        // 与处理器源码逐字对过："> $quote$attribution"，attribution 为空时不留尾随换行
        assertTrue(buildBotQuoteCardContent("q", "").endsWith("q"), "无署名时内容以 quote 结尾")
    }

    @Test
    fun `wrong-typed known fields still fail loudly`() {
        // 对象型 chatId
        assertFailsWith<IllegalArgumentException>("对象型 chatId 必须大声失败") {
            parseOf(JsonObject(mapOf("chatId" to JsonObject(emptyMap()))))
        }
        // 数组型 chatId
        assertFailsWith<IllegalArgumentException>("数组型 chatId 必须大声失败") {
            parseOf(JsonObject(mapOf("chatId" to JsonArray(emptyList()))))
        }
        // 对象 / 数组型 quote、by
        for (key in listOf("quote", "by")) {
            assertFailsWith<IllegalArgumentException>("对象型 " + key + " 必须大声失败") {
                parseOf(
                    JsonObject(
                        mapOf(
                            "chatId" to JsonPrimitive("c"),
                            "quote" to JsonPrimitive("q"),
                            key to JsonObject(emptyMap()),
                        ),
                    ),
                )
            }
            assertFailsWith<IllegalArgumentException>("数组型 " + key + " 必须大声失败") {
                parseOf(
                    JsonObject(
                        mapOf(
                            "chatId" to JsonPrimitive("c"),
                            "quote" to JsonPrimitive("q"),
                            key to JsonArray(emptyList()),
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun `extraction order is pinned`() {
        // chatId 空白 + 后续字段坏类型 → 仍抛错而非回 MissingRequired（抽取在必填校验之前）
        assertFailsWith<IllegalArgumentException>("chatId 空白时 quote 坏类型仍须大声失败") {
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive(""),
                        "quote" to JsonArray(emptyList()),
                    ),
                ),
            )
        }
        // chatId 与 quote 同时坏类型 → 抛错（chatId 先抽取）
        assertFailsWith<IllegalArgumentException>("chatId 先于 quote 抽取") {
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonObject(emptyMap()),
                        "quote" to JsonObject(emptyMap()),
                    ),
                ),
            )
        }
        // quote 坏类型时 by 缺席仍抛错（quote 先于 by 抽取）
        assertFailsWith<IllegalArgumentException>("quote 先于 by 抽取") {
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "quote" to JsonArray(emptyList()),
                    ),
                ),
            )
        }
    }
}
