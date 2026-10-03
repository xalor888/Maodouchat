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

class SealedSenderCertificateParseFuzzTest {

    private companion object {
        private val KNOWN_FIELD_NAMES = setOf("certificate")
        private const val ITERATIONS = 150
    }

    private fun bodyOf(obj: JsonObject): String = Json.encodeToString(obj)

    private fun certOf(body: String): String = parseSealedSenderCertificate(body)

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
        val random = Random(2026100495)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            val expected = "cert-" + i
            base["certificate"] = JsonPrimitive(expected)
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            assertEquals(expected, certOf(bodyOf(JsonObject(base))), "未知键不得污染 certificate，迭代 " + i)
        }
    }

    @Test
    fun missingAndBadInputBecomeEmpty() {
        // 缺席 / 空 body / 坏 JSON / 顶层非对象 → ""（下游 verify 判失败，ok=false）。
        assertEquals("", certOf("{}"), "certificate 缺席应为空串")
        assertEquals("", certOf(""), "空 body 应为空串")
        assertEquals("", certOf("{oops"), "坏 JSON 应为空串")
        assertEquals("", certOf("\"cert\""), "顶层字符串应为空串")
    }

    @Test
    fun badTypeSwallowed() {
        assertEquals(
            "", certOf(bodyOf(JsonObject(mapOf("certificate" to JsonObject(mapOf("x" to JsonPrimitive(1))))))),
            "对象型 certificate 应被吞为空串",
        )
        assertEquals(
            "", certOf(bodyOf(JsonObject(mapOf("certificate" to JsonArray(listOf(JsonPrimitive(1))))))),
            "数组型 certificate 应被吞为空串",
        )
    }
}
