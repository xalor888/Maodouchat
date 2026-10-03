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

class AuthTotpCodeParseFuzzTest {

    private companion object {
        private val KNOWN_FIELD_NAMES = setOf("code")
        private const val ITERATIONS = 150
    }

    private fun bodyOf(obj: JsonObject): String = Json.encodeToString(obj)

    private fun codeOf(body: String): String = parseAuthTotpCode(body)

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
        val random = Random(2026100491)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            val expected = "9".repeat(6) + i
            base["code"] = JsonPrimitive(expected)
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            assertEquals(expected, codeOf(bodyOf(JsonObject(base))), "未知键不得污染 code，迭代 " + i)
        }
    }

    @Test
    fun missingAndBadInputBecomeEmpty() {
        // 缺席 / 空 body / 坏 JSON / 顶层非对象 → ""（下游 MfaService 验不过，400）。
        assertEquals("", codeOf("{}"), "code 缺席应为空串")
        assertEquals("", codeOf(""), "空 body 应为空串")
        assertEquals("", codeOf("{oops"), "坏 JSON 应为空串")
        assertEquals("", codeOf("[1, 2]"), "顶层数组应为空串")
    }

    @Test
    fun badTypeSwallowed() {
        // code 为对象/数组型 → ?.jsonPrimitive 抛 → 吞掉 → ""（逐字怪语义）。
        assertEquals(
            "", codeOf(bodyOf(JsonObject(mapOf("code" to JsonObject(mapOf("x" to JsonPrimitive(1))))))),
            "对象型 code 应被吞为空串",
        )
        assertEquals(
            "", codeOf(bodyOf(JsonObject(mapOf("code" to JsonArray(listOf(JsonPrimitive(1))))))),
            "数组型 code 应被吞为空串",
        )
    }

    @Test
    fun primitiveContentSemantics() {
        // 数字/布尔型走 .content（逐字语义，不抛）。
        assertEquals("123456", codeOf(bodyOf(JsonObject(mapOf("code" to JsonPrimitive(123456))))), "数字型取 content")
        assertEquals("true", codeOf(bodyOf(JsonObject(mapOf("code" to JsonPrimitive(true))))), "布尔型取 content")
        // 显式 null → 字面量 "null"（JsonNull 是 JsonPrimitive，下游验不过）。
        assertEquals("null", codeOf(bodyOf(JsonObject(mapOf("code" to JsonNull)))), "显式 null 应为字面量")
    }
}
