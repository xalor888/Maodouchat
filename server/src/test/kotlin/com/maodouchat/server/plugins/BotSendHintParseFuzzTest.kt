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

class BotSendHintParseFuzzTest {

    private data class HintSpec(
        val endpoint: String,
        val defaultHint: String,
        val prefix: String,
    )

    private companion object {
        /** 十二个 send*Hint 端点的逐字规格（默认文案与内容前缀与处理器源码逐字一致）。 */
        private val HINT_SPECS = listOf(
            HintSpec("sendInviteHint", "Invite link ready", "INVITEHINT:"),
            HintSpec("sendSafetyHint", "Verify safety code out-of-band", "🔐 "),
            HintSpec("sendQrHint", "Scan my QR to connect", "📷 "),
            HintSpec("sendSpoilerHint", "Spoiler media: tap to reveal", "🌫️ "),
            HintSpec("sendDownloadHint", "Auto-download is on for this network", "⬇️ "),
            HintSpec("sendLocationHint", "Share a static pin", "📍 "),
            HintSpec("sendFileHint", "File share is available", "📎 "),
            HintSpec("sendSecureHint", "Screen capture protection is active", "🛡️ "),
            HintSpec("sendPhotoHint", "Photo send is available", "🖼️ "),
            HintSpec("sendVideoHint", "Video send is available", "🎬 "),
            HintSpec("sendGifHint", "GIF send can be toggled separately from images", "🎞️ "),
            HintSpec("sendWatermarkHint", "Blind watermarks embed user id + time for leak forensics", "🔏 "),
        )

        /** hint 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId", "hint",
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

    private fun fieldsOf(obj: JsonObject, spec: HintSpec): BotSendHintFields {
        val result = parseBotSendHintFields(obj, spec.defaultHint)
        assertTrue(result is BotSendHintFieldsResult.Ok, spec.endpoint + " 合法请求必须解析成功，实际 $result")
        return result.fields
    }

    @Test
    fun `sendHint family survives seeded unknown-field fuzz`() {
        val random = Random(0x5EED_2026)
        for (spec in HINT_SPECS) {
            repeat(ITERATIONS) { i ->
                val chatId = "c-$i"
                val hint = "hint-$i"
                val base = if (i % 3 == 0) {
                    // 每三个里有一个不带 hint：钉住缺省回默认文案
                    JsonObject(mapOf("chatId" to JsonPrimitive(chatId)))
                } else {
                    JsonObject(mapOf("chatId" to JsonPrimitive(chatId), "hint" to JsonPrimitive(hint)))
                }
                val payload = injectUnknownFields(base, random)
                val fields = fieldsOf(payload, spec)
                assertEquals(chatId, fields.chatId, spec.endpoint + " chatId")
                val expectedHint = if (i % 3 == 0) spec.defaultHint else hint
                assertEquals(expectedHint, fields.hint, spec.endpoint + " hint")
            }
        }
    }

    @Test
    fun `sendHint chatId required, hint not required`() {
        for (spec in HINT_SPECS) {
            // chatId 缺席 / 空串 / 纯空白 → MissingRequired
            assertTrue(
                parseBotSendHintFields(JsonObject(mapOf("hint" to JsonPrimitive("h"))), spec.defaultHint)
                    is BotSendHintFieldsResult.MissingRequired,
                spec.endpoint + " chatId 缺席应判缺",
            )
            assertTrue(
                parseBotSendHintFields(
                    JsonObject(mapOf("chatId" to JsonPrimitive(""), "hint" to JsonPrimitive("h"))),
                    spec.defaultHint,
                ) is BotSendHintFieldsResult.MissingRequired,
                spec.endpoint + " chatId 空串应判缺",
            )
            assertTrue(
                parseBotSendHintFields(
                    JsonObject(mapOf("chatId" to JsonPrimitive("   "), "hint" to JsonPrimitive("h"))),
                    spec.defaultHint,
                ) is BotSendHintFieldsResult.MissingRequired,
                spec.endpoint + " chatId 纯空白应判缺（isBlank，不 trim 比较）",
            )
            // hint 缺席 → 默认文案，不判缺
            val missingHint = fieldsOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"))), spec)
            assertEquals(spec.defaultHint, missingHint.hint, spec.endpoint + " hint 缺席回默认文案")
            // hint 空白 → 原样保留，不判缺
            val blankHint = fieldsOf(
                JsonObject(mapOf("chatId" to JsonPrimitive("c"), "hint" to JsonPrimitive("   "))),
                spec,
            )
            assertEquals("   ", blankHint.hint, spec.endpoint + " hint 空白原样保留不判缺")
        }
    }

    @Test
    fun `sendHint hint quirk semantics pinned`() {
        for (spec in HINT_SPECS) {
            // 显式 JSON null 得字面 "null"，不回退默认值
            val explicitNull = fieldsOf(
                JsonObject(mapOf("chatId" to JsonPrimitive("c"), "hint" to JsonNull)),
                spec,
            )
            assertEquals("null", explicitNull.hint, spec.endpoint + " 显式 null 得字面 null")

            // 不 trim：前后空格原样保留
            val untrimmed = fieldsOf(
                JsonObject(mapOf("chatId" to JsonPrimitive("c"), "hint" to JsonPrimitive("  hi  "))),
                spec,
            )
            assertEquals("  hi  ", untrimmed.hint, spec.endpoint + " hint 不 trim")

            // 超长截 120：前导空格计入上限（repeat 提到模板外拼接，K2 模板内嵌套引号是语法错误）
            val xs = "x".repeat(200)
            val longHint = fieldsOf(
                JsonObject(mapOf("chatId" to JsonPrimitive("c"), "hint" to JsonPrimitive("  " + xs))),
                spec,
            )
            assertEquals("  " + "x".repeat(118), longHint.hint, spec.endpoint + " 超长先截 120，前导空格计入上限")

            // JSON 数字 hint 经 .content 照样解析（原处理器逐字语义）
            val numeric = fieldsOf(
                JsonObject(mapOf("chatId" to JsonPrimitive("c"), "hint" to JsonPrimitive(42))),
                spec,
            )
            assertEquals("42", numeric.hint, spec.endpoint + " 数字 hint 照 .content 解析")

            // 默认文案本身不超 120，不被截断
            assertTrue(spec.defaultHint.length <= 120, spec.endpoint + " 默认文案不应超 120")
        }
    }

    @Test
    fun `sendHint content template pinned`() {
        for (spec in HINT_SPECS) {
            // 前缀 + hint 逐字拼接，type = "SYSTEM" 由处理器固定
            assertEquals(
                spec.prefix + "hello",
                buildBotSendHintContent(spec.prefix, "hello"),
                spec.endpoint + " 内容模板",
            )
            // 默认文案组合
            assertEquals(
                spec.prefix + spec.defaultHint,
                buildBotSendHintContent(spec.prefix, spec.defaultHint),
                spec.endpoint + " 默认文案内容组合",
            )
            // 怪语义组合：字面 null
            assertEquals(
                spec.prefix + "null",
                buildBotSendHintContent(spec.prefix, "null"),
                spec.endpoint + " 字面 null 内容组合",
            )
        }
        // sendInviteHint 的前缀无空格，特意钉住
        assertEquals(
            "INVITEHINT:x",
            buildBotSendHintContent("INVITEHINT:", "x"),
            "INVITEHINT 前缀无空格",
        )
    }

    @Test
    fun `sendHint wrong-typed known fields fail loudly`() {
        for (spec in HINT_SPECS) {
            // 对象型 chatId → 大声失败（不是吞掉）
            assertFailsWith<IllegalArgumentException>(spec.endpoint + " 对象型 chatId") {
                parseBotSendHintFields(
                    JsonObject(mapOf("chatId" to JsonObject(emptyMap()), "hint" to JsonPrimitive("h"))),
                    spec.defaultHint,
                )
            }
            // 数组型 chatId → 大声失败
            assertFailsWith<IllegalArgumentException>(spec.endpoint + " 数组型 chatId") {
                parseBotSendHintFields(
                    JsonObject(mapOf("chatId" to JsonArray(emptyList()), "hint" to JsonPrimitive("h"))),
                    spec.defaultHint,
                )
            }
            // 对象型 hint → 大声失败（即使 chatId 合法）
            assertFailsWith<IllegalArgumentException>(spec.endpoint + " 对象型 hint") {
                parseBotSendHintFields(
                    JsonObject(mapOf("chatId" to JsonPrimitive("c"), "hint" to JsonObject(emptyMap()))),
                    spec.defaultHint,
                )
            }
            // 数组型 hint → 大声失败
            assertFailsWith<IllegalArgumentException>(spec.endpoint + " 数组型 hint") {
                parseBotSendHintFields(
                    JsonObject(mapOf("chatId" to JsonPrimitive("c"), "hint" to JsonArray(emptyList()))),
                    spec.defaultHint,
                )
            }
        }
    }

    @Test
    fun `sendHint extraction order matches handler verbatim`() {
        // 原处理器先抽 chatId 再抽 hint：chatId 坏类型时先抛错，hint 的值不影响抛错点
        for (spec in HINT_SPECS) {
            assertFailsWith<IllegalArgumentException>(spec.endpoint + " 抽取顺序") {
                parseBotSendHintFields(
                    JsonObject(
                        mapOf(
                            "chatId" to JsonObject(emptyMap()),
                            "hint" to JsonArray(emptyList()),
                        )
                    ),
                    spec.defaultHint,
                )
            }
        }
        // hint 抽取发生在 chatId 判缺之前：chatId 空白 + hint 坏类型 → 仍抛错（不是 400）
        for (spec in HINT_SPECS) {
            assertFailsWith<IllegalArgumentException>(spec.endpoint + " 坏类型先于判缺") {
                parseBotSendHintFields(
                    JsonObject(
                        mapOf(
                            "chatId" to JsonPrimitive(""),
                            "hint" to JsonObject(emptyMap()),
                        )
                    ),
                    spec.defaultHint,
                )
            }
        }
    }
}
