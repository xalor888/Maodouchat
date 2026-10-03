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

class BotSendMentionNudgeParseFuzzTest {

    private data class MentionNudgeSpec(
        val endpoint: String,
        val defaultLabel: String,
        val prefix: String,
        val suffix: String,
    )

    private companion object {
        /** 两个 mention/nudge 卡片端点的逐字规格（默认文案与包裹前后缀与处理器源码逐字一致）。 */
        private val MENTION_NUDGE_SPECS = listOf(
            MentionNudgeSpec("sendMentionCard", "mention", "> @", ""),
            MentionNudgeSpec("sendNudgeCard", "nudge", "> ~nudge:", "~"),
        )

        /** label 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId", "label", "text",
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

    private fun fieldsOf(obj: JsonObject, spec: MentionNudgeSpec): BotMentionNudgeFields {
        val result = parseBotMentionNudgeFields(obj, spec.defaultLabel)
        assertTrue(result is BotMentionNudgeFieldsResult.Ok, spec.endpoint + " 合法请求必须解析成功，实际 $result")
        return result.fields
    }

    @Test
    fun `mentionNudge family survives seeded unknown-field fuzz`() {
        val random = Random(0x5EED_2030)
        for (spec in MENTION_NUDGE_SPECS) {
            repeat(ITERATIONS) { i ->
                val chatId = "c-$i"
                val label = "label-$i"
                val base = if (i % 3 == 0) {
                    // 每三个里有一个不带 label：钉住回退链（text 缺席 → 默认文案）
                    JsonObject(mapOf("chatId" to JsonPrimitive(chatId)))
                } else if (i % 3 == 1) {
                    // 另三分之一带 text 不带 label：钉住回退到 text
                    JsonObject(
                        mapOf(
                            "chatId" to JsonPrimitive(chatId),
                            "text" to JsonPrimitive("text-$i"),
                        )
                    )
                } else {
                    JsonObject(
                        mapOf(
                            "chatId" to JsonPrimitive(chatId),
                            "label" to JsonPrimitive(label),
                        )
                    )
                }
                val payload = injectUnknownFields(base, random)
                val fields = fieldsOf(payload, spec)
                assertEquals(chatId, fields.chatId, spec.endpoint + " chatId")
                val expectedLabel = when (i % 3) {
                    0 -> spec.defaultLabel
                    1 -> "text-$i"
                    else -> label
                }
                assertEquals(expectedLabel, fields.label, spec.endpoint + " label")
            }
        }
    }

    @Test
    fun `mentionNudge chatId required, label not required`() {
        for (spec in MENTION_NUDGE_SPECS) {
            // chatId 缺席 / 空串 / 纯空白 → MissingRequired
            assertTrue(
                parseBotMentionNudgeFields(JsonObject(mapOf("label" to JsonPrimitive("l"))), spec.defaultLabel)
                    is BotMentionNudgeFieldsResult.MissingRequired,
                spec.endpoint + " chatId 缺席应判缺",
            )
            assertTrue(
                parseBotMentionNudgeFields(
                    JsonObject(mapOf("chatId" to JsonPrimitive(""), "label" to JsonPrimitive("l"))),
                    spec.defaultLabel,
                ) is BotMentionNudgeFieldsResult.MissingRequired,
                spec.endpoint + " chatId 空串应判缺",
            )
            assertTrue(
                parseBotMentionNudgeFields(
                    JsonObject(mapOf("chatId" to JsonPrimitive("   "), "label" to JsonPrimitive("l"))),
                    spec.defaultLabel,
                ) is BotMentionNudgeFieldsResult.MissingRequired,
                spec.endpoint + " chatId 纯空白应判缺（isBlank，不 trim 比较）",
            )
            // label 与 text 都缺席 → 默认文案，不判缺
            val missingLabel = fieldsOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"))), spec)
            assertEquals(spec.defaultLabel, missingLabel.label, spec.endpoint + " label/text 缺席回默认文案")
            // label 空白 → 原样保留，不判缺
            val blankLabel = fieldsOf(
                JsonObject(mapOf("chatId" to JsonPrimitive("c"), "label" to JsonPrimitive("   "))),
                spec,
            )
            assertEquals("   ", blankLabel.label, spec.endpoint + " label 空白原样保留不判缺")
            // label 缺席、text 在 → 回退到 text
            val textFallback = fieldsOf(
                JsonObject(mapOf("chatId" to JsonPrimitive("c"), "text" to JsonPrimitive("tb"))),
                spec,
            )
            assertEquals("tb", textFallback.label, spec.endpoint + " label 缺席回退到 text")
        }
    }

    @Test
    fun `mentionNudge label quirk semantics pinned`() {
        for (spec in MENTION_NUDGE_SPECS) {
            // label 显式 JSON null 得字面 "null"，不继续回退到 text / 默认文案
            val explicitNull = fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "label" to JsonNull,
                        "text" to JsonPrimitive("tb"),
                    )
                ),
                spec,
            )
            assertEquals("null", explicitNull.label, spec.endpoint + " label 显式 null 得字面 null，不回退")

            // 不 trim：前后空格原样保留
            val untrimmed = fieldsOf(
                JsonObject(mapOf("chatId" to JsonPrimitive("c"), "label" to JsonPrimitive("  hi  "))),
                spec,
            )
            assertEquals("  hi  ", untrimmed.label, spec.endpoint + " label 不 trim")

            // 超长截 80：前导空格计入上限（repeat 提到模板外拼接，K2 模板内嵌套引号是语法错误）
            val xs = "x".repeat(200)
            val longLabel = fieldsOf(
                JsonObject(mapOf("chatId" to JsonPrimitive("c"), "label" to JsonPrimitive("  " + xs))),
                spec,
            )
            assertEquals("  " + "x".repeat(78), longLabel.label, spec.endpoint + " 超长先截 80，前导空格计入上限")

            // JSON 数字 label 经 .content 照样解析（原处理器逐字语义）
            val numeric = fieldsOf(
                JsonObject(mapOf("chatId" to JsonPrimitive("c"), "label" to JsonPrimitive(42))),
                spec,
            )
            assertEquals("42", numeric.label, spec.endpoint + " 数字 label 照 .content 解析")

            // text 显式 null 且 label 缺席 → 回退链到默认文案（text 的 JsonNull 是 JsonPrimitive，得 "null"……）
            // 注意：(obj["text"]?.jsonPrimitive?.content) 对显式 null 是字面 "null"（非 null），
            // 所以不回退到 defaultLabel——这里钉住该语义。
            val nullText = fieldsOf(
                JsonObject(mapOf("chatId" to JsonPrimitive("c"), "text" to JsonNull)),
                spec,
            )
            assertEquals("null", nullText.label, spec.endpoint + " text 显式 null 得字面 null，不回默认文案")

            // 默认文案本身不超 80，不被截断
            assertTrue(spec.defaultLabel.length <= 80, spec.endpoint + " 默认文案不应超 80")
        }
    }

    @Test
    fun `mentionNudge content template pinned`() {
        for (spec in MENTION_NUDGE_SPECS) {
            // 前缀 + label + 后缀逐字拼接，type = "MARKDOWN" 由处理器固定
            assertEquals(
                spec.prefix + "hello" + spec.suffix,
                buildBotMentionNudgeContent(spec.prefix, "hello", spec.suffix),
                spec.endpoint + " 内容模板",
            )
            // 默认文案组合
            assertEquals(
                spec.prefix + spec.defaultLabel + spec.suffix,
                buildBotMentionNudgeContent(spec.prefix, spec.defaultLabel, spec.suffix),
                spec.endpoint + " 默认文案内容组合",
            )
        }
        // sendMentionCard 的包裹逐字钉住："> @" + label（无后缀）
        assertEquals(
            "> @x",
            buildBotMentionNudgeContent("> @", "x", ""),
            "sendMentionCard 包裹前后缀逐字",
        )
        // sendNudgeCard 的包裹逐字钉住："> ~nudge:" + label + "~"
        assertEquals(
            "> ~nudge:x~",
            buildBotMentionNudgeContent("> ~nudge:", "x", "~"),
            "sendNudgeCard 包裹前后缀逐字",
        )
    }

    @Test
    fun `mentionNudge wrong-typed known fields fail loudly`() {
        for (spec in MENTION_NUDGE_SPECS) {
            // 对象型 chatId → 大声失败（不是吞掉）
            assertFailsWith<IllegalArgumentException>(spec.endpoint + " 对象型 chatId") {
                parseBotMentionNudgeFields(
                    JsonObject(mapOf("chatId" to JsonObject(emptyMap()), "label" to JsonPrimitive("l"))),
                    spec.defaultLabel,
                )
            }
            // 数组型 chatId → 大声失败
            assertFailsWith<IllegalArgumentException>(spec.endpoint + " 数组型 chatId") {
                parseBotMentionNudgeFields(
                    JsonObject(mapOf("chatId" to JsonArray(emptyList()), "label" to JsonPrimitive("l"))),
                    spec.defaultLabel,
                )
            }
            // 对象型 label → 大声失败（即使 chatId 合法）
            assertFailsWith<IllegalArgumentException>(spec.endpoint + " 对象型 label") {
                parseBotMentionNudgeFields(
                    JsonObject(mapOf("chatId" to JsonPrimitive("c"), "label" to JsonObject(emptyMap()))),
                    spec.defaultLabel,
                )
            }
            // 数组型 text → 大声失败（回退链里的第二位）
            assertFailsWith<IllegalArgumentException>(spec.endpoint + " 数组型 text") {
                parseBotMentionNudgeFields(
                    JsonObject(mapOf("chatId" to JsonPrimitive("c"), "text" to JsonArray(emptyList()))),
                    spec.defaultLabel,
                )
            }
        }
    }

    @Test
    fun `mentionNudge extraction order matches handler verbatim`() {
        // 原处理器先抽 chatId 再抽 label：chatId 坏类型时先抛错，label 的值不影响抛错点
        for (spec in MENTION_NUDGE_SPECS) {
            assertFailsWith<IllegalArgumentException>(spec.endpoint + " 抽取顺序") {
                parseBotMentionNudgeFields(
                    JsonObject(
                        mapOf(
                            "chatId" to JsonObject(emptyMap()),
                            "label" to JsonArray(emptyList()),
                        )
                    ),
                    spec.defaultLabel,
                )
            }
        }
        // label 抽取发生在 chatId 判缺之前：chatId 空白 + label 坏类型 → 仍抛错（不是 400）
        for (spec in MENTION_NUDGE_SPECS) {
            assertFailsWith<IllegalArgumentException>(spec.endpoint + " 坏类型先于判缺") {
                parseBotMentionNudgeFields(
                    JsonObject(
                        mapOf(
                            "chatId" to JsonPrimitive(""),
                            "label" to JsonObject(emptyMap()),
                        )
                    ),
                    spec.defaultLabel,
                )
            }
        }
    }
}
