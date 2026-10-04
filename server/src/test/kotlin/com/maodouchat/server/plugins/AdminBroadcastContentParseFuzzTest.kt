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

class AdminBroadcastContentParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("text", "title")
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
    private fun oldWay(obj: JsonObject): AdminBroadcastContent {
        val text = obj["text"]?.jsonPrimitive?.content?.trim().orEmpty().take(2000)
        val title = obj["title"]?.jsonPrimitive?.content?.trim()?.take(120).orEmpty().ifBlank { "System" }
        return AdminBroadcastContent(text, title)
    }

    @Test
    fun `old and new agree on seeded random payloads`() {
        val random = Random(17002)
        repeat(ITERATIONS) { i ->
            val entries = mutableListOf<Pair<String, JsonElement>>()
            repeat(random.nextInt(0, 10)) { entries += randomName(random) to randomScalar(random) }
            entries += "text" to randomScalar(random)
            entries += "title" to randomScalar(random)
            val obj = JsonObject(entries.toMap())
            val old = runCatching { oldWay(obj) }
            val new = runCatching { parseAdminBroadcastContent(obj) }
            assertEquals(old.isSuccess, new.isSuccess, "iteration " + i + ": throw behavior must match")
            if (old.isSuccess) {
                assertEquals(old.getOrNull(), new.getOrNull(), "iteration " + i + ": content must match old inline way")
            } else {
                assertTrue(new.exceptionOrNull() is IllegalArgumentException, "iteration " + i + ": object/array value must fail loudly")
            }
        }
    }

    @Test
    fun `truncation limits are pinned`() {
        val obj = JsonObject(
            mapOf(
                "text" to JsonPrimitive("t".repeat(3000)),
                "title" to JsonPrimitive("x".repeat(200)),
            )
        )
        val parsed = parseAdminBroadcastContent(obj)
        assertEquals(2000, parsed.text.length, "text truncated to 2000 chars")
        assertEquals(120, parsed.title.length, "title truncated to 120 chars")
    }

    @Test
    fun `missing or blank title falls back to System`() {
        assertEquals("System", parseAdminBroadcastContent(JsonObject(mapOf("text" to JsonPrimitive("hi")))).title)
        assertEquals(
            "System",
            parseAdminBroadcastContent(JsonObject(mapOf("text" to JsonPrimitive("hi"), "title" to JsonPrimitive("  ")))).title,
        )
    }

    @Test
    fun `missing text becomes empty string`() {
        assertEquals("", parseAdminBroadcastContent(JsonObject(emptyMap())).text, "missing text must give empty string")
    }

    @Test
    fun `explicit null becomes literal null string`() {
        val parsed = parseAdminBroadcastContent(JsonObject(mapOf("text" to JsonNull, "title" to JsonNull)))
        assertEquals("null", parsed.text, "JsonNull.content is the literal \"null\", kept verbatim")
        assertEquals("null", parsed.title, "literal \"null\" is not blank, no System fallback")
    }

    @Test
    fun `object value fails loudly`() {
        val obj = JsonObject(mapOf("text" to JsonObject(mapOf("x" to JsonPrimitive(1)))))
        assertFailsWith<IllegalArgumentException>(
            "object text value must fail loudly at jsonPrimitive, mapped to 400 by StatusPages",
        ) { parseAdminBroadcastContent(obj) }
    }
}
