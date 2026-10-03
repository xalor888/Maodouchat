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
 * Bot `sendSteps` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，
 * G355 `sendMessage` 起至 `echo` 之后第七十六块）。
 *
 * 本测试直接钉住纯函数 ([parseBotSendStepsFields]) 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 钉住 **chatId 无 trim**：`" c1 "` 原样保留仍通过必填（`isBlank()` 只判空）。
 * - 钉住 **title 缺键才回退 `"Steps"`**：显式 null 得字面量 `"null"`（不回退默认值）；
 *   显式空字符串保留空（也不回退默认值）。
 * - 钉住 **steps 元素静默丢弃规则**：对象/数组元素被丢弃，`JsonNull` 元素得字面量 `"null"`。
 * - 反证坏类型大声失败：title 对象/数组型在 `?.jsonPrimitive` 处、steps 非数组型在
 *   `?.jsonArray` 处抛 [IllegalArgumentException]（路由层 `StatusPages` 映射为 400
 *   「参数无效」，不是 500）。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
class BotSendStepsParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId",
            "title",
            "steps",
        )
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotSendStepsFieldsResult =
        parseBotSendStepsFields(obj)

    private fun okOf(chatId: String, title: JsonElement?, steps: JsonElement?): BotSendStepsFields {
        val map = mutableMapOf<String, JsonElement>()
        map["chatId"] = JsonPrimitive(chatId)
        if (title != null) map["title"] = title
        if (steps != null) map["steps"] = steps
        return (parseOf(JsonObject(map)) as BotSendStepsFieldsResult.Ok).fields
    }

    private fun baseSteps(): JsonArray = JsonArray(
        listOf(JsonPrimitive("one"), JsonPrimitive("two"))
    )

    private fun randomScalar(random: Random): JsonElement = when (random.nextInt(7)) {
        0 -> JsonPrimitive(random.nextBoolean())
        1 -> JsonPrimitive(random.nextInt(-1000, 1000))
        2 -> JsonPrimitive(random.nextDouble(-1000.0, 1000.0))
        3 -> JsonPrimitive(randomString(random, random.nextInt(0, 40)))
        4 -> JsonNull
        5 -> JsonArray(List(random.nextInt(0, 4)) { randomScalar(random) })
        else -> JsonObject(mapOf(randomName(random) to randomScalar(random)))
    }

    private fun randomString(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomName(random: Random): String {
        var name: String
        do {
            name = "fuzz_" + randomString(random, random.nextInt(3, 12)).replace(" ", "_")
        } while (name in KNOWN_FIELD_NAMES)
        return name
    }

    @Test
    fun fuzzUnknownKeysIgnored() {
        val random = Random(2026100376)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            // 基 payload：chatId 恒为 "c<i>"、title 恒为 "Steps"、steps 恒为 ["one", "two"]。
            val expectedChatId = "c" + i
            base["chatId"] = JsonPrimitive(expectedChatId)
            base["title"] = JsonPrimitive("Steps")
            base["steps"] = baseSteps()
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val viaParse = parseOf(JsonObject(base))
            assertTrue(viaParse is BotSendStepsFieldsResult.Ok, "注入未知键后仍应为 Ok，迭代 " + i)
            assertEquals(expectedChatId, viaParse.fields.chatId, "未知键不得污染 chatId，迭代 " + i)
            assertEquals("Steps", viaParse.fields.title, "未知键不得污染 title，迭代 " + i)
            assertEquals(listOf("one", "two"), viaParse.fields.steps, "未知键不得污染 steps，迭代 " + i)
        }
    }

    @Test
    fun requiredSemantics() {
        // chatId 与 steps 双缺 → Invalid。
        assertTrue(
            parseOf(JsonObject(emptyMap())) is BotSendStepsFieldsResult.Invalid,
            "双缺应为 Invalid",
        )
        // chatId 纯空白 → Invalid（无 trim）。
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("   "), "steps" to baseSteps())))
                is BotSendStepsFieldsResult.Invalid,
            "chatId 纯空白应为 Invalid",
        )
        // chatId 合法但 steps 缺席 → Invalid。
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"))))
                is BotSendStepsFieldsResult.Invalid,
            "steps 缺席应为 Invalid",
        )
        // steps 为空数组 → Invalid。
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "steps" to JsonArray(emptyList()))))
                is BotSendStepsFieldsResult.Invalid,
            "steps 为空数组应为 Invalid",
        )
        // 合法 → Ok。
        val fields = okOf("c1", JsonPrimitive("Steps"), baseSteps())
        assertEquals("c1", fields.chatId)
        assertEquals("Steps", fields.title)
        assertEquals(listOf("one", "two"), fields.steps)
    }

    @Test
    fun titleDefaultsOnlyWhenKeyMissing() {
        // title 键缺席 → "Steps"。
        assertEquals("Steps", okOf("c1", null, baseSteps()).title)
        // 显式 null → 字面量 "null"（不回退默认值）。
        assertEquals("null", okOf("c1", JsonNull, baseSteps()).title)
        // 显式空字符串 → 保留空（不回退默认值）。
        assertEquals("", okOf("c1", JsonPrimitive(""), baseSteps()).title)
        // 超长 title 截到 80。
        val long = "t".repeat(120)
        assertEquals(long.take(80), okOf("c1", JsonPrimitive(long), baseSteps()).title)
    }

    @Test
    fun stepsElementRules() {
        // 非 primitive 元素静默丢弃；JsonNull 元素得 "null" 字符串。
        val fields = okOf(
            "c1",
            JsonPrimitive("Steps"),
            JsonArray(
                listOf(
                    JsonPrimitive("keep"),
                    JsonObject(mapOf("a" to JsonPrimitive(1))),
                    JsonArray(listOf(JsonPrimitive(1))),
                    JsonNull,
                )
            )
        )
        assertEquals(listOf("keep", "null"), fields.steps)
        // 逐项 take(160)，再全表 take(20)。
        val longItem = "x".repeat(200)
        val many = okOf(
            "c1",
            JsonPrimitive("Steps"),
            JsonArray(List(30) { JsonPrimitive(longItem) })
        )
        assertEquals(20, many.steps.size)
        assertTrue(many.steps.all { it.length == 160 }, "逐项应截到 160")
    }

    @Test
    fun whitespaceNotTrimmed() {
        // chatId 前后空白原样保留，仍通过必填（isBlank 只判空，不 trim）。
        assertEquals(" c1 ", okOf(" c1 ", JsonPrimitive("Steps"), baseSteps()).chatId)
    }

    @Test
    fun nonStringPrimitivesUseContent() {
        // 数字 / 布尔型走 .content 字符串（原处理器逐字语义）。
        assertEquals("123", okOf("c1", JsonPrimitive(123), baseSteps()).title)
        assertEquals("true", okOf("c1", JsonPrimitive(true), baseSteps()).title)
        val num = parseOf(JsonObject(mapOf("chatId" to JsonPrimitive(123), "steps" to baseSteps())))
        assertTrue(num is BotSendStepsFieldsResult.Ok, "数字型 chatId 应为 Ok")
        assertEquals("123", num.fields.chatId)
    }

    @Test
    fun loudFailureOnWrongType() {
        // chatId 对象 / 数组型在 ?.jsonPrimitive 处抛 IllegalArgumentException。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonObject(mapOf("a" to JsonPrimitive(1))), "steps" to baseSteps())))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonArray(listOf(JsonPrimitive(1))), "steps" to baseSteps())))
        }
        // title 对象 / 数组型在 ?.jsonPrimitive 处抛。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "title" to JsonObject(mapOf("a" to JsonPrimitive(1))), "steps" to baseSteps())))
        }
        // steps 显式 null / 对象 / 字符串在 ?.jsonArray 处抛（JsonNull 不是数组）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "steps" to JsonNull)))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "steps" to JsonObject(mapOf("a" to JsonPrimitive(1))))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "steps" to JsonPrimitive("nope"))))
        }
    }

    @Test
    fun nearMissNamesIgnored() {
        // 近似字段名按未知键忽略；真字段缺席 → Invalid。
        for (name in listOf("ChatId", "CHATID", "chat_id", "chatid", "chatId2", "Titles", "stepz")) {
            val obj = JsonObject(mapOf(name to JsonPrimitive("v1")))
            assertTrue(
                parseOf(obj) is BotSendStepsFieldsResult.Invalid,
                "近似字段名 " + name + " 应被忽略，真字段缺席 → Invalid",
            )
        }
    }
}
