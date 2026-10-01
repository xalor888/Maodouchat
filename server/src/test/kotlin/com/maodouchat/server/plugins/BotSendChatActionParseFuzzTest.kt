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
 * Bot `sendChatAction` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，G355
 * `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、
 * `sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、
 * `sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、
 * `forwardMessage`/`copyMessage`、`sendNudge`、`sendContactCard`、
 * `sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、`sendAlert`、
 * `sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、
 * `sendDivider`、`sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard`、
 * `sendMetric`/`sendCompare`、`sendKeyValue`、`sendQuoteCard`、`sendBanner`、
 * `sendJsonCard`、`sendMarkdown`、`sendQuote`、`sendCode`、`setMessageReaction`、
 * `starMessage`、`sendStatus`、`sendTable`、`sendAnimation`、`sendAudio`、
 * `editMessageCaption`、`sendTimeline`、`sendRemind`、`sendMessageSilent`
 * 之后第四十八块）。
 *
 * 本测试直接钉住纯函数 ([parseBotSendChatActionFields]) 的生产语义：
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
 *   `okOf` 的 `as Ok` 强转抛 `ClassCastException`。这里 chatId 用 `"c" + i` 恒合法；
 *   `action` 恒有默认值（缺席→`"typing"`），从不判空，合并必填语义由
 *   `missingRequiredSemantics` 钉住。
 * - 钉住 **action 归一化**：先 `.lowercase()` 后判空——缺席 / 空串 / 纯空白
 *   → `"typing"`；`"TYPING"` / `"Upload_Photo"` → 小写化；
 *   **显式 JSON null 得字面量 `"null"`**（非空→不回退 `"typing"`，逐字怪语义）。
 * - 钉住 **chatId 无 `trim()`**（`" c "` 原样通过、原样进下游）。
 * - 反证坏类型大声失败：对象 / 数组型 `chatId`、`action` 在
 *   `?.jsonPrimitive` 处抛 [IllegalArgumentException]（路由层 `StatusPages`
 *   映射为 400「参数无效」，不是 500）；显式 null 不抛的反证。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
class BotSendChatActionParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES =
            setOf(
                "chatId", "action",
            )
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotSendChatActionFieldsResult =
        parseBotSendChatActionFields(obj)

    private fun okOf(obj: JsonObject): BotSendChatActionFields =
        (parseOf(obj) as BotSendChatActionFieldsResult.Ok).fields

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

    private fun basePayload(random: Random, i: Int): Pair<MutableMap<String, JsonElement>, BotSendChatActionFields> {
        val base = mutableMapOf<String, JsonElement>()
        // 必填字段确定性合法（第四十一块 CI 教训）：chatId 恒为 "c<i>"，
        // 未知键注入永不触达必填。
        base["chatId"] = JsonPrimitive("c" + i)
        // action 轮换归一化变体：缺席（→"typing"）/ 大小写混杂 / 纯空白（→"typing"）。
        val rawAction = when (i % 4) {
            0 -> "TYPING"
            1 -> "Upload_Photo"
            2 -> "  "
            else -> null
        }
        if (rawAction != null) base["action"] = JsonPrimitive(rawAction)
        // 程序化注入未知字段：永不破坏 JSON 语法。
        repeat(random.nextInt(0, 6)) {
            base[randomName(random)] = randomScalar(random)
        }
        val expectedAction = (rawAction ?: "").lowercase().ifBlank { "typing" }
        return base to BotSendChatActionFields("c" + i, expectedAction)
    }

    @Test
    fun fuzzUnknownKeysIgnoredAndKnownSemanticsHold() {
        val random = Random(20261001)
        repeat(ITERATIONS) { i ->
            val (base, expected) = basePayload(random, i)
            val fields = okOf(JsonObject(base))
            assertEquals(expected.chatId, fields.chatId, "未知键不得污染 chatId，迭代 " + i)
            assertEquals(expected.action, fields.action, "未知键不得污染 action，迭代 " + i)
        }
    }

    @Test
    fun missingRequiredSemantics() {
        val valid = mapOf("action" to JsonPrimitive("typing"))
        // chatId 缺 / 空 / 纯空白 → MissingRequired（action 合法时）。
        assertTrue(parseOf(JsonObject(valid)) is BotSendChatActionFieldsResult.MissingRequired)
        assertTrue(
            parseOf(JsonObject(valid + ("chatId" to JsonPrimitive("")))) is BotSendChatActionFieldsResult.MissingRequired
        )
        assertTrue(
            parseOf(JsonObject(valid + ("chatId" to JsonPrimitive("   ")))) is BotSendChatActionFieldsResult.MissingRequired
        )
        // 只有 chatId 参与必填：action 缺席也照样 Ok（恒默认 "typing"）。
        val withoutAction = okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"))))
        assertEquals("c1", withoutAction.chatId)
        assertEquals("typing", withoutAction.action)
        // 显式 null 得字面量 "null"→非空→Ok 的逐字怪语义。
        val nullChatId = okOf(JsonObject(mapOf("chatId" to JsonNull)))
        assertEquals("null", nullChatId.chatId)
    }

    @Test
    fun actionDefaultAndNormalization() {
        fun actionOf(action: JsonElement?): String {
            val base = mutableMapOf<String, JsonElement>("chatId" to JsonPrimitive("c1"))
            if (action != null) base["action"] = action
            return okOf(JsonObject(base)).action
        }
        // 缺席 / 空串 / 纯空白 → "typing"。
        assertEquals("typing", actionOf(null))
        assertEquals("typing", actionOf(JsonPrimitive("")))
        assertEquals("typing", actionOf(JsonPrimitive("   ")))
        // 先 lowercase 后判空：大写混写被小写化。
        assertEquals("typing", actionOf(JsonPrimitive("typing")))
        assertEquals("typing", actionOf(JsonPrimitive("TYPING")))
        assertEquals("typing", actionOf(JsonPrimitive("Typing")))
        assertEquals("upload_photo", actionOf(JsonPrimitive("upload_photo")))
        assertEquals("upload_photo", actionOf(JsonPrimitive("UPLOAD_PHOTO")))
        assertEquals("record_video", actionOf(JsonPrimitive("Record_Video")))
        // 非典型值原样（小写化后）通过，不校验白名单——路由层照旧透传。
        assertEquals("cancel", actionOf(JsonPrimitive("cancel")))
        assertEquals(" md ", actionOf(JsonPrimitive(" MD ")))
        // 显式 null 得字面量 "null"→非空→不回退 "typing" 的逐字怪语义。
        assertEquals("null", actionOf(JsonNull))
    }

    @Test
    fun noTrimOnChatId() {
        // 无 trim：首尾空白原样保留。
        val spaced = okOf(JsonObject(mapOf("chatId" to JsonPrimitive(" c1 "), "action" to JsonPrimitive("typing"))))
        assertEquals(" c1 ", spaced.chatId)
    }

    @Test
    fun loudFailureCounterexamples() {
        // 对象 / 数组型已知字段在 ?.jsonPrimitive 处大声失败（路由层映射 400，不是 500）。
        val badChatIdObj = JsonObject(mapOf("chatId" to JsonObject(mapOf("a" to JsonPrimitive(1))), "action" to JsonPrimitive("typing")))
        assertFailsWith<IllegalArgumentException> { parseOf(badChatIdObj) }
        val badChatIdArr = JsonObject(mapOf("chatId" to JsonArray(listOf(JsonPrimitive("c"))), "action" to JsonPrimitive("typing")))
        assertFailsWith<IllegalArgumentException> { parseOf(badChatIdArr) }
        val badActionObj = JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "action" to JsonObject(mapOf("a" to JsonPrimitive(1)))))
        assertFailsWith<IllegalArgumentException> { parseOf(badActionObj) }
        val badActionArr = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c1"),
                "action" to JsonArray(listOf(JsonPrimitive("typing"))),
            )
        )
        assertFailsWith<IllegalArgumentException> { parseOf(badActionArr) }
        // 反证：显式 null 不抛（得字面量 "null"）。
        okOf(JsonObject(mapOf("chatId" to JsonNull)))
        okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "action" to JsonNull)))
    }

    @Test
    fun nearMissFieldNamesAreIgnored() {
        // 近似字段名按未知键忽略：真字段缺席→MissingRequired。
        val nearMiss = JsonObject(
            mapOf(
                "ChatId" to JsonPrimitive("c1"),
                "chatid2" to JsonPrimitive("c1"),
                "Action" to JsonPrimitive("typing"),
                "act" to JsonPrimitive("typing"),
            )
        )
        assertTrue(parseOf(nearMiss) is BotSendChatActionFieldsResult.MissingRequired)
    }
}
