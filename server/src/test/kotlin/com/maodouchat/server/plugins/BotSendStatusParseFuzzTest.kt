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
 * Bot `sendStatus` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，G355
 * `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、`sendPhoto`、
 * `sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、`sendVenue`、
 * `sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、
 * `sendChecklist`、`sendAlert`、`sendCountdown`、`sendNotice`、`sendBadge`、
 * `sendToast`、`sendHr`、`sendDivider`、`sendProgress`、`send*Hint`、
 * `sendMentionCard`/`sendNudgeCard`、`sendMetric`/`sendCompare`、`sendKeyValue`、
 * `sendQuoteCard`、`sendBanner`、`sendJsonCard`、`sendMarkdown`、`sendQuote`、
 * `sendCode`、`setMessageReaction`、`starMessage` 之后第四十块）。
 *
 * 本测试直接钉住纯函数 ([parseBotSendStatusFields]) 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 钉住 **`text`/`status` 回退**：`text` 缺键时取 `status`，`text` 显键（哪怕是
 *   显式 JSON null）不回退——`(obj["text"] ?: obj["status"])` 的 `?:` 判的是
 *   Kotlin null，不是 `JsonNull`。
 * - 钉住**先 `take(200)` 后判空白**：超长正文截断到 200 字符，前 200 字符全空白
 *   即判缺失（第 201 个字符之后的内容进不了判据）。
 * - 钉住**无 `trim()`**：`chatId` 与 `text` 都判未裁剪串的 `isBlank()`——
 *   `" c1 "` 原样通过必填检查、原样进下游（不裁剪、不改写）。
 * - 钉住**合并必填**（`chatId` 或 `text` 缺/空/纯空白 → `MissingRequired`，
 *   判据在截断后）。
 * - 反证 `wrong-typed known fields still fail loudly`：手写解析里 `chatId` /
 *   `text` 的 `?.jsonPrimitive` 在类型错时抛 [IllegalArgumentException]
 *   （对象 / 数组型值在 `?.jsonPrimitive` 处抛；显式 null 取到 `"null"` 字面量
 *   字符串、不抛；注意**数字 / 布尔不抛**，`JsonPrimitive.content` 对它们是
 *   `toString()`——特意钉住），路由层 `StatusPages` 把它映射为 400「参数无效」
 *   （不是 500）——坏数据必须大声失败，不能悄悄吞掉。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
class BotSendStatusParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("chatId", "text", "status")
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotSendStatusFieldsResult =
        parseBotSendStatusFields(obj)

    private fun okOf(obj: JsonObject): BotSendStatusFields =
        (parseOf(obj) as BotSendStatusFieldsResult.Ok).fields

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

    private fun basePayload(random: Random, i: Int): Pair<MutableMap<String, JsonElement>, String> {
        val base = mutableMapOf<String, JsonElement>()
        // 五分之一用整数钉住 content→toString，其余用字符串。
        val expectedText = if (i % 5 == 4) {
            base["text"] = JsonPrimitive(7000 + i)
            (7000 + i).toString()
        } else {
            base["text"] = JsonPrimitive("status-" + i)
            "status-" + i
        }
        base["chatId"] = JsonPrimitive("c" + i)
        // 程序化注入未知字段：永不破坏 JSON 语法。
        repeat(random.nextInt(0, 6)) {
            base[randomName(random)] = randomScalar(random)
        }
        return base to expectedText
    }

    @Test
    fun fuzzUnknownKeysIgnoredAndKnownSemanticsHold() {
        val random = Random(20261002)
        repeat(ITERATIONS) { i ->
            val (base, expectedText) = basePayload(random, i)
            val fields = okOf(JsonObject(base))
            assertEquals("c" + i, fields.chatId, "未知键不得污染 chatId，迭代 " + i)
            assertEquals(expectedText, fields.text, "未知键不得污染 text，迭代 " + i)
        }
    }

    @Test
    fun missingRequiredSemantics() {
        // chatId 缺 / 空 / 纯空白 → MissingRequired（text 合法时）。
        val text = mapOf("text" to JsonPrimitive("ok"))
        assertTrue(parseOf(JsonObject(text)) is BotSendStatusFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(text + ("chatId" to JsonPrimitive("")))) is BotSendStatusFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(text + ("chatId" to JsonPrimitive("   ")))) is BotSendStatusFieldsResult.MissingRequired)
        // text 缺 / 空 / 纯空白 → MissingRequired（chatId 合法时）。
        val chatId = mapOf("chatId" to JsonPrimitive("c1"))
        assertTrue(parseOf(JsonObject(chatId)) is BotSendStatusFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(chatId + ("text" to JsonPrimitive("")))) is BotSendStatusFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(chatId + ("text" to JsonPrimitive("  ")))) is BotSendStatusFieldsResult.MissingRequired)
        // 两端合法 → Ok，原样。
        val ok = okOf(JsonObject(chatId + text))
        assertEquals("c1", ok.chatId)
        assertEquals("ok", ok.text)
    }

    @Test
    fun textStatusFallbackSemantics() {
        val chatId = "chatId" to JsonPrimitive("c1")
        // text 缺键 → 回退 status。
        val ok = okOf(JsonObject(mapOf(chatId, "status" to JsonPrimitive("s2"))))
        assertEquals("s2", ok.text)
        // text 与 status 都在 → 取 text。
        val ok2 = okOf(JsonObject(mapOf(chatId, "text" to JsonPrimitive("t1"), "status" to JsonPrimitive("s2"))))
        assertEquals("t1", ok2.text)
        // text 显式 null 不回退：?: 判的是 Kotlin null，不是 JsonNull。
        val ok3 = okOf(JsonObject(mapOf(chatId, "text" to JsonNull, "status" to JsonPrimitive("s2"))))
        assertEquals("null", ok3.text)
        // status 显式 null → 取字面量 "null"。
        val ok4 = okOf(JsonObject(mapOf(chatId, "status" to JsonNull)))
        assertEquals("null", ok4.text)
        // 两端都缺 → text 得空串 → MissingRequired。
        assertTrue(parseOf(JsonObject(mapOf(chatId))) is BotSendStatusFieldsResult.MissingRequired)
    }

    @Test
    fun take200TruncationAndNoTrimSemantics() {
        val chatId = "chatId" to JsonPrimitive("c1")
        // 钉住无 trim：两端空白原样通过必填检查、原样进下游。
        val ok = okOf(JsonObject(mapOf(chatId, "text" to JsonPrimitive(" s1 "))))
        assertEquals(" s1 ", ok.text)
        val okChat = okOf(JsonObject(mapOf("chatId" to JsonPrimitive(" c1 "), "text" to JsonPrimitive("t"))))
        assertEquals(" c1 ", okChat.chatId)
        // 超长正文截断到 200 字符。
        val long = "x".repeat(250)
        val okLong = okOf(JsonObject(mapOf(chatId, "text" to JsonPrimitive(long))))
        assertEquals(200, okLong.text.length)
        assertEquals(long.take(200), okLong.text)
        // 先截断后判空白：前 200 字符全空白 → MissingRequired，第 201 字符之后进不了判据。
        val padded = " ".repeat(200) + "x"
        assertTrue(parseOf(JsonObject(mapOf(chatId, "text" to JsonPrimitive(padded)))) is BotSendStatusFieldsResult.MissingRequired)
        // 整 200 非空白 → 通过。
        val ok200 = okOf(JsonObject(mapOf(chatId, "text" to JsonPrimitive("y".repeat(200)))))
        assertEquals(200, ok200.text.length)
    }

    @Test
    fun nullAndScalarQuirksAndLoudFailures() {
        // chatId 显式 null → 字面 "null"（不抛、非空）→ Ok。
        val ok = okOf(JsonObject(mapOf("chatId" to JsonNull, "text" to JsonPrimitive("t"))))
        assertEquals("null", ok.chatId)
        // JSON 布尔 / 整数经 content 取 toString，不抛。
        val ok2 = okOf(JsonObject(mapOf("chatId" to JsonPrimitive(true), "text" to JsonPrimitive("t"))))
        assertEquals("true", ok2.chatId)
        val ok3 = okOf(JsonObject(mapOf("chatId" to JsonPrimitive(42), "text" to JsonPrimitive("t"))))
        assertEquals("42", ok3.chatId)
        // 对象 / 数组型 chatId 在 ?.jsonPrimitive 处抛 IllegalArgumentException。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonObject(mapOf()), "text" to JsonPrimitive("t"))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonArray(emptyList()), "text" to JsonPrimitive("t"))))
        }
        // 对象 / 数组型 text 同样抛（(obj["text"] ?: obj["status"])?.jsonPrimitive）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "text" to JsonObject(mapOf("x" to JsonPrimitive(1))))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "text" to JsonArray(listOf(JsonPrimitive(1))))))
        }
        // 反证：显式 null 不抛（是 JsonPrimitive 的一种）。
        val okNull = okOf(JsonObject(mapOf("chatId" to JsonNull, "text" to JsonNull)))
        assertEquals("null", okNull.chatId)
        assertEquals("null", okNull.text)
    }
}
