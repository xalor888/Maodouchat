package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotManagementWebhookParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("url")
        private const val ITERATIONS = 150
    }

    private fun bodyOf(obj: JsonObject): String = Json.encodeToString(obj)

    private fun urlOf(body: String): String? = parseManagementWebhookUrl(body)

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
        val random = Random(2026100380)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            // 基 payload：url 恒为 "u<i>"（恒等断言只看不被未知键污染）。
            val expected = "u" + i
            base["url"] = JsonPrimitive(expected)
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val url = urlOf(bodyOf(JsonObject(base)))
            assertEquals(expected, url, "未知键不得污染 url，迭代 " + i)
        }
    }

    @Test
    fun missingSemantics() {
        // url 缺席 → null（原处理器逐字语义）。
        assertNull(urlOf("{}"), "url 缺席应为 null")
        assertNull(urlOf(bodyOf(JsonObject(mapOf("fuzz_other" to JsonPrimitive("x"))))), "url 缺席应为 null")
        // 空 body → parse 失败被吞 → null。
        assertNull(urlOf(""), "空 body 应为 null")
    }

    @Test
    fun badJsonAndNonObjectSwallowed() {
        // 坏 JSON / 顶层非对象 → runCatching 吞掉 → null（逐字怪语义，下游即清空 webhook）。
        listOf(
            "{oops",
            "[1, 2]",
            "\"str\"",
            "123",
            "true",
            "null",
        ).forEach { bad ->
            assertNull(urlOf(bad), "坏 JSON / 非对象顶层应被吞为 null，body 为 " + bad)
        }
    }

    @Test
    fun explicitNullIsLiteral() {
        // url 显式 null → 字面量 "null"（逐字怪语义，非空，照常进白名单校验）。
        val url = urlOf(bodyOf(JsonObject(mapOf("url" to JsonNull))))
        assertEquals("null", url, "url 显式 null 应为字面量而非 null")
    }

    @Test
    fun badTypeSwallowedNotLoud() {
        // url 对象 / 数组型 → ?.jsonPrimitive 处抛 → 外层 runCatching 吞掉 → null。
        // 注意：与 Bot API /api/bot/setWebhook 的大声失败不同，本端点逐字语义是吞掉。
        listOf(
            JsonObject(mapOf("x" to JsonPrimitive(1))),
            JsonArray(listOf(JsonPrimitive(1))),
        ).forEach { badValue ->
            val url = urlOf(bodyOf(JsonObject(mapOf("url" to badValue))))
            assertNull(url, "url 坏类型应被吞为 null 而非抛异常")
        }
    }

    @Test
    fun trimBeforeTake() {
        // 前后空白先 trim（原处理器逐字语义）。
        val padded = urlOf(bodyOf(JsonObject(mapOf("url" to JsonPrimitive("  https://example.com/hook  ")))))
        assertEquals("https://example.com/hook", padded, "前后空白应先 trim")
        // 纯空白 → ""（下游 isNullOrBlank 跳过白名单校验→原样清空 webhook）。
        val blank = urlOf(bodyOf(JsonObject(mapOf("url" to JsonPrimitive("   ")))))
        assertEquals("", blank, "纯空白应得空串")
        // trim 先于 take(500)：尾部空白先被 trim 掉，再截断。
        val trailing = "a".repeat(490) + " ".repeat(20)
        val trimmed = urlOf(bodyOf(JsonObject(mapOf("url" to JsonPrimitive(trailing)))))
        assertEquals(490, trimmed?.length, "trim 应在 take(500) 之前")
        // 超长 URL → 截断为 500（trim 之后）。
        val long = "  " + "b".repeat(600) + "  "
        val truncated = urlOf(bodyOf(JsonObject(mapOf("url" to JsonPrimitive(long)))))
        assertEquals(500, truncated?.length, "超长 URL 应截断为 500")
        assertTrue(truncated!!.all { it == 'b' }, "截断后内容应全为 b")
    }

    @Test
    fun nonStringPrimitiveContent() {
        // 非字符串 primitive 走 .content：数字/布尔原样通过（原处理器逐字语义）。
        assertEquals("123", urlOf(bodyOf(JsonObject(mapOf("url" to JsonPrimitive(123))))))
        assertEquals("true", urlOf(bodyOf(JsonObject(mapOf("url" to JsonPrimitive(true))))))
        assertEquals("45.6", urlOf(bodyOf(JsonObject(mapOf("url" to JsonPrimitive(45.6))))))
    }

    @Test
    fun nearMissFieldNamesIgnored() {
        // 近似字段名仍按未知键忽略：真实 url 缺席 → null。
        val url = urlOf(bodyOf(JsonObject(mapOf(
            "URL" to JsonPrimitive("https://evil.example/"),
            "Url" to JsonPrimitive("https://evil.example/"),
            "urls" to JsonPrimitive("https://evil.example/"),
            "webhook_url" to JsonPrimitive("https://evil.example/"),
        ))))
        assertNull(url, "近似字段名应按未知键忽略，url 缺席应为 null")
    }
}
