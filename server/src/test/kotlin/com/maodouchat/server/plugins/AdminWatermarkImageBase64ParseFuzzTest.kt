package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class AdminWatermarkImageBase64ParseFuzzTest {

    private fun oldWay(body: String): String = runCatching {
        adminJson.parseToJsonElement(body).jsonObject["imageBase64"]?.jsonPrimitive?.content.orEmpty()
    }.getOrDefault("")

    private fun bodyOf(obj: JsonObject): String = Json.encodeToString(obj)

    private fun randomScalar(random: Random): JsonElement = when (random.nextInt(6)) {
        0 -> JsonPrimitive(random.nextBoolean())
        1 -> JsonPrimitive(random.nextInt(-1000, 1000))
        2 -> JsonPrimitive(randomString(random, random.nextInt(0, 40)))
        3 -> JsonNull
        4 -> JsonArray(List(random.nextInt(0, 4)) { randomScalar(random) })
        else -> JsonObject(mapOf(randomName(random) to randomScalar(random)))
    }

    private fun randomString(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?~+/="
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomName(random: Random): String =
        "fuzz_" + randomString(random, random.nextInt(3, 12)).replace(" ", "_")

    @Test
    fun fuzzMatchesOldInlineExtraction() {
        val random = Random(2026100415)
        repeat(150) { i ->
            val base = mutableMapOf<String, JsonElement>()
            if (random.nextBoolean()) {
                base["imageBase64"] = randomScalar(random)
            }
            repeat(random.nextInt(0, 6)) { base[randomName(random)] = randomScalar(random) }
            val body = bodyOf(JsonObject(base))
            assertEquals(oldWay(body), parseAdminWatermarkImageBase64(body), "抽取必须与旧内联写法逐字等价，迭代 " + i)
        }
    }

    @Test
    fun missingAndBadInputBecomeEmpty() {
        // 缺席 / 坏 JSON / 顶层非对象 → ""，下游判空报 400 "imageBase64_required"。
        assertEquals("", parseAdminWatermarkImageBase64("{}"), "缺席应为空串")
        assertEquals("", parseAdminWatermarkImageBase64(""), "空 body 应为空串")
        assertEquals("", parseAdminWatermarkImageBase64("{oops"), "坏 JSON 应为空串")
        assertEquals("", parseAdminWatermarkImageBase64("[1, 2]"), "顶层数组应为空串")
    }

    @Test
    fun nonPrimitiveSwallowed() {
        // 对象/数组型 → ?.jsonPrimitive 抛 → 吞掉 → ""；显式 null 走 JsonNull.content 得字面量 "null"。
        assertEquals(
            "", parseAdminWatermarkImageBase64(bodyOf(JsonObject(mapOf("imageBase64" to JsonObject(mapOf("x" to JsonPrimitive(1))))))),
            "对象型应被吞为空串",
        )
        assertEquals(
            "", parseAdminWatermarkImageBase64(bodyOf(JsonObject(mapOf("imageBase64" to JsonArray(listOf(JsonPrimitive(1))))))),
            "数组型应被吞为空串",
        )
        assertEquals(
            "null", parseAdminWatermarkImageBase64(bodyOf(JsonObject(mapOf("imageBase64" to JsonNull)))),
            "显式 null 应为字面量",
        )
    }

    @Test
    fun primitiveContentSemantics() {
        // 字符串恒等；数字/布尔走 .content（逐字语义）。
        assertEquals("aGVsbG8=", parseAdminWatermarkImageBase64(bodyOf(JsonObject(mapOf("imageBase64" to JsonPrimitive("aGVsbG8="))))), "字符串恒等")
        assertEquals("123", parseAdminWatermarkImageBase64(bodyOf(JsonObject(mapOf("imageBase64" to JsonPrimitive(123))))), "数字型取 content")
        assertEquals("true", parseAdminWatermarkImageBase64(bodyOf(JsonObject(mapOf("imageBase64" to JsonPrimitive(true))))), "布尔型取 content")
    }
}
