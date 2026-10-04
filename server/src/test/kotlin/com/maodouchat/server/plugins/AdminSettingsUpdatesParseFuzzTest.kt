package com.maodouchat.server.plugins

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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdminSettingsUpdatesParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("settings")
        private const val ITERATIONS = 150
    }

    private fun randomString(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?~\t\n"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomName(random: Random): String {
        val name = randomString(random, random.nextInt(1, 20)).trim().ifEmpty { "f" }
        return if (name in KNOWN_FIELD_NAMES) "unknown_" + name else name
    }

    private fun randomScalar(random: Random, allowNull: Boolean = true): JsonElement = when (random.nextInt(6)) {
        0 -> JsonPrimitive(random.nextBoolean())
        1 -> JsonPrimitive(random.nextLong(-1000, 1000))
        2 -> JsonPrimitive(random.nextDouble(-1000.0, 1000.0))
        3 -> JsonPrimitive(randomString(random, random.nextInt(0, 40)))
        4 -> if (allowNull) JsonNull else JsonPrimitive(randomString(random, 5))
        else -> JsonArray(List(random.nextInt(0, 5)) { randomScalar(random, allowNull = false) })
    }

    // 旧内联写法的逐字复刻，只供等价性比对。
    private fun oldWay(obj: JsonObject): Map<String, String> {
        val settingsObj = obj["settings"]?.jsonObject ?: obj
        return settingsObj.mapNotNull { (k, v) ->
            val s = runCatching { v.jsonPrimitive.content }.getOrNull() ?: return@mapNotNull null
            k to s
        }.toMap()
    }

    @Test
    fun `old and new agree on seeded random payloads`() {
        val random = Random(17001)
        repeat(ITERATIONS) { i ->
            val entries = mutableListOf<Pair<String, JsonElement>>()
            repeat(random.nextInt(0, 10)) { entries += randomName(random) to randomScalar(random) }
            val obj = JsonObject(entries.toMap())
            assertEquals(oldWay(obj), parseAdminSettingsUpdates(obj), "iteration " + i + ": must match old inline way")
        }
    }

    @Test
    fun `settings key object wins over top level`() {
        val obj = JsonObject(
            mapOf(
                "maxBots" to JsonPrimitive("top"),
                "settings" to JsonObject(mapOf("maxBots" to JsonPrimitive("inner"), "flag" to JsonPrimitive(true))),
            )
        )
        assertEquals(mapOf("maxBots" to "inner", "flag" to "true"), parseAdminSettingsUpdates(obj))
    }

    @Test
    fun `missing settings key uses whole object`() {
        val obj = JsonObject(mapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive("x")))
        assertEquals(mapOf("a" to "1", "b" to "x"), parseAdminSettingsUpdates(obj))
    }

    @Test
    fun `non object settings value fails loudly`() {
        val obj = JsonObject(mapOf("settings" to JsonPrimitive("nope")))
        assertFailsWith<IllegalArgumentException>(
            "jsonObject on a non-object settings value must fail loudly, mapped to 400 by StatusPages",
        ) { parseAdminSettingsUpdates(obj) }
    }

    @Test
    fun `null becomes literal null string and complex values are dropped`() {
        val obj = JsonObject(
            mapOf(
                "n" to JsonNull,
                "arr" to JsonArray(listOf(JsonPrimitive(1))),
                "o" to JsonObject(mapOf("x" to JsonPrimitive(1))),
                "s" to JsonPrimitive("v"),
            )
        )
        assertEquals(mapOf("n" to "null", "s" to "v"), parseAdminSettingsUpdates(obj))
    }

    @Test
    fun `empty object yields empty map`() {
        assertTrue(parseAdminSettingsUpdates(JsonObject(emptyMap())).isEmpty(), "empty object must give empty map")
    }
}
