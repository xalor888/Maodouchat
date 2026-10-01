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
 * Bot `sendRemind` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，G355
 * `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、
 * `sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、
 * `sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、
 * `sendChecklist`、`sendAlert`、`sendCountdown`、`sendNotice`、`sendBadge`、
 * `sendToast`、`sendHr`、`sendDivider`、`sendProgress`、`send*Hint`、
 * `sendMentionCard`/`sendNudgeCard`、`sendMetric`/`sendCompare`、`sendKeyValue`、
 * `sendQuoteCard`、`sendBanner`、`sendJsonCard`、`sendMarkdown`、`sendQuote`、
 * `sendCode`、`setMessageReaction`、`starMessage`、`sendStatus`、`sendTable`、
 * `sendAnimation`、`sendAudio`、`editMessageCaption`、`sendTimeline` 之后第四十六块）。
 *
 * 本测试直接钉住纯函数 ([parseBotSendRemindFields]) 与组装
 * ([buildBotRemindContent]) 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 吸取第四十一块（`sendTable`）的 CI 教训：fuzz 基 payload 的**必填字段必须
 *   确定性合法**——全随机时已知字段可能被丢弃/变空 → `MissingRequired` →
 *   `okOf` 的 `as Ok` 强转抛 `ClassCastException`。这里 chatId 用 `"c" + i` 恒合法、
 *   text 用确定性字符串恒非空，合并必填语义由 `missingRequiredSemantics` 钉住。
 * - 钉住 **chatId / text 无 `trim()`**（`" c "` / `" t "` 原样通过、原样进下游）与
 *   **text 双别名**（只有 `text` 键缺席才回退到 `message`；`text` 显式 JSON null
 *   得字面 `"null"`、不回退，特意钉住）、**截 300**（先取后截、前导空格计入上限）。
 * - 钉住**合并必填**（`chatId` 空白或 text 空白→`MissingRequired`）。
 * - 钉住**内容组装**：`"REMIND: " + text` 逐字。
 * - 反证坏类型大声失败：对象 / 数组型 `chatId`、`text` 在 `?.jsonPrimitive` 处抛
 *   [IllegalArgumentException]（路由层 `StatusPages` 映射为 400「参数无效」，
 *   不是 500）；显式 null 不抛的反证。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
class BotSendRemindParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES =
            setOf(
                "chatId", "text", "message",
            )
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotSendRemindFieldsResult =
        parseBotSendRemindFields(obj)

    private fun okOf(obj: JsonObject): BotSendRemindFields =
        (parseOf(obj) as BotSendRemindFieldsResult.Ok).fields

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

    private fun basePayload(random: Random, i: Int): Pair<MutableMap<String, JsonElement>, BotSendRemindFields> {
        val base = mutableMapOf<String, JsonElement>()
        // 必填字段确定性合法（第四十一块 CI 教训）：chatId 恒为 "c<i>"、text 恒为
        // 非空确定性字符串——未知键注入永不触达必填。
        base["chatId"] = JsonPrimitive("c" + i)
        // text 无 trim：首尾空格原样保留；取 " t<i> " 钉住该语义。
        // i % 3 == 0 时塞一个超长文本（400 字符→截 300），钉住截断。
        val longText = "x".repeat(400)
        val text = if (i % 3 == 0) longText else " t" + i + " "
        base["text"] = JsonPrimitive(text)
        // 程序化注入未知字段：永不破坏 JSON 语法。
        repeat(random.nextInt(0, 6)) {
            base[randomName(random)] = randomScalar(random)
        }
        val expectedText = text.take(300)
        return base to BotSendRemindFields("c" + i, expectedText)
    }

    @Test
    fun fuzzUnknownKeysIgnoredAndKnownSemanticsHold() {
        val random = Random(20261004)
        repeat(ITERATIONS) { i ->
            val (base, expected) = basePayload(random, i)
            val fields = okOf(JsonObject(base))
            assertEquals(expected.chatId, fields.chatId, "未知键不得污染 chatId，迭代 " + i)
            assertEquals(expected.text, fields.text, "未知键不得污染 text，迭代 " + i)
            // 组装恒等：未知键不得污染正文。
            val expectedContent = "REMIND: " + expected.text
            assertEquals(
                expectedContent,
                buildBotRemindContent(fields.text),
                "未知键不得污染组装正文，迭代 " + i,
            )
        }
    }

    @Test
    fun missingRequiredSemantics() {
        val chatId = mapOf("chatId" to JsonPrimitive("c1"))
        val text = mapOf("text" to JsonPrimitive("x"))
        // chatId 缺 / 空 / 纯空白 → MissingRequired（text 合法时）。
        assertTrue(parseOf(JsonObject(text)) is BotSendRemindFieldsResult.MissingRequired)
        assertTrue(
            parseOf(JsonObject(text + ("chatId" to JsonPrimitive("")))) is BotSendRemindFieldsResult.MissingRequired
        )
        assertTrue(
            parseOf(JsonObject(text + ("chatId" to JsonPrimitive("   ")))) is BotSendRemindFieldsResult.MissingRequired
        )
        // text 缺 / 空 / 纯空白 → MissingRequired（chatId 合法时）。
        assertTrue(parseOf(JsonObject(chatId)) is BotSendRemindFieldsResult.MissingRequired)
        assertTrue(
            parseOf(JsonObject(chatId + ("text" to JsonPrimitive("")))) is BotSendRemindFieldsResult.MissingRequired
        )
        assertTrue(
            parseOf(JsonObject(chatId + ("text" to JsonPrimitive("   ")))) is BotSendRemindFieldsResult.MissingRequired
        )
        // 两端合法 → Ok。
        val ok = okOf(JsonObject(chatId + text))
        assertEquals("c1", ok.chatId)
        assertEquals("x", ok.text)
        // 显式 JSON null 的 chatId 得字面 "null"（非空→Ok，逐字怪语义）。
        val nullChatId = okOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonNull,
                    "text" to JsonPrimitive("x"),
                )
            )
        )
        assertEquals("null", nullChatId.chatId)
    }

    @Test
    fun textAliasTrimAndTruncation() {
        // 只有 text 键缺席才回退到 message 别名。
        val viaAlias = okOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c1"),
                    "message" to JsonPrimitive("m1"),
                )
            )
        )
        assertEquals("m1", viaAlias.text)
        // text 优先于 message（逐字顺序）。
        val textWins = okOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c1"),
                    "text" to JsonPrimitive("t1"),
                    "message" to JsonPrimitive("m1"),
                )
            )
        )
        assertEquals("t1", textWins.text)
        // 显式 JSON null 的 text 得字面 "null"，不回退到 message（特意钉住）。
        val explicitNull = okOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c1"),
                    "text" to JsonNull,
                    "message" to JsonPrimitive("m1"),
                )
            )
        )
        assertEquals("null", explicitNull.text)
        // 无 trim：首尾空格原样保留；400 字符→截 300（前导空格计入上限）。
        val padded = okOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c1"),
                    "text" to JsonPrimitive("  " + "y".repeat(400)),
                )
            )
        )
        assertEquals(("  " + "y".repeat(400)).take(300), padded.text)
        assertEquals(300, padded.text.length)
    }

    @Test
    fun loudFailureOnBadKnownFieldTypes() {
        fun parseWith(chatId: JsonElement, text: JsonElement): BotSendRemindFieldsResult =
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to chatId,
                        "text" to text,
                    )
                )
            )
        // 对象 / 数组型 chatId、text 在 ?.jsonPrimitive 处抛 IllegalArgumentException
        //（路由层 StatusPages 映射为 400「参数无效」，不是 500）。
        assertFailsWith<IllegalArgumentException> {
            parseWith(JsonObject(mapOf("k" to JsonPrimitive("v"))), JsonPrimitive("t"))
        }
        assertFailsWith<IllegalArgumentException> {
            parseWith(JsonArray(listOf(JsonPrimitive("z"))), JsonPrimitive("t"))
        }
        assertFailsWith<IllegalArgumentException> {
            parseWith(JsonPrimitive("c"), JsonObject(mapOf("k" to JsonPrimitive("v"))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseWith(JsonPrimitive("c"), JsonArray(listOf(JsonPrimitive("z"))))
        }
        // 别名 message 坏类型同样大声失败（text 缺席时走别名分支）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "message" to JsonArray(listOf(JsonPrimitive("z"))),
                    )
                )
            )
        }
        // 显式 null 不抛的反证：chatId 得字面 "null"、text 得字面 "null"。
        val ok = okOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonNull,
                    "text" to JsonNull,
                )
            )
        )
        assertEquals("null", ok.chatId)
        assertEquals("null", ok.text)
    }

    @Test
    fun contentAssembly() {
        // 逐字："REMIND: " + text。
        assertEquals(
            "REMIND: hello",
            buildBotRemindContent("hello"),
        )
        // text 首尾空格、内嵌换行原样拼接，不做任何处理。
        assertEquals(
            "REMIND:   padded  \nline2",
            buildBotRemindContent("  padded  \nline2"),
        )
        // 空 text 也逐字组装（必填校验在解析层，不在组装层）。
        assertEquals(
            "REMIND: ",
            buildBotRemindContent(""),
        )
    }
}
