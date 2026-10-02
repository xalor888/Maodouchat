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
 * Bot `setChatTitle` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，G355
 * `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、
 * `sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、
 * `sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、
 * `forwardMessage`/`copyMessage`、`sendNudge`、`sendContactCard`、
 * `sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、`sendAlert`、
 * `sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、
 * `sendDivider`、`sendProgress`、`send*Hint`、`sendMentionCard`/`sendNudgeCard`、
 * `sendMetric`/`sendCompare`、`sendKeyValue`/`sendQuoteCard`、`sendBanner`、
 * `sendJsonCard`、`sendMarkdown`、`sendQuote`、`sendCode`、`setMessageReaction`、
 * `starMessage`、`sendStatus`、`sendTable`、`sendAnimation`、`sendAudio`、
 * `editMessageCaption`、`sendTimeline`、`sendRemind`、`sendMessageSilent`、
 * `sendChatAction`、`exportChatInviteLink`、`revokeChatInviteLink`、
 * `unpinAllChatMessages`、`setChatPhoto`、`deleteChatPhoto`、`pinChatMessage`、
 * `unpinChatMessage`、`setMyName` 之后第五十七块）。
 *
 * 本测试直接钉住纯函数 ([parseBotSetChatTitleFields]) 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 吸取第四十一块（`sendTable`）的 CI 教训：fuzz 基 payload 的**必填字段必须
 *   确定性合法**——全随机时已知字段可能被丢弃/变空 → `Invalid` →
 *   `okOf` 的 `as Ok` 强转抛 `ClassCastException`。这里 chatId 恒为 `"c" + i`、
 *   title 恒为 `"T" + i`（均无空白、无需 trim 干预，长度远小于 50）；
 *   必填语义由 `requiredSemantics` 钉住。
 * - 钉住 **trim 在长度校验之前**（`"  群名  "`→`"群名"`；恰 50 字符→Ok；
 *   51 字符→`Invalid`；前后空白不计入长度，因为先 trim）。
 * - 钉住 **`title` 优先、`groupName` 回退**：title 在场即用 title；title 缺席用 groupName；
 *   **显式 null 不触发回退**（`JsonNull` 非空→得字面量 `"null"`→`Ok`，逐字怪语义）；
 *   `chatId` 无 trim（`" c1 "` 原样保留，原处理器逐字如此）。
 * - 反证坏类型大声失败：对象 / 数组型在 `?.jsonPrimitive`
 *   处抛 [IllegalArgumentException]（路由层 `StatusPages` 映射为 400「参数无效」，
 *   不是 500）；显式 null 不抛的反证。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
class BotSetChatTitleParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("chatId", "title", "groupName")
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotSetChatTitleFieldsResult =
        parseBotSetChatTitleFields(obj)

    private fun okOf(obj: JsonObject): BotSetChatTitleFields =
        (parseOf(obj) as BotSetChatTitleFieldsResult.Ok).fields

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
    fun fuzzUnknownKeysIgnored() {
        val random = Random(2026100257)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            // 必填字段确定性合法（第四十一块 CI 教训）：chatId 恒为 "c<i>"、
            // title 恒为 "T<i>"（均无空白、远短于 50），未知键注入永不触达必填。
            base["chatId"] = JsonPrimitive("c" + i)
            base["title"] = JsonPrimitive("T" + i)
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val fields = okOf(JsonObject(base))
            assertEquals("c" + i, fields.chatId, "未知键不得污染 chatId，迭代 " + i)
            assertEquals("T" + i, fields.title, "未知键不得污染 title，迭代 " + i)
        }
    }

    @Test
    fun titlePriorityAndFallback() {
        // title 在场 → 优先用 title（groupName 被忽略）。
        val both = okOf(
            JsonObject(mapOf("title" to JsonPrimitive("新群名"), "groupName" to JsonPrimitive("旧群名")))
        )
        assertEquals("新群名", both.title)
        // title 缺席 → 回退 groupName。
        val fallback = okOf(JsonObject(mapOf("groupName" to JsonPrimitive("旧群名"))))
        assertEquals("旧群名", fallback.title)
        // 显式 null 不触发回退（JsonNull 非空→?: 不生效）→ 字面量 "null" → Ok。
        val nullTitle = okOf(
            JsonObject(mapOf("title" to JsonNull, "groupName" to JsonPrimitive("旧群名")))
        )
        assertEquals("null", nullTitle.title, "显式 null 的 title 不得回退到 groupName")
        // 显式 null 的 groupName → 字面量 "null" → Ok。
        val nullGroup = okOf(JsonObject(mapOf("groupName" to JsonNull)))
        assertEquals("null", nullGroup.title)
    }

    @Test
    fun trimAndLengthClamp() {
        // trim 在长度校验之前：前后空白去掉，不计入长度。
        assertEquals("群名", okOf(JsonObject(mapOf("title" to JsonPrimitive("  群名  "),
            "chatId" to JsonPrimitive("c1")))).title)
        // 恰好 50 字符 → 不超限 → Ok。
        val exact = "x".repeat(50)
        assertEquals(exact, okOf(JsonObject(mapOf("title" to JsonPrimitive(exact),
            "chatId" to JsonPrimitive("c1")))).title)
        // 前后空白 + 48 字符 → trim 后 48 → Ok（空白不计入长度）。
        val padded = "  " + "y".repeat(48) + "  "
        assertEquals("y".repeat(48), okOf(JsonObject(mapOf("title" to JsonPrimitive(padded),
            "chatId" to JsonPrimitive("c1")))).title)
        // 51 字符 → Invalid（长度上限判的是 trim 之后的 title）。
        assertTrue(
            parseOf(JsonObject(mapOf("title" to JsonPrimitive("x".repeat(51)),
                "chatId" to JsonPrimitive("c1")))) is BotSetChatTitleFieldsResult.Invalid
        )
        // 纯空白 title → Invalid（trim 后判空）。
        assertTrue(
            parseOf(JsonObject(mapOf("title" to JsonPrimitive("   \t "),
                "chatId" to JsonPrimitive("c1")))) is BotSetChatTitleFieldsResult.Invalid
        )
        // chatId 无 trim：" c1 " 原样保留（原处理器逐字如此），但仍通过必填（非空）。
        assertEquals(" c1 ", okOf(JsonObject(mapOf("title" to JsonPrimitive("群名"),
            "chatId" to JsonPrimitive(" c1 ")))).chatId)
    }

    @Test
    fun requiredSemantics() {
        // chatId 缺 / 空 / 纯空白 → Invalid（chatId 无 trim，纯空白直接判空）。
        assertTrue(parseOf(JsonObject(emptyMap())) is BotSetChatTitleFieldsResult.Invalid)
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive(""),
                "title" to JsonPrimitive("群名")))) is BotSetChatTitleFieldsResult.Invalid
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("   "),
                "title" to JsonPrimitive("群名")))) is BotSetChatTitleFieldsResult.Invalid
        )
        // title 缺 → 无回退键 → Invalid。
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1")))) is BotSetChatTitleFieldsResult.Invalid
        )
        // 显式 null 的 chatId 不判空（逐字怪语义）：得字面量 "null" → Ok。
        val nullChatId = okOf(JsonObject(mapOf("chatId" to JsonNull, "title" to JsonPrimitive("群名"))))
        assertEquals("null", nullChatId.chatId)
        // 显式 null 的 title 不判空（逐字怪语义）：得字面量 "null" → Ok。
        val nullTitle = okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "title" to JsonNull)))
        assertEquals("null", nullTitle.title)
    }

    @Test
    fun loudFailureOnBadTypes() {
        // 对象 / 数组型在 ?.jsonPrimitive 处大声失败（路由层 StatusPages → 400，不是 500）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonObject(mapOf("a" to JsonPrimitive(1))),
                "title" to JsonPrimitive("群名"))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("title" to JsonArray(listOf(JsonPrimitive(1))),
                "chatId" to JsonPrimitive("c1"))))
        }
        // title 缺席时 groupName 的坏类型同样大声失败。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("groupName" to JsonObject(emptyMap()),
                "chatId" to JsonPrimitive("c1"))))
        }
        // 显式 null 不抛的反证：JsonNull 是 JsonPrimitive。
        assertEquals("null", okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "title" to JsonNull))).title)
    }

    @Test
    fun nearMissNamesIgnored() {
        // 近似字段名按未知键忽略，真字段缺席 → Invalid。
        val obj = JsonObject(
            mapOf(
                "ChatId" to JsonPrimitive("c1"),
                "chatid2" to JsonPrimitive("c1"),
                "chat_id" to JsonPrimitive("c1"),
                "Title" to JsonPrimitive("群名"),
                "group_name" to JsonPrimitive("群名"),
                "groupname" to JsonPrimitive("群名")
            )
        )
        assertTrue(parseOf(obj) is BotSetChatTitleFieldsResult.Invalid)
    }
}
