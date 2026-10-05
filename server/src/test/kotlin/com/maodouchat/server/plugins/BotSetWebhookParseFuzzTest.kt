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

class BotSetWebhookParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "url"
        )
        private const val ITERATIONS = 150
        private const val BASE_URL_PREFIX = "https://example.com/hook"
    }

    /** 生产侧白名单的测试替身：只放行固定前缀，并记录收到的全部输入。 */
    private class RecordingAllowList {
        val seen = mutableListOf<String>()
        fun check(url: String): Boolean {
            seen.add(url)
            return url.startsWith(BASE_URL_PREFIX)
        }
    }

    private fun parseOf(obj: JsonObject, allow: (String) -> Boolean = { true }): BotSetWebhookFieldsResult =
        parseBotSetWebhookFields(obj, allow)

    private fun okOf(obj: JsonObject, allow: (String) -> Boolean = { true }): BotSetWebhookFields =
        (parseOf(obj, allow) as BotSetWebhookFieldsResult.Ok).fields

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
        val random = Random(2026100370)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            // 基 payload：url 恒为固定前缀 + 序号（白名单确定性放行，恒等断言只看不被污染）。
            val expected = BASE_URL_PREFIX + i
            base["url"] = JsonPrimitive(expected)
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val allow = RecordingAllowList()
            val fields = okOf(JsonObject(base), allow::check)
            assertEquals(expected, fields.url, "未知键不得污染 url，迭代 " + i)
            assertEquals(listOf(expected), allow.seen, "校验器应恰好收到截断前的 url，迭代 " + i)
        }
    }

    @Test
    fun missingUrlClearsWebhook() {
        // url 缺席 → Ok(null)：下游 setWebhookByToken(bot.id, null) 清 webhook。
        val fields = okOf(JsonObject(emptyMap()))
        assertEquals(null, fields.url, "缺席 url 应为 Ok(null)")
        // 近似字段名按未知键忽略，同样 Ok(null)。
        val nearMiss = JsonObject(
            mapOf(
                "URL" to JsonPrimitive("https://example.com/hook0"),
                "Url" to JsonPrimitive("https://example.com/hook0"),
                "webhook_url" to JsonPrimitive("https://example.com/hook0"),
            )
        )
        val nearFields = okOf(nearMiss)
        assertEquals(null, nearFields.url, "近似字段名不得被识别为 url")
    }

    @Test
    fun trimBeforeTake500() {
        val allow = RecordingAllowList()
        // 前后空白先去。
        val padded = okOf(JsonObject(mapOf("url" to JsonPrimitive("  https://example.com/hook9  "))), allow::check)
        assertEquals("https://example.com/hook9", padded.url, "前后空白应先 trim")
        // 600 字符先 trim 再截断为 500，白名单看到截断后的值。
        // 注意：输入必须以 BASE_URL_PREFIX 开头（与 RecordingAllowList 的放行前缀一致），
        // 否则白名单裁决会让 parseOf 返回 InvalidUrl，okOf 的强制 cast 先炸（2026-10-03 CI 已踩一次）。
        val long = BASE_URL_PREFIX + "x".repeat(600 - BASE_URL_PREFIX.length)
        assertEquals(600, long.length, "前置条件：测试输入为 600 字符")
        val truncated = okOf(JsonObject(mapOf("url" to JsonPrimitive(long))), allow::check)
        assertEquals(500, truncated.url!!.length, "超长 url 应截断为 500 字符")
        assertEquals(long.take(500), truncated.url, "截断应为前 500 字符")
        assertEquals(long.take(500), allow.seen.last(), "校验器应收到截断后的值")
    }

    @Test
    fun blankUrlSkipsAllowCheck() {
        val allow = RecordingAllowList()
        val empty = okOf(JsonObject(mapOf("url" to JsonPrimitive(""))), allow::check)
        assertEquals("", empty.url, "空字符串应 Ok 原样")
        val spaces = okOf(JsonObject(mapOf("url" to JsonPrimitive("   "))), allow::check)
        assertEquals("", spaces.url, "纯空白 trim 后为空，应 Ok")
        assertTrue(allow.seen.isEmpty(), "空/纯空白不得调用白名单校验器")
    }

    @Test
    fun allowListVerdict() {
        // 白名单放行 → Ok；不放行 → InvalidUrl。
        val okFields = okOf(
            JsonObject(mapOf("url" to JsonPrimitive("https://example.com/hook1"))),
            RecordingAllowList()::check,
        )
        assertEquals("https://example.com/hook1", okFields.url, "放行 url 应 Ok")
        val rejected = parseOf(
            JsonObject(mapOf("url" to JsonPrimitive("https://evil.example/hook"))),
            RecordingAllowList()::check,
        )
        assertTrue(rejected is BotSetWebhookFieldsResult.InvalidUrl, "未放行 url 应为 InvalidUrl")
    }

    @Test
    fun explicitNullGoesThroughAllowCheck() {
        val allow = RecordingAllowList()
        // 显式 JSON null → 字面量 "null"（非空）→ 走白名单校验；这里放行以便断言抽取值。
        val fields = okOf(JsonObject(mapOf("url" to JsonNull))) { url -> allow.check(url); true }
        assertEquals("null", fields.url, "显式 null 应得字面量 null")
        assertEquals(listOf("null"), allow.seen, "校验器应收到字面量 null")
        val rejected = parseOf(JsonObject(mapOf("url" to JsonNull)), RecordingAllowList()::check)
        assertTrue(rejected is BotSetWebhookFieldsResult.InvalidUrl, "字面量 null 未放行应为 InvalidUrl")
    }

    @Test
    fun loudFailOnObjectOrArrayUrl() {
        // 对象/数组型在 ?.jsonPrimitive 处抛 IllegalArgumentException（大声失败），
        // 即使白名单会放行也先抛，不走 InvalidUrl。
        val objUrl = JsonObject(mapOf("url" to JsonObject(mapOf("x" to JsonPrimitive(1)))))
        assertFailsWith<IllegalArgumentException>("对象型 url 应大声失败") {
            parseOf(objUrl) { true }
        }
        val arrUrl = JsonObject(mapOf("url" to JsonArray(listOf(JsonPrimitive("https://example.com/hook1")))))
        assertFailsWith<IllegalArgumentException>("数组型 url 应大声失败") {
            parseOf(arrUrl) { true }
        }
    }
}
