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
import kotlin.test.assertNull

/**
 * Bot `answerCallbackQuery` 请求体手写解析的**模糊兼容性测试**（清单 Q01「协议模型向前/向后
 * 兼容与 fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、
 * `sendSticker`、`sendContact`、`sendVenue`、`sendPoll`、`sendDice`、
 * `sendDiceCustom`、`forwardMessage`/`copyMessage`、`sendNudge`、`sendContactCard`、
 * `sendPollQuiz` 之后**第十八块**）。
 *
 * Bot 路由没有 typed DTO——请求体是 `receiveBoundedTextOrEmpty()` 拿到的原始字符串，
 * 经 `Json.parseToJsonElement(body).jsonObject` 再手写抽取。本轮把原来内联在
 * `/api/bot/answerCallbackQuery` 处理器里的抽取逻辑收敛为纯函数
 * [parseBotAnswerCallbackQueryFields]
 * （生产侧零行为改动：`logCommand` 与响应组装仍在处理器里），本测试直接钉住这个函数的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 反证 `wrong-typed known fields still fail loudly`：手写解析里 `?.jsonPrimitive`
 *   在类型错时抛 [IllegalArgumentException]，路由层 `StatusPages` 把它映射为 400
 *   「参数无效」（不是 500）——坏数据必须大声失败，不能悄悄吞掉。
 *   例外：显式 JSON null 不是类型错（`JsonNull` 本就是 `JsonPrimitive` 的子类型，
 *   `.content` 为 `"null"` 字符串）——原处理器逐字如此，特意钉住。
 * - 钉住别名链（`callbackQueryId` > `id`，`?:` 接在字段存在性上）、双缺省 `""`、
 *   `text` 的 `take(200)` **不 trim** 与缺省 `null`。
 */
class BotAnswerCallbackQueryParseFuzzTest {

    private companion object {
        /** answerCallbackQuery 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("callbackQueryId", "id", "text")
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

    @Test
    fun `answerCallbackQuery parse survives seeded unknown-field fuzz`() {
        val random = Random(0xCA11BAC_1AC)
        repeat(ITERATIONS) { i ->
            val callbackQueryId = "cbq-$i"
            val text = "text-$i"
            val base = JsonObject(
                mapOf(
                    "callbackQueryId" to JsonPrimitive(callbackQueryId),
                    "text" to JsonPrimitive(text),
                )
            )
            val obj = injectUnknownFields(base, random)
            val fields = parseBotAnswerCallbackQueryFields(obj)
            assertEquals(
                BotAnswerCallbackQueryFields(callbackQueryId, text),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
        }
    }

    @Test
    fun `answerCallbackQuery alias chain and defaults are pinned`() {
        // callbackQueryId 优先于 id
        val prio = parseBotAnswerCallbackQueryFields(
            JsonObject(
                mapOf(
                    "callbackQueryId" to JsonPrimitive("main"),
                    "id" to JsonPrimitive("alias"),
                    "text" to JsonPrimitive("hi"),
                )
            )
        )
        assertEquals("main", prio.callbackQueryId, "callbackQueryId 必须优先于 id")
        assertEquals("hi", prio.text)
        // 只有 id 时回退到 id
        val alias = parseBotAnswerCallbackQueryFields(
            JsonObject(mapOf("id" to JsonPrimitive("alias-only")))
        )
        assertEquals("alias-only", alias.callbackQueryId, "缺 callbackQueryId 时必须回退到 id")
        // 双缺省回 ""（不 400——端点只做 ack，无必填）
        val empty = parseBotAnswerCallbackQueryFields(JsonObject(emptyMap()))
        assertEquals("", empty.callbackQueryId, "两键都缺必须回空串")
        assertNull(empty.text, "text 缺失纯函数返回 null（\"\" 的回退在处理器组装响应时）")
        // 近似字段名被忽略（不是别名）
        val approx = parseBotAnswerCallbackQueryFields(
            JsonObject(
                mapOf(
                    "CallbackQueryId" to JsonPrimitive("wrong"),
                    "callbackqueryid" to JsonPrimitive("wrong"),
                    "id" to JsonPrimitive("right"),
                )
            )
        )
        assertEquals("right", approx.callbackQueryId, "近似字段名必须被忽略")
    }

    @Test
    fun `answerCallbackQuery text truncation is pinned without trimming`() {
        // 300 字符 → take(200)，不 trim：前导空格计入上限
        val long = "x".repeat(300)
        val truncated = parseBotAnswerCallbackQueryFields(
            JsonObject(
                mapOf(
                    "callbackQueryId" to JsonPrimitive("c"),
                    "text" to JsonPrimitive(long),
                )
            )
        )
        assertEquals("x".repeat(200), truncated.text, "text 超长必须截断到 200 字符")
        val padded = parseBotAnswerCallbackQueryFields(
            JsonObject(
                mapOf(
                    "callbackQueryId" to JsonPrimitive("c"),
                    "text" to JsonPrimitive("   padded"),
                )
            )
        )
        assertEquals("   padded", padded.text, "text 不得被 trim")
    }

    @Test
    fun `answerCallbackQuery explicit null keeps legacy semantics`() {
        // 「怪」语义钉住：JsonNull.content 为 "null" 字符串——显式 null 的 callbackQueryId
        // 得字面 "null"，且 `?:` 接在字段存在性上所以不穿透到 id（零行为改动）。
        val nullMain = parseBotAnswerCallbackQueryFields(
            JsonObject(
                mapOf(
                    "callbackQueryId" to JsonNull,
                    "id" to JsonPrimitive("alias"),
                )
            )
        )
        assertEquals("null", nullMain.callbackQueryId, "显式 null 的 callbackQueryId 必须得到字面 \"null\"（不穿透到 id）")
        // 显式 null 的 text 得字面 "null"（不是缺省）
        val nullText = parseBotAnswerCallbackQueryFields(
            JsonObject(
                mapOf(
                    "callbackQueryId" to JsonPrimitive("c"),
                    "text" to JsonNull,
                )
            )
        )
        assertEquals("null", nullText.text, "显式 null 的 text 必须得到字面 \"null\"")
        // 显式 null 的 id 在 callbackQueryId 缺失时也得字面 "null"
        val nullAlias = parseBotAnswerCallbackQueryFields(
            JsonObject(mapOf("id" to JsonNull))
        )
        assertEquals("null", nullAlias.callbackQueryId, "显式 null 的 id 必须得到字面 \"null\"")
    }

    @Test
    fun `answerCallbackQuery wrong-typed known fields fail loudly`() {
        // 已知字段类型错 → IllegalArgumentException（路由层 StatusPages 映射 400，不是 500）
        assertFailsWith<IllegalArgumentException>(
            "callbackQueryId 收对象必须大声失败",
        ) {
            parseBotAnswerCallbackQueryFields(
                JsonObject(
                    mapOf(
                        "callbackQueryId" to JsonObject(mapOf("v" to JsonPrimitive("c"))),
                        "text" to JsonPrimitive("hi"),
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException>(
            "id 收数组必须大声失败",
        ) {
            parseBotAnswerCallbackQueryFields(
                JsonObject(
                    mapOf("id" to JsonArray(listOf(JsonPrimitive("c"))))
                )
            )
        }
        assertFailsWith<IllegalArgumentException>(
            "text 收对象必须大声失败",
        ) {
            parseBotAnswerCallbackQueryFields(
                JsonObject(
                    mapOf(
                        "callbackQueryId" to JsonPrimitive("c"),
                        "text" to JsonObject(mapOf("v" to JsonPrimitive("hi"))),
                    )
                )
            )
        }
    }
}
