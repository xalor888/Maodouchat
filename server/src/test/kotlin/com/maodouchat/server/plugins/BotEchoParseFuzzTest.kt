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
 * Bot `echo` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，
 * G355 `sendMessage` 起至 `deleteUpdates` 之后第七十二块）。
 *
 * 本测试直接钉住纯函数 ([parseBotEchoFields]) 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 钉住 **text→message 别名链**：`?:` 接在字段存在性上，text 键存在（哪怕显式 null）
 *   不穿透到 message；两键都缺 → `""`（缺省不 400）。
 * - 钉住 **take(500) 不 trim**：前后空白计入上限；600 字符截断为 500。
 * - 钉住 **显式 null 得字面量 `"null"`**：`JsonNull` 是 `JsonPrimitive`，
 *   `.content` 为 `"null"`——不是类型错。
 * - 钉住 **非字符串原语走 content**：数字 → `"123"`、布尔 → `"true"`。
 * - 反证坏类型大声失败：对象 / 数组型在 `?.jsonPrimitive`
 *   处抛 [IllegalArgumentException]（路由层 `StatusPages` 映射为 400「参数无效」，
 *   不是 500）。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
class BotEchoParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "text",
            "message",
        )
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotEchoFields =
        parseBotEchoFields(obj)

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
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?~"
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
        val random = Random(2026100372)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            // 基 payload：text 恒为 "t<i>"（恒等断言只看不被未知键污染）。
            val expected = "t" + i
            base["text"] = JsonPrimitive(expected)
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val fields = parseOf(JsonObject(base))
            assertEquals(expected, fields.text, "未知键不得污染 text，迭代 " + i)
        }
    }

    @Test
    fun textMessageFallback() {
        // text 缺席 → 用 message。
        assertEquals("m1", parseOf(JsonObject(mapOf("message" to JsonPrimitive("m1")))).text)
        // 双在 → text 优先。
        val both = JsonObject(
            mapOf(
                "text" to JsonPrimitive("t1"),
                "message" to JsonPrimitive("m1"),
            )
        )
        assertEquals("t1", parseOf(both).text)
        // 双缺 → ""（缺省不 400）。
        assertEquals("", parseOf(JsonObject(emptyMap())).text)
    }

    @Test
    fun explicitNullDoesNotFallThrough() {
        // text 键存在（显式 null）→ 不穿透到 message，得字面量 "null"。
        val obj = JsonObject(
            mapOf(
                "text" to JsonNull,
                "message" to JsonPrimitive("m1"),
            )
        )
        assertEquals("null", parseOf(obj).text)
        // text 缺席、message 显式 null → 字面量 "null"。
        assertEquals(
            "null",
            parseOf(JsonObject(mapOf("message" to JsonNull))).text,
        )
    }

    @Test
    fun take500TruncationNoTrim() {
        // 600 字符截断为 500。
        val long = "x".repeat(600)
        assertEquals(long.take(500), parseOf(JsonObject(mapOf("text" to JsonPrimitive(long)))).text)
        // message 别名同样截断。
        assertEquals(
            long.take(500),
            parseOf(JsonObject(mapOf("message" to JsonPrimitive(long)))).text,
        )
        // 前后空白计入上限、不 trim：2 空格 + 600 x → 2 空格 + 498 x。
        val padded = "  " + "x".repeat(600)
        val fields = parseOf(JsonObject(mapOf("text" to JsonPrimitive(padded))))
        assertEquals(500, fields.text.length)
        assertTrue(fields.text.startsWith("  "), "前导空白应保留（不 trim）")
    }

    @Test
    fun nonStringPrimitivesUseContent() {
        // 数字 / 布尔型走 .content 字符串（原处理器逐字语义）。
        assertEquals("123", parseOf(JsonObject(mapOf("text" to JsonPrimitive(123)))).text)
        assertEquals("true", parseOf(JsonObject(mapOf("message" to JsonPrimitive(true)))).text)
    }

    @Test
    fun loudFailureOnBadTypes() {
        // 对象 / 数组型在 ?.jsonPrimitive 处抛 IllegalArgumentException
        //（路由层 StatusPages 映射 400，不是 500）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("text" to JsonObject(emptyMap()))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("text" to JsonArray(listOf(JsonPrimitive(1))))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("message" to JsonObject(emptyMap()))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("message" to JsonArray(listOf(JsonPrimitive(1))))))
        }
    }

    @Test
    fun nearMissNamesIgnored() {
        // 近似字段名按未知键忽略，真字段缺席 → ""。
        val obj = JsonObject(
            mapOf(
                "Text" to JsonPrimitive("t1"),
                "TEXT" to JsonPrimitive("t1"),
                "text2" to JsonPrimitive("t1"),
                "Message" to JsonPrimitive("m1"),
                "message2" to JsonPrimitive("m1"),
            )
        )
        assertEquals("", parseOf(obj).text)
    }
}
