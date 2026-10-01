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
 * Bot `sendQuote` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，G355
 * `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、`sendPhoto`、
 * `sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、`sendVenue`、
 * `sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、
 * `sendChecklist`、`sendAlert`、`sendCountdown`、`sendNotice`、`sendBadge`、
 * `sendToast`、`sendHr`、`sendDivider`、`sendProgress`、`send*Hint`、
 * `sendMentionCard`/`sendNudgeCard`、`sendMetric`/`sendCompare`、`sendKeyValue`、
 * `sendQuoteCard`、`sendBanner`、`sendJsonCard`、`sendMarkdown` 之后第三十六块）。
 *
 * 本测试直接钉住纯函数 ([parseBotQuoteFields]) 与内容组装 ([buildBotQuoteContent])
 * 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]，含 `text`（`quote` 的
 *   存在性回退键）与 `note`）：否则测的是「重复键覆盖语义」，而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 钉住 `quote` 的存在性回退：只有 `quote` 键完全缺席才看 `text`；`quote` 在但为
 *   显式 null 时仍走 `quote` 分支（取到字面量 `"null"` 字符串，不回退、不抛——
 *   kotlinx-serialization-json 1.11.0 的 `JsonNull.content` 即 `"null"`，
 *   `sendMarkdown` 第三十五块 CI 已实证）。
 * - 钉住 `quote` 取 `?.jsonPrimitive?.content` **不是** `?.toString()`：字符串不带引号
 *   进模板（与 `sendJsonCard` 的怪语义相反，特意钉住）；`take(1500)` 作用于
 *   `orEmpty()` **之后**（裁的是 content 本身）；`note` 同理 `take(500)`。
 * - 钉住**双必填**（`chatId` 或 `quote` 缺/空白 → `MissingRequired`；判的是裁过
 *   1500 的空白性；`note` 再长也不参与必填）。
 * - 钉住内容组装：`quote.lines()` 逐行加 `"> "` 前缀；`note` 非空（`isNotBlank`，
 *   判的是裁过 500 的串）时以 `"\n\n"` 拼在引用块之后，否则只发引用块。
 * - 反证 `wrong-typed known fields still fail loudly`：手写解析里 `chatId` /
 *   `quote` / `note` 的 `?.jsonPrimitive` 在类型错时抛 [IllegalArgumentException]
 *   （对象 / 数组型值在 `?.jsonPrimitive` 处抛；显式 null 取到 `"null"` 字面量
 *   字符串、不抛；注意**数字 / 布尔不抛**，`JsonPrimitive.content` 对它们是
 *   `toString()`——特意钉住），路由层 `StatusPages` 把它映射为 400「参数无效」
 *   （不是 500）——坏数据必须大声失败，不能悄悄吞掉。
 * - 反证抽取顺序：`chatId` 先于 `quote` 抽取——`chatId` 坏类型 + `quote` 乱值→抛错；
 *   `chatId` 缺席 + `quote` 乱值→抛错（不是 `MissingRequired`：抽取抛在必填
 *   判断之前，原处理器逐字如此）。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
class BotSendQuoteParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("chatId", "quote", "text", "note")
        private const val ITERATIONS = 150
        private const val QUOTE_CAP = 1500
        private const val NOTE_CAP = 500
    }

    private fun parseOf(obj: JsonObject): BotQuoteFieldsResult =
        parseBotQuoteFields(obj)

    private fun okOf(obj: JsonObject): BotQuoteFields =
        (parseOf(obj) as BotQuoteFieldsResult.Ok).fields

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
        val random = Random(20261001)
        repeat(ITERATIONS) { i ->
            val mode = i % 3
            val base = mutableMapOf<String, JsonElement>()
            base["chatId"] = JsonPrimitive("chat-quote-" + i)
            // mode 0：quote 缺席 → 钉住 text 回退；mode 1：quote + text
            // 同时在场 → 钉住 quote 分支（text 被忽略）；mode 2：全字段抽取。
            val expectedQuote: String
            when (mode) {
                0 -> {
                    base["text"] = JsonPrimitive("fallback-q-" + i)
                    expectedQuote = "fallback-q-" + i
                }
                1 -> {
                    base["quote"] = JsonPrimitive("quote-wins-" + i)
                    base["text"] = JsonPrimitive("ignored-t-" + i)
                    expectedQuote = "quote-wins-" + i
                }
                else -> {
                    base["quote"] = JsonPrimitive("plain-q-" + i)
                    expectedQuote = "plain-q-" + i
                }
            }
            // mode 2 的一半用例钉住 take(1500) 作用于 content 之后。
            val longQuote = mode == 2 && i % 2 == 0
            if (longQuote) {
                base["quote"] = JsonPrimitive("q".repeat(QUOTE_CAP + 777))
            }
            // note：三分之一缺席（空串语义），其余钉住 take(500)。
            val expectedNote: String = if (i % 3 == 0) {
                ""
            } else if (i % 5 == 4) {
                base["note"] = JsonPrimitive("n".repeat(NOTE_CAP + 111))
                "n".repeat(NOTE_CAP)
            } else {
                base["note"] = JsonPrimitive("note-" + i)
                "note-" + i
            }
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val fields = okOf(JsonObject(base))
            assertEquals("chat-quote-" + i, fields.chatId, "未知键不得污染 chatId，迭代 " + i)
            val wantQuote = if (longQuote) "q".repeat(QUOTE_CAP) else expectedQuote
            assertEquals(wantQuote, fields.quote, "未知键不得污染 quote 抽取/回退/截断语义，迭代 " + i)
            assertEquals(expectedNote, fields.note, "未知键不得污染 note 抽取/截断语义，迭代 " + i)
            assertEquals(
                buildBotQuoteContent(wantQuote, expectedNote),
                buildBotQuoteContent(fields.quote, fields.note),
                "内容组装必须与字段逐字一致，迭代 " + i,
            )
        }
    }

    @Test
    fun missingRequiredSemantics() {
        // chatId 缺 / 空 / 纯空白 → MissingRequired。
        assertTrue(parseOf(JsonObject(mapOf("quote" to JsonPrimitive("hi")))) is BotQuoteFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive(""), "quote" to JsonPrimitive("hi")))) is BotQuoteFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("   "), "quote" to JsonPrimitive("hi")))) is BotQuoteFieldsResult.MissingRequired)
        // quote 与 text 均缺席 → MissingRequired。
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1")))) is BotQuoteFieldsResult.MissingRequired)
        // quote 空 / 纯空白 → MissingRequired（判的是 content 空白性）。
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "quote" to JsonPrimitive("")))) is BotQuoteFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "quote" to JsonPrimitive("  \t ")))) is BotQuoteFieldsResult.MissingRequired)
        // quote 缺席但 text 纯空白 → MissingRequired。
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "text" to JsonPrimitive("   ")))) is BotQuoteFieldsResult.MissingRequired)
        // note 再长也不参与必填：note 缺席 + 双合法 → Ok。
        val ok = okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "quote" to JsonPrimitive("hello"))))
        assertEquals("c1", ok.chatId)
        assertEquals("hello", ok.quote)
        assertEquals("", ok.note)
    }

    @Test
    fun capAndQuirks() {
        // take(1500) 作用于 orEmpty() 之后：超长 content 被裁到恰好 1500，不带引号。
        val long = "y".repeat(QUOTE_CAP + 999)
        assertEquals("y".repeat(QUOTE_CAP), okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "quote" to JsonPrimitive(long)))).quote)
        // 恰好 1500 不动。
        val exact = "z".repeat(QUOTE_CAP)
        assertEquals(exact, okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "quote" to JsonPrimitive(exact)))).quote)
        // note take(500) 同理。
        val longNote = "n".repeat(NOTE_CAP + 321)
        assertEquals("n".repeat(NOTE_CAP), okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "quote" to JsonPrimitive("q"), "note" to JsonPrimitive(longNote)))).note)
        // quote 显式 null → 不抛、不回退：取到 "null" 字面量字符串。
        assertEquals("null", okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "quote" to JsonNull, "text" to JsonPrimitive("fallback")))).quote)
        // quote 缺席 + text 显式 null → 同样取到 "null"，不抛。
        assertEquals("null", okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "text" to JsonNull))).quote)
        // chatId 显式 null → 同样取到 "null"（非空不断言必填），不抛。
        assertEquals("null", okOf(JsonObject(mapOf("chatId" to JsonNull, "quote" to JsonPrimitive("hi")))).chatId)
        // note 显式 null → 取到 "null" 字符串，不抛、不参与必填。
        assertEquals("null", okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "quote" to JsonPrimitive("q"), "note" to JsonNull))).note)
        // 数字 / 布尔型 quote 不抛：JsonPrimitive.content 是 toString()，特意钉住。
        assertEquals("5", okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "quote" to JsonPrimitive(5)))).quote)
        assertEquals("true", okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "quote" to JsonPrimitive(true)))).quote)
        // 数字型 text 回退同样不抛。
        assertEquals("42", okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "text" to JsonPrimitive(42)))).quote)
    }

    @Test
    fun contentAssembly() {
        // 单行引用无 note。
        assertEquals("> hello", buildBotQuoteContent("hello", ""))
        // 多行逐行加前缀。
        assertEquals("> a\n> b", buildBotQuoteContent("a\nb", ""))
        // note 非空 → "\n\n" 拼在引用块后。
        assertEquals("> q\n\nmy note", buildBotQuoteContent("q", "my note"))
        // note 纯空白 → 视为无 note（isNotBlank 语义）。
        assertEquals("> q", buildBotQuoteContent("q", "   "))
        // 引用原文的换行逐字保留（含尾随空行）。
        assertEquals("> a\n> ", buildBotQuoteContent("a\n", ""))
        // 空 quote → 单个 "> "（lines() 的逐字行为，特意钉住）。
        assertEquals("> ", buildBotQuoteContent("", ""))
    }

    @Test
    fun wrongTypedKnownFieldsFailLoudly() {
        // 对象 / 数组型 chatId 在 ?.jsonPrimitive 处抛。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonObject(emptyMap()), "quote" to JsonPrimitive("hi"))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonArray(emptyList()), "quote" to JsonPrimitive("hi"))))
        }
        // 对象 / 数组型 quote 在 ?.jsonPrimitive 处抛。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "quote" to JsonObject(emptyMap()))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "quote" to JsonArray(emptyList()))))
        }
        // 对象型 text（quote 缺席走回退）同样在 ?.jsonPrimitive 处抛。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "text" to JsonObject(emptyMap()))))
        }
        // 对象 / 数组型 note 在 ?.jsonPrimitive 处抛。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "quote" to JsonPrimitive("q"), "note" to JsonObject(emptyMap()))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "quote" to JsonPrimitive("q"), "note" to JsonArray(emptyList()))))
        }
    }

    @Test
    fun extractionOrder() {
        // chatId 先于 quote 抽取：chatId 坏类型 + quote 乱值 → 抛错（不是先判必填）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonObject(emptyMap()), "quote" to JsonObject(emptyMap()))))
        }
        // chatId 缺席 + quote 乱值 → 抛错而非 MissingRequired：quote 抽取抛在必填判断之前。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("quote" to JsonArray(emptyList()))))
        }
        // chatId 纯空白 + quote 乱值 → 仍抛错（抽取先于必填判断）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("  "), "quote" to JsonObject(emptyMap()))))
        }
        // 反证：quote 合法时 chatId 纯空白 → MissingRequired（不抛）。
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("  "), "quote" to JsonPrimitive("hi"))))
                is BotQuoteFieldsResult.MissingRequired,
        )
    }
}
