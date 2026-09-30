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
 * Bot `sendMetric` / `sendCompare` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，G355
 * `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、`sendPhoto`、
 * `sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、`sendVenue`、
 * `sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、
 * `sendChecklist`、`sendAlert`、`sendCountdown`、`sendNotice`、`sendBadge`、
 * `sendToast`、`sendHr`、`sendDivider`、`sendProgress`、`send*Hint`、
 * `sendMentionCard`/`sendNudgeCard` 之后第三十块）。
 *
 * 两个端点（见 [METRIC_COMPARE_SPECS]）的解析逐字同构，本测试直接钉住共用纯函数
 * ([parseBotMetricCompareFields] / [buildBotMetricContent] / [buildBotCompareContent])
 * 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，每个端点 150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 钉住各字段 `(obj[key]?.jsonPrimitive?.content ?: default).take(cap)` 逐字顺序：
 *   回退链接存在性——键缺席时才回默认，显式 JSON null 得字面 `"null"`（不回退）；
 *   键在但空白**原样保留空白、不回默认**（原处理器逐字语义，特意钉住）；
 *   **不 trim**（前导空格计入上限），截断 cap。
 * - 钉住单必填（`chatId` 缺或空白 → 400），其余字段非必填（缺省回默认，空白原样保留）。
 * - 钉住两个端点的内容模板逐字一致（[METRIC_COMPARE_SPECS] 表，与处理器源码逐字对过；
 *   `sendMetric` 的 markdown 硬换行 `**  \n` 与 unit 后缀条件逐字钉住）。
 * - 钉住 `sendCompare` 的第三字段槽恒为 `""`（`keyC = null` 时不读键）。
 * - 反证 `wrong-typed known fields still fail loudly`：手写解析里 `?.jsonPrimitive`
 *   在类型错时抛 [IllegalArgumentException]，路由层 `StatusPages` 把它映射为 400
 *   「参数无效」（不是 500）——坏数据必须大声失败，不能悄悄吞掉。
 * - 反证抽取顺序：`chatId` 空白 + 后续字段坏类型 → 仍抛错而非回 `MissingRequired`
 *   （抽取在必填校验之前，原处理器逐字如此）。
 */
class BotSendMetricCompareParseFuzzTest {

    private data class MetricCompareSpec(
        val endpoint: String,
        val keys: List<String>,
        val defaults: List<String>,
        val caps: List<Int>,
        val thirdSlotActive: Boolean,
    )

    private companion object {
        /** 两个 metric/compare 卡片端点的逐字规格（字段三元组与处理器源码逐字一致）。 */
        private val METRIC_COMPARE_SPECS = listOf(
            MetricCompareSpec(
                endpoint = "sendMetric",
                keys = listOf("label", "value", "unit"),
                defaults = listOf("metric", "0", ""),
                caps = listOf(40, 40, 20),
                thirdSlotActive = true,
            ),
            MetricCompareSpec(
                endpoint = "sendCompare",
                keys = listOf("left", "right"),
                defaults = listOf("A", "B"),
                caps = listOf(80, 80),
                thirdSlotActive = false,
            ),
        )

        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId", "label", "value", "unit", "left", "right",
        )
        private const val ITERATIONS = 150
    }

    private fun randomFieldName(random: Random): String {
        val stems = listOf(
            "future", "x", "v9", "extra", "unknown", "meta", "debug", "tmp",
            "clientExt", "appExt", "exp", "flag",
        )
        var name = stems.random(random) + "_" + random.nextInt(10000)
        while (name in KNOWN_FIELD_NAMES) name = "z$name"
        return name
    }

    private fun randomJsonValue(random: Random, depth: Int): JsonElement {
        val leafKinds = 5 // bool / long / double / string / null
        return when (random.nextInt(if (depth <= 0) leafKinds else leafKinds + 2)) {
            0 -> JsonPrimitive(random.nextBoolean())
            1 -> JsonPrimitive(random.nextLong())
            2 -> JsonPrimitive(random.nextDouble())
            3 -> JsonPrimitive("str_" + random.nextInt(100000) + "_" + random.nextLong())
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

    private fun parseOf(obj: JsonObject, spec: MetricCompareSpec): BotMetricCompareFieldsResult =
        parseBotMetricCompareFields(
            obj,
            spec.keys[0], spec.defaults[0], spec.caps[0],
            spec.keys[1], spec.defaults[1], spec.caps[1],
            if (spec.thirdSlotActive) spec.keys[2] else null,
            if (spec.thirdSlotActive) spec.defaults[2] else "",
            if (spec.thirdSlotActive) spec.caps[2] else 0,
        )

    private fun fieldsOf(obj: JsonObject, spec: MetricCompareSpec): BotMetricCompareFields {
        val result = parseOf(obj, spec)
        assertTrue(result is BotMetricCompareFieldsResult.Ok, spec.endpoint + " 合法请求必须解析成功，实际 $result")
        return result.fields
    }

    @Test
    fun `metricCompare family survives seeded unknown-field fuzz`() {
        val random = Random(0x5EED_2031)
        for (spec in METRIC_COMPARE_SPECS) {
            repeat(ITERATIONS) { i ->
                val chatId = "c-$i"
                val provided = mutableMapOf<String, String>()
                val entries = mutableMapOf<String, JsonElement>("chatId" to JsonPrimitive(chatId))
                if (i % 3 != 0) {
                    // 三分之二带上第一个字段：钉住其余字段回默认
                    val v0 = "v0-$i"
                    provided[spec.keys[0]] = v0
                    entries[spec.keys[0]] = JsonPrimitive(v0)
                }
                if (i % 3 == 2) {
                    // 另三分之一带全字段：钉住全量抽取 + 截断上限
                    val v1 = "v1-$i"
                    provided[spec.keys[1]] = v1
                    entries[spec.keys[1]] = JsonPrimitive(v1)
                    if (spec.thirdSlotActive) {
                        val v2 = "v2-$i"
                        provided[spec.keys[2]] = v2
                        entries[spec.keys[2]] = JsonPrimitive(v2)
                    }
                }
                val payload = injectUnknownFields(JsonObject(entries), random)
                val fields = fieldsOf(payload, spec)
                assertEquals(chatId, fields.chatId, spec.endpoint + " chatId 必须原样保留")
                val expected = spec.keys.mapIndexed { j, key ->
                    (provided[key] ?: spec.defaults[j]).take(spec.caps[j])
                }
                assertEquals(expected[0], fields.first, spec.endpoint + " first 字段逐字一致")
                assertEquals(expected[1], fields.second, spec.endpoint + " second 字段逐字一致")
                if (spec.thirdSlotActive) {
                    assertEquals(expected[2], fields.third, spec.endpoint + " third 字段逐字一致")
                } else {
                    assertEquals("", fields.third, spec.endpoint + " 无第三字段槽时恒为空字符串")
                }
            }
        }
    }

    @Test
    fun `single required chatId semantics`() {
        for (spec in METRIC_COMPARE_SPECS) {
            // chatId 缺 / 空 / 纯空白 → MissingRequired
            assertTrue(
                parseOf(JsonObject(emptyMap()), spec) is BotMetricCompareFieldsResult.MissingRequired,
                spec.endpoint + " chatId 缺席必须判缺",
            )
            assertTrue(
                parseOf(JsonObject(mapOf("chatId" to JsonPrimitive(""))), spec)
                    is BotMetricCompareFieldsResult.MissingRequired,
                spec.endpoint + " chatId 空字符串必须判缺",
            )
            assertTrue(
                parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("   "))), spec)
                    is BotMetricCompareFieldsResult.MissingRequired,
                spec.endpoint + " chatId 纯空白必须判缺",
            )
            // 其余字段缺省 / 空白都不判缺
            val blankOthers = mutableMapOf<String, JsonElement>("chatId" to JsonPrimitive("c"))
            for (key in spec.keys) blankOthers[key] = JsonPrimitive("")
            val fields = fieldsOf(JsonObject(blankOthers), spec)
            assertEquals("c", fields.chatId)
            assertEquals("", fields.first, spec.endpoint + " 首字段空白原样保留不判缺")
            assertEquals("", fields.second, spec.endpoint + " 次字段空白原样保留不判缺")
        }
    }

    @Test
    fun `existence-fallback defaults are pinned per endpoint`() {
        val metric = METRIC_COMPARE_SPECS[0]
        val compare = METRIC_COMPARE_SPECS[1]
        // 键缺席 → 默认文案
        assertEquals("metric", fieldsOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"))), metric).first)
        assertEquals("0", fieldsOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"))), metric).second)
        assertEquals("", fieldsOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"))), metric).third)
        assertEquals("A", fieldsOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"))), compare).first)
        assertEquals("B", fieldsOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"))), compare).second)
        // 显式 JSON null → 字面 "null"，不继续回退
        val nullLabel = JsonObject(mapOf("chatId" to JsonPrimitive("c"), "label" to JsonNull))
        assertEquals("null", fieldsOf(nullLabel, metric).first, "显式 null 得字面 null 不回默认")
        val nullLeft = JsonObject(mapOf("chatId" to JsonPrimitive("c"), "left" to JsonNull))
        assertEquals("null", fieldsOf(nullLeft, compare).first, "显式 null 得字面 null 不回默认")
        // 键在但空白 → 原样保留空白，不回默认（原处理器逐字语义，特意钉住）
        val blankLabel = JsonObject(mapOf("chatId" to JsonPrimitive("c"), "label" to JsonPrimitive("  ")))
        assertEquals("  ", fieldsOf(blankLabel, metric).first, "label 空白不回默认 metric")
        val blankLeft = JsonObject(mapOf("chatId" to JsonPrimitive("c"), "left" to JsonPrimitive("")))
        assertEquals("", fieldsOf(blankLeft, compare).first, "left 空白不回默认 A")
        // 截断上限：不 trim，前导空格计入上限
        val longLabel = "y".repeat(50)
        val overLabel = JsonObject(mapOf("chatId" to JsonPrimitive("c"), "label" to JsonPrimitive(longLabel)))
        assertEquals(longLabel.take(40), fieldsOf(overLabel, metric).first, "label 超长截 40")
        val padded = "  " + "z".repeat(50)
        val paddedLabel = JsonObject(mapOf("chatId" to JsonPrimitive("c"), "label" to JsonPrimitive(padded)))
        assertEquals(padded.take(40), fieldsOf(paddedLabel, metric).first, "前导空格计入 40 上限")
        val longLeft = "w".repeat(100)
        val overLeft = JsonObject(mapOf("chatId" to JsonPrimitive("c"), "left" to JsonPrimitive(longLeft)))
        assertEquals(longLeft.take(80), fieldsOf(overLeft, compare).first, "left 超长截 80")
        // JSON 数字经 .content 照样解析
        val numValue = JsonObject(mapOf("chatId" to JsonPrimitive("c"), "value" to JsonPrimitive(42)))
        assertEquals("42", fieldsOf(numValue, metric).second, "数字 value 经 content 解析")
    }

    @Test
    fun `content templates are pinned verbatim`() {
        // sendMetric：unit 非空白 → " value unit" 后缀；markdown 硬换行两个空格逐字保留
        assertEquals("**cpu**  \n`42 ms`", buildBotMetricContent("cpu", "42", "ms"))
        // sendMetric：unit 缺省/空白 → 无后缀
        assertEquals("**cpu**  \n`42`", buildBotMetricContent("cpu", "42", ""))
        assertEquals("**cpu**  \n`42`", buildBotMetricContent("cpu", "42", "   "))
        // sendCompare：单行对比表格逐字
        assertEquals(
            "| Left | Right |\n| --- | --- |\n| a | b |",
            buildBotCompareContent("a", "b"),
        )
        // 与纯函数解析链路打通：label 含 markdown 特殊字符原样进模板
        val parsed = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "label" to JsonPrimitive("a*b"),
                    "value" to JsonPrimitive("1"),
                ),
            ),
            METRIC_COMPARE_SPECS[0],
        )
        assertEquals("**a*b**  \n`1`", buildBotMetricContent(parsed.first, parsed.second, parsed.third))
    }

    @Test
    fun `wrong-typed known fields still fail loudly`() {
        for (spec in METRIC_COMPARE_SPECS) {
            // 对象型 chatId
            assertFailsWith<IllegalArgumentException>(spec.endpoint + " 对象型 chatId 必须大声失败") {
                parseOf(JsonObject(mapOf("chatId" to JsonObject(emptyMap()))), spec)
            }
            // 数组型 chatId
            assertFailsWith<IllegalArgumentException>(spec.endpoint + " 数组型 chatId 必须大声失败") {
                parseOf(JsonObject(mapOf("chatId" to JsonArray(emptyList()))), spec)
            }
            // 各已知字段对象/数组型
            for (key in spec.keys) {
                assertFailsWith<IllegalArgumentException>(spec.endpoint + " 对象型 " + key + " 必须大声失败") {
                    parseOf(
                        JsonObject(
                            mapOf(
                                "chatId" to JsonPrimitive("c"),
                                key to JsonObject(emptyMap()),
                            ),
                        ),
                        spec,
                    )
                }
                assertFailsWith<IllegalArgumentException>(spec.endpoint + " 数组型 " + key + " 必须大声失败") {
                    parseOf(
                        JsonObject(
                            mapOf(
                                "chatId" to JsonPrimitive("c"),
                                key to JsonArray(emptyList()),
                            ),
                        ),
                        spec,
                    )
                }
            }
        }
    }

    @Test
    fun `extraction order is pinned`() {
        // chatId 空白 + 后续字段坏类型 → 仍抛错而非回 MissingRequired（抽取在必填校验之前）
        assertFailsWith<IllegalArgumentException>("chatId 空白时 label 坏类型仍须大声失败") {
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive(""),
                        "label" to JsonArray(emptyList()),
                    ),
                ),
                METRIC_COMPARE_SPECS[0],
            )
        }
        assertFailsWith<IllegalArgumentException>("chatId 空白时 left 坏类型仍须大声失败") {
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("  "),
                        "left" to JsonObject(emptyMap()),
                    ),
                ),
                METRIC_COMPARE_SPECS[1],
            )
        }
        // chatId 与首字段同时坏类型 → 抛错（chatId 先抽取）
        assertFailsWith<IllegalArgumentException>("chatId 先于 label 抽取") {
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonObject(emptyMap()),
                        "label" to JsonObject(emptyMap()),
                    ),
                ),
                METRIC_COMPARE_SPECS[0],
            )
        }
    }
}
