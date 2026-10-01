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
 * Bot `starMessage` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，G355
 * `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、`sendPhoto`、
 * `sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、`sendVenue`、
 * `sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、
 * `sendChecklist`、`sendAlert`、`sendCountdown`、`sendNotice`、`sendBadge`、
 * `sendToast`、`sendHr`、`sendDivider`、`sendProgress`、`send*Hint`、
 * `sendMentionCard`/`sendNudgeCard`、`sendMetric`/`sendCompare`、`sendKeyValue`、
 * `sendQuoteCard`、`sendBanner`、`sendJsonCard`、`sendMarkdown`、`sendQuote`、
 * `sendCode`、`setMessageReaction` 之后第三十九块）。
 *
 * 本测试直接钉住纯函数 ([parseBotStarMessageFields]) 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 钉住**无 `trim()`**：`messageId` 取 `?.jsonPrimitive?.content.orEmpty()` 原样；
 *   必填判的是未裁剪串的 `isBlank()`——`" m1 "` **原样**通过必填检查进下游
 *   （不裁剪、不改写），全空白（`"  "`）则判 `MissingRequired`。这与
 *   `setMessageReaction` 那块的 `trim()` 语义是刻意差异，原处理器逐字如此，
 *   特意钉住。
 * - 钉住**单必填**（`messageId` 缺/空/纯空白 → `MissingRequired`）。
 * - 反证 `wrong-typed known fields still fail loudly`：手写解析里 `messageId` 的
 *   `?.jsonPrimitive` 在类型错时抛 [IllegalArgumentException]
 *   （对象 / 数组型值在 `?.jsonPrimitive` 处抛；显式 null 取到 `"null"` 字面量
 *   字符串、不抛；注意**数字 / 布尔不抛**，`JsonPrimitive.content` 对它们是
 *   `toString()`——特意钉住），路由层 `StatusPages` 把它映射为 400「参数无效」
 *   （不是 500）——坏数据必须大声失败，不能悄悄吞掉。
 * - 反证抽取抛在必填判断之前：`messageId` 缺席 + 坏类型不可能共存于单字段，
 *   故抽取顺序在此块不做顺序反证——但对象型 `messageId` 必抛、而非
 *   `MissingRequired`（原处理器逐字如此）。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
class BotStarMessageParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("messageId")
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotStarMessageFieldsResult =
        parseBotStarMessageFields(obj)

    private fun okOf(obj: JsonObject): BotStarMessageFields =
        (parseOf(obj) as BotStarMessageFieldsResult.Ok).fields

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
            val base = mutableMapOf<String, JsonElement>()
            // 五分之一用整数钉住 content→toString，其余用字符串。
            val expectedMessageId = if (i % 5 == 4) {
                base["messageId"] = JsonPrimitive(9000 + i)
                (9000 + i).toString()
            } else {
                base["messageId"] = JsonPrimitive("msg-" + i)
                "msg-" + i
            }
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val fields = okOf(JsonObject(base))
            assertEquals(expectedMessageId, fields.messageId, "未知键不得污染 messageId，迭代 " + i)
        }
    }

    @Test
    fun missingRequiredSemantics() {
        // messageId 缺 / 空 / 纯空白 → MissingRequired。
        assertTrue(parseOf(JsonObject(mapOf())) is BotStarMessageFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(mapOf("messageId" to JsonPrimitive("")))) is BotStarMessageFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(mapOf("messageId" to JsonPrimitive("   ")))) is BotStarMessageFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(mapOf("messageId" to JsonPrimitive(" \t\n ")))) is BotStarMessageFieldsResult.MissingRequired)
        // 合法 → Ok。
        assertEquals("m1", okOf(JsonObject(mapOf("messageId" to JsonPrimitive("m1")))).messageId)
    }

    @Test
    fun noTrimSemantics() {
        // 钉住无 trim：两端空白原样通过必填检查、原样进下游（不裁剪）。
        val ok = okOf(JsonObject(mapOf("messageId" to JsonPrimitive(" m1 "))))
        assertEquals(" m1 ", ok.messageId)
        val ok2 = okOf(JsonObject(mapOf("messageId" to JsonPrimitive("\tmsg-2\n"))))
        assertEquals("\tmsg-2\n", ok2.messageId)
    }

    @Test
    fun nullAndScalarQuirks() {
        // messageId 显式 null → 字面 "null"（不抛、非空）→ Ok。
        val ok = okOf(JsonObject(mapOf("messageId" to JsonNull)))
        assertEquals("null", ok.messageId)
        // JSON 布尔经 content 取 toString，不抛。
        val ok2 = okOf(JsonObject(mapOf("messageId" to JsonPrimitive(true))))
        assertEquals("true", ok2.messageId)
        // JSON 整数经 content 取 toString，不抛。
        val ok3 = okOf(JsonObject(mapOf("messageId" to JsonPrimitive(42))))
        assertEquals("42", ok3.messageId)
        // 对象 / 数组型 messageId 在 ?.jsonPrimitive 处抛 IllegalArgumentException。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("messageId" to JsonObject(mapOf()))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("messageId" to JsonArray(emptyList()))))
        }
    }

    @Test
    fun wrongTypedKnownFieldsFailLoudly() {
        // messageId 对象型 → 抛（大声失败，路由层 StatusPages 映射 400，不是 500）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("messageId" to JsonObject(mapOf("x" to JsonPrimitive(1))))))
        }
        // messageId 数组型 → 抛。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("messageId" to JsonArray(listOf(JsonPrimitive(1))))))
        }
        // 反证：显式 null 不抛（是 JsonPrimitive 的一种），messageId 得字面 "null"。
        val ok = okOf(JsonObject(mapOf("messageId" to JsonNull)))
        assertEquals("null", ok.messageId)
    }
}
