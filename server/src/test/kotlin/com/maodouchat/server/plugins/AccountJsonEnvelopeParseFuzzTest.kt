package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountJsonEnvelopeParseFuzzTest {

    private fun oldWay(body: String): JsonObject? =
        runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()

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
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?~"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomName(random: Random): String =
        "fuzz_" + randomString(random, random.nextInt(3, 12)).replace(" ", "_")

    @Test
    fun fuzzMatchesOldInlineExtraction() {
        val random = Random(2026100414)
        repeat(150) { i ->
            val base = mutableMapOf<String, JsonElement>()
            base["username"] = JsonPrimitive(randomString(random, random.nextInt(0, 30)))
            repeat(random.nextInt(0, 6)) { base[randomName(random)] = randomScalar(random) }
            val body = bodyOf(JsonObject(base))
            assertEquals(
                oldWay(body)?.toString(), parseAccountJsonEnvelopeOrNull(body)?.toString(),
                "信封抽取必须与旧内联写法逐字等价，迭代 " + i,
            )
        }
    }

    @Test
    fun badInputBecomesNull() {
        // 坏 JSON / 顶层非对象 / 空 body → null，下游报 400 "参数无效"。
        assertNull(parseAccountJsonEnvelopeOrNull(""), "空 body 应为 null")
        assertNull(parseAccountJsonEnvelopeOrNull("{oops"), "坏 JSON 应为 null")
        assertNull(parseAccountJsonEnvelopeOrNull("[1, 2]"), "顶层数组应为 null")
        assertNull(parseAccountJsonEnvelopeOrNull("42"), "顶层数字应为 null")
    }
}
