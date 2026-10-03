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

class DeveloperEnabledParseFuzzTest {

    private companion object {
        private val KNOWN_FIELD_NAMES = setOf("enabled")
        private const val ITERATIONS = 150
    }

    private fun bodyOf(obj: JsonObject): String = Json.encodeToString(obj)

    private fun enabledOf(body: String): Boolean? = parseDeveloperBotEnabled(body)

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
        val random = Random(2026100493)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            val expected = (i % 2 == 0)
            base["enabled"] = JsonPrimitive(expected)
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            assertEquals(expected, enabledOf(bodyOf(JsonObject(base))), "未知键不得污染 enabled，迭代 " + i)
        }
    }

    @Test
    fun missingAndBadInputBecomeNull() {
        // 缺席 / 空 body / 坏 JSON / 顶层非对象 → null（下游 400 "enabled required"）。
        assertNull(enabledOf("{}"), "enabled 缺席应为 null")
        assertNull(enabledOf(""), "空 body 应为 null")
        assertNull(enabledOf("{oops"), "坏 JSON 应为 null")
        assertNull(enabledOf("[true]"), "顶层数组应为 null")
    }

    @Test
    fun booleanSemantics() {
        // 布尔字面量与 "true"/"false" 字符串有效（含大小写变体，toBooleanStrictOrNull 逐字语义）。
        assertEquals(true, enabledOf(bodyOf(JsonObject(mapOf("enabled" to JsonPrimitive(true))))), "true 字面量")
        assertEquals(false, enabledOf(bodyOf(JsonObject(mapOf("enabled" to JsonPrimitive(false))))), "false 字面量")
        assertEquals(true, enabledOf(bodyOf(JsonObject(mapOf("enabled" to JsonPrimitive("TRUE"))))), "\"TRUE\" 有效")
        assertNull(enabledOf(bodyOf(JsonObject(mapOf("enabled" to JsonPrimitive("1"))))), "\"1\" 无效")
        assertNull(enabledOf(bodyOf(JsonObject(mapOf("enabled" to JsonPrimitive(1))))), "数字 1 无效")
        assertNull(enabledOf(bodyOf(JsonObject(mapOf("enabled" to JsonNull)))), "显式 null 无效")
    }

    @Test
    fun badTypeSwallowed() {
        // enabled 对象 / 数组型 → ?.jsonPrimitive 抛 → 吞掉 → null。
        assertNull(
            enabledOf(bodyOf(JsonObject(mapOf("enabled" to JsonObject(mapOf("x" to JsonPrimitive(1))))))),
            "对象型 enabled 应被吞为 null",
        )
        assertNull(
            enabledOf(bodyOf(JsonObject(mapOf("enabled" to JsonArray(listOf(JsonPrimitive(true))))))),
            "数组型 enabled 应被吞为 null",
        )
    }
}
