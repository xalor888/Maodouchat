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
 * Bot `sendHr` 请求体手写解析的**模糊兼容性测试**（清单 Q01「协议模型向前/向后兼容与
 * fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、
 * `sendContact`、`sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、
 * `forwardMessage`/`copyMessage`、`sendNudge`、`sendContactCard`、`sendPollQuiz`、
 * `answerCallbackQuery`、`sendChecklist`、`sendAlert`、`sendCountdown`、`sendNotice`、
 * `sendBadge`、`sendToast` 之后第二十五块）。
 *
 * Bot 路由没有 typed DTO——请求体是 `receiveBoundedTextOrEmpty()` 拿到的原始字符串，
 * 经 `Json.parseToJsonElement(body).jsonObject` 再手写抽取。本轮把原来内联在
 * `/api/bot/sendHr` 处理器里的抽取 / 校验 / 内容组装逻辑收敛为纯函数
 * ([parseBotSendHrFields] / [buildBotHrContent])
 * （生产侧零行为改动：校验顺序仍为必填（纯函数）→ 成员检查（处理器），
 * markdown 总开关检查仍在处理器解析之前），本测试直接钉住这些函数的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 钉住 `note`（键名 `text`）的 `.orEmpty().take(200)`：缺省回 `""`（**无默认值**），
 *   显式 JSON null 得字面 `"null"`，**不 trim**（前导空格计入上限，原处理器逐字语义），
 *   超长截 200。
 * - 钉住单必填（`chatId` 缺或空白 → 400），`text` 非必填。
 * - 钉住内容模板形状（`note` 非空白 → `"---\n$note\n---"`，空白 → `"---"`，
 *   `type = "MARKDOWN"` 由处理器固定）。
 * - 反证 `wrong-typed known fields still fail loudly`：手写解析里 `?.jsonPrimitive`
 *   在类型错时抛 [IllegalArgumentException]，路由层 `StatusPages` 把它映射为 400
 *   「参数无效」（不是 500）——坏数据必须大声失败，不能悄悄吞掉。
 */
class BotSendHrParseFuzzTest {

    private companion object {
        /** sendHr 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId", "text",
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

    private fun fieldsOf(obj: JsonObject): BotSendHrFields {
        val result = parseBotSendHrFields(obj)
        assertTrue(result is BotSendHrFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    private fun validBase(
        chatId: String = "c1",
        text: String = "本日小结",
    ): JsonObject = JsonObject(
        mapOf(
            "chatId" to JsonPrimitive(chatId),
            "text" to JsonPrimitive(text),
        )
    )

    @Test
    fun `sendHr parse survives seeded unknown-field fuzz`() {
        val random = Random(0x5E_2026)
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val note = "note-$i"
            val base = JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive(chatId),
                    "text" to JsonPrimitive(note),
                )
            )
            val obj = injectUnknownFields(base, random)
            val fields = fieldsOf(obj)
            assertEquals(
                BotSendHrFields(chatId, note),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
        }
    }

    @Test
    fun `sendHr required fields are enforced`() {
        // chatId 缺失 → MissingRequired
        assertTrue(
            parseBotSendHrFields(
                JsonObject(
                    mapOf(
                        "text" to JsonPrimitive("t"),
                    )
                )
            ) is BotSendHrFieldsResult.MissingRequired,
            "chatId 缺失必须判缺",
        )
        // chatId 空白 → MissingRequired
        assertTrue(
            parseBotSendHrFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("   "),
                        "text" to JsonPrimitive("t"),
                    )
                )
            ) is BotSendHrFieldsResult.MissingRequired,
            "chatId 空白必须判缺",
        )
        // text 缺失 → 不判缺，回 ""（无默认值，特意钉住）
        assertEquals(
            BotSendHrFields("c1", ""),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                    )
                )
            ),
            "text 缺省必须不判缺、回空字符串",
        )
        // text 空白 → 不判缺，回空白原样（是否判缺只看 chatId）
        assertEquals(
            BotSendHrFields("c1", "  "),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "text" to JsonPrimitive("  "),
                    )
                )
            ),
            "text 空白必须不判缺、原样保留",
        )
    }

    @Test
    fun `sendHr note quirks are pinned`() {
        // 不 trim：前导空格原样保留并计入 200 上限
        // 注：Kotlin 2.4.0（K2）下模板内 ${\"…\"} 转义引号报 Syntax error，
        // 故把 repeat 提到模板外，避免模板内嵌套引号。
        val xs = "x".repeat(196)
        val leading = "  备注 $xs"
        val expected = leading.take(200)
        assertEquals(
            BotSendHrFields("c1", expected),
            fieldsOf(validBase(text = leading)),
            "note 不 trim：前导空格原样保留并计入 take(200)",
        )
        // 超长截 200
        val long = "y".repeat(500)
        assertEquals(
            BotSendHrFields("c1", long.take(200)),
            fieldsOf(validBase(text = long)),
            "note 超长必须截断到 200",
        )
        // 显式 JSON null → 字面 "null"
        assertEquals(
            BotSendHrFields("c1", "null"),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "text" to JsonNull,
                    )
                )
            ),
            "text 显式 null 必须得字面 \"null\"",
        )
    }

    @Test
    fun `sendHr content template is pinned`() {
        // 与原处理器逐字一致：note 非空白 → "---\n$note\n---"
        assertEquals(
            "---\n本日小结\n---",
            buildBotHrContent("本日小结"),
            "非空白 note 的内容模板必须与原处理器逐字一致",
        )
        // note 为空 → "---"
        assertEquals(
            "---",
            buildBotHrContent(""),
            "空 note 必须得裸分隔线",
        )
        // note 全空白 → "---"（isNotBlank 为 false）
        assertEquals(
            "---",
            buildBotHrContent("   "),
            "全空白 note 必须得裸分隔线",
        )
        // 怪语义组合：显式 null 字面 + 前导空格不 trim
        assertEquals(
            "---\n  null\n---",
            buildBotHrContent("  null"),
            "怪语义组合必须逐字透传",
        )
    }

    @Test
    fun `sendHr wrong-typed known fields fail loudly`() {
        // 对象型 chatId → ?.jsonPrimitive 抛 IllegalArgumentException
        assertFailsWith<IllegalArgumentException>(
            "对象型 chatId 必须大声失败",
        ) {
            parseBotSendHrFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonObject(mapOf("nested" to JsonPrimitive("x"))),
                    )
                )
            )
        }
        // 数组型 text → ?.jsonPrimitive 抛 IllegalArgumentException
        assertFailsWith<IllegalArgumentException>(
            "数组型 text 必须大声失败",
        ) {
            parseBotSendHrFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "text" to JsonArray(listOf(JsonPrimitive("a"))),
                    )
                )
            )
        }
    }
}
