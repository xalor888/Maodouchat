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
 * Bot `sendTable` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，G355
 * `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、`sendPhoto`、
 * `sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、`sendVenue`、
 * `sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、
 * `sendChecklist`、`sendAlert`、`sendCountdown`、`sendNotice`、`sendBadge`、
 * `sendToast`、`sendHr`、`sendDivider`、`sendProgress`、`send*Hint`、
 * `sendMentionCard`/`sendNudgeCard`、`sendMetric`/`sendCompare`、`sendKeyValue`、
 * `sendQuoteCard`、`sendBanner`、`sendJsonCard`、`sendMarkdown`、`sendQuote`、
 * `sendCode`、`setMessageReaction`、`starMessage`、`sendStatus` 之后第四十一块）。
 *
 * 本测试直接钉住纯函数 ([parseBotSendTableFields]) 与组装 ([buildBotTableContent])
 * 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 钉住 **chatId 无 `trim()`**：`" c1 "` 原样通过必填检查、原样进下游。
 * - 钉住 **headers**：非数组不是错误而是空表头（随即判缺）；单元格
 *   `(as? JsonPrimitive)` 让对象 / 数组单元格静默丢弃；trim + 取 40 → 剔空白 →
 *   取前 8（顺序逐字）；显式 JSON null 得字面 `"null"` 保留为表头。
 * - 钉住 **rows**：行元素非数组则整行静默丢弃；单元格 trim + 取 40 但**不剔空白**
 *   （纯空白单元格保留为空串 `""`）；每行取 8；先剔全空行、后取前 20（顺序逐字）。
 * - 钉住**合并必填**（`chatId` 或 `headers` 或 `rows` 缺 → `MissingRequired`）。
 * - 反证 `chatId` 坏类型大声失败：对象 / 数组型在 `?.jsonPrimitive` 处抛
 *   [IllegalArgumentException]（路由层 `StatusPages` 映射为 400「参数无效」，
 *   不是 500）；`headers`/`rows` 的坏类型是反例——`as?` 分支让它们变成"缺失"，
 *   不抛。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
class BotSendTableParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("chatId", "headers", "rows")
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotSendTableFieldsResult =
        parseBotSendTableFields(obj)

    private fun okOf(obj: JsonObject): BotSendTableFields =
        (parseOf(obj) as BotSendTableFieldsResult.Ok).fields

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

    private fun randomHeaderCell(random: Random): Pair<JsonElement, String?> {
        // 返回（注入的 JsonElement，解析后期望的单元格或 null 表示被丢弃）。
        return when (random.nextInt(5)) {
            0 -> {
                val s = randomString(random, random.nextInt(0, 50))
                JsonPrimitive(s) to s.trim().take(40).let { if (it.isNotBlank()) it else null }
            }
            1 -> {
                val n = random.nextInt(-1000, 1000)
                JsonPrimitive(n) to n.toString()
            }
            2 -> JsonNull to "null"
            3 -> JsonObject(mapOf("k" to JsonPrimitive(1))) to null
            else -> JsonArray(listOf(JsonPrimitive(1))) to null
        }
    }

    private fun basePayload(random: Random, i: Int): Pair<MutableMap<String, JsonElement>, BotSendTableFields> {
        val base = mutableMapOf<String, JsonElement>()
        // 合法表头：2~4 个单元格，混入怪语义用例。
        val headerCells = List(random.nextInt(2, 5)) { randomHeaderCell(random) }
        val headerValues = headerCells.map { it.second }.filterNotNull().take(8)
        base["headers"] = JsonArray(headerCells.map { it.first })
        // 合法行：1~3 行，每行 1~4 单元格（布尔/整数/null/字符串）。
        val rows = List(random.nextInt(1, 4)) {
            List(random.nextInt(1, 5)) { random.nextInt(0, 3) }.map { k ->
                when (k) {
                    0 -> JsonPrimitive(random.nextBoolean())
                    1 -> JsonNull
                    else -> JsonPrimitive("r" + i)
                }
            }
        }
        base["rows"] = JsonArray(rows.map { JsonArray(it) })
        base["chatId"] = JsonPrimitive("c" + i)
        // 期望值：与纯函数同语义重算（trim+截 40 → 剔空白 → 取 8；行内 trim+截 40 不剔空白 →
        // 行取 8 → 剔空行 → 取 20；chatId 无 trim）。
        val expectedHeaders = headerValues
        val expectedRows = rows.map { row ->
            row.mapNotNull { cell ->
                when (cell) {
                    is JsonPrimitive -> cell.content.trim().take(40)
                    else -> null
                }
            }.take(8)
        }.filter { it.isNotEmpty() }.take(20)
        // 程序化注入未知字段：永不破坏 JSON 语法。
        repeat(random.nextInt(0, 6)) {
            base[randomName(random)] = randomScalar(random)
        }
        return base to BotSendTableFields("c" + i, expectedHeaders, expectedRows)
    }

    @Test
    fun fuzzUnknownKeysIgnoredAndKnownSemanticsHold() {
        val random = Random(20261001)
        repeat(ITERATIONS) { i ->
            val (base, expected) = basePayload(random, i)
            val fields = okOf(JsonObject(base))
            assertEquals(expected.chatId, fields.chatId, "未知键不得污染 chatId，迭代 " + i)
            assertEquals(expected.headers, fields.headers, "未知键不得污染 headers，迭代 " + i)
            assertEquals(expected.rows, fields.rows, "未知键不得污染 rows，迭代 " + i)
        }
    }

    @Test
    fun missingRequiredSemantics() {
        val headers = mapOf("headers" to JsonArray(listOf(JsonPrimitive("h"))))
        val rows = mapOf("rows" to JsonArray(listOf(JsonArray(listOf(JsonPrimitive("x"))))))
        val base = headers + rows
        // chatId 缺 / 空 / 纯空白 → MissingRequired（其他合法时）。
        assertTrue(parseOf(JsonObject(base)) is BotSendTableFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(base + ("chatId" to JsonPrimitive("")))) is BotSendTableFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(base + ("chatId" to JsonPrimitive("   ")))) is BotSendTableFieldsResult.MissingRequired)
        // headers 缺 → MissingRequired（其他合法时）。
        val chatId = mapOf("chatId" to JsonPrimitive("c1"))
        assertTrue(parseOf(JsonObject(chatId + rows)) is BotSendTableFieldsResult.MissingRequired)
        // rows 缺 → MissingRequired（其他合法时）。
        assertTrue(parseOf(JsonObject(chatId + headers)) is BotSendTableFieldsResult.MissingRequired)
        // 三者合法 → Ok，原样。
        val ok = okOf(JsonObject(chatId + headers + rows))
        assertEquals("c1", ok.chatId)
        assertEquals(listOf("h"), ok.headers)
        assertEquals(listOf(listOf("x")), ok.rows)
    }

    @Test
    fun chatIdHasNoTrim() {
        val ok = okOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive(" c1 "),
                    "headers" to JsonArray(listOf(JsonPrimitive("h"))),
                    "rows" to JsonArray(listOf(JsonArray(listOf(JsonPrimitive("x"))))),
                )
            )
        )
        assertEquals(" c1 ", ok.chatId)
    }

    @Test
    fun headerCellSemantics() {
        val chatId = "chatId" to JsonPrimitive("c1")
        val rows = "rows" to JsonArray(listOf(JsonArray(listOf(JsonPrimitive("x")))))
        fun withHeaders(vararg cells: JsonElement) =
            okOf(JsonObject(mapOf(chatId, rows, "headers" to JsonArray(cells.toList()))))
        // trim + 取 40：两端空白被裁，前导空格计入 40 上限。
        val ok = withHeaders(JsonPrimitive("  abc  "))
        assertEquals(listOf("abc"), ok.headers)
        val long = " ".repeat(5) + "y".repeat(50)
        val okLong = withHeaders(JsonPrimitive(long))
        assertEquals(listOf(long.trim().take(40)), okLong.headers)
        assertEquals(40, okLong.headers.single().length)
        // 纯空白单元格被剔除；显式 null 保留为字面 "null"。
        val okMix = withHeaders(JsonPrimitive("   "), JsonPrimitive("h1"), JsonNull)
        assertEquals(listOf("h1", "null"), okMix.headers)
        // 对象/数组单元格静默丢弃，不抛。
        val okDrop = withHeaders(JsonObject(mapOf()), JsonArray(emptyList()), JsonPrimitive("h2"))
        assertEquals(listOf("h2"), okDrop.headers)
        // 数字/布尔经 content 取 toString，不抛。
        val okScalar = withHeaders(JsonPrimitive(42), JsonPrimitive(true))
        assertEquals(listOf("42", "true"), okScalar.headers)
        // 取前 8：空白被剔后不足 8 也照收，多余丢弃。
        val okTake8 = withHeaders(*Array(12) { JsonPrimitive("h" + it) })
        assertEquals((0 until 8).map { "h" + it }, okTake8.headers)
        // headers 非数组 → 不是错误，而是空表头 → MissingRequired。
        assertTrue(
            parseOf(
                JsonObject(mapOf(chatId, rows, "headers" to JsonPrimitive("nope")))
            ) is BotSendTableFieldsResult.MissingRequired
        )
        assertTrue(
            parseOf(
                JsonObject(mapOf(chatId, rows, "headers" to JsonNull))
            ) is BotSendTableFieldsResult.MissingRequired
        )
    }

    @Test
    fun rowSemantics() {
        val chatId = "chatId" to JsonPrimitive("c1")
        val headers = "headers" to JsonArray(listOf(JsonPrimitive("h")))
        fun withRows(vararg rowsEl: JsonElement) =
            okOf(JsonObject(mapOf(chatId, headers, "rows" to JsonArray(rowsEl.toList()))))
        // 非数组行元素整行静默丢弃。
        val ok = withRows(JsonPrimitive("nope"), JsonArray(listOf(JsonPrimitive("x"))))
        assertEquals(listOf(listOf("x")), ok.rows)
        // 单元格 trim+截 40，但纯空白单元格保留为空串 ""（不剔空白）。
        val okBlank = withRows(JsonArray(listOf(JsonPrimitive("  "), JsonPrimitive("y"))))
        assertEquals(listOf(listOf("", "y")), okBlank.rows)
        // 全丢光的行被剔掉（filter isNotEmpty）；对象/数组单元格静默丢弃。
        val okEmpty = withRows(
            JsonArray(listOf(JsonObject(mapOf()), JsonArray(emptyList()))),
            JsonArray(listOf(JsonPrimitive("z"))),
        )
        assertEquals(listOf(listOf("z")), okEmpty.rows)
        // 每行取 8。
        val okTake8 = withRows(JsonArray((0 until 12).map { JsonPrimitive("c" + it) }))
        assertEquals(listOf((0 until 8).map { "c" + it }), okTake8.rows)
        // 整体取 20。
        val okTake20 = withRows(*Array(25) { JsonArray(listOf(JsonPrimitive("r" + it))) })
        assertEquals(20, okTake20.rows.size)
        assertEquals(listOf("r0"), okTake20.rows.first())
        assertEquals(listOf("r19"), okTake20.rows.last())
        // rows 非数组 → 不是错误，而是空行集 → MissingRequired。
        assertTrue(
            parseOf(
                JsonObject(mapOf(chatId, headers, "rows" to JsonPrimitive("nope")))
            ) is BotSendTableFieldsResult.MissingRequired
        )
    }

    @Test
    fun tableContentAssembly() {
        val headers = listOf("a", "b")
        val rows = listOf(listOf("1", "2"), listOf("3"))
        val content = buildBotTableContent(headers, rows)
        // 列数以 headers.size 为准：不足补空串。
        assertEquals(
            "| a | b |\n| --- | --- |\n| 1 | 2 |\n| 3 |  |",
            content
        )
    }

    @Test
    fun nullAndLoudFailures() {
        val headers = mapOf("headers" to JsonArray(listOf(JsonPrimitive("h"))))
        val rows = mapOf("rows" to JsonArray(listOf(JsonArray(listOf(JsonPrimitive("x"))))))
        // chatId 显式 null → 字面 "null"（不抛、非空）→ Ok。
        val ok = okOf(JsonObject(mapOf("chatId" to JsonNull) + headers + rows))
        assertEquals("null", ok.chatId)
        // 对象 / 数组型 chatId 在 ?.jsonPrimitive 处抛 IllegalArgumentException。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonObject(mapOf())) + headers + rows))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonArray(emptyList())) + headers + rows))
        }
        // 反证：headers/rows 坏类型不抛（as? 分支变成"缺失"）。
        assertTrue(
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "headers" to JsonObject(mapOf()),
                        "rows" to JsonPrimitive(42),
                    )
                )
            ) is BotSendTableFieldsResult.MissingRequired
        )
    }
}
