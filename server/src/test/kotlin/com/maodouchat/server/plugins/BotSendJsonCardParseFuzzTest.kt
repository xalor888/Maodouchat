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
 * Bot `sendJsonCard` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，G355
 * `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、`sendPhoto`、
 * `sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、`sendVenue`、
 * `sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、
 * `sendChecklist`、`sendAlert`、`sendCountdown`、`sendNotice`、`sendBadge`、
 * `sendToast`、`sendHr`、`sendDivider`、`sendProgress`、`send*Hint`、
 * `sendMentionCard`/`sendNudgeCard`、`sendMetric`/`sendCompare`、`sendKeyValue`、
 * `sendQuoteCard`、`sendBanner` 之后第三十四块）。
 *
 * 本测试直接钉住纯函数 ([parseBotJsonCardFields] / [buildBotJsonCardContent])
 * 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]，含 `data`——它是
 *   `json` 的存在性回退键，同样视为已知）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 钉住 `payload` 的存在性回退：只有 `json` 键完全缺席才看 `data`；`json` 在但为
 *   显式 null 时仍走 `json` 分支得字面 `"null"`。
 * - 钉住 `payload` 取 `?.toString()` **不是** `?.jsonPrimitive`：对象 / 数组型
 *   `json`/`data` 不抛错，原样序列化（字符串带引号）；`take(500)` 作用于
 *   `toString()` 之后。
 * - 钉住**双必填**（`chatId` 或 `payload` 缺/空白 → 400）。
 * - 钉住内容模板逐字一致（`"```json\n" + payload + "\n```"`，与处理器源码逐字对过）。
 * - 反证 `wrong-typed known fields still fail loudly`：手写解析里 `chatId` 的
 *   `?.jsonPrimitive` 在类型错时抛 [IllegalArgumentException]，路由层 `StatusPages`
 *   把它映射为 400「参数无效」（不是 500）——坏数据必须大声失败，不能悄悄吞掉；
 *   而 `json`/`data` 的 `?.toString()` **不抛**（原处理器逐字如此，特意钉住）。
 * - 反证抽取顺序：`chatId` 空白 + `json` 乱值 → 仍回 `MissingRequired` 而非抛错
 *   （只有 `chatId` 会抛；`chatId` 先于 `payload` 抽取，原处理器逐字如此）。
 */
class BotSendJsonCardParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("chatId", "json", "data")
        private const val ITERATIONS = 150
        private const val PAYLOAD_CAP = 500
    }

    private fun parseOf(obj: JsonObject): BotJsonCardFieldsResult =
        parseBotJsonCardFields(obj)

    private fun fieldsOf(obj: JsonObject): BotJsonCardFields {
        val result = parseOf(obj)
        assertTrue(result is BotJsonCardFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
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

    @Test
    fun `jsonCard survives seeded unknown-field fuzz`() {
        val random = Random(0x9E0A_2034)
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val entries = mutableMapOf<String, JsonElement>(
                "chatId" to JsonPrimitive(chatId),
            )
            val expectedPayload: String
            val text = if (i % 5 == 0) {
                // 五分之一超长载荷：钉住 take(500) 作用于 toString() 之后
                "pl-" + i + "-" + "x".repeat(600)
            } else {
                "pl-$i some json card payload"
            }
            when {
                i % 3 == 0 -> {
                    // 三分之一 json 缺省：钉住存在性回退，data 生效
                    entries["data"] = JsonPrimitive(text)
                    expectedPayload = JsonPrimitive(text).toString().take(PAYLOAD_CAP)
                }
                i % 3 == 1 -> {
                    // 另三分之一 json 与 data 同时在场：钉住 json 分支，data 被忽略
                    entries["json"] = JsonPrimitive(text)
                    entries["data"] = JsonPrimitive("shadow-$i must be ignored")
                    expectedPayload = JsonPrimitive(text).toString().take(PAYLOAD_CAP)
                }
                else -> {
                    // 其余全字段抽取：json 生效
                    entries["json"] = JsonPrimitive(text)
                    expectedPayload = JsonPrimitive(text).toString().take(PAYLOAD_CAP)
                }
            }
            val payload = injectUnknownFields(JsonObject(entries), random)
            val fields = fieldsOf(payload)
            assertEquals(chatId, fields.chatId, "chatId 必须原样保留")
            assertEquals(expectedPayload, fields.payload, "payload 逐字一致")
        }
    }

    @Test
    fun `dual required chatId and payload semantics`() {
        // chatId 缺 / 空 / 纯空白 → MissingRequired
        assertTrue(
            parseOf(JsonObject(emptyMap())) is BotJsonCardFieldsResult.MissingRequired,
            "chatId 缺席必须判缺",
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive(""))))
                is BotJsonCardFieldsResult.MissingRequired,
            "chatId 空字符串必须判缺",
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("   "))))
                is BotJsonCardFieldsResult.MissingRequired,
            "chatId 纯空白必须判缺",
        )
        // payload 缺 / 空 / 纯空白 → MissingRequired（chatId 合法）
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"))))
                is BotJsonCardFieldsResult.MissingRequired,
            "json 与 data 均缺席必须判缺",
        )
        assertTrue(
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "json" to JsonPrimitive(""),
                    ),
                ),
            ) is BotJsonCardFieldsResult.MissingRequired,
            "json 空字符串必须判缺",
        )
        assertTrue(
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "data" to JsonPrimitive("   "),
                    ),
                ),
            ) is BotJsonCardFieldsResult.MissingRequired,
            "data 纯空白必须判缺",
        )
        // 双字段都合法 → Ok
        val okFields = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "json" to JsonPrimitive("payload"),
                ),
            ),
        )
        assertEquals("c", okFields.chatId)
        assertEquals("\"payload\"", okFields.payload, "字符串 payload 经 toString 带引号")
    }

    @Test
    fun `caps and special payload semantics are pinned`() {
        // payload 超长截 500，且 take 作用于 toString() 之后（含字符串引号）
        val longText = "y".repeat(600)
        val overPayload = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "json" to JsonPrimitive(longText),
            ),
        )
        assertEquals(
            JsonPrimitive(longText).toString().take(PAYLOAD_CAP),
            fieldsOf(overPayload).payload,
            "payload 超长截 500（take 作用于 toString 之后）",
        )
        // json 显式 JSON null → 字面 "null"，不被 orEmpty 吞掉（非空故仍判合法）
        val nullJson = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "json" to JsonNull,
            ),
        )
        assertEquals("null", fieldsOf(nullJson).payload, "json 显式 null 得字面 null")
        // json 键在但为 null 时不触发回退（data 被忽略）
        val nullJsonWithData = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "json" to JsonNull,
                "data" to JsonPrimitive("fallback"),
            ),
        )
        assertEquals(
            "null",
            fieldsOf(nullJsonWithData).payload,
            "json 键在时 data 回退键不生效",
        )
        // json 缺席时回退到 data
        val fallbackData = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "data" to JsonPrimitive("via-data"),
            ),
        )
        assertEquals(
            "\"via-data\"",
            fieldsOf(fallbackData).payload,
            "json 缺席回退到 data（字符串带引号）",
        )
        // JSON 数字 / 布尔经 toString 原样（不抛错）
        val numJson = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "json" to JsonPrimitive(42),
            ),
        )
        assertEquals("42", fieldsOf(numJson).payload, "数字 json 经 toString 解析")
        val boolJson = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "json" to JsonPrimitive(true),
            ),
        )
        assertEquals("true", fieldsOf(boolJson).payload, "布尔 json 经 toString 解析")
        // 对象 / 数组型 json、data：不抛错，原样序列化
        val objectJson = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "json" to JsonObject(mapOf("a" to JsonPrimitive(1))),
            ),
        )
        val objectPayload = fieldsOf(objectJson).payload
        assertEquals(
            JsonObject(mapOf("a" to JsonPrimitive(1))).toString(),
            objectPayload,
            "对象型 json 不抛错且原样序列化",
        )
        val arrayData = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "data" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))),
            ),
        )
        assertEquals(
            JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))).toString(),
            fieldsOf(arrayData).payload,
            "数组型 data 不抛错且原样序列化",
        )
        // 字符串带引号进模板：payload 即带引号的序列化串
        val quotedJson = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "json" to JsonPrimitive("abc"),
            ),
        )
        assertEquals("\"abc\"", fieldsOf(quotedJson).payload, "字符串 payload 带引号")
        // 前导空格计入上限（take 作用于 toString 之后）
        val padded = "  " + "w".repeat(600)
        val paddedJson = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "json" to JsonPrimitive(padded),
            ),
        )
        assertEquals(
            JsonPrimitive(padded).toString().take(PAYLOAD_CAP),
            fieldsOf(paddedJson).payload,
            "payload 前导空格计入 500 上限",
        )
    }

    @Test
    fun `content template is pinned verbatim`() {
        assertEquals("```json\nbody\n```", buildBotJsonCardContent("body"))
        // 字符串 payload（带引号）原样进模板
        assertEquals("```json\n\"abc\"\n```", buildBotJsonCardContent("\"abc\""))
        // 与纯函数解析链路打通：markdown 特殊字符原样进模板
        val parsed = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "json" to JsonPrimitive("a*b_c"),
                ),
            ),
        )
        assertEquals(
            "```json\n\"a*b_c\"\n```",
            buildBotJsonCardContent(parsed.payload),
        )
        // 与处理器源码逐字对过：`"```json\n" + payload + "\n```"`
        val content = buildBotJsonCardContent("T")
        assertTrue(content.startsWith("```json\nT"), "内容以 json 围栏行开头")
        assertTrue(content.endsWith("\n```"), "内容以围栏闭合行结尾")
    }

    @Test
    fun `wrong-typed chatId fails loudly but json data do not throw`() {
        // 对象型 chatId
        assertFailsWith<IllegalArgumentException>("对象型 chatId 必须大声失败") {
            parseOf(JsonObject(mapOf("chatId" to JsonObject(emptyMap()))))
        }
        // 数组型 chatId
        assertFailsWith<IllegalArgumentException>("数组型 chatId 必须大声失败") {
            parseOf(JsonObject(mapOf("chatId" to JsonArray(emptyList()))))
        }
        // 对象 / 数组型 json、data：必须不抛（?.toString() 而非 ?.jsonPrimitive）
        val objectJson = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "json" to JsonObject(mapOf("k" to JsonPrimitive("v"))),
            ),
        )
        assertTrue(
            parseOf(objectJson) is BotJsonCardFieldsResult.Ok,
            "对象型 json 必须不抛错",
        )
        val arrayJson = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "json" to JsonArray(listOf(JsonPrimitive(1))),
            ),
        )
        assertTrue(
            parseOf(arrayJson) is BotJsonCardFieldsResult.Ok,
            "数组型 json 必须不抛错",
        )
        val objectData = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "data" to JsonObject(mapOf("k" to JsonPrimitive("v"))),
            ),
        )
        assertTrue(
            parseOf(objectData) is BotJsonCardFieldsResult.Ok,
            "对象型 data 必须不抛错",
        )
        val arrayData = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "data" to JsonArray(listOf(JsonPrimitive(1))),
            ),
        )
        assertTrue(
            parseOf(arrayData) is BotJsonCardFieldsResult.Ok,
            "数组型 data 必须不抛错",
        )
    }

    @Test
    fun `extraction order is pinned`() {
        // chatId 空白 + json 乱值 → 仍回 MissingRequired 而非抛错
        // （只有 chatId 会抛；chatId 先于 payload 抽取，原处理器逐字如此）
        assertTrue(
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive(""),
                        "json" to JsonObject(mapOf("k" to JsonPrimitive("v"))),
                    ),
                ),
            ) is BotJsonCardFieldsResult.MissingRequired,
            "chatId 空白时 json 乱值仍不抛错，直接判缺",
        )
        // chatId 坏类型 + json 乱值 → 抛错（chatId 先于 payload 抽取）
        assertFailsWith<IllegalArgumentException>("chatId 先于 payload 抽取") {
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonObject(emptyMap()),
                        "json" to JsonObject(mapOf("k" to JsonPrimitive("v"))),
                    ),
                ),
            )
        }
        // chatId 缺席 + json 显式 null → payload 为字面 "null"（非空），仍因 chatId 判缺
        assertTrue(
            parseOf(
                JsonObject(
                    mapOf(
                        "json" to JsonNull,
                    ),
                ),
            ) is BotJsonCardFieldsResult.MissingRequired,
            "chatId 缺席时 json null 不救场，仍判缺",
        )
    }
}
