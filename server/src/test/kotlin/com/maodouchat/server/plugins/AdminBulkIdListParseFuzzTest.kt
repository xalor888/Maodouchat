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

class AdminBulkIdListParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("userIds", "chatIds")
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
    private fun oldForceLogoutWay(obj: JsonObject): List<String> {
        val rawIds = obj["userIds"]
        return when {
            rawIds == null -> emptyList()
            rawIds is JsonArray -> rawIds.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(200)
    }

    private fun oldInviteTokensWay(obj: JsonObject): List<String> {
        val rawIds = obj["chatIds"]
        return when {
            rawIds == null -> emptyList()
            rawIds is JsonArray -> rawIds.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
            else -> rawIds.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
        }.map { it.take(64) }.distinct().take(100)
    }

    @Test
    fun `old and new agree on seeded random payloads`() {
        val random = Random(16001)
        repeat(ITERATIONS) { i ->
            val entries = mutableListOf<Pair<String, JsonElement>>()
            repeat(random.nextInt(0, 10)) { entries += randomName(random) to randomScalar(random) }
            entries += "userIds" to randomScalar(random)
            entries += "chatIds" to randomScalar(random)
            val obj = JsonObject(entries.toMap())
            assertEquals(
                oldForceLogoutWay(obj), parseAdminBulkIdList(obj, "userIds", 200),
                "iteration " + i + ": userIds must match old inline way",
            )
            assertEquals(
                oldInviteTokensWay(obj), parseAdminBulkIdList(obj, "chatIds", 100),
                "iteration " + i + ": chatIds must match old inline way",
            )
        }
    }

    @Test
    fun `missing key yields empty list`() {
        val obj = JsonObject(mapOf("days" to JsonPrimitive(3)))
        assertTrue(parseAdminBulkIdList(obj, "userIds", 200).isEmpty(), "missing key must give empty list")
        assertTrue(parseAdminBulkIdList(obj, "chatIds", 100).isEmpty(), "missing key must give empty list")
    }

    @Test
    fun `truncation dedup and limit are pinned`() {
        val long = "x".repeat(100)
        val ids = List(300) { "id" + it % 150 } + long
        val obj = JsonObject(mapOf("userIds" to JsonArray(ids.map { JsonPrimitive(it) })))
        val parsed = parseAdminBulkIdList(obj, "userIds", 200)
        assertEquals(151, parsed.size, "distinct 151 values, limit 200")
        assertTrue(parsed.none { it.length > 64 }, "each id truncated to 64 chars")
        assertTrue(parsed.contains(long.take(64)), "long id truncated not dropped")
        val limited = parseAdminBulkIdList(obj, "chatIds", 100)
        assertEquals(100, limited.size, "limit 100 pinned")
    }

    @Test
    fun `bad array elements are dropped silently`() {
        val obj = JsonObject(
            mapOf(
                "userIds" to JsonArray(
                    listOf(
                        JsonPrimitive("ok"),
                        JsonObject(mapOf("x" to JsonPrimitive(1))),
                        JsonArray(listOf(JsonPrimitive(1))),
                        JsonNull,
                        JsonPrimitive(42),
                        JsonPrimitive(true),
                    )
                )
            )
        )
        assertEquals(
            listOf("ok", "null", "42", "true"),
            parseAdminBulkIdList(obj, "userIds", 200),
            "object/array elements dropped, null keeps literal \"null\", primitives stringified",
        )
    }

    @Test
    fun `string value is split on separators and blanks filtered`() {
        val obj = JsonObject(mapOf("userIds" to JsonPrimitive("a, b  c\nd\te, ,,  f ")))
        assertEquals(
            listOf("a", "b", "c", "d", "e", "f"),
            parseAdminBulkIdList(obj, "userIds", 200),
        )
    }

    @Test
    fun `object value fails loudly`() {
        val obj = JsonObject(mapOf("userIds" to JsonObject(mapOf("x" to JsonPrimitive(1)))))
        assertFailsWith<IllegalArgumentException>(
            "object value must fail loudly at jsonPrimitive, mapped to 400 by StatusPages",
        ) { parseAdminBulkIdList(obj, "userIds", 200) }
    }

    @Test
    fun `explicit null becomes literal null string`() {
        val obj = JsonObject(mapOf("userIds" to JsonNull))
        assertEquals(
            listOf("null"),
            parseAdminBulkIdList(obj, "userIds", 200),
            "JsonNull.content is the literal \"null\", kept verbatim",
        )
    }
}
