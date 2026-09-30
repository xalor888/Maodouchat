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
 * Bot `sendPollQuiz` 请求体手写解析的**模糊兼容性测试**（清单 Q01「协议模型向前/向后兼容与
 * fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、
 * `sendContact`、`sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、
 * `forwardMessage`/`copyMessage`、`sendNudge`、`sendContactCard` 之后第十七块）。
 *
 * Bot 路由没有 typed DTO——请求体是 `receiveBoundedTextOrEmpty()` 拿到的原始字符串，
 * 经 `Json.parseToJsonElement(body).jsonObject` 再手写抽取。本轮把原来内联在
 * `/api/bot/sendPollQuiz` 处理器里的抽取 / 校验 / 内容组装逻辑收敛为纯函数
 * ([parseBotSendPollQuizFields] / [buildBotPollQuizContent])
 * （生产侧零行为改动：校验顺序仍为 群玩法开关（处理器，先）→ 必填（纯函数）→
 * 成员检查（处理器），`safeIdx` 钳位仍在处理器里逐字 `coerceIn`），
 * 本测试直接钉住这些函数的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 钉住别名链（`question`→`text`）：`?:` 接在字段**存在性**上——`question` 键存在
 *   但为显式 JSON null 时**不**穿透到 `text`，得字面 `"null"`；`question`
 *   `.orEmpty().take(200)` **不 trim**（前导空格计入 200 上限）。
 * - 钉住 `options` 的 `as? JsonArray` 分支（非数组不是错误而是 `emptyList()`→判缺）、
 *   元素 `trim()` + `take(80)` + 空白剔除 + `take(10)` 的逐字顺序、
 *   显式 JSON null 元素保留为字面 `"null"` 选项、对象/数组元素被 `(as? JsonPrimitive)`
 *   **静默丢弃**（不是大声失败——与已知字段类型错的语义不同，特意钉住）。
 * - 钉住 `correctOptionIndex` 的 `toIntOrNull() ?: 0`（JSON 数字与数字字符串都可，
 *   垃圾/浮点/显式 null/缺省一律→0）与内容模板形状
 *   （`"QUIZ:"` + question + `"|"` 分隔、正确选项多一个 `"*"` 标记，整体 `take(2000)`）。
 * - 反证 `wrong-typed known fields still fail loudly`：手写解析里 `?.jsonPrimitive`
 *   在类型错时抛 [IllegalArgumentException]，路由层 `StatusPages` 把它映射为 400
 *   「参数无效」（不是 500）——坏数据必须大声失败，不能悄悄吞掉。
 *   例外：`options` **元素**的类型错是静默丢弃（见上），`options` 本体非数组是
 *   `emptyList()`→判缺（也不是抛错）。
 */
class BotSendPollQuizParseFuzzTest {

    private companion object {
        /** sendPollQuiz 解析涉及的全部已知字段名（含别名）：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId", "question", "text", "options", "correctOptionIndex",
        )
        private const val ITERATIONS = 150
    }

    private fun randomFieldName(random: Random): String {
        val stems = listOf(
            "future", "x", "v9", "extra", "unknown", "meta", "debug", "tmp",
            "clientExt", "appExt", "exp", "flag",
        )
        var name = "${stems.random(random)}_${random.nextInt(10000)}"
        while (name in KNOWN_FIELD_NAMES) name = "z$name"
        return name
    }

    private fun randomJsonValue(random: Random, depth: Int): JsonElement {
        val leafKinds = 5 // bool / long / double / string / null
        return when (random.nextInt(if (depth <= 0) leafKinds else leafKinds + 2)) {
            0 -> JsonPrimitive(random.nextBoolean())
            1 -> JsonPrimitive(random.nextLong())
            2 -> JsonPrimitive(random.nextDouble())
            3 -> JsonPrimitive("str_${random.nextInt(100000)}_${random.nextLong()}")
            4 -> JsonNull
            5 -> JsonArray(List(random.nextInt(1, 4)) { randomJsonValue(random, depth - 1) })
            else -> JsonObject(
                (0 until random.nextInt(1, 4))
                    .associate { randomFieldName(random) to randomJsonValue(random, depth - 1) }
            )
        }
    }

    /** 顶层注入 1–5 个未知字段。 */
    private fun injectUnknownFields(base: JsonObject, random: Random): JsonObject {
        val fields = base.toMutableMap()
        repeat(random.nextInt(1, 6)) {
            fields[randomFieldName(random)] = randomJsonValue(random, 2)
        }
        return JsonObject(fields)
    }

    private fun fieldsOf(obj: JsonObject): BotSendPollQuizFields {
        val result = parseBotSendPollQuizFields(obj)
        assertTrue(result is BotSendPollQuizFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    private fun validBase(
        chatId: String = "c1",
        question: String = "今晚吃什么？",
        options: List<String> = listOf("火锅", "烧烤"),
        correctOptionIndex: Int = 1,
    ): JsonObject = JsonObject(
        mapOf(
            "chatId" to JsonPrimitive(chatId),
            "question" to JsonPrimitive(question),
            "options" to JsonArray(options.map { JsonPrimitive(it) }),
            "correctOptionIndex" to JsonPrimitive(correctOptionIndex),
        )
    )

    @Test
    fun `sendPollQuiz parse survives seeded unknown-field fuzz`() {
        val random = Random(0x90_12)
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val question = "q-$i"
            val options = listOf("opt-a-$i", "opt-b-$i", "opt-c-$i")
            val correct = random.nextInt(0, 3)
            val base = JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive(chatId),
                    "question" to JsonPrimitive(question),
                    "options" to JsonArray(options.map { JsonPrimitive(it) }),
                    "correctOptionIndex" to JsonPrimitive(correct),
                )
            )
            val obj = injectUnknownFields(base, random)
            val fields = fieldsOf(obj)
            assertEquals(
                BotSendPollQuizFields(chatId, question, options, correct),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
        }
    }

    @Test
    fun `sendPollQuiz required fields are enforced`() {
        val twoOptions = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))
        // chatId 缺失 → MissingRequired
        assertTrue(
            parseBotSendPollQuizFields(
                JsonObject(
                    mapOf(
                        "question" to JsonPrimitive("q"),
                        "options" to twoOptions,
                    )
                )
            ) is BotSendPollQuizFieldsResult.MissingRequired,
            "chatId 缺失必须判缺",
        )
        // chatId 空白 → MissingRequired
        assertTrue(
            parseBotSendPollQuizFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("  "),
                        "question" to JsonPrimitive("q"),
                        "options" to twoOptions,
                    )
                )
            ) is BotSendPollQuizFieldsResult.MissingRequired,
            "chatId 空白必须判缺",
        )
        // question 缺失 → MissingRequired
        assertTrue(
            parseBotSendPollQuizFields(
                JsonObject(mapOf("chatId" to JsonPrimitive("c"), "options" to twoOptions))
            ) is BotSendPollQuizFieldsResult.MissingRequired,
            "question 缺失必须判缺",
        )
        // 空白 question → MissingRequired（question 本身不 trim，空白判缺靠 isBlank）
        assertTrue(
            parseBotSendPollQuizFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "question" to JsonPrimitive("   "),
                        "options" to twoOptions,
                    )
                )
            ) is BotSendPollQuizFieldsResult.MissingRequired,
            "空白 question 必须判缺",
        )
        // options 缺失 → MissingRequired
        assertTrue(
            parseBotSendPollQuizFields(
                JsonObject(mapOf("chatId" to JsonPrimitive("c"), "question" to JsonPrimitive("q")))
            ) is BotSendPollQuizFieldsResult.MissingRequired,
            "options 缺失必须判缺",
        )
        // options 只有 1 个 → MissingRequired
        assertTrue(
            parseBotSendPollQuizFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "question" to JsonPrimitive("q"),
                        "options" to JsonArray(listOf(JsonPrimitive("a"))),
                    )
                )
            ) is BotSendPollQuizFieldsResult.MissingRequired,
            "options 不足 2 个必须判缺",
        )
        // options 恰 2 个 → Ok
        assertTrue(
            parseBotSendPollQuizFields(validBase()) is BotSendPollQuizFieldsResult.Ok,
            "options 恰 2 个必须通过",
        )
    }

    @Test
    fun `sendPollQuiz question alias fallback and truncation quirks`() {
        val twoOptions = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))
        fun withQuestion(vararg pairs: Pair<String, JsonElement>): BotSendPollQuizFields =
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "options" to twoOptions,
                    ) + pairs.toMap()
                )
            )
        // question 缺失 → text 别名生效
        assertEquals(
            "来自 text 的题面",
            withQuestion("text" to JsonPrimitive("来自 text 的题面")).question,
            "question 缺失时 text 别名必须生效",
        )
        // question 与 text 并存 → question 优先（即使 question 是显式 null 也不穿透）
        assertEquals(
            "null",
            withQuestion(
                "question" to JsonNull,
                "text" to JsonPrimitive("来自 text 的题面"),
            ).question,
            "显式 JSON null 的 question 是 JsonPrimitive、content 为 \"null\"，不能穿透到 text",
        )
        // question 不 trim：首尾空格原样保留
        assertEquals(
            "  前后空格  ",
            withQuestion("question" to JsonPrimitive("  前后空格  ")).question,
            "question 必须不 trim，原样保留",
        )
        // take(200)：前导空格计入上限
        val long = " ".repeat(150) + "y".repeat(100)
        val truncated = withQuestion("question" to JsonPrimitive(long)).question
        assertEquals(200, truncated.length, "question 必须截断到 200 字符")
        assertEquals(" ".repeat(150) + "y".repeat(50), truncated, "前导空格必须计入 200 上限")
    }

    @Test
    fun `sendPollQuiz option filtering keeps production quirks`() {
        fun withOptions(options: JsonElement): BotSendPollQuizFields =
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "question" to JsonPrimitive("q"),
                        "options" to options,
                    )
                )
            )
        // 元素 trim + 空白剔除
        assertEquals(
            listOf("火锅", "烧烤"),
            withOptions(
                JsonArray(
                    listOf(
                        JsonPrimitive("  火锅  "),
                        JsonPrimitive("   "),
                        JsonPrimitive("烧烤"),
                    )
                )
            ).options,
            "选项元素必须 trim 并剔除空白",
        )
        // 元素 take(80)：截断发生在 trim 之后、空白剔除之前
        val long80 = withOptions(
            JsonArray(listOf(JsonPrimitive("x".repeat(100)), JsonPrimitive("y")))
        ).options
        assertEquals(listOf("x".repeat(80), "y"), long80, "选项元素必须截断到 80 字符")
        // take(10)：空白剔除之后再取前 10（剔掉 3 个空白后 12 个剩 9 个，全收）
        val twelve = (1..12).map { JsonPrimitive("o$it") } +
            listOf(JsonPrimitive("  "), JsonPrimitive(""), JsonPrimitive("\t"))
        assertEquals(
            (1..12).map { "o$it" }.take(10),
            withOptions(JsonArray(twelve)).options,
            "空白剔除后不足 10 个必须全收，多出的只取前 10",
        )
        // 显式 JSON null 是 JsonPrimitive：content 为 "null" 字符串，保留为字面选项
        assertEquals(
            listOf("a", "null", "b"),
            withOptions(JsonArray(listOf(JsonPrimitive("a"), JsonNull, JsonPrimitive("b")))).options,
            "显式 null 必须保留为字面 \"null\" 选项",
        )
        // 数字元素：content 字符串化后保留
        assertEquals(
            listOf("42", "b"),
            withOptions(JsonArray(listOf(JsonPrimitive(42), JsonPrimitive("b")))).options,
            "数字元素必须按 content 字符串保留",
        )
        // 非原始类型元素（对象/数组）被 (as? JsonPrimitive) 静默丢弃——不抛错
        assertEquals(
            listOf("a", "b"),
            withOptions(
                JsonArray(
                    listOf(
                        JsonPrimitive("a"),
                        JsonObject(mapOf("k" to JsonPrimitive("v"))),
                        JsonArray(listOf(JsonPrimitive("x"))),
                        JsonPrimitive("b"),
                    )
                )
            ).options,
            "对象/数组型选项元素必须被静默丢弃",
        )
        // options 非数组不是错误：emptyList → 判缺（不是抛错）
        assertTrue(
            parseBotSendPollQuizFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "question" to JsonPrimitive("q"),
                        "options" to JsonPrimitive("not-an-array"),
                    )
                )
            ) is BotSendPollQuizFieldsResult.MissingRequired,
            "options 非数组必须走 emptyList → 判缺，而不是抛错",
        )
    }

    @Test
    fun `sendPollQuiz correctOptionIndex defaults parses and clamps`() {
        fun withCorrect(correct: JsonElement): Int =
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "question" to JsonPrimitive("q"),
                        "options" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
                        "correctOptionIndex" to correct,
                    )
                )
            ).correctOptionIndex
        // 缺省 → 0
        assertEquals(
            0,
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "question" to JsonPrimitive("q"),
                        "options" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
                    )
                )
            ).correctOptionIndex,
            "correctOptionIndex 缺省必须为 0",
        )
        assertEquals(0, withCorrect(JsonPrimitive("garbage")), "非数字字符串必须→0")
        assertEquals(0, withCorrect(JsonPrimitive("1.5")), "浮点字符串 toIntOrNull 为 null→0")
        assertEquals(0, withCorrect(JsonNull), "显式 JSON null 的 content 是 \"null\"→0")
        assertEquals(2, withCorrect(JsonPrimitive("2")), "数字字符串必须解析")
        assertEquals(2, withCorrect(JsonPrimitive(2)), "JSON 数字必须解析")
        assertEquals(-3, withCorrect(JsonPrimitive(-3)), "纯函数返回解析原始值，不钳位")
        // 内容模板：QUIZ: + question + | 分隔、正确选项 "*" 标记
        assertEquals(
            "QUIZ:今晚吃什么？|火锅|*烧烤",
            buildBotPollQuizContent("今晚吃什么？", listOf("火锅", "烧烤"), 1),
            "内容模板必须逐字一致，正确选项带 * 标记",
        )
        // safeIdx=0 时第一个选项带标记
        assertEquals(
            "QUIZ:q|*a|b",
            buildBotPollQuizContent("q", listOf("a", "b"), 0),
            "safeIdx=0 时标记必须在第一个选项",
        )
        // 整体 take(2000)
        assertEquals(
            2000,
            buildBotPollQuizContent("x".repeat(5000), listOf("a", "b"), 0).length,
            "内容整体必须截断到 2000 字符",
        )
    }

    @Test
    fun `sendPollQuiz wrong-typed known fields fail loudly`() {
        val twoOptions = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))
        val badChatId = JsonObject(
            mapOf(
                "chatId" to JsonObject(mapOf("nested" to JsonPrimitive("x"))),
                "question" to JsonPrimitive("q"),
                "options" to twoOptions,
            )
        )
        assertFailsWith<IllegalArgumentException>("对象型 chatId 必须抛 IllegalArgumentException") {
            parseBotSendPollQuizFields(badChatId)
        }
        val badQuestion = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "question" to JsonArray(emptyList()),
                "options" to twoOptions,
            )
        )
        assertFailsWith<IllegalArgumentException>("数组型 question 必须抛 IllegalArgumentException") {
            parseBotSendPollQuizFields(badQuestion)
        }
        val badCorrect = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "question" to JsonPrimitive("q"),
                "options" to twoOptions,
                "correctOptionIndex" to JsonObject(mapOf("nested" to JsonPrimitive(1))),
            )
        )
        assertFailsWith<IllegalArgumentException>("对象型 correctOptionIndex 必须抛 IllegalArgumentException") {
            parseBotSendPollQuizFields(badCorrect)
        }
        // correct 抽取在必填检查之前：chatId 缺失也不能掩盖 correct 的类型错
        val badCorrectMissingChatId = JsonObject(
            mapOf(
                "question" to JsonPrimitive("q"),
                "options" to twoOptions,
                "correctOptionIndex" to JsonArray(listOf(JsonPrimitive(1))),
            )
        )
        assertFailsWith<IllegalArgumentException>("correct 类型错即使必填缺失也必须先抛（逐字顺序）") {
            parseBotSendPollQuizFields(badCorrectMissingChatId)
        }
    }
}
