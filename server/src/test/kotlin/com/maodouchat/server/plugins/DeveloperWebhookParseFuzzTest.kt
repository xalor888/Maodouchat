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

class DeveloperWebhookParseFuzzTest {

    private companion object {
        private val KNOWN_FIELD_NAMES = setOf("url")
        private const val ITERATIONS = 150
    }

    private fun bodyOf(obj: JsonObject): String = Json.encodeToString(obj)

    private fun urlOf(body: String): String? = parseDeveloperWebhookUrl(body)

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
        val random = Random(2026100492)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            val expected = "u" + i
            base["url"] = JsonPrimitive(expected)
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            assertEquals(expected, urlOf(bodyOf(JsonObject(base))), "未知键不得污染 url，迭代 " + i)
        }
    }

    @Test
    fun missingAndBadInputBecomeNull() {
        // 缺席 / 空 body / 坏 JSON / 顶层非对象 → null（下游跳过白名单校验）。
        assertNull(urlOf("{}"), "url 缺席应为 null")
        assertNull(urlOf(""), "空 body 应为 null")
        assertNull(urlOf("{oops"), "坏 JSON 应为 null")
        assertNull(urlOf("[1, 2]"), "顶层数组应为 null")
    }

    @Test
    fun badTypeSwallowed() {
        // url 对象 / 数组型 → ?.jsonPrimitive 抛 → 吞掉 → null。
        assertNull(
            urlOf(bodyOf(JsonObject(mapOf("url" to JsonObject(mapOf("x" to JsonPrimitive(1))))))),
            "对象型 url 应被吞为 null",
        )
        assertNull(
            urlOf(bodyOf(JsonObject(mapOf("url" to JsonArray(listOf(JsonPrimitive(1))))))),
            "数组型 url 应被吞为 null",
        )
    }

    @Test
    fun trimBeforeTake() {
        // 前后空白先 trim，再截断到 500（逐字语义）。
        assertEquals(
            "https://example.com/hook",
            urlOf(bodyOf(JsonObject(mapOf("url" to JsonPrimitive("  https://example.com/hook  "))))),
            "前后空白应先 trim",
        )
        assertEquals("", urlOf(bodyOf(JsonObject(mapOf("url" to JsonPrimitive("   "))))), "纯空白应得空串")
        val long = "a".repeat(490) + " ".repeat(20)
        assertEquals("a".repeat(490), urlOf(bodyOf(JsonObject(mapOf("url" to JsonPrimitive(long))))), "trim 先于 take(500)")
    }
}
