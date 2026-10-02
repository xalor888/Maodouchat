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
 * Bot `setMyName` 请求体手写解析的**模糊兼容性测试**
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
 * `editMessageCaption`、`sendTimeline`、`sendRemind`、`sendMessageSilent`、
 * `sendChatAction`、`exportChatInviteLink`、`revokeChatInviteLink`、
 * `unpinAllChatMessages`、`setChatPhoto`、`deleteChatPhoto`、`pinChatMessage`、
 * `unpinChatMessage` 之后第五十六块）。
 *
 * 本测试直接钉住纯函数 ([parseBotSetMyNameFields]) 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 吸取第四十一块（`sendTable`）的 CI 教训：fuzz 基 payload 的**必填字段必须
 *   确定性合法**——全随机时已知字段可能被丢弃/变空 → `InvalidName` →
 *   `okOf` 的 `as Ok` 强转抛 `ClassCastException`。这里 name 恒为 `"n" + i`
 *   （无空白、无需 trim 干预）；必填语义由 `invalidNameSemantics` 钉住。
 * - 钉住 **trim 在 take(120) 之前**（`"  Alice  "`→`"Alice"`；200 字符超长→120 字符截断；
 *   纯空白→`InvalidName`）。
 * - 钉住 **`name` 优先、`displayName` 回退**：name 在场即用 name；name 缺席用 displayName；
 *   **显式 null 不触发回退**（`JsonNull` 非空→得字面量 `"null"`→`Ok`，逐字怪语义）。
 * - 反证坏类型大声失败：对象 / 数组型在 `?.jsonPrimitive`
 *   处抛 [IllegalArgumentException]（路由层 `StatusPages` 映射为 400「参数无效」，
 *   不是 500）；显式 null 不抛的反证。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
class BotSetMyNameParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("name", "displayName")
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotSetMyNameFieldsResult =
        parseBotSetMyNameFields(obj)

    private fun okOf(obj: JsonObject): BotSetMyNameFields =
        (parseOf(obj) as BotSetMyNameFieldsResult.Ok).fields

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
        val random = Random(2026100256)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            // 必填字段确定性合法（第四十一块 CI 教训）：name 恒为 "n<i>"，
            // 未知键注入永不触达必填。name 本身无空白，原样进下游。
            base["name"] = JsonPrimitive("n" + i)
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val fields = okOf(JsonObject(base))
            assertEquals("n" + i, fields.name, "未知键不得污染 name，迭代 " + i)
        }
    }

    @Test
    fun namePriorityAndFallback() {
        // name 在场 → 优先用 name（displayName 被忽略）。
        val both = okOf(
            JsonObject(mapOf("name" to JsonPrimitive("Alice"), "displayName" to JsonPrimitive("Bob")))
        )
        assertEquals("Alice", both.name)
        // name 缺席 → 回退 displayName。
        val fallback = okOf(JsonObject(mapOf("displayName" to JsonPrimitive("Bob"))))
        assertEquals("Bob", fallback.name)
        // 显式 null 不触发回退（JsonNull 非空→?: 不生效）→ 字面量 "null" → Ok。
        val nullName = okOf(
            JsonObject(mapOf("name" to JsonNull, "displayName" to JsonPrimitive("Bob")))
        )
        assertEquals("null", nullName.name, "显式 null 的 name 不得回退到 displayName")
        // 显式 null 的 displayName → 字面量 "null" → Ok。
        val nullDisplay = okOf(JsonObject(mapOf("displayName" to JsonNull)))
        assertEquals("null", nullDisplay.name)
    }

    @Test
    fun trimAndLengthClamp() {
        // trim 在 take(120) 之前：前后空白去掉。
        assertEquals("Alice", okOf(JsonObject(mapOf("name" to JsonPrimitive("  Alice  ")))).name)
        // 纯空白 → InvalidName（trim 后判空）。
        assertTrue(
            parseOf(JsonObject(mapOf("name" to JsonPrimitive("   \t ")))) is BotSetMyNameFieldsResult.InvalidName
        )
        // 超长 → take(120) 截断（200 个 x → 120 个）。
        val long = "x".repeat(200)
        val clamped = okOf(JsonObject(mapOf("name" to JsonPrimitive(long))))
        assertEquals(120, clamped.name.length)
        assertEquals("x".repeat(120), clamped.name)
        // 恰好 120 → 不截断。
        val exact = "y".repeat(120)
        assertEquals(exact, okOf(JsonObject(mapOf("name" to JsonPrimitive(exact)))).name)
    }

    @Test
    fun invalidNameSemantics() {
        // 缺 / 空 / 纯空白 → InvalidName。
        assertTrue(parseOf(JsonObject(emptyMap())) is BotSetMyNameFieldsResult.InvalidName)
        assertTrue(
            parseOf(JsonObject(mapOf("name" to JsonPrimitive("")))) is BotSetMyNameFieldsResult.InvalidName
        )
        assertTrue(
            parseOf(JsonObject(mapOf("displayName" to JsonPrimitive("")))) is BotSetMyNameFieldsResult.InvalidName
        )
        // 显式 null 不判空（逐字怪语义）：name 缺席、displayName 显式 null → "null" → Ok。
        val explicitNull = okOf(JsonObject(mapOf("displayName" to JsonNull)))
        assertEquals("null", explicitNull.name)
    }

    @Test
    fun loudFailureOnBadTypes() {
        // 对象 / 数组型在 ?.jsonPrimitive 处大声失败（路由层 StatusPages → 400，不是 500）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("name" to JsonObject(mapOf("a" to JsonPrimitive(1))))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("name" to JsonArray(listOf(JsonPrimitive(1))))))
        }
        // name 缺席时 displayName 的坏类型同样大声失败。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("displayName" to JsonObject(emptyMap()))))
        }
    }

    @Test
    fun nearMissNamesIgnored() {
        // 近似字段名按未知键忽略，真字段缺席 → InvalidName。
        val obj = JsonObject(
            mapOf(
                "Name" to JsonPrimitive("Alice"),
                "NAME" to JsonPrimitive("Alice"),
                "name2" to JsonPrimitive("Alice"),
                "display_name" to JsonPrimitive("Bob"),
                "displayname" to JsonPrimitive("Bob")
            )
        )
        assertTrue(parseOf(obj) is BotSetMyNameFieldsResult.InvalidName)
    }
}
