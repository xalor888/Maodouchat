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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 钉住 `parseAdminManagementJsonEnvelopeOrNull` 的生产语义：
 * 固定种子 150 个随机 payload 未知键恒等、坏 JSON / 顶层非对象一律 null。
 * （sessions/revoke 的空 body 宽容在处理器里，不在此测。）
 */
class AdminManagementJsonEnvelopeParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "tokenHashPrefix",
            "all",
            "text",
            "title",
        )
        private const val ITERATIONS = 150
    }

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
        val name = randomString(random, random.nextInt(1, 20))
        return if (name in KNOWN_FIELD_NAMES) "unknown_" + name else name
    }

    @Test
    fun `random object bodies round-trip identically with unknown keys preserved`() {
        val random = Random(18032)
        repeat(ITERATIONS) { i ->
            val obj = JsonObject(
                List(random.nextInt(0, 12)) { randomName(random) to randomScalar(random) }.toMap()
            )
            val body = Json.encodeToString(JsonObject.serializer(), obj)
            val parsed = parseAdminManagementJsonEnvelopeOrNull(body)
            assertNotNull(parsed, "iteration " + i + ": valid object body must parse")
            assertEquals(obj, parsed, "iteration " + i + ": object must round-trip identically")
        }
    }

    @Test
    fun `malformed bodies map to null`() {
        val badBodies = listOf(
            "",
            "   ",
            "\n\t ",
            "{",
            "{\"a\":",
            "{\"a\": 1,",
            "[1, 2",
            "{\"a\": \"unterminated}",
            "not json at all",
            "{\"a\": 1}}",
        )
        badBodies.forEachIndexed { index, body ->
            assertNull(
                parseAdminManagementJsonEnvelopeOrNull(body),
                "bad body #" + index + " must map to null",
            )
        }
    }

    @Test
    fun `non-object top-level elements map to null`() {
        val bodies = listOf(
            "[1, 2, 3]",
            "\"just a string\"",
            "42",
            "-3.5",
            "true",
            "null",
            "{} ",
        )
        val expected = listOf(true, true, true, true, true, true, false)
        bodies.forEachIndexed { index, body ->
            val parsed = parseAdminManagementJsonEnvelopeOrNull(body)
            if (expected[index]) {
                assertNull(parsed, "top-level non-object body #" + index + " must map to null")
            } else {
                assertNotNull(parsed, "trailing-whitespace object body must still parse")
                assertTrue(parsed.isEmpty(), "empty object body must parse to empty object")
            }
        }
    }

    @Test
    fun `explicit nested json null inside object is preserved`() {
        val obj = JsonObject(mapOf("a" to JsonNull, "b" to JsonPrimitive("x")))
        val parsed = parseAdminManagementJsonEnvelopeOrNull(Json.encodeToString(JsonObject.serializer(), obj))
        assertNotNull(parsed)
        assertEquals(obj, parsed)
    }
}
