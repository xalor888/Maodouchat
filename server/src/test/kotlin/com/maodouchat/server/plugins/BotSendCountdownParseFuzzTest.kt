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
 * Bot `sendCountdown` 请求体手写解析的**模糊兼容性测试**（清单 Q01「协议模型向前/向后兼容与
 * fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、
 * `sendContact`、`sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、
 * `forwardMessage`/`copyMessage`、`sendNudge`、`sendContactCard`、`sendPollQuiz`、
 * `answerCallbackQuery`、`sendChecklist`、`sendAlert` 之后第二十一块）。
 *
 * Bot 路由没有 typed DTO——请求体是 `receiveBoundedTextOrEmpty()` 拿到的原始字符串，
 * 经 `Json.parseToJsonElement(body).jsonObject` 再手写抽取。本轮把原来内联在
 * `/api/bot/sendCountdown` 处理器里的抽取 / 校验 / 内容组装逻辑收敛为纯函数
 * ([parseBotSendCountdownFields] / [buildBotCountdownContent])
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
 * - 钉住 `title` 的 `.orEmpty().ifBlank { "Countdown" }.take(40)` 逐字顺序：缺省/空白回
 *   `"Countdown"`，显式 JSON null 得字面 `"null"`（非空白，`ifBlank` 不触发），
 *   超长先回退默认再截 40，**不 trim**（前导空格计入上限，原处理器逐字语义）。
 * - 钉住 `seconds` 的 `(obj["seconds"]?.jsonPrimitive?.content?.toIntOrNull() ?: 60).coerceIn(5, 86400)`：
 *   非整数字符串（`"abc"`、浮点 `"30.5"`）`toIntOrNull()` 回 `null`→**回退 60** 不报错；
 *   显式 JSON null 得字面 `"null"`→60；负数/超界被夹回 `[5, 86400]`；边界 5 与 86400 保留。
 * - 钉住单必填（`chatId` 缺或空白 → 400），`title`/`seconds` 非必填（回默认值）。
 * - 钉住内容模板形状（`"**$title**\n`T-${seconds}s`"`，`type = "MARKDOWN"` 由处理器固定）。
 * - 反证 `wrong-typed known fields still fail loudly`：手写解析里 `?.jsonPrimitive`
 *   在类型错时抛 [IllegalArgumentException]，路由层 `StatusPages` 把它映射为 400
 *   「参数无效」（不是 500）——坏数据必须大声失败，不能悄悄吞掉。
 */
class BotSendCountdownParseFuzzTest {

    private companion object {
        /** sendCountdown 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId", "title", "seconds",
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

    private fun fieldsOf(obj: JsonObject): BotSendCountdownFields {
        val result = parseBotSendCountdownFields(obj)
        assertTrue(result is BotSendCountdownFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    private fun validBase(
        chatId: String = "c1",
        title: String = "上线倒计时",
        seconds: Int = 3600,
    ): JsonObject = JsonObject(
        mapOf(
            "chatId" to JsonPrimitive(chatId),
            "title" to JsonPrimitive(title),
            "seconds" to JsonPrimitive(seconds),
        )
    )

    @Test
    fun `sendCountdown parse survives seeded unknown-field fuzz`() {
        val random = Random(0xCD_2026)
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val title = "title-$i"
            val seconds = random.nextInt(5, 86401)
            val base = JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive(chatId),
                    "title" to JsonPrimitive(title),
                    "seconds" to JsonPrimitive(seconds),
                )
            )
            val obj = injectUnknownFields(base, random)
            val fields = fieldsOf(obj)
            assertEquals(
                BotSendCountdownFields(chatId, title, seconds),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
        }
    }

    @Test
    fun `sendCountdown required fields are enforced`() {
        // chatId 缺失 → MissingRequired
        assertTrue(
            parseBotSendCountdownFields(
                JsonObject(
                    mapOf(
                        "title" to JsonPrimitive("t"),
                        "seconds" to JsonPrimitive(30),
                    )
                )
            ) is BotSendCountdownFieldsResult.MissingRequired,
            "chatId 缺失必须判缺",
        )
        // chatId 空白 → MissingRequired
        assertTrue(
            parseBotSendCountdownFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("   "),
                        "title" to JsonPrimitive("t"),
                        "seconds" to JsonPrimitive(30),
                    )
                )
            ) is BotSendCountdownFieldsResult.MissingRequired,
            "chatId 空白必须判缺",
        )
        // title 缺失 → 不判缺，回 "Countdown"
        assertEquals(
            BotSendCountdownFields("c1", "Countdown", 30),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "seconds" to JsonPrimitive(30),
                    )
                )
            ),
            "title 缺省必须不判缺、回默认值",
        )
        // title 空白 → 不判缺，回 "Countdown"
        assertEquals(
            BotSendCountdownFields("c1", "Countdown", 30),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "title" to JsonPrimitive("  "),
                        "seconds" to JsonPrimitive(30),
                    )
                )
            ),
            "title 空白必须不判缺、回默认值",
        )
        // seconds 缺失 → 不判缺，回 60
        assertEquals(
            BotSendCountdownFields("c1", "Countdown", 60),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                    )
                )
            ),
            "seconds 缺省必须不判缺、回 60",
        )
    }

    @Test
    fun `sendCountdown title legacy quirks are pinned`() {
        // 不 trim：前导空格原样保留并计入 40 上限
        // 注：Kotlin 2.4.0（K2）下模板内 ${\"…\"} 转义引号报 Syntax error，
        // 故把 repeat 提到模板外，避免模板内嵌套引号。
        val xs = "x".repeat(36)
        val leading = "  倒计时 $xs"
        val expected = leading.take(40)
        assertEquals(
            BotSendCountdownFields("c1", expected, 30),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "title" to JsonPrimitive(leading),
                        "seconds" to JsonPrimitive(30),
                    )
                )
            ),
            "title 不 trim、前导空格计入 take(40) 上限",
        )
        // 超长先回退默认再截 40（缺省语义下本例直接走 take）
        val long = "y".repeat(100)
        assertEquals(
            "y".repeat(40),
            fieldsOf(validBase(title = long)).title,
            "title 超长先回退默认再截 40",
        )
        // 显式 JSON null 得字面 "null"，不穿透、ifBlank 不触发
        assertEquals(
            BotSendCountdownFields("c1", "null", 30),
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "title" to JsonNull,
                        "seconds" to JsonPrimitive(30),
                    )
                )
            ),
            "title 显式 JSON null 必须得字面 \"null\"、不回默认值",
        )
    }

    @Test
    fun `sendCountdown seconds legacy semantics are pinned`() {
        // 非整数字符串 toIntOrNull 回 null → 回退 60，不报错
        assertEquals(60, fieldsOf(validBase(seconds = 0).let {
            JsonObject(it + mapOf("seconds" to JsonPrimitive("abc")))
        }).seconds, "seconds 非整数字符串必须回退 60 不报错")
        // 浮点字符串同样 toIntOrNull 失败 → 60
        assertEquals(60, fieldsOf(
            JsonObject(validBase() + mapOf("seconds" to JsonPrimitive("30.5")))
        ).seconds, "seconds 浮点字符串必须回退 60")
        // 显式 JSON null → 字面 "null" → toIntOrNull 失败 → 60
        assertEquals(60, fieldsOf(
            JsonObject(validBase() + mapOf("seconds" to JsonNull))
        ).seconds, "seconds 显式 JSON null 必须回退 60")
        // 负数夹回下界 5
        assertEquals(5, fieldsOf(
            JsonObject(validBase() + mapOf("seconds" to JsonPrimitive(-100)))
        ).seconds, "seconds 负数必须夹回 5")
        // 0 夹回下界 5
        assertEquals(5, fieldsOf(
            JsonObject(validBase() + mapOf("seconds" to JsonPrimitive(0)))
        ).seconds, "seconds 0 必须夹回 5")
        // 超界夹回上界 86400
        assertEquals(86400, fieldsOf(
            JsonObject(validBase() + mapOf("seconds" to JsonPrimitive(99999999)))
        ).seconds, "seconds 超界必须夹回 86400")
        // 边界 5 与 86400 保留
        assertEquals(5, fieldsOf(
            JsonObject(validBase() + mapOf("seconds" to JsonPrimitive(5)))
        ).seconds, "seconds 5 必须保留")
        assertEquals(86400, fieldsOf(
            JsonObject(validBase() + mapOf("seconds" to JsonPrimitive(86400)))
        ).seconds, "seconds 86400 必须保留")
        // 正常整数值通过
        assertEquals(1234, fieldsOf(
            JsonObject(validBase() + mapOf("seconds" to JsonPrimitive(1234)))
        ).seconds, "seconds 正常整数值必须通过")
    }

    @Test
    fun `sendCountdown content template is pinned`() {
        assertEquals(
            "**上线倒计时**\n`T-3600s`",
            buildBotCountdownContent("上线倒计时", 3600),
            "内容模板必须与原处理器逐字一致",
        )
        assertEquals(
            "**Countdown**\n`T-60s`",
            buildBotCountdownContent("Countdown", 60),
            "默认 title + 默认 seconds 的内容模板",
        )
    }

    @Test
    fun `sendCountdown wrong-typed known fields fail loudly`() {
        // 对象型 chatId → IllegalArgumentException（StatusPages 映射 400，不是 500）
        assertFailsWith<IllegalArgumentException> {
            parseBotSendCountdownFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonObject(mapOf("x" to JsonPrimitive(1))),
                        "title" to JsonPrimitive("t"),
                        "seconds" to JsonPrimitive(30),
                    )
                )
            )
        }
        // 数组型 title → IllegalArgumentException
        assertFailsWith<IllegalArgumentException> {
            parseBotSendCountdownFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "title" to JsonArray(listOf(JsonPrimitive("t"))),
                        "seconds" to JsonPrimitive(30),
                    )
                )
            )
        }
        // 对象型 seconds → IllegalArgumentException
        assertFailsWith<IllegalArgumentException> {
            parseBotSendCountdownFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "title" to JsonPrimitive("t"),
                        "seconds" to JsonObject(mapOf("x" to JsonPrimitive(1))),
                    )
                )
            )
        }
        // 数组型 seconds → IllegalArgumentException
        assertFailsWith<IllegalArgumentException> {
            parseBotSendCountdownFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "title" to JsonPrimitive("t"),
                        "seconds" to JsonArray(listOf(JsonPrimitive(30))),
                    )
                )
            )
        }
    }
}
