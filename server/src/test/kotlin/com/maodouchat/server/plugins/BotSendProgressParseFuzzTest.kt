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
 * Bot `sendProgress` 请求体手写解析的**模糊兼容性测试**（清单 Q01「协议模型向前/向后兼容与
 * fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、
 * `sendContact`、`sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、
 * `forwardMessage`/`copyMessage`、`sendNudge`、`sendContactCard`、`sendPollQuiz`、
 * `answerCallbackQuery`、`sendChecklist`、`sendAlert`、`sendCountdown`、`sendNotice`、
 * `sendBadge`、`sendToast`、`sendHr`、`sendDivider` 之后第二十七块）。
 *
 * Bot 路由没有 typed DTO——请求体是 `receiveBoundedTextOrEmpty()` 拿到的原始字符串，
 * 经 `Json.parseToJsonElement(body).jsonObject` 再手写抽取。本轮把原来内联在
 * `/api/bot/sendProgress` 处理器里的抽取 / 校验 / 内容组装逻辑收敛为纯函数
 * ([parseBotSendProgressFields] / [buildBotProgressContent])
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
 * - 钉住 `title` 的 `.orEmpty().ifBlank { "Progress" }.take(40)` 逐字顺序：缺省/空白回
 *   `"Progress"`，显式 JSON null 得字面 `"null"`（非空白，`ifBlank` 不触发），
 *   超长先回退默认再截 40，**不 trim**（前导空格计入上限，原处理器逐字语义）。
 * - 钉住 `percent` 的字符串解析 + 钳制：`(content?.toIntOrNull() ?: 0).coerceIn(0, 100)`——
 *   缺省 / 显式 null / 非数字串 / 小数串一律回 `0`，负数钳 `0`，超 100 钳 `100`。
 * - 钉住单必填（`chatId` 缺或空白 → 400），`title`/`percent` 非必填。
 * - 钉住内容模板形状（`"**$title**\n`[$bar]` $percent%"`，`type = "MARKDOWN"` 由处理器固定；
 *   `bar` 为 10 格，`filled = percent / 10` 整数除法）。
 * - 反证 `wrong-typed known fields still fail loudly`：手写解析里 `?.jsonPrimitive`
 *   在类型错时抛 [IllegalArgumentException]，路由层 `StatusPages` 把它映射为 400
 *   「参数无效」（不是 500）——坏数据必须大声失败，不能悄悄吞掉。
 */
class BotSendProgressParseFuzzTest {

    private companion object {
        /** sendProgress 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId", "title", "percent",
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

    private fun fieldsOf(obj: JsonObject): BotSendProgressFields {
        val result = parseBotSendProgressFields(obj)
        assertTrue(result is BotSendProgressFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    private fun validBase(
        chatId: String = "c1",
        title: String = "上传中",
        percent: JsonElement = JsonPrimitive(30),
    ): JsonObject = JsonObject(
        mapOf(
            "chatId" to JsonPrimitive(chatId),
            "title" to JsonPrimitive(title),
            "percent" to percent,
        )
    )

    @Test
    fun `sendProgress parse survives seeded unknown-field fuzz`() {
        val random = Random(0x5EED_2026)
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val title = "title-$i"
            val percent = i % 101
            val base = JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive(chatId),
                    "title" to JsonPrimitive(title),
                    "percent" to JsonPrimitive(percent),
                )
            )
            val obj = injectUnknownFields(base, random)
            val fields = fieldsOf(obj)
            assertEquals(
                BotSendProgressFields(chatId, title, percent),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
        }
    }

    @Test
    fun `sendProgress required fields are enforced`() {
        // chatId 缺失 → MissingRequired
        assertTrue(
            parseBotSendProgressFields(
                JsonObject(
                    mapOf(
                        "title" to JsonPrimitive("t"),
                        "percent" to JsonPrimitive(10),
                    )
                )
            ) is BotSendProgressFieldsResult.MissingRequired,
            "chatId 缺失必须判缺",
        )
        // chatId 空白 → MissingRequired
        assertTrue(
            parseBotSendProgressFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("   "),
                        "title" to JsonPrimitive("t"),
                        "percent" to JsonPrimitive(10),
                    )
                )
            ) is BotSendProgressFieldsResult.MissingRequired,
            "chatId 空白必须判缺",
        )
        // title 缺失 → 不判缺，回 "Progress"
        assertEquals(
            BotSendProgressFields("c1", "Progress", 10),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "percent" to JsonPrimitive(10),
                    )
                )
            ),
            "title 缺省必须不判缺、回默认值",
        )
        // title 空白 → 不判缺，回 "Progress"
        assertEquals(
            BotSendProgressFields("c1", "Progress", 10),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "title" to JsonPrimitive("  "),
                        "percent" to JsonPrimitive(10),
                    )
                )
            ),
            "title 空白必须不判缺、回默认值",
        )
        // percent 缺失 → 不判缺，回 0
        assertEquals(
            BotSendProgressFields("c1", "t", 0),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "title" to JsonPrimitive("t"),
                    )
                )
            ),
            "percent 缺省必须不判缺、回 0",
        )
    }

    @Test
    fun `sendProgress title legacy quirks are pinned`() {
        // 不 trim：前导空格原样保留并计入 40 上限
        // 注：Kotlin 2.4.0（K2）下模板内 ${"…"} 转义引号报 Syntax error，
        // 故把 repeat 提到模板外，避免模板内嵌套引号。
        val xs = "x".repeat(36)
        val leading = "  进度 " + xs
        val expected = leading.take(40)
        assertEquals(
            BotSendProgressFields("c1", expected, 30),
            fieldsOf(validBase(title = leading)),
            "title 不 trim：前导空格原样保留并计入 take(40)",
        )
        // 超长截 40（默认值回退在截断之前，这里用非空白长串验证截断本身）
        val long = "y".repeat(100)
        assertEquals(
            BotSendProgressFields("c1", long.take(40), 30),
            fieldsOf(validBase(title = long)),
            "title 超长必须截断到 40",
        )
        // 显式 JSON null → 字面 "null"（非空白，ifBlank 不触发）
        assertEquals(
            BotSendProgressFields("c1", "null", 30),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "title" to JsonNull,
                        "percent" to JsonPrimitive(30),
                    )
                )
            ),
            "title 显式 null 必须得字面 \"null\"，不回默认值",
        )
    }

    @Test
    fun `sendProgress percent clamping is pinned`() {
        // 字符串数字照样解析
        assertEquals(50, fieldsOf(validBase(percent = JsonPrimitive("50"))).percent, "字符串 \"50\" 必须解析为 50")
        // JSON 数字经 .content 得 "75" 照样解析
        assertEquals(75, fieldsOf(validBase(percent = JsonPrimitive(75))).percent, "JSON 数字 75 必须解析为 75")
        // 非数字串 → 0
        assertEquals(0, fieldsOf(validBase(percent = JsonPrimitive("abc"))).percent, "非数字串必须回 0")
        // 空串 → 0
        assertEquals(0, fieldsOf(validBase(percent = JsonPrimitive(""))).percent, "空串必须回 0")
        // 小数串 → toIntOrNull 为 null → 0
        assertEquals(0, fieldsOf(validBase(percent = JsonPrimitive("50.5"))).percent, "小数串必须回 0")
        // 显式 JSON null → .content 为 "null" → 0
        assertEquals(
            0,
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "title" to JsonPrimitive("t"),
                        "percent" to JsonNull,
                    )
                )
            ).percent,
            "显式 null 必须回 0",
        )
        // 负数钳到 0
        assertEquals(0, fieldsOf(validBase(percent = JsonPrimitive(-5))).percent, "负数必须钳到 0")
        assertEquals(0, fieldsOf(validBase(percent = JsonPrimitive("-5"))).percent, "字符串负数必须钳到 0")
        // 超 100 钳到 100
        assertEquals(100, fieldsOf(validBase(percent = JsonPrimitive(150))).percent, "150 必须钳到 100")
        assertEquals(100, fieldsOf(validBase(percent = JsonPrimitive("999"))).percent, "字符串超限必须钳到 100")
        // 边界 0 / 100 原样保留
        assertEquals(0, fieldsOf(validBase(percent = JsonPrimitive(0))).percent, "0 必须保留")
        assertEquals(100, fieldsOf(validBase(percent = JsonPrimitive(100))).percent, "100 必须保留")
    }

    @Test
    fun `sendProgress content template is pinned`() {
        // 与原处理器逐字一致："**$title**\n`[$bar]` $percent%"，bar 为 10 格，filled = percent / 10
        // 注：期望串用拼接构造，避免模板内嵌套引号（K2 Syntax error 坑）。
        val bar0 = "----------"
        assertEquals(
            "**上传中**\n`[" + bar0 + "]` 0%",
            buildBotProgressContent("上传中", 0),
            "percent=0 时进度条必须全空",
        )
        val bar5 = "#####" + "-----"
        assertEquals(
            "**t**\n`[" + bar5 + "]` 50%",
            buildBotProgressContent("t", 50),
            "percent=50 时进度条必须 5 格填充",
        )
        // 整数除法：5 / 10 = 0
        assertEquals(
            "**t**\n`[" + bar0 + "]` 5%",
            buildBotProgressContent("t", 5),
            "percent=5 时 filled=0，进度条必须全空",
        )
        val bar10 = "##########"
        assertEquals(
            "**t**\n`[" + bar10 + "]` 100%",
            buildBotProgressContent("t", 100),
            "percent=100 时进度条必须全满",
        )
        // 怪语义组合：title 显式 null 字面逐字透传
        assertEquals(
            "**null**\n`[" + bar5 + "]` 50%",
            buildBotProgressContent("null", 50),
            "怪语义组合必须逐字透传",
        )
    }

    @Test
    fun `sendProgress wrong-typed known fields fail loudly`() {
        // 对象型 chatId → ?.jsonPrimitive 抛 IllegalArgumentException
        assertFailsWith<IllegalArgumentException>(
            "对象型 chatId 必须大声失败",
        ) {
            parseBotSendProgressFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonObject(mapOf("nested" to JsonPrimitive("x"))),
                    )
                )
            )
        }
        // 数组型 title → ?.jsonPrimitive 抛 IllegalArgumentException
        assertFailsWith<IllegalArgumentException>(
            "数组型 title 必须大声失败",
        ) {
            parseBotSendProgressFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "title" to JsonArray(listOf(JsonPrimitive("a"))),
                    )
                )
            )
        }
        // 对象型 percent → ?.jsonPrimitive 抛 IllegalArgumentException
        assertFailsWith<IllegalArgumentException>(
            "对象型 percent 必须大声失败",
        ) {
            parseBotSendProgressFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "percent" to JsonObject(mapOf("nested" to JsonPrimitive(1))),
                    )
                )
            )
        }
    }
}
