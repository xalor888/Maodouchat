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

class BotInboxParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("text", "botId")
        private const val ITERATIONS = 150
    }

    private fun fieldsOf(obj: JsonObject): BotInboxFields = parseBotInboxFields(obj)

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
        val random = Random(2026100382)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            val expectedText = "/cmd" + i
            val expectedHint = if (i % 2 == 0) "bot_" + i else null
            base["text"] = JsonPrimitive(expectedText)
            if (expectedHint != null) base["botId"] = JsonPrimitive(expectedHint)
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val got = fieldsOf(JsonObject(base))
            assertEquals(
                expectedText, got.text,
                "seed iteration " + i + ": unknown keys must not change text",
            )
            assertEquals(
                expectedHint, got.botIdHint,
                "seed iteration " + i + ": unknown keys must not change botIdHint",
            )
        }
    }

    @Test
    fun missingSemantics() {
        // 缺席：text → ""（orEmpty），botId → null。
        val missing = fieldsOf(JsonObject(emptyMap()))
        assertEquals("", missing.text, "missing text -> empty string")
        assertNull(missing.botIdHint, "missing botId -> null")
        // 空串 text 照原样保留（orEmpty 只管 null，不 trim）。
        val emptyText = fieldsOf(JsonObject(mapOf("text" to JsonPrimitive(""))))
        assertEquals("", emptyText.text, "empty text stays empty")
    }

    @Test
    fun textTypeCoercions() {
        // 显式 null → 字面量 "null"（JsonNull 是 JsonPrimitive，as? 不失败）。
        assertEquals(
            "null",
            fieldsOf(JsonObject(mapOf("text" to JsonNull))).text,
            "explicit null text -> \"null\" literal",
        )
        // 对象 / 数组型 → ""（as? 静默失败，不抛——与 ?.jsonPrimitive 的大声失败不同）。
        assertEquals(
            "",
            fieldsOf(JsonObject(mapOf("text" to JsonObject(emptyMap())))).text,
            "object text -> empty string, silently",
        )
        assertEquals(
            "",
            fieldsOf(JsonObject(mapOf("text" to JsonArray(listOf(JsonPrimitive(1)))))).text,
            "array text -> empty string, silently",
        )
        // 数字 / 布尔型 → 其字面量 content。
        assertEquals(
            "1",
            fieldsOf(JsonObject(mapOf("text" to JsonPrimitive(1)))).text,
            "number text -> \"1\"",
        )
        assertEquals(
            "true",
            fieldsOf(JsonObject(mapOf("text" to JsonPrimitive(true)))).text,
            "boolean text -> \"true\"",
        )
        // 普通字符串原样透传。
        assertEquals(
            "/start hello",
            fieldsOf(JsonObject(mapOf("text" to JsonPrimitive("/start hello")))).text,
            "string text passthrough",
        )
    }

    @Test
    fun botIdHintSemantics() {
        // 空串 / 全空白 → null（takeIf { it.isNotBlank() }）。
        assertNull(
            fieldsOf(JsonObject(mapOf("botId" to JsonPrimitive("")))).botIdHint,
            "empty botId -> null",
        )
        assertNull(
            fieldsOf(JsonObject(mapOf("botId" to JsonPrimitive("   ")))).botIdHint,
            "blank botId -> null",
        )
        // 显式 null → "null"（非空字面量，不被滤掉——逐字怪语义）。
        assertEquals(
            "null",
            fieldsOf(JsonObject(mapOf("botId" to JsonNull))).botIdHint,
            "explicit null botId -> \"null\" literal",
        )
        // 对象 / 数组型 → null（静默）。
        assertNull(
            fieldsOf(JsonObject(mapOf("botId" to JsonObject(emptyMap())))).botIdHint,
            "object botId -> null, silently",
        )
        // 非空照原样保留。
        assertEquals(
            "bot_42",
            fieldsOf(JsonObject(mapOf("botId" to JsonPrimitive("bot_42")))).botIdHint,
            "non-blank botId passthrough",
        )
        // 数字型 → 字面量 content。
        assertEquals(
            "7",
            fieldsOf(JsonObject(mapOf("botId" to JsonPrimitive(7)))).botIdHint,
            "number botId -> \"7\"",
        )
    }

    @Test
    fun nearMissFieldNamesIgnored() {
        // 近似字段名不被识别 → 等同缺席。
        for (name in listOf("Text", "TEXT", "text2", "txt")) {
            assertEquals(
                "",
                fieldsOf(JsonObject(mapOf(name to JsonPrimitive("x")))).text,
                "near-miss " + name + " ignored -> empty text",
            )
        }
        for (name in listOf("botid", "BOTID", "botId2", "bot_id")) {
            assertNull(
                fieldsOf(JsonObject(mapOf(name to JsonPrimitive("b")))).botIdHint,
                "near-miss " + name + " ignored -> null hint",
            )
        }
        // 但真字段混在近似字段里仍被识别。
        val withNoise = JsonObject(
            mapOf(
                "Text" to JsonPrimitive("noise"),
                "text" to JsonPrimitive("real"),
                "botid" to JsonPrimitive("noise"),
                "botId" to JsonPrimitive("realBot"),
            ),
        )
        val got = fieldsOf(withNoise)
        assertEquals("real", got.text, "real text wins among near-misses")
        assertEquals("realBot", got.botIdHint, "real botId wins among near-misses")
    }

    @Test
    fun invalidJsonGateStaysInHandler() {
        // 坏 JSON / 顶层非对象的 "invalid json" 判定仍在处理器（不在纯函数内）——
        // 纯函数只收 JsonObject；这里钉住 Json 解析层面的等价输入仍被正确抽取。
        val encoded = Json.encodeToString(JsonObject(mapOf("text" to JsonPrimitive("/ping"))))
        val decoded = Json.parseToJsonElement(encoded).let {
            assertTrue(it is JsonObject, "round-trip stays object")
            it
        }
        assertEquals("/ping", fieldsOf(decoded).text, "encode/decode round-trip text")
    }
}
