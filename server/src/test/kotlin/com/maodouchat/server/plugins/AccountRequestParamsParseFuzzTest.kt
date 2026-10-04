package com.maodouchat.server.plugins

import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AccountRequestParamsParseFuzzTest {

    private companion object {
        private const val ITERATIONS = 150
    }

    private fun randomString(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?~\t\n"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomParamValue(random: Random): String = when (random.nextInt(8)) {
        0 -> ""
        1 -> "   "
        2 -> random.nextInt(-200, 200).toString()
        3 -> (random.nextDouble(-50.0, 150.0)).toString()
        4 -> randomString(random, random.nextInt(1, 30))
        5 -> randomString(random, random.nextInt(90, 160))
        6 -> "abc"
        else -> random.nextLong(-5, 5).toString() + ".5"
    }

    private fun randomParams(random: Random): Parameters {
        val pairs = mutableListOf<Pair<String, List<String>>>()
        if (random.nextBoolean()) pairs += "q" to listOf(randomParamValue(random))
        if (random.nextBoolean()) pairs += "limit" to listOf(randomParamValue(random))
        if (random.nextBoolean()) pairs += "offset" to listOf(randomParamValue(random))
        if (random.nextBoolean()) pairs += "radiusKm" to listOf(randomParamValue(random))
        return parametersOf(*pairs.toTypedArray())
    }

    // 旧内联写法的逐字复刻，只供等价性比对。
    private fun oldQueryWay(params: Parameters): String =
        params["q"]?.trim().orEmpty().take(100)

    private fun oldLimitWay(params: Parameters, defaultLimit: Int): Int =
        (params["limit"]?.toIntOrNull() ?: defaultLimit).coerceIn(1, 100)

    private fun oldOffsetWay(params: Parameters): Int =
        (params["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

    private fun oldRadiusKmWay(params: Parameters): Double =
        (params["radiusKm"]?.toDoubleOrNull() ?: 10.0).coerceIn(0.5, 30.0)

    @Test
    fun `page params old and new agree on seeded random params`() {
        val random = Random(21001)
        repeat(ITERATIONS) { i ->
            val params = randomParams(random)
            assertEquals(oldQueryWay(params), parseAccountSearchQuery(params), "iteration " + i + ": q must match")
            assertEquals(oldLimitWay(params, 30), parseAccountPageLimit(params, 30), "iteration " + i + ": limit(30) must match")
            assertEquals(oldLimitWay(params, 50), parseAccountPageLimit(params, 50), "iteration " + i + ": limit(50) must match")
            assertEquals(oldOffsetWay(params), parseAccountPageOffset(params), "iteration " + i + ": offset must match")
            assertEquals(oldRadiusKmWay(params), parseAccountNearbyRadiusKm(params), "iteration " + i + ": radiusKm must match")
        }
    }

    @Test
    fun `limit clamping is pinned`() {
        assertEquals(1, parseAccountPageLimit(parametersOf("limit" to listOf("0")), 30))
        assertEquals(100, parseAccountPageLimit(parametersOf("limit" to listOf("101")), 30))
        assertEquals(30, parseAccountPageLimit(parametersOf("limit" to listOf("abc")), 30))
        assertEquals(30, parseAccountPageLimit(parametersOf(), 30))
        assertEquals(50, parseAccountPageLimit(parametersOf(), 50))
        assertEquals(1, parseAccountPageLimit(parametersOf("limit" to listOf("-5")), 50))
    }

    @Test
    fun `offset negatives become zero`() {
        assertEquals(0, parseAccountPageOffset(parametersOf("offset" to listOf("-3"))))
        assertEquals(0, parseAccountPageOffset(parametersOf("offset" to listOf("xyz"))))
        assertEquals(0, parseAccountPageOffset(parametersOf()))
        assertEquals(7, parseAccountPageOffset(parametersOf("offset" to listOf("7"))))
    }

    @Test
    fun `radiusKm clamping is pinned`() {
        assertEquals(0.5, parseAccountNearbyRadiusKm(parametersOf("radiusKm" to listOf("0.1"))))
        assertEquals(30.0, parseAccountNearbyRadiusKm(parametersOf("radiusKm" to listOf("99"))))
        assertEquals(10.0, parseAccountNearbyRadiusKm(parametersOf("radiusKm" to listOf("xyz"))))
        assertEquals(10.0, parseAccountNearbyRadiusKm(parametersOf()))
    }

    @Test
    fun `search query truncation is pinned`() {
        val long = "x".repeat(150)
        assertEquals("x".repeat(100), parseAccountSearchQuery(parametersOf("q" to listOf("  " + long + "  "))))
        assertEquals("", parseAccountSearchQuery(parametersOf()))
    }

    // ─── username ───

    private fun oldUsernameWay(obj: JsonObject): String =
        obj["username"]?.jsonPrimitive?.content.orEmpty().trim().lowercase()

    private fun randomScalar(random: Random): JsonElement = when (random.nextInt(5)) {
        0 -> JsonPrimitive(random.nextBoolean())
        1 -> JsonPrimitive(random.nextLong(-1000, 1000))
        2 -> JsonPrimitive(randomString(random, random.nextInt(0, 40)))
        3 -> JsonNull
        else -> JsonObject(mapOf("x" to JsonPrimitive(1)))
    }

    @Test
    fun `username old and new agree on seeded random payloads`() {
        val random = Random(21002)
        repeat(ITERATIONS) { i ->
            val obj = JsonObject(mapOf("username" to randomScalar(random)))
            val old = runCatching { oldUsernameWay(obj) }
            val new = runCatching { parseAccountUsername(obj) }
            assertEquals(old.isSuccess, new.isSuccess, "iteration " + i + ": throw behavior must match")
            if (old.isSuccess) {
                assertEquals(old.getOrNull(), new.getOrNull(), "iteration " + i + ": username must match old inline way")
            } else {
                assertTrue(new.exceptionOrNull() is IllegalArgumentException, "iteration " + i + ": non-primitive must fail loudly")
            }
        }
    }

    @Test
    fun `username normalization is pinned`() {
        assertEquals("alice_9", parseAccountUsername(JsonObject(mapOf("username" to JsonPrimitive("  Alice_9 ")))))
        assertEquals("", parseAccountUsername(JsonObject(emptyMap())))
        // 显式 JSON null 取字面量 "null"（与旧内联写法一致），不是 Kotlin null。
        assertEquals("null", parseAccountUsername(JsonObject(mapOf("username" to JsonNull))))
    }

    @Test
    fun `username non-primitive fails loudly`() {
        val obj = JsonObject(mapOf("username" to JsonObject(mapOf("x" to JsonPrimitive(1)))))
        assertFailsWith<IllegalArgumentException> { parseAccountUsername(obj) }
    }
}
