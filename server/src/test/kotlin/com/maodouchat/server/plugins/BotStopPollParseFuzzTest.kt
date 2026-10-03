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

class BotStopPollParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "pollId",
        )
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotStopPollFieldsResult =
        parseBotStopPollFields(obj)

    private fun okOf(pollId: String): BotStopPollFields =
        (parseOf(JsonObject(mapOf("pollId" to JsonPrimitive(pollId)))) as BotStopPollFieldsResult.Ok).fields

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
        val random = Random(2026100375)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            // 基 payload：pollId 恒为 "p<i>"（恒等断言只看不被未知键污染）。
            val expected = "p" + i
            base["pollId"] = JsonPrimitive(expected)
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val viaParse = parseOf(JsonObject(base))
            assertTrue(viaParse is BotStopPollFieldsResult.Ok, "注入未知键后仍应为 Ok，迭代 " + i)
            assertEquals(expected, viaParse.fields.pollId, "未知键不得污染 pollId，迭代 " + i)
        }
    }

    @Test
    fun requiredSemantics() {
        // 缺席 → Invalid。
        assertTrue(
            parseOf(JsonObject(emptyMap())) is BotStopPollFieldsResult.Invalid,
            "pollId 缺席应为 Invalid",
        )
        // 空字符串 → Invalid。
        assertTrue(
            parseOf(JsonObject(mapOf("pollId" to JsonPrimitive("")))) is BotStopPollFieldsResult.Invalid,
            "pollId 为空应为 Invalid",
        )
        // 纯空白 → Invalid（无 trim，全空白直接判空）。
        assertTrue(
            parseOf(JsonObject(mapOf("pollId" to JsonPrimitive("   ")))) is BotStopPollFieldsResult.Invalid,
            "pollId 纯空白应为 Invalid",
        )
        // 合法 → Ok。
        assertEquals("p1", okOf("p1").pollId)
    }

    @Test
    fun explicitNullIsLiteral() {
        // pollId 显式 null → 字面量 "null"，非空 → Ok（原处理器逐字语义）。
        val fields = okOf("null")
        assertEquals("null", fields.pollId)
        val viaParse = parseOf(JsonObject(mapOf("pollId" to JsonNull)))
        assertTrue(viaParse is BotStopPollFieldsResult.Ok, "显式 null 应为 Ok（字面量)")
        assertEquals("null", viaParse.fields.pollId)
    }

    @Test
    fun whitespaceNotTrimmed() {
        // 前后空白原样保留，仍通过必填（isBlank 只判空，不 trim）。
        assertEquals(" p1 ", okOf(" p1 ").pollId)
    }

    @Test
    fun nonStringPrimitivesUseContent() {
        // 数字 / 布尔型走 .content 字符串（原处理器逐字语义）。
        assertEquals("123", okOf("123").pollId)
        assertEquals("true", okOf("true").pollId)
        val num = parseOf(JsonObject(mapOf("pollId" to JsonPrimitive(123))))
        assertTrue(num is BotStopPollFieldsResult.Ok, "数字型 pollId 应为 Ok")
        assertEquals("123", num.fields.pollId)
    }

    @Test
    fun loudFailureOnWrongType() {
        // 对象 / 数组型在 ?.jsonPrimitive 处抛 IllegalArgumentException（大声失败，
        // 路由层 StatusPages 映射 400，不是 500）；显式 null 不抛。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("pollId" to JsonObject(mapOf("a" to JsonPrimitive(1))))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("pollId" to JsonArray(listOf(JsonPrimitive(1))))))
        }
    }

    @Test
    fun nearMissNamesIgnored() {
        // 近似字段名按未知键忽略；真字段缺席 → Invalid。
        for (name in listOf("PollId", "POLLID", "poll_id", "pollid", "pollId2")) {
            val obj = JsonObject(mapOf(name to JsonPrimitive("p1")))
            assertTrue(
                parseOf(obj) is BotStopPollFieldsResult.Invalid,
                "近似字段名 " + name + " 应被忽略，真字段缺席 → Invalid",
            )
        }
    }
}
