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
 * Bot `sendBanner` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，G355
 * `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、`sendPhoto`、
 * `sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、`sendVenue`、
 * `sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、
 * `sendChecklist`、`sendAlert`、`sendCountdown`、`sendNotice`、`sendBadge`、
 * `sendToast`、`sendHr`、`sendDivider`、`sendProgress`、`send*Hint`、
 * `sendMentionCard`/`sendNudgeCard`、`sendMetric`/`sendCompare`、`sendKeyValue`、
 * `sendQuoteCard` 之后第三十三块）。
 *
 * 本测试直接钉住纯函数 ([parseBotBannerFields] / [buildBotBannerContent])
 * 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]，含 `message`——它是
 *   `text` 的存在性回退键，同样视为已知）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 钉住 `title` 的空白性默认值：缺席**与**空白都得 `"Banner"`（显式 null 得字面
 *   `"null"` 不触发 `ifBlank`），`.take(40)` 接在 `ifBlank` 之后。
 * - 钉住 `text` 的存在性回退：只有 `text` 键完全缺席才看 `message`；`text` 在但为
 *   显式 null 时仍走 `text` 分支得字面 `"null"`。
 * - 钉住**双必填**（`chatId` 或 `text` 缺/空白 → 400），`title` 非必填。
 * - 钉住内容模板逐字一致（`"## " + title + "\n" + text`，与处理器源码逐字对过）。
 * - 反证 `wrong-typed known fields still fail loudly`：手写解析里 `?.jsonPrimitive`
 *   在类型错时抛 [IllegalArgumentException]，路由层 `StatusPages` 把它映射为 400
 *   「参数无效」（不是 500）——坏数据必须大声失败，不能悄悄吞掉。
 * - 反证抽取顺序：`chatId` 空白 + 后续字段坏类型 → 仍抛错而非回 `MissingRequired`
 *   （抽取在必填校验之前，原处理器逐字如此）。
 */
class BotSendBannerParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("chatId", "title", "text", "message")
        private const val ITERATIONS = 150
        private const val TITLE_CAP = 40
        private const val TEXT_CAP = 240
        private const val DEFAULT_TITLE = "Banner"
    }

    private fun parseOf(obj: JsonObject): BotBannerFieldsResult =
        parseBotBannerFields(obj)

    private fun fieldsOf(obj: JsonObject): BotBannerFields {
        val result = parseOf(obj)
        assertTrue(result is BotBannerFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
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
    fun `banner survives seeded unknown-field fuzz`() {
        val random = Random(0x9E0A_2033)
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val entries = mutableMapOf<String, JsonElement>(
                "chatId" to JsonPrimitive(chatId),
            )
            val expectedTitle: String
            if (i % 3 == 0) {
                // 三分之一 title 缺省：钉住 title 回 "Banner"
                expectedTitle = DEFAULT_TITLE
            } else {
                val t = "t-$i some banner title"
                entries["title"] = JsonPrimitive(t)
                expectedTitle = t.take(TITLE_CAP)
            }
            val text = "txt-$i some banner body"
            if (i % 3 == 1) {
                // 另三分之一 text 经存在性回退键 message 传入
                entries["message"] = JsonPrimitive(text)
            } else {
                entries["text"] = JsonPrimitive(text)
            }
            val payload = injectUnknownFields(JsonObject(entries), random)
            val fields = fieldsOf(payload)
            assertEquals(chatId, fields.chatId, "chatId 必须原样保留")
            assertEquals(expectedTitle, fields.title, "title 逐字一致")
            assertEquals(text.take(TEXT_CAP), fields.text, "text 逐字一致")
        }
    }

    @Test
    fun `dual required chatId and text semantics`() {
        // chatId 缺 / 空 / 纯空白 → MissingRequired
        assertTrue(
            parseOf(JsonObject(emptyMap())) is BotBannerFieldsResult.MissingRequired,
            "chatId 缺席必须判缺",
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive(""))))
                is BotBannerFieldsResult.MissingRequired,
            "chatId 空字符串必须判缺",
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("   "))))
                is BotBannerFieldsResult.MissingRequired,
            "chatId 纯空白必须判缺",
        )
        // text 缺 / 空 / 纯空白 → MissingRequired（chatId 合法）
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"))))
                is BotBannerFieldsResult.MissingRequired,
            "text 缺席必须判缺",
        )
        assertTrue(
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "text" to JsonPrimitive(""),
                    ),
                ),
            ) is BotBannerFieldsResult.MissingRequired,
            "text 空字符串必须判缺",
        )
        assertTrue(
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "text" to JsonPrimitive("   "),
                    ),
                ),
            ) is BotBannerFieldsResult.MissingRequired,
            "text 纯空白必须判缺",
        )
        // title 缺省 / 空白都不判缺（回 "Banner"）
        val blankTitle = mapOf(
            "chatId" to JsonPrimitive("c"),
            "text" to JsonPrimitive("t"),
            "title" to JsonPrimitive(""),
        )
        val fields = fieldsOf(JsonObject(blankTitle))
        assertEquals("c", fields.chatId)
        assertEquals("t", fields.text)
        assertEquals(DEFAULT_TITLE, fields.title, "title 空白回默认，不判缺")
    }

    @Test
    fun `caps defaults and null semantics are pinned`() {
        // title 超长截 40（ifBlank 在 take 之前）
        val longTitle = "y".repeat(50)
        val overTitle = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "title" to JsonPrimitive(longTitle),
                "text" to JsonPrimitive("t"),
            ),
        )
        assertEquals(longTitle.take(TITLE_CAP), fieldsOf(overTitle).title, "title 超长截 40")
        // text 超长截 240
        val longText = "z".repeat(300)
        val overText = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "text" to JsonPrimitive(longText),
            ),
        )
        assertEquals(longText.take(TEXT_CAP), fieldsOf(overText).text, "text 超长截 240")
        // title 缺席 → "Banner"
        val missingTitle = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "text" to JsonPrimitive("t"),
            ),
        )
        assertEquals(DEFAULT_TITLE, fieldsOf(missingTitle).title, "title 缺席回默认")
        // title 显式 JSON null → 字面 "null"，不触发 ifBlank（"null" 非空白）
        val nullTitle = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "title" to JsonNull,
                "text" to JsonPrimitive("t"),
            ),
        )
        assertEquals("null", fieldsOf(nullTitle).title, "title 显式 null 得字面 null")
        // text 显式 JSON null → 字面 "null"，不被 orEmpty 吞掉（text 非空故仍判合法）
        val nullText = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "text" to JsonNull,
            ),
        )
        assertEquals("null", fieldsOf(nullText).text, "text 显式 null 得字面 null")
        // text 键在但为 null 时不触发回退（message 被忽略）
        val nullTextWithMessage = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "text" to JsonNull,
                "message" to JsonPrimitive("fallback"),
            ),
        )
        assertEquals(
            "null",
            fieldsOf(nullTextWithMessage).text,
            "text 键在时 message 回退键不生效",
        )
        // text 缺席时回退到 message
        val fallbackText = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "message" to JsonPrimitive("via-message"),
            ),
        )
        assertEquals("via-message", fieldsOf(fallbackText).text, "text 缺席回退到 message")
        // text 空白时仍走 text 分支（不触发回退），随后判缺
        val blankTextWithMessage = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "text" to JsonPrimitive(""),
                "message" to JsonPrimitive("fallback"),
            ),
        )
        assertTrue(
            parseOf(blankTextWithMessage) is BotBannerFieldsResult.MissingRequired,
            "text 空白不回退，直接判缺",
        )
        // JSON 数字经 .content 照样解析
        val numText = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "text" to JsonPrimitive(42),
            ),
        )
        assertEquals("42", fieldsOf(numText).text, "数字 text 经 content 解析")
        // 前导空格计入上限（title 不触发默认时）
        val padded = "  " + "w".repeat(50)
        val paddedTitle = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "title" to JsonPrimitive(padded),
                "text" to JsonPrimitive("t"),
            ),
        )
        assertEquals(
            padded.take(TITLE_CAP),
            fieldsOf(paddedTitle).title,
            "title 前导空格计入 40 上限",
        )
    }

    @Test
    fun `content template is pinned verbatim`() {
        assertEquals("## Hi\nbody", buildBotBannerContent("Hi", "body"))
        // title 缺省 → "Banner"
        assertEquals("## Banner\nbody", buildBotBannerContent(DEFAULT_TITLE, "body"))
        // 与纯函数解析链路打通：markdown 特殊字符原样进模板
        val parsed = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "title" to JsonPrimitive("a*b"),
                    "text" to JsonPrimitive("x_y"),
                ),
            ),
        )
        assertEquals("## a*b\nx_y", buildBotBannerContent(parsed.title, parsed.text))
        // 与处理器源码逐字对过：`"## " + title + "\n" + text`
        assertTrue(
            buildBotBannerContent("T", "B").startsWith("## T"),
            "内容以标题行开头",
        )
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
        // 对象 / 数组型 title、text
        for (key in listOf("title", "text")) {
            assertFailsWith<IllegalArgumentException>("对象型 " + key + " 必须大声失败") {
                parseOf(
                    JsonObject(
                        mapOf(
                            "chatId" to JsonPrimitive("c"),
                            "text" to JsonPrimitive("t"),
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
                            "text" to JsonPrimitive("t"),
                            key to JsonArray(emptyList()),
                        ),
                    ),
                )
            }
        }
        // 对象 / 数组型 message：只有 text 缺席时 message 才被读取（存在性回退），
        // 此时坏类型同样大声失败；text 在时 message 坏类型不会被触及（原处理器逐字如此）
        assertFailsWith<IllegalArgumentException>("text 缺席时对象型 message 必须大声失败") {
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "message" to JsonObject(emptyMap()),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException>("text 缺席时数组型 message 必须大声失败") {
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "message" to JsonArray(emptyList()),
                    ),
                ),
            )
        }
    }

    @Test
    fun `extraction order is pinned`() {
        // chatId 空白 + 后续字段坏类型 → 仍抛错而非回 MissingRequired（抽取在必填校验之前）
        assertFailsWith<IllegalArgumentException>("chatId 空白时 title 坏类型仍须大声失败") {
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive(""),
                        "title" to JsonArray(emptyList()),
                        "text" to JsonPrimitive("t"),
                    ),
                ),
            )
        }
        // chatId 与 title 同时坏类型 → 抛错（chatId 先抽取）
        assertFailsWith<IllegalArgumentException>("chatId 先于 title 抽取") {
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonObject(emptyMap()),
                        "title" to JsonObject(emptyMap()),
                        "text" to JsonPrimitive("t"),
                    ),
                ),
            )
        }
        // title 坏类型时 text 缺席仍抛错（title 先于 text 抽取）
        assertFailsWith<IllegalArgumentException>("title 先于 text 抽取") {
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "title" to JsonArray(emptyList()),
                    ),
                ),
            )
        }
    }
}
