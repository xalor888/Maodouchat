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
 * Bot `sendNotice` 请求体手写解析的**模糊兼容性测试**（清单 Q01「协议模型向前/向后兼容与
 * fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、
 * `sendContact`、`sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、
 * `forwardMessage`/`copyMessage`、`sendNudge`、`sendContactCard`、`sendPollQuiz`、
 * `answerCallbackQuery`、`sendChecklist`、`sendAlert`、`sendCountdown`
 * 之后第二十二块）。
 *
 * Bot 路由没有 typed DTO——请求体是 `receiveBoundedTextOrEmpty()` 拿到的原始字符串，
 * 经 `Json.parseToJsonElement(body).jsonObject` 再手写抽取。本轮把原来内联在
 * `/api/bot/sendNotice` 处理器里的抽取 / 校验 / 内容组装逻辑收敛为纯函数
 * ([parseBotSendNoticeFields] / [buildBotNoticeContent])
 * （生产侧零行为改动：校验顺序仍为必填（纯函数）→ 成员检查（处理器）），
 * 本测试直接钉住这些函数的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]，含 `text`→`message` 别名）：
 *   否则测的是「重复键覆盖语义」，而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 钉住 `text` 的 `(obj["text"] ?: obj["message"])?.jsonPrimitive?.content.orEmpty().take(300)`
 *   逐字顺序：`text`→`message` 别名链是 `?:` 接存在性（`text` 键缺席才穿透）；
 *   显式 JSON null 的 `text` 得字面 `"null"`（非空白，不穿透到 `message`，不判缺）；
 *   **不 trim**（前导空格计入上限，原处理器逐字语义）；超长截 300。
 * - 钉住双必填（`chatId`/`text` 缺或空白 → 400 `"chatId/text required"`）——
 *   `text` 缺省/空白判缺，没有回退默认值（与 `sendAlert` 的 `text` 不同，特意钉住）。
 * - 钉住内容模板形状（`"NOTICE: $text"`，`type = "SYSTEM"` 由处理器固定）。
 * - 反证 `wrong-typed known fields still fail loudly`：手写解析里 `?.jsonPrimitive`
 *   在类型错时抛 [IllegalArgumentException]，路由层 `StatusPages` 把它映射为 400
 *   「参数无效」（不是 500）——坏数据必须大声失败，不能悄悄吞掉。
 */
class BotSendNoticeParseFuzzTest {

    private companion object {
        /** sendNotice 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId", "text", "message",
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

    private fun fieldsOf(obj: JsonObject): BotSendNoticeFields {
        val result = parseBotSendNoticeFields(obj)
        assertTrue(result is BotSendNoticeFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    private fun validBase(
        chatId: String = "c1",
        text: String = "系统通知",
    ): JsonObject = JsonObject(
        mapOf(
            "chatId" to JsonPrimitive(chatId),
            "text" to JsonPrimitive(text),
        )
    )

    @Test
    fun `sendNotice parse survives seeded unknown-field fuzz`() {
        val random = Random(0xB1_2026)
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val text = "notice-$i"
            val base = JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive(chatId),
                    "text" to JsonPrimitive(text),
                )
            )
            val obj = injectUnknownFields(base, random)
            val fields = fieldsOf(obj)
            assertEquals(
                BotSendNoticeFields(chatId, text),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
        }
    }

    @Test
    fun `sendNotice required fields are enforced`() {
        // chatId 缺失 → MissingRequired
        assertTrue(
            parseBotSendNoticeFields(
                JsonObject(
                    mapOf(
                        "text" to JsonPrimitive("t"),
                    )
                )
            ) is BotSendNoticeFieldsResult.MissingRequired,
            "chatId 缺失必须判缺",
        )
        // chatId 空白 → MissingRequired
        assertTrue(
            parseBotSendNoticeFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("   "),
                        "text" to JsonPrimitive("t"),
                    )
                )
            ) is BotSendNoticeFieldsResult.MissingRequired,
            "chatId 空白必须判缺",
        )
        // text 缺失 → 判缺（没有回退默认值，与 sendAlert 的 text 不同）
        assertTrue(
            parseBotSendNoticeFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                    )
                )
            ) is BotSendNoticeFieldsResult.MissingRequired,
            "text 缺省必须判缺",
        )
        // text 空白 → 判缺
        assertTrue(
            parseBotSendNoticeFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "text" to JsonPrimitive("  "),
                    )
                )
            ) is BotSendNoticeFieldsResult.MissingRequired,
            "text 空白必须判缺",
        )
        // message 别名满足必填
        assertEquals(
            BotSendNoticeFields("c1", "别名文本"),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "message" to JsonPrimitive("别名文本"),
                    )
                )
            ),
            "message 别名必须能满足 text 必填",
        )
        // text 显式 JSON null → 得字面 "null"（非空白），不判缺、不穿透到 message
        assertEquals(
            BotSendNoticeFields("c1", "null"),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "text" to JsonNull,
                        "message" to JsonPrimitive("不该被穿透"),
                    )
                )
            ),
            "text 显式 JSON null 必须得字面 \"null\"、不穿透到 message",
        )
    }

    @Test
    fun `sendNotice text legacy quirks are pinned`() {
        // 不 trim：前导空格原样保留并计入 300 上限
        // 注：repeat 提到模板外——Kotlin 2.4.0（K2）下模板内转义引号嵌套报 Syntax error。
        val xs = "x".repeat(340)
        val leading = "  系统通知 " + xs
        val expected = leading.take(300)
        assertEquals(
            BotSendNoticeFields("c1", expected),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "text" to JsonPrimitive(leading),
                    )
                )
            ),
            "text 不 trim、前导空格计入 take(300) 上限",
        )
        // 超长截 300
        val long = "y".repeat(500)
        assertEquals(
            "y".repeat(300),
            fieldsOf(validBase(text = long)).text,
            "text 超长截 300",
        )
        // text 键缺席 → 穿透到 message
        assertEquals(
            "穿透文本",
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "message" to JsonPrimitive("穿透文本"),
                    )
                )
            ).text,
            "text 缺席时 message 别名必须穿透",
        )
        // text 与 message 同时存在 → text 优先
        assertEquals(
            "主文本",
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "text" to JsonPrimitive("主文本"),
                        "message" to JsonPrimitive("别名文本"),
                    )
                )
            ).text,
            "text 与 message 并存时 text 优先",
        )
    }

    @Test
    fun `sendNotice content template is pinned`() {
        assertEquals(
            "NOTICE: 系统通知",
            buildBotNoticeContent("系统通知"),
            "内容模板必须与原处理器逐字一致",
        )
        assertEquals(
            "NOTICE: null",
            buildBotNoticeContent("null"),
            "字面 null 文本的内容模板",
        )
    }

    @Test
    fun `sendNotice wrong-typed known fields fail loudly`() {
        // 对象型 chatId → IllegalArgumentException（StatusPages 映射 400，不是 500）
        assertFailsWith<IllegalArgumentException> {
            parseBotSendNoticeFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonObject(mapOf("x" to JsonPrimitive(1))),
                        "text" to JsonPrimitive("t"),
                    )
                )
            )
        }
        // 数组型 text → IllegalArgumentException
        assertFailsWith<IllegalArgumentException> {
            parseBotSendNoticeFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "text" to JsonArray(listOf(JsonPrimitive("t"))),
                    )
                )
            )
        }
        // 对象型 message 别名 → IllegalArgumentException
        assertFailsWith<IllegalArgumentException> {
            parseBotSendNoticeFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "message" to JsonObject(mapOf("x" to JsonPrimitive(1))),
                    )
                )
            )
        }
        // 对象型 text → IllegalArgumentException（即使 message 别名合法也不穿透）
        assertFailsWith<IllegalArgumentException> {
            parseBotSendNoticeFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "text" to JsonObject(mapOf("x" to JsonPrimitive(1))),
                        "message" to JsonPrimitive("别名文本"),
                    )
                )
            )
        }
    }
}
