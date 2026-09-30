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
 * Bot `sendMarkdown` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，G355
 * `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、`sendPhoto`、
 * `sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、`sendVenue`、
 * `sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、
 * `sendChecklist`、`sendAlert`、`sendCountdown`、`sendNotice`、`sendBadge`、
 * `sendToast`、`sendHr`、`sendDivider`、`sendProgress`、`send*Hint`、
 * `sendMentionCard`/`sendNudgeCard`、`sendMetric`/`sendCompare`、`sendKeyValue`、
 * `sendQuoteCard`、`sendBanner`、`sendJsonCard` 之后第三十五块）。
 *
 * 本测试直接钉住纯函数 ([parseBotMarkdownFields]) 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]，含 `markdown` 与
 *   `silent`——`markdown` 是 `text` 的存在性回退键，`silent` 是已知解析字段，
 *   同样视为已知）：否则测的是「重复键覆盖语义」，而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 钉住 `text` 的存在性回退：只有 `text` 键完全缺席才看 `markdown`；`text` 在但为
 *   显式 null 时仍走 `text` 分支（在 `.content` 处抛，**不**回退）。
 * - 钉住 `text` 取 `?.jsonPrimitive?.content` **不是** `?.toString()`：字符串不带引号
 *   进模板（与 `sendJsonCard` 的怪语义相反，特意钉住）；`take(4000)` 作用于
 *   `orEmpty()` **之后**（裁的是 content 本身）。
 * - 钉住 `silentRequested` 取 `?.jsonPrimitive?.booleanOrNull == true`：缺键 /
 *   JSON null 得 `false`；字符串 `"true"` 按布尔语义得 `true`；对象 / 数组型
 *   `silent` 大声失败。
 * - 钉住**双必填**（`chatId` 或 `text` 缺/空白 → `MissingRequired`；判的是裁过
 *   4000 的空白性）。
 * - 反证 `wrong-typed known fields still fail loudly`：手写解析里 `chatId` /
 *   `text` 的 `?.jsonPrimitive` 在类型错时抛 [IllegalArgumentException]
 *   （对象 / 数组 / 显式 null 型值在 `.content` 处抛；注意**数字 / 布尔不抛**，
 *   `JsonPrimitive.content` 对它们是 `toString()`——特意钉住），路由层
 *   `StatusPages` 把它映射为 400「参数无效」（不是 500）——坏数据必须大声失败，
 *   不能悄悄吞掉。
 * - 反证抽取顺序：`chatId` 先于 `text` 抽取——`chatId` 坏类型 + `text` 乱值→抛错；
 *   `chatId` 缺席 + `text` 乱值→抛错（不是 `MissingRequired`：抽取抛在必填
 *   判断之前，原处理器逐字如此）。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
class BotSendMarkdownParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("chatId", "text", "markdown", "silent")
        private const val ITERATIONS = 150
        private const val TEXT_CAP = 4000
    }

    private fun parseOf(obj: JsonObject): BotMarkdownFieldsResult =
        parseBotMarkdownFields(obj)

    private fun okOf(obj: JsonObject): BotMarkdownFields =
        (parseOf(obj) as BotMarkdownFieldsResult.Ok).fields

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
            base["chatId"] = JsonPrimitive("chat-fuzz-" + i)
            // mode 0：text 缺席 → 钉住 markdown 回退；mode 1：text + markdown
            // 同时在场 → 钉住 text 分支（markdown 被忽略）；mode 2：全字段抽取。
            val expectedText: String
            when (mode) {
                0 -> {
                    base["markdown"] = JsonPrimitive("fallback-md-" + i)
                    expectedText = "fallback-md-" + i
                }
                1 -> {
                    base["text"] = JsonPrimitive("text-wins-" + i)
                    base["markdown"] = JsonPrimitive("ignored-md-" + i)
                    expectedText = "text-wins-" + i
                }
                else -> {
                    base["text"] = JsonPrimitive("plain-text-" + i)
                    expectedText = "plain-text-" + i
                }
            }
            // mode 2 的一半用例钉住 take(4000) 作用于 content 之后。
            val longText = mode == 2 && i % 2 == 0
            if (longText) {
                val over = "x".repeat(TEXT_CAP + 1234)
                base["text"] = JsonPrimitive(over)
            }
            base["silent"] = JsonPrimitive(i % 2 == 0)
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val fields = okOf(JsonObject(base))
            assertEquals("chat-fuzz-" + i, fields.chatId, "未知键不得污染 chatId，迭代 " + i)
            val wantText = if (longText) "x".repeat(TEXT_CAP) else expectedText
            assertEquals(wantText, fields.text, "未知键不得污染 text 抽取/回退/截断语义，迭代 " + i)
            assertEquals(i % 2 == 0, fields.silentRequested, "未知键不得污染 silent 抽取，迭代 " + i)
        }
    }

    @Test
    fun missingRequiredSemantics() {
        // chatId 缺 / 空 / 纯空白 → MissingRequired。
        assertTrue(parseOf(JsonObject(mapOf("text" to JsonPrimitive("hi")))) is BotMarkdownFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive(""), "text" to JsonPrimitive("hi")))) is BotMarkdownFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("   "), "text" to JsonPrimitive("hi")))) is BotMarkdownFieldsResult.MissingRequired)
        // text 与 markdown 均缺席 → MissingRequired。
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1")))) is BotMarkdownFieldsResult.MissingRequired)
        // text 空 / 纯空白 → MissingRequired（判的是 content 空白性）。
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "text" to JsonPrimitive("")))) is BotMarkdownFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "text" to JsonPrimitive("  \t ")))) is BotMarkdownFieldsResult.MissingRequired)
        // text 缺席但 markdown 纯空白 → MissingRequired。
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "markdown" to JsonPrimitive("   ")))) is BotMarkdownFieldsResult.MissingRequired)
        // 双合法 → Ok。
        val ok = okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "text" to JsonPrimitive("hello"))))
        assertEquals("c1", ok.chatId)
        assertEquals("hello", ok.text)
    }

    @Test
    fun capAndQuirks() {
        // take(4000) 作用于 orEmpty() 之后：超长 content 被裁到恰好 4000，不带引号。
        val long = "y".repeat(TEXT_CAP + 999)
        assertEquals("y".repeat(TEXT_CAP), okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "text" to JsonPrimitive(long)))).text)
        // 恰好 4000 不动。
        val exact = "z".repeat(TEXT_CAP)
        assertEquals(exact, okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "text" to JsonPrimitive(exact)))).text)
        // text 显式 null → 在 .content 处抛，不回退到 markdown。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "text" to JsonNull, "markdown" to JsonPrimitive("fallback"))))
        }
        // text 缺席 + markdown 显式 null → 同样在 .content 处抛。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "markdown" to JsonNull)))
        }
        // 数字 / 布尔型 text 不抛：JsonPrimitive.content 是 toString()，特意钉住。
        assertEquals("5", okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "text" to JsonPrimitive(5)))).text)
        assertEquals("true", okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "text" to JsonPrimitive(true)))).text)
        // 数字型 markdown 回退同样不抛。
        assertEquals("42", okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "markdown" to JsonPrimitive(42)))).text)
    }

    @Test
    fun silentSemantics() {
        fun silentOf(obj: JsonObject): Boolean = okOf(obj).silentRequested
        fun base(silent: JsonElement?): JsonObject {
            val m = mutableMapOf<String, JsonElement>(
                "chatId" to JsonPrimitive("c"),
                "text" to JsonPrimitive("hi"),
            )
            if (silent != null) m["silent"] = silent
            return JsonObject(m)
        }
        // 缺键 / JSON null → false。
        assertEquals(false, silentOf(base(null)))
        assertEquals(false, silentOf(base(JsonNull)))
        // 布尔 true → true，false → false。
        assertEquals(true, silentOf(base(JsonPrimitive(true))))
        assertEquals(false, silentOf(base(JsonPrimitive(false))))
        // 字符串 "true"/"false" 按布尔语义解析（booleanOrNull 认 "true"）。
        assertEquals(true, silentOf(base(JsonPrimitive("true"))))
        assertEquals(false, silentOf(base(JsonPrimitive("false"))))
        // 数字 1 不认 → false。
        assertEquals(false, silentOf(base(JsonPrimitive(1))))
        // 对象 / 数组型 silent 大声失败。
        assertFailsWith<IllegalArgumentException> { parseOf(base(JsonObject(emptyMap()))) }
        assertFailsWith<IllegalArgumentException> { parseOf(base(JsonArray(emptyList()))) }
    }

    @Test
    fun wrongTypedKnownFieldsFailLoudly() {
        // 对象 / 数组型 chatId 在 .content 处抛。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonObject(emptyMap()), "text" to JsonPrimitive("hi"))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonArray(emptyList()), "text" to JsonPrimitive("hi"))))
        }
        // 对象 / 数组型 text 在 .content 处抛。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "text" to JsonObject(emptyMap()))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "text" to JsonArray(emptyList()))))
        }
        // 对象型 markdown（text 缺席走回退）同样在 .content 处抛。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "markdown" to JsonObject(emptyMap()))))
        }
    }

    @Test
    fun extractionOrder() {
        // chatId 先于 text 抽取：chatId 坏类型 + text 乱值 → 抛错（不是先判必填）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonObject(emptyMap()), "text" to JsonObject(emptyMap()))))
        }
        // chatId 缺席 + text 乱值 → 抛错而非 MissingRequired：text 抽取抛在必填判断之前。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("text" to JsonArray(emptyList()))))
        }
        // chatId 纯空白 + text 乱值 → 仍抛错（抽取先于必填判断）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("  "), "text" to JsonObject(emptyMap()))))
        }
        // 反证：text 合法时 chatId 纯空白 → MissingRequired（不抛）。
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("  "), "text" to JsonPrimitive("hi"))))
                is BotMarkdownFieldsResult.MissingRequired,
        )
    }
}
