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
 * Bot `sendKeyValue` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，G355
 * `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、`sendPhoto`、
 * `sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、`sendVenue`、
 * `sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、
 * `sendChecklist`、`sendAlert`、`sendCountdown`、`sendNotice`、`sendBadge`、
 * `sendToast`、`sendHr`、`sendDivider`、`sendProgress`、`send*Hint`、
 * `sendMentionCard`/`sendNudgeCard`、`sendMetric`/`sendCompare` 之后第三十一块）。
 *
 * 本测试直接钉住纯函数 ([parseBotKeyValueFields] / [buildBotKeyValueContent])
 * 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 钉住 `key` / `value` 的逐字抽取顺序：`key` 接**空白性**默认值（缺席与空白
 *   都得 `"key"`，`"null"` 字面与其它非空白串不触发），`.take(40)`；
 *   `value` **无默认值**（缺席得 `""`），空白原样保留，显式 JSON null 得字面
 *   `"null"`（不被 `orEmpty` 吞掉），`.take(120)`。
 * - 钉住单必填（`chatId` 缺或空白 → 400），其余字段非必填。
 * - 钉住内容模板逐字一致（`` `key` = **value** ``，与处理器源码逐字对过）。
 * - 反证 `wrong-typed known fields still fail loudly`：手写解析里 `?.jsonPrimitive`
 *   在类型错时抛 [IllegalArgumentException]，路由层 `StatusPages` 把它映射为 400
 *   「参数无效」（不是 500）——坏数据必须大声失败，不能悄悄吞掉。
 * - 反证抽取顺序：`chatId` 空白 + 后续字段坏类型 → 仍抛错而非回 `MissingRequired`
 *   （抽取在必填校验之前，原处理器逐字如此）。
 */
class BotSendKeyValueParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("chatId", "key", "value")
        private const val ITERATIONS = 150
        private const val KEY_CAP = 40
        private const val VALUE_CAP = 120
    }

    private fun parseOf(obj: JsonObject): BotKeyValueFieldsResult =
        parseBotKeyValueFields(obj)

    private fun fieldsOf(obj: JsonObject): BotKeyValueFields {
        val result = parseOf(obj)
        assertTrue(result is BotKeyValueFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
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
    fun `keyValue survives seeded unknown-field fuzz`() {
        val random = Random(0x6EE0_2031)
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val entries = mutableMapOf<String, JsonElement>("chatId" to JsonPrimitive(chatId))
            val expectedKey: String
            val expectedValue: String
            if (i % 3 == 0) {
                // 三分之一全缺省：钉住 key 回 "key"、value 回 ""
                expectedKey = "key"
                expectedValue = ""
            } else {
                val k = "k-$i"
                entries["key"] = JsonPrimitive(k)
                expectedKey = k.take(KEY_CAP)
                if (i % 3 == 1) {
                    // 另三分之一只有 key：钉住 value 缺省为 ""
                    expectedValue = ""
                } else {
                    val v = "v-$i"
                    entries["value"] = JsonPrimitive(v)
                    expectedValue = v.take(VALUE_CAP)
                }
            }
            val payload = injectUnknownFields(JsonObject(entries), random)
            val fields = fieldsOf(payload)
            assertEquals(chatId, fields.chatId, "chatId 必须原样保留")
            assertEquals(expectedKey, fields.key, "key 逐字一致")
            assertEquals(expectedValue, fields.value, "value 逐字一致")
        }
    }

    @Test
    fun `single required chatId semantics`() {
        // chatId 缺 / 空 / 纯空白 → MissingRequired
        assertTrue(
            parseOf(JsonObject(emptyMap())) is BotKeyValueFieldsResult.MissingRequired,
            "chatId 缺席必须判缺",
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive(""))))
                is BotKeyValueFieldsResult.MissingRequired,
            "chatId 空字符串必须判缺",
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("   "))))
                is BotKeyValueFieldsResult.MissingRequired,
            "chatId 纯空白必须判缺",
        )
        // key / value 缺省 / 空白都不判缺
        val blankOthers = mapOf(
            "chatId" to JsonPrimitive("c"),
            "key" to JsonPrimitive(""),
            "value" to JsonPrimitive(""),
        )
        val fields = fieldsOf(JsonObject(blankOthers))
        assertEquals("c", fields.chatId)
        assertEquals("key", fields.key, "key 空白回默认 key，不判缺")
        assertEquals("", fields.value, "value 空白原样保留，不判缺")
    }

    @Test
    fun `blank-triggered key default and valueless value are pinned`() {
        // key 缺席 → "key"
        assertEquals("key", fieldsOf(JsonObject(mapOf("chatId" to JsonPrimitive("c")))).key)
        // key 空字符串 / 纯空白 → "key"（接的是空白性而非存在性）
        val emptyKey = JsonObject(mapOf("chatId" to JsonPrimitive("c"), "key" to JsonPrimitive("")))
        assertEquals("key", fieldsOf(emptyKey).key, "key 空字符串得 key")
        val blankKey = JsonObject(mapOf("chatId" to JsonPrimitive("c"), "key" to JsonPrimitive("   ")))
        assertEquals("key", fieldsOf(blankKey).key, "key 纯空白得 key")
        // key 显式 JSON null → 字面 "null"，不触发 ifBlank
        val nullKey = JsonObject(mapOf("chatId" to JsonPrimitive("c"), "key" to JsonNull))
        assertEquals("null", fieldsOf(nullKey).key, "key 显式 null 得字面 null")
        // value 无默认值：缺席 → ""
        assertEquals("", fieldsOf(JsonObject(mapOf("chatId" to JsonPrimitive("c")))).value)
        // value 显式 JSON null → 字面 "null"，不被 orEmpty 吞掉
        val nullValue = JsonObject(mapOf("chatId" to JsonPrimitive("c"), "value" to JsonNull))
        assertEquals("null", fieldsOf(nullValue).value, "value 显式 null 得字面 null")
        // 截断上限：key 40、value 120；前导空格计入上限（key 侧前导空格会先触发 ifBlank）
        val longKey = "y".repeat(50)
        val overKey = JsonObject(mapOf("chatId" to JsonPrimitive("c"), "key" to JsonPrimitive(longKey)))
        assertEquals(longKey.take(KEY_CAP), fieldsOf(overKey).key, "key 超长截 40")
        val longValue = "w".repeat(150)
        val overValue = JsonObject(mapOf("chatId" to JsonPrimitive("c"), "value" to JsonPrimitive(longValue)))
        assertEquals(longValue.take(VALUE_CAP), fieldsOf(overValue).value, "value 超长截 120")
        val padded = "  " + "z".repeat(150)
        val paddedValue = JsonObject(mapOf("chatId" to JsonPrimitive("c"), "value" to JsonPrimitive(padded)))
        assertEquals(padded.take(VALUE_CAP), fieldsOf(paddedValue).value, "value 前导空格计入 120 上限")
        // JSON 数字经 .content 照样解析
        val numValue = JsonObject(mapOf("chatId" to JsonPrimitive("c"), "value" to JsonPrimitive(42)))
        assertEquals("42", fieldsOf(numValue).value, "数字 value 经 content 解析")
    }

    @Test
    fun `content template is pinned verbatim`() {
        assertEquals("`cpu` = **42**", buildBotKeyValueContent("cpu", "42"))
        assertEquals("`key` = ****", buildBotKeyValueContent("key", ""))
        // 与纯函数解析链路打通：markdown 特殊字符原样进模板
        val parsed = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "key" to JsonPrimitive("a*b"),
                    "value" to JsonPrimitive("1"),
                ),
            ),
        )
        assertEquals("`a*b` = **1**", buildBotKeyValueContent(parsed.key, parsed.value))
    }

    @Test
    fun `wrong-typed known fields still fail loudly`() {
        // 对象型 chatId
        assertFailsWith<IllegalArgumentException>("对象型 chatId 必须大声失败") {
            parseOf(JsonObject(mapOf("chatId" to JsonObject(emptyMap()))))
        }
        // 数组型 chatId
        assertFailsWith<IllegalArgumentException>("数组型 chatId 必须大声失败") {
            parseOf(JsonObject(mapOf("chatId" to JsonArray(emptyList()))))
        }
        // 对象 / 数组型 key、value
        for (key in listOf("key", "value")) {
            assertFailsWith<IllegalArgumentException>("对象型 " + key + " 必须大声失败") {
                parseOf(
                    JsonObject(
                        mapOf(
                            "chatId" to JsonPrimitive("c"),
                            key to JsonObject(emptyMap()),
                        ),
                    ),
                )
            }
            assertFailsWith<IllegalArgumentException>("数组型 " + key + " 必须大声失败") {
                parseOf(
                    JsonObject(
                        mapOf(
                            "chatId" to JsonPrimitive("c"),
                            key to JsonArray(emptyList()),
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun `extraction order is pinned`() {
        // chatId 空白 + 后续字段坏类型 → 仍抛错而非回 MissingRequired（抽取在必填校验之前）
        assertFailsWith<IllegalArgumentException>("chatId 空白时 key 坏类型仍须大声失败") {
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive(""),
                        "key" to JsonArray(emptyList()),
                    ),
                ),
            )
        }
        // chatId 与 key 同时坏类型 → 抛错（chatId 先抽取）
        assertFailsWith<IllegalArgumentException>("chatId 先于 key 抽取") {
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonObject(emptyMap()),
                        "key" to JsonObject(emptyMap()),
                    ),
                ),
            )
        }
    }
}
