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

class BotEnabledParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("enabled")
        private const val ITERATIONS = 150
    }

    private fun bodyOf(obj: JsonObject): String = Json.encodeToString(obj)

    private fun enabledOf(body: String): Boolean? = parseManagementBotEnabled(body)

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
        val random = Random(2026100381)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            val expected = (i % 2 == 0)
            base["enabled"] = JsonPrimitive(expected)
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val got = enabledOf(bodyOf(JsonObject(base)))
            assertEquals(
                expected, got,
                "seed iteration " + i + ": unknown keys must not change enabled",
            )
        }
    }

    @Test
    fun missingSemantics() {
        // 缺席 / 空 body / 空对象 → null（处理器报 400 "enabled required"）。
        assertNull(enabledOf(bodyOf(JsonObject(emptyMap()))), "missing enabled -> null")
        assertNull(enabledOf(""), "empty body -> null")
        assertNull(enabledOf(bodyOf(JsonObject(mapOf("other" to JsonPrimitive(1))))), "no enabled -> null")
    }

    @Test
    fun booleanLiterals() {
        assertEquals(true, enabledOf(bodyOf(JsonObject(mapOf("enabled" to JsonPrimitive(true))))), "true literal")
        assertEquals(false, enabledOf(bodyOf(JsonObject(mapOf("enabled" to JsonPrimitive(false))))), "false literal")
    }

    @Test
    fun strictStringBooleans() {
        // "true"/"false" 有效（toBooleanStrictOrNull 大小写不敏感，"TRUE"/"True" 同样有效——
        // 与原处理器逐字语义一致，生产代码无辜，错的是旧测试期望）。
        assertEquals(true, enabledOf(bodyOf(JsonObject(mapOf("enabled" to JsonPrimitive("true"))))), "\"true\" string")
        assertEquals(false, enabledOf(bodyOf(JsonObject(mapOf("enabled" to JsonPrimitive("false"))))), "\"false\" string")
        assertEquals(true, enabledOf(bodyOf(JsonObject(mapOf("enabled" to JsonPrimitive("TRUE"))))), "\"TRUE\" -> true")
        assertEquals(true, enabledOf(bodyOf(JsonObject(mapOf("enabled" to JsonPrimitive("True"))))), "\"True\" -> true")
        assertNull(enabledOf(bodyOf(JsonObject(mapOf("enabled" to JsonPrimitive("1"))))), "\"1\" -> null")
        assertNull(enabledOf(bodyOf(JsonObject(mapOf("enabled" to JsonPrimitive(""))))), "empty string -> null")
        assertNull(enabledOf(bodyOf(JsonObject(mapOf("enabled" to JsonPrimitive("yes"))))), "\"yes\" -> null")
        // 数字型走 .content：content 为 "1" → toBooleanStrictOrNull 为 null。
        assertNull(enabledOf(bodyOf(JsonObject(mapOf("enabled" to JsonPrimitive(1))))), "number 1 -> null")
        assertNull(enabledOf(bodyOf(JsonObject(mapOf("enabled" to JsonPrimitive(0))))), "number 0 -> null")
    }

    @Test
    fun explicitNullIsNotAccepted() {
        // 与 webhook 管理块不同：显式 null 在本端点直接 → null（400），
        // 而不是字面量 "null" 字符串继续往下游走。
        assertNull(enabledOf(bodyOf(JsonObject(mapOf("enabled" to JsonNull)))), "explicit null -> null")
    }

    @Test
    fun badJsonAndNonObjectSwallowed() {
        // 坏 JSON 与顶层非对象被吞 → null（处理器统一 400 "enabled required"，
        // 本端点没有 "invalid json" 判定，逐字怪语义）。
        assertNull(enabledOf("{"), "truncated json -> null")
        assertNull(enabledOf("{enabled: true}"), "unquoted key -> null")
        assertNull(enabledOf("[true]"), "top-level array -> null")
        assertNull(enabledOf("\"enabled\""), "top-level string -> null")
        assertNull(enabledOf("42"), "top-level number -> null")
    }

    @Test
    fun badTypeSwallowedNotLoud() {
        // enabled 为对象/数组型：?.jsonPrimitive 处抛 → 被吞 → null（大声失败的反证）。
        assertNull(
            enabledOf(bodyOf(JsonObject(mapOf("enabled" to JsonObject(emptyMap()))))),
            "object enabled -> null",
        )
        assertNull(
            enabledOf(bodyOf(JsonObject(mapOf("enabled" to JsonArray(listOf(JsonPrimitive(true))))))),
            "array enabled -> null",
        )
    }

    @Test
    fun nearMissFieldNamesIgnored() {
        // 近似字段名不被识别 → 等同缺席 → null。
        for (name in listOf("Enabled", "ENABLED", "enabled2", "enable", "isEnabled")) {
            assertNull(
                enabledOf(bodyOf(JsonObject(mapOf(name to JsonPrimitive(true))))),
                "near-miss " + name + " ignored -> null",
            )
        }
        // 但真字段混在近似字段里仍被识别。
        val withNoise = JsonObject(
            mapOf(
                "Enabled" to JsonPrimitive(false),
                "enabled" to JsonPrimitive(true),
                "ENABLED" to JsonPrimitive(false),
            ),
        )
        assertEquals(true, enabledOf(bodyOf(withNoise)), "real enabled wins among near-misses")
    }
}
