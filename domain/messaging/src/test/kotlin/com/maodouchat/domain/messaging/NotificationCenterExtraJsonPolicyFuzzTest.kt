package com.maodouchat.domain.messaging

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationCenterExtraJsonPolicyFuzzTest {

    private companion object {
        private const val RANDOM_ITERATIONS = 200
        private const val STRUCTURED_ITERATIONS = 150
        private const val TEXT_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?\"'\\/\n\t@：中文"
        private const val GARBAGE_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_{}\":,./\\-_=+ \n\t@：中文"
    }

    private fun randomText(random: Random, alphabet: String, maxLength: Int, minLength: Int = 0): String {
        val length = random.nextInt(minLength, maxLength + 1)
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomJsonScalar(random: Random): kotlinx.serialization.json.JsonElement {
        return when (random.nextInt(5)) {
            0 -> JsonPrimitive(random.nextBoolean())
            1 -> JsonPrimitive(random.nextLong(-1000L, 1001L))
            2 -> JsonPrimitive(random.nextDouble(-1000.0, 1000.0))
            3 -> JsonPrimitive(randomText(random, TEXT_ALPHABET, 16))
            else -> JsonNull
        }
    }

    @Test
    fun `random maps round trip`() {
        val random = Random(0x70_2026)
        repeat(STRUCTURED_ITERATIONS) { i ->
            val extra = (0 until random.nextInt(0, 8)).associate {
                randomText(random, TEXT_ALPHABET, 16) to randomText(random, TEXT_ALPHABET, 32)
            }
            assertEquals(
                extra,
                NotificationCenterExtraJsonPolicy.decode(NotificationCenterExtraJsonPolicy.encode(extra)),
                "fuzz 迭代 #$i：extra 映射往返必须恒等"
            )
        }
    }

    @Test
    fun `random garbage never throws on decode`() {
        val random = Random(0x71_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val raw = randomText(random, GARBAGE_ALPHABET, 200)
            val result = NotificationCenterExtraJsonPolicy.decode(raw)
            assertTrue(result is Map<String, String>, "fuzz 迭代 #$i：解码垃圾串必须返回映射，raw=$raw")
            NotificationCenterExtraJsonPolicy.encode(result)
        }
        // 固定坏输入一律回空映射。
        assertEquals(emptyMap(), NotificationCenterExtraJsonPolicy.decode(""))
        assertEquals(emptyMap(), NotificationCenterExtraJsonPolicy.decode("not json"))
        assertEquals(emptyMap(), NotificationCenterExtraJsonPolicy.decode("[]"))
        assertEquals(emptyMap(), NotificationCenterExtraJsonPolicy.decode("123"))
        assertEquals(emptyMap(), NotificationCenterExtraJsonPolicy.decode("null"))
        assertEquals(emptyMap(), NotificationCenterExtraJsonPolicy.decode("{}"))
        assertEquals("{}", NotificationCenterExtraJsonPolicy.encode(emptyMap()))
    }

    @Test
    fun `random json objects with mixed value types decode to string values`() {
        val random = Random(0x72_2026)
        repeat(STRUCTURED_ITERATIONS) { i ->
            val entries = (0 until random.nextInt(1, 7)).associate {
                "key$it" to randomJsonScalar(random)
            }
            // 嵌套值只断言“能解出字符串”，不钉 org.json 的 toString 细节。
            val nested = (0 until random.nextInt(0, 3)).associate {
                "nested$it" to when (random.nextInt(2)) {
                    0 -> JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("x")))
                    else -> JsonObject(mapOf("a" to JsonPrimitive(1)))
                }
            }
            val obj = JsonObject(entries + nested)
            val raw = Json.encodeToString(JsonObject.serializer(), obj)
            val decoded = NotificationCenterExtraJsonPolicy.decode(raw)
            assertEquals(
                entries.keys + nested.keys,
                decoded.keys,
                "fuzz 迭代 #$i：解出的键集合必须与输入一致"
            )
            for ((key, value) in entries) {
                val expected = when (value) {
                    is JsonNull -> ""
                    is JsonPrimitive -> value.content
                    else -> error("unexpected scalar")
                }
                assertEquals(expected, decoded[key], "fuzz 迭代 #$i：键 $key 的标量值必须按字符串解出")
            }
            for (key in nested.keys) {
                assertTrue(decoded[key] is String, "fuzz 迭代 #$i：嵌套值必须解出字符串")
            }
        }
    }

    @Test
    fun `displayCount clamps to one`() {
        val random = Random(0x73_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val count = when (random.nextInt(5)) {
                0 -> Int.MIN_VALUE
                1 -> Int.MAX_VALUE
                2 -> 0
                3 -> 1
                else -> random.nextInt()
            }
            assertEquals(
                maxOf(1, count),
                NotificationCenterExtraJsonPolicy.displayCount(count),
                "fuzz 迭代 #$i：displayCount 必须钳在 ≥1"
            )
        }
    }
}
