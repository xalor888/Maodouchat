package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonArray
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

class AdminBulkReasonCodeParseFuzzTest {

    private companion object {
        private const val ITERATIONS = 150
    }

    private fun randomString(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?~\t\n"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
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
    private fun oldReasonCodeWay(obj: JsonObject): String =
        obj["reasonCode"]?.jsonPrimitive?.content.orEmpty().ifBlank { "BULK_BAN" }

    @Test
    fun `reasonCode old and new agree on seeded random payloads`() {
        val random = Random(20001)
        repeat(ITERATIONS) { i ->
            val entries = mutableMapOf<String, JsonElement>()
            repeat(random.nextInt(0, 6)) {
                var name = randomString(random, random.nextInt(1, 12)).trim().ifEmpty { "f" }
                if (name == "reasonCode") name = "unknown_$name"
                entries[name] = randomScalar(random)
            }
            entries["reasonCode"] = randomScalar(random)
            val obj = JsonObject(entries)
            val old = runCatching { oldReasonCodeWay(obj) }
            val new = runCatching { parseAdminBulkReasonCode(obj) }
            assertEquals(old.isSuccess, new.isSuccess, "iteration " + i + ": throw behavior must match")
            if (old.isSuccess) {
                assertEquals(old.getOrNull(), new.getOrNull(), "iteration " + i + ": reasonCode must match old inline way")
            } else {
                assertTrue(new.exceptionOrNull() is IllegalArgumentException, "iteration " + i + ": non-primitive must fail loudly")
            }
        }
    }

    @Test
    fun `reasonCode defaults are pinned`() {
        assertEquals("BULK_BAN", parseAdminBulkReasonCode(JsonObject(emptyMap())))
        assertEquals("BULK_BAN", parseAdminBulkReasonCode(JsonObject(mapOf("reasonCode" to JsonPrimitive("   ")))))
        // 旧写法不 trim：显式值原样保留。
        assertEquals(" SPAM ", parseAdminBulkReasonCode(JsonObject(mapOf("reasonCode" to JsonPrimitive(" SPAM ")))))
        // JsonNull.content 是字面量 "null"，非 blank，原样保留。
        assertEquals("null", parseAdminBulkReasonCode(JsonObject(mapOf("reasonCode" to JsonNull))))
        assertEquals("OTHER", parseAdminBulkReasonCode(JsonObject(emptyMap()), "OTHER"))
    }

    @Test
    fun `reasonCode non-primitive fails loudly`() {
        assertFailsWith<IllegalArgumentException> {
            parseAdminBulkReasonCode(JsonObject(mapOf("reasonCode" to JsonObject(mapOf("a" to JsonPrimitive(1))))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseAdminBulkReasonCode(JsonObject(mapOf("reasonCode" to JsonArray(listOf(JsonPrimitive(1))))))
        }
    }
}
