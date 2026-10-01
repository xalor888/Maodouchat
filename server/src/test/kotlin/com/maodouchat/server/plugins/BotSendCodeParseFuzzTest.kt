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
 * Bot `sendCode` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，G355
 * `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、`sendPhoto`、
 * `sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、`sendVenue`、
 * `sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、
 * `sendChecklist`、`sendAlert`、`sendCountdown`、`sendNotice`、`sendBadge`、
 * `sendToast`、`sendHr`、`sendDivider`、`sendProgress`、`send*Hint`、
 * `sendMentionCard`/`sendNudgeCard`、`sendMetric`/`sendCompare`、`sendKeyValue`、
 * `sendQuoteCard`、`sendBanner`、`sendJsonCard`、`sendMarkdown`、`sendQuote`
 * 之后第三十七块）。
 *
 * 本测试直接钉住纯函数 ([parseBotCodeFields]) 与围栏组装 ([buildBotCodeContent])
 * 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]，含 `text`（`code` 的
 *   存在性回退键）与 `language`）：否则测的是「重复键覆盖语义」，而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 钉住 `code` 的存在性回退：只有 `code` 键完全缺席才看 `text`；`code` 在但为
 *   显式 null 时仍走 `code` 分支（取到字面量 `"null"` 字符串，不回退、不抛——
 *   kotlinx-serialization-json 1.11.0 的 `JsonNull.content` 即 `"null"`，
 *   `sendMarkdown` 第三十五块 CI 已实证）。
 * - 钉住 `code` 取 `?.jsonPrimitive?.content` **不是** `?.toString()`：字符串不带引号
 *   进组装（与 `sendJsonCard` 的怪语义相反，特意钉住）；`take(3500)` 作用于
 *   `orEmpty()` **之后**（裁的是 content 本身）；`language` 同理 `take(24)`。
 * - 钉住**双必填**（`chatId` 或 `code` 缺/空白 → `MissingRequired`；判的是裁过
 *   3500 的空白性；`language` 再长也不参与必填）。
 * - 钉住围栏组装：`language` 非空（`isNotBlank`，判的是裁过 24 的串）时
 *   `` ``` `` + lang + `"\n"` + code + `"\n```"`，否则 `` ``` `` + `"\n"` +
 *   code + `"\n```"`——注意 `language` 显式 null 得字面 `"null"`（非空），
 *   会走带语言围栏分支，特意钉住。
 * - 反证 `wrong-typed known fields still fail loudly`：手写解析里 `chatId` /
 *   `code` / `language` 的 `?.jsonPrimitive` 在类型错时抛 [IllegalArgumentException]
 *   （对象 / 数组型值在 `?.jsonPrimitive` 处抛；显式 null 取到 `"null"` 字面量
 *   字符串、不抛；注意**数字 / 布尔不抛**，`JsonPrimitive.content` 对它们是
 *   `toString()`——特意钉住），路由层 `StatusPages` 把它映射为 400「参数无效」
 *   （不是 500）——坏数据必须大声失败，不能悄悄吞掉。
 * - 反证抽取顺序：`chatId` 先于 `code` 抽取——`chatId` 坏类型 + `code` 乱值→抛错；
 *   `chatId` 缺席 + `code` 乱值→抛错（不是 `MissingRequired`：抽取抛在必填
 *   判断之前，原处理器逐字如此）。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
class BotSendCodeParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("chatId", "code", "text", "language")
        private const val ITERATIONS = 150
        private const val CODE_CAP = 3500
        private const val LANG_CAP = 24
    }

    private fun parseOf(obj: JsonObject): BotCodeFieldsResult =
        parseBotCodeFields(obj)

    private fun okOf(obj: JsonObject): BotCodeFields =
        (parseOf(obj) as BotCodeFieldsResult.Ok).fields

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
        val random = Random(20261002)
        repeat(ITERATIONS) { i ->
            val mode = i % 3
            val base = mutableMapOf<String, JsonElement>()
            base["chatId"] = JsonPrimitive("chat-code-" + i)
            // mode 0：code 缺席 → 钉住 text 回退；mode 1：code + text
            // 同时在场 → 钉住 code 分支（text 被忽略）；mode 2：全字段抽取。
            val expectedCode: String
            when (mode) {
                0 -> {
                    base["text"] = JsonPrimitive("fallback-c-" + i)
                    expectedCode = "fallback-c-" + i
                }
                1 -> {
                    base["code"] = JsonPrimitive("code-wins-" + i)
                    base["text"] = JsonPrimitive("ignored-t-" + i)
                    expectedCode = "code-wins-" + i
                }
                else -> {
                    base["code"] = JsonPrimitive("plain-c-" + i)
                    expectedCode = "plain-c-" + i
                }
            }
            // mode 2 的一半用例钉住 take(3500) 作用于 content 之后。
            val longCode = mode == 2 && i % 2 == 0
            if (longCode) {
                base["code"] = JsonPrimitive("c".repeat(CODE_CAP + 777))
            }
            // language：三分之一缺席（空串语义），其余钉住 take(24)。
            val expectedLang: String = if (i % 3 == 0) {
                ""
            } else if (i % 5 == 4) {
                base["language"] = JsonPrimitive("l".repeat(LANG_CAP + 111))
                "l".repeat(LANG_CAP)
            } else {
                base["language"] = JsonPrimitive("lang-" + i)
                "lang-" + i
            }
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val fields = okOf(JsonObject(base))
            assertEquals("chat-code-" + i, fields.chatId, "未知键不得污染 chatId，迭代 " + i)
            val wantCode = if (longCode) "c".repeat(CODE_CAP) else expectedCode
            assertEquals(wantCode, fields.code, "未知键不得污染 code 抽取/回退/截断语义，迭代 " + i)
            assertEquals(expectedLang, fields.lang, "未知键不得污染 language 抽取/截断语义，迭代 " + i)
            assertEquals(
                buildBotCodeContent(wantCode, expectedLang),
                buildBotCodeContent(fields.code, fields.lang),
                "围栏组装必须与字段逐字一致，迭代 " + i,
            )
        }
    }

    @Test
    fun missingRequiredSemantics() {
        // chatId 缺 / 空 / 纯空白 → MissingRequired。
        assertTrue(parseOf(JsonObject(mapOf("code" to JsonPrimitive("hi")))) is BotCodeFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive(""), "code" to JsonPrimitive("hi")))) is BotCodeFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("   "), "code" to JsonPrimitive("hi")))) is BotCodeFieldsResult.MissingRequired)
        // code 与 text 均缺席 → MissingRequired。
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1")))) is BotCodeFieldsResult.MissingRequired)
        // code 空 / 纯空白 → MissingRequired（判的是 content 空白性）。
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "code" to JsonPrimitive("")))) is BotCodeFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "code" to JsonPrimitive("  \t ")))) is BotCodeFieldsResult.MissingRequired)
        // code 缺席但 text 纯空白 → MissingRequired。
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "text" to JsonPrimitive("   ")))) is BotCodeFieldsResult.MissingRequired)
        // language 再长也不参与必填：language 缺席 + 双合法 → Ok。
        val ok = okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "code" to JsonPrimitive("hello"))))
        assertEquals("c1", ok.chatId)
        assertEquals("hello", ok.code)
        assertEquals("", ok.lang)
    }

    @Test
    fun capAndQuirks() {
        // code 超长截 3500（作用于 orEmpty() 之后，裁的是 content）。
        val ok = okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "code" to JsonPrimitive("x".repeat(3500 + 42)))))
        assertEquals("x".repeat(3500), ok.code)
        // language 超长截 24。
        val ok2 = okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "code" to JsonPrimitive("hi"), "language" to JsonPrimitive("y".repeat(24 + 7)))))
        assertEquals("y".repeat(24), ok2.lang)
        // code 显式 null → 字面 "null"，不抛、不回退到 text。
        val ok3 = okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "code" to JsonNull, "text" to JsonPrimitive("fallback-ignored"))))
        assertEquals("null", ok3.code)
        // code 缺席 + text 显式 null → "null"。
        val ok4 = okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "text" to JsonNull)))
        assertEquals("null", ok4.code)
        // language 显式 null → 字面 "null"（注意：此时围栏组装走带语言分支）。
        val ok5 = okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "code" to JsonPrimitive("hi"), "language" to JsonNull)))
        assertEquals("null", ok5.lang)
        // chatId 显式 null 同理得 "null"。
        val ok6 = okOf(JsonObject(mapOf("chatId" to JsonNull, "code" to JsonPrimitive("hi"))))
        assertEquals("null", ok6.chatId)
        // JSON 数字 / 布尔经 content 取 toString，不抛。
        val ok7 = okOf(JsonObject(mapOf("chatId" to JsonPrimitive(123), "code" to JsonPrimitive(true))))
        assertEquals("123", ok7.chatId)
        assertEquals("true", ok7.code)
        // 对象 / 数组型 chatId / code / language 在 ?.jsonPrimitive 处抛 IllegalArgumentException。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonObject(mapOf()), "code" to JsonPrimitive("hi"))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "code" to JsonArray(emptyList()))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "code" to JsonPrimitive("hi"), "language" to JsonArray(emptyList()))))
        }
    }

    @Test
    fun contentAssembly() {
        // lang 非空 → 带语言围栏；判的是裁过 24 的串。
        assertEquals("```kotlin\nprintln(1)\n```", buildBotCodeContent("println(1)", "kotlin"))
        // lang 为空 → 无语言围栏。
        assertEquals("```\nprintln(1)\n```", buildBotCodeContent("println(1)", ""))
        // lang 纯空白 → 无语言围栏（isNotBlank 逐字）。
        assertEquals("```\nprintln(1)\n```", buildBotCodeContent("println(1)", "  "))
        // 显式 null 的 language 经裁剪得 "null"（非空）→ 带语言围栏分支，特意钉住。
        assertEquals("```null\nx\n```", buildBotCodeContent("x", "null"))
        // code 多行逐字保留。
        assertEquals("```\nline1\nline2\n```", buildBotCodeContent("line1\nline2", ""))
    }

    @Test
    fun wrongTypedKnownFieldsFailLoudly() {
        // chatId 对象型 → 抛（大声失败，路由层 StatusPages 映射 400，不是 500）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonObject(mapOf("x" to JsonPrimitive(1))), "code" to JsonPrimitive("hi"))))
        }
        // code 数组型 → 抛。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "code" to JsonArray(listOf(JsonPrimitive(1))))))
        }
        // language 对象型 → 抛。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "code" to JsonPrimitive("hi"), "language" to JsonObject(mapOf()))))
        }
        // 反证：显式 null 不抛（是 JsonPrimitive 的一种），得字面 "null"。
        val ok = okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "code" to JsonNull)))
        assertEquals("null", ok.code)
    }

    @Test
    fun extractionOrder() {
        // chatId 坏类型 + code 乱值 → 抛错（chatId 先抽取）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonArray(emptyList()), "code" to JsonPrimitive("whatever"))))
        }
        // chatId 缺席 + code 乱值 → 抛错，而非 MissingRequired（抽取抛在必填判断之前）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("code" to JsonArray(emptyList()))))
        }
        // chatId 纯空白 + code 乱值 → 仍抛错（抽取先于必填判断）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("   "), "code" to JsonObject(mapOf()))))
        }
        // 反证：code 合法时 chatId 纯空白 → MissingRequired（抽取不抛，轮到必填判断）。
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("  "), "code" to JsonPrimitive("hi")))) is BotCodeFieldsResult.MissingRequired)
    }
}
