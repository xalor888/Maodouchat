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
 * Bot hint 端点群（`BotHintRouting.kt` 的 `BOT_HINT_SPECS` 共享循环，
 * 35 个 `/api/bot/send*Hint`）请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，
 * G355 `sendMessage` 起至 `echo` 之后第七十七块）。
 *
 * 本测试直接钉住两个纯函数（[parseBotHintChatId]、[resolveBotHint]）的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 钉住 **chatId 无 trim**：`" c1 "` 原样保留仍通过必填（`isBlank()` 只判空）。
 * - 钉住 **chatId 显式 null 得字面量 `"null"`**（非空→Ok 的逐字怪语义）。
 * - 钉住 **hint 语义双分支**：`sanitize=false` 时缺键回退 `defaultHint`、
 *   显式 null 得字面量 `"null"`（不回退）、超长 take(120)；
 *   `sanitize=true` 时走 `sanitizeBotHint`（控制字符/空白折叠、trim、take(120)），
 *   清洗后空白回退 `defaultHint`。
 * - 反证坏类型大声失败：chatId / hint 对象/数组型在 `?.jsonPrimitive` 处抛
 *   [IllegalArgumentException]（路由层 `StatusPages` 映射为 400「参数无效」，
 *   不是 500）。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
class BotHintParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId",
            "hint",
        )
        private const val ITERATIONS = 150
        private const val DEFAULT_HINT = "默认提示"
    }

    private fun chatIdOf(obj: JsonObject): BotHintChatIdResult =
        parseBotHintChatId(obj)

    private fun okChatIdOf(chatId: JsonElement): String =
        (chatIdOf(JsonObject(mapOf("chatId" to chatId))) as BotHintChatIdResult.Ok).chatId

    private fun hintOf(obj: JsonObject, sanitize: Boolean): String =
        resolveBotHint(obj, sanitize, DEFAULT_HINT)

    private fun hintWith(hint: JsonElement?, sanitize: Boolean): String {
        val map = mutableMapOf<String, JsonElement>()
        map["chatId"] = JsonPrimitive("c1")
        if (hint != null) map["hint"] = hint
        return hintOf(JsonObject(map), sanitize)
    }

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
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?"
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
        val random = Random(2026100377)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            // 基 payload：chatId 恒为 "c<i>"、hint 恒为 "h<i>"（纯文本，清洗前后恒等）。
            val expectedChatId = "c" + i
            val expectedHint = "h" + i
            base["chatId"] = JsonPrimitive(expectedChatId)
            base["hint"] = JsonPrimitive(expectedHint)
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val obj = JsonObject(base)
            val chatIdResult = chatIdOf(obj)
            assertTrue(chatIdResult is BotHintChatIdResult.Ok, "注入未知键后 chatId 仍应为 Ok，迭代 " + i)
            assertEquals(expectedChatId, chatIdResult.chatId, "未知键不得污染 chatId，迭代 " + i)
            assertEquals(expectedHint, hintOf(obj, false), "未知键不得污染 hint（非清洗分支），迭代 " + i)
            assertEquals(expectedHint, hintOf(obj, true), "未知键不得污染 hint（清洗分支），迭代 " + i)
        }
    }

    @Test
    fun requiredSemantics() {
        // chatId 缺席 → Invalid。
        assertTrue(
            chatIdOf(JsonObject(emptyMap())) is BotHintChatIdResult.Invalid,
            "chatId 缺席应为 Invalid",
        )
        // chatId 空字符串 → Invalid。
        assertTrue(
            chatIdOf(JsonObject(mapOf("chatId" to JsonPrimitive("")))) is BotHintChatIdResult.Invalid,
            "chatId 为空应为 Invalid",
        )
        // chatId 纯空白 → Invalid（无 trim）。
        assertTrue(
            chatIdOf(JsonObject(mapOf("chatId" to JsonPrimitive("   ")))) is BotHintChatIdResult.Invalid,
            "chatId 纯空白应为 Invalid",
        )
        // chatId 显式 null → 字面量 "null"，非空 → Ok（逐字怪语义）。
        assertEquals("null", okChatIdOf(JsonNull))
        // 合法 → Ok。
        assertEquals("c1", okChatIdOf(JsonPrimitive("c1")))
    }

    @Test
    fun hintDefaultsPlainBranch() {
        // sanitize=false：hint 键缺席 → 回退 defaultHint。
        assertEquals(DEFAULT_HINT, hintWith(null, false))
        // 显式 null → 字面量 "null"（不回退默认值）。
        assertEquals("null", hintWith(JsonNull, false))
        // 显式空字符串 → 保留空（不回退默认值）。
        assertEquals("", hintWith(JsonPrimitive(""), false))
        // 超长 hint 截到 120。
        val long = "h".repeat(200)
        assertEquals(long.take(120), hintWith(JsonPrimitive(long), false))
    }

    @Test
    fun hintSanitizeBranch() {
        // sanitize=true：hint 键缺席 → sanitizeBotHint(null) 为空 → 回退 defaultHint。
        assertEquals(DEFAULT_HINT, hintWith(null, true))
        // 清洗后全空白 → 回退 defaultHint。
        assertEquals(DEFAULT_HINT, hintWith(JsonPrimitive("  \t\n "), true))
        // 控制字符被替换为空格、连续空白折叠、trim。
        assertEquals("a b", hintWith(JsonPrimitive("a\u0000b"), true))
        assertEquals("x y", hintWith(JsonPrimitive("  x\n\ny  "), true))
        // 显式 null → 字面量 "null"（不回退默认值）。
        assertEquals("null", hintWith(JsonNull, true))
        // 超长 hint 清洗后截到 120。
        val long = "h".repeat(200)
        assertEquals(long.take(120), hintWith(JsonPrimitive(long), true))
    }

    @Test
    fun whitespaceNotTrimmed() {
        // chatId 前后空白原样保留，仍通过必填（isBlank 只判空，不 trim）。
        assertEquals(" c1 ", okChatIdOf(JsonPrimitive(" c1 ")))
    }

    @Test
    fun nonStringPrimitivesUseContent() {
        // 数字 / 布尔型走 .content 字符串（原处理器逐字语义）。
        assertEquals("123", okChatIdOf(JsonPrimitive(123)))
        assertEquals("true", okChatIdOf(JsonPrimitive(true)))
        assertEquals("123", hintWith(JsonPrimitive(123), false))
        assertEquals("true", hintWith(JsonPrimitive(true), false))
        assertEquals("123", hintWith(JsonPrimitive(123), true))
    }

    @Test
    fun loudFailureOnWrongType() {
        // chatId 对象 / 数组型在 ?.jsonPrimitive 处抛 IllegalArgumentException。
        assertFailsWith<IllegalArgumentException> {
            chatIdOf(JsonObject(mapOf("chatId" to JsonObject(mapOf("a" to JsonPrimitive(1))))))
        }
        assertFailsWith<IllegalArgumentException> {
            chatIdOf(JsonObject(mapOf("chatId" to JsonArray(listOf(JsonPrimitive(1))))))
        }
        // hint 对象 / 数组型在 ?.jsonPrimitive 处抛 IllegalArgumentException。
        assertFailsWith<IllegalArgumentException> {
            hintWith(JsonObject(mapOf("a" to JsonPrimitive(1))), false)
        }
        assertFailsWith<IllegalArgumentException> {
            hintWith(JsonArray(listOf(JsonPrimitive(1))), true)
        }
    }

    @Test
    fun nearMissNamesIgnored() {
        // 近似字段名按未知键忽略；真字段缺席 → Invalid。
        for (name in listOf("ChatId", "CHATID", "chat_id", "chatid", "chatId2", "Hint", "HINT", "hint2", "hints")) {
            val obj = JsonObject(mapOf(name to JsonPrimitive("v1")))
            assertTrue(
                chatIdOf(obj) is BotHintChatIdResult.Invalid,
                "近似字段名 " + name + " 应被忽略，真字段缺席 → Invalid",
            )
        }
    }
}
