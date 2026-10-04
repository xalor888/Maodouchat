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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeveloperAccountParseFuzzTest {

    private companion object {
        private const val ITERATIONS = 150
    }

    private fun randomString(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?~\t\n"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomName(random: Random, known: Set<String>): String {
        val name = randomString(random, random.nextInt(1, 20)).trim().ifEmpty { "f" }
        return if (name in known) "unknown_" + name else name
    }

    private fun randomScalar(random: Random, allowNull: Boolean = true): JsonElement = when (random.nextInt(6)) {
        0 -> JsonPrimitive(random.nextBoolean())
        1 -> JsonPrimitive(random.nextLong(-1000, 1000))
        2 -> JsonPrimitive(random.nextDouble(-1000.0, 1000.0))
        3 -> JsonPrimitive(randomString(random, random.nextInt(0, 40)))
        4 -> if (allowNull) JsonNull else JsonPrimitive(randomString(random, 5))
        else -> JsonArray(List(random.nextInt(0, 5)) { randomScalar(random, allowNull = false) })
    }

    private fun randomObject(random: Random, known: Set<String>): JsonObject {
        val entries = mutableListOf<Pair<String, JsonElement>>()
        repeat(random.nextInt(0, 8)) { entries += randomName(random, known) to randomScalar(random) }
        return JsonObject(entries.toMap())
    }

    // ─── 第一块：登录字段 ───

    // 旧内联写法的逐字复刻，只供等价性比对。
    private fun oldLoginWay(obj: JsonObject): DeveloperLoginFields {
        val email = obj["email"]?.jsonPrimitive?.content.orEmpty()
        val password = obj["password"]?.jsonPrimitive?.content.orEmpty()
        val totpCode = obj["totpCode"]?.jsonPrimitive?.content
        return DeveloperLoginFields(email, password, totpCode)
    }

    @Test
    fun `login old and new agree on seeded random payloads`() {
        val known = setOf("email", "password", "totpCode")
        val random = Random(19001)
        repeat(ITERATIONS) { i ->
            val entries = randomObject(random, known).toMutableMap()
            entries["email"] = randomScalar(random)
            entries["password"] = randomScalar(random)
            entries["totpCode"] = randomScalar(random)
            val obj = JsonObject(entries)
            val old = runCatching { oldLoginWay(obj) }
            val new = runCatching { parseDeveloperLoginFields(obj) }
            assertEquals(old.isSuccess, new.isSuccess, "iteration " + i + ": throw behavior must match")
            if (old.isSuccess) {
                assertEquals(old.getOrNull(), new.getOrNull(), "iteration " + i + ": fields must match old inline way")
            } else {
                assertTrue(new.exceptionOrNull() is IllegalArgumentException, "iteration " + i + ": non-primitive must fail loudly")
            }
        }
    }

    @Test
    fun `login missing fields default and totpCode stays nullable`() {
        val parsed = parseDeveloperLoginFields(JsonObject(emptyMap()))
        assertEquals("", parsed.email)
        assertEquals("", parsed.password)
        assertNull(parsed.totpCode)
        val withNull = parseDeveloperLoginFields(JsonObject(mapOf("totpCode" to JsonNull)))
        assertNull(withNull.totpCode)
    }

    @Test
    fun `login non-primitive field fails loudly`() {
        val obj = JsonObject(mapOf("email" to JsonObject(mapOf("x" to JsonPrimitive(1)))))
        assertFailsWith<IllegalArgumentException>(
            "non-primitive email must fail at jsonPrimitive, mapped to 400 by StatusPages",
        ) { parseDeveloperLoginFields(obj) }
    }

    // ─── 第二块：建 bot 字段 ───

    private fun oldBotCreateWay(obj: JsonObject): DeveloperBotCreateFields {
        val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
        val username = obj["username"]?.jsonPrimitive?.content.orEmpty()
        val description = obj["description"]?.jsonPrimitive?.content
        return DeveloperBotCreateFields(name, username, description)
    }

    @Test
    fun `create bot old and new agree on seeded random payloads`() {
        val known = setOf("name", "username", "description")
        val random = Random(19002)
        repeat(ITERATIONS) { i ->
            val entries = randomObject(random, known).toMutableMap()
            entries["name"] = randomScalar(random)
            entries["username"] = randomScalar(random)
            entries["description"] = randomScalar(random)
            val obj = JsonObject(entries)
            val old = runCatching { oldBotCreateWay(obj) }
            val new = runCatching { parseDeveloperBotCreateFields(obj) }
            assertEquals(old.isSuccess, new.isSuccess, "iteration " + i + ": throw behavior must match")
            if (old.isSuccess) {
                assertEquals(old.getOrNull(), new.getOrNull(), "iteration " + i + ": fields must match old inline way")
            } else {
                assertTrue(new.exceptionOrNull() is IllegalArgumentException, "iteration " + i + ": non-primitive must fail loudly")
            }
        }
    }

    @Test
    fun `create bot defaults are pinned`() {
        val parsed = parseDeveloperBotCreateFields(JsonObject(emptyMap()))
        assertEquals("", parsed.name)
        assertEquals("", parsed.username)
        assertNull(parsed.description)
        val explicitNull = parseDeveloperBotCreateFields(JsonObject(mapOf("name" to JsonNull)))
        assertEquals("null", explicitNull.name, "JsonNull.content is the literal \"null\", kept verbatim")
    }

    @Test
    fun `create bot array value fails loudly`() {
        val obj = JsonObject(mapOf("username" to JsonArray(listOf(JsonPrimitive(1)))))
        assertFailsWith<IllegalArgumentException> { parseDeveloperBotCreateFields(obj) }
    }

    // ─── 第三块：命令菜单数组 ───

    private class CmdErr(message: String) : IllegalStateException(message)

    // 旧内联写法的逐字复刻（原先直接在路由里 respond 400，复刻里用异常携带文案比对）。
    private fun oldCommandsWay(obj: JsonObject): List<Pair<String, String>> {
        val arr = obj["commands"] as? JsonArray ?: throw CmdErr("commands array required")
        return buildList {
            for (item in arr) {
                val o = item as? JsonObject ?: throw CmdErr("invalid command item")
                val command = o["command"]?.jsonPrimitive?.content.orEmpty()
                val description = o["description"]?.jsonPrimitive?.content.orEmpty()
                if (command.isBlank() || description.isBlank()) {
                    throw CmdErr("invalid command (command and description required)")
                }
                add(command to description)
            }
        }
    }

    private fun newCommandsAsThrowing(obj: JsonObject): List<Pair<String, String>> {
        return when (val parsed = parseDeveloperBotCommands(obj)) {
            is DeveloperBotCommandsParseResult.Ok -> parsed.defs
            is DeveloperBotCommandsParseResult.Invalid -> throw CmdErr(parsed.message)
        }
    }

    private fun randomCommandItem(random: Random): JsonElement {
        return when (random.nextInt(10)) {
            0 -> randomScalar(random) // 非对象条目 → invalid command item
            1 -> JsonNull
            else -> {
                val entries = mutableListOf<Pair<String, JsonElement>>()
                repeat(random.nextInt(0, 4)) { entries += randomName(random, setOf("command", "description")) to randomScalar(random) }
                entries += "command" to randomScalar(random)
                entries += "description" to randomScalar(random)
                JsonObject(entries.toMap())
            }
        }
    }

    @Test
    fun `commands old and new agree on seeded random payloads`() {
        val random = Random(19003)
        repeat(ITERATIONS) { i ->
            val entries = mutableListOf<Pair<String, JsonElement>>()
            repeat(random.nextInt(0, 4)) { entries += randomName(random, setOf("commands")) to randomScalar(random) }
            entries += "commands" to if (random.nextBoolean()) {
                JsonArray(List(random.nextInt(0, 6)) { randomCommandItem(random) })
            } else {
                randomScalar(random)
            }
            val obj = JsonObject(entries.toMap())
            val old = runCatching { oldCommandsWay(obj) }
            val new = runCatching { newCommandsAsThrowing(obj) }
            assertEquals(old.isSuccess, new.isSuccess, "iteration " + i + ": throw behavior must match")
            if (old.isSuccess) {
                assertEquals(old.getOrNull(), new.getOrNull(), "iteration " + i + ": defs must match old inline way")
            } else {
                val oldEx = old.exceptionOrNull()!!
                val newEx = new.exceptionOrNull()!!
                assertEquals(oldEx::class, newEx::class, "iteration " + i + ": exception class must match")
                assertEquals(oldEx.message, newEx.message, "iteration " + i + ": 400 message must match")
            }
        }
    }

    @Test
    fun `commands strict failure messages are preserved`() {
        val notArray = parseDeveloperBotCommands(JsonObject(mapOf("commands" to JsonPrimitive("x"))))
        assertEquals("commands array required", (notArray as DeveloperBotCommandsParseResult.Invalid).message)
        val notItem = parseDeveloperBotCommands(JsonObject(mapOf("commands" to JsonArray(listOf(JsonPrimitive(1))))))
        assertEquals("invalid command item", (notItem as DeveloperBotCommandsParseResult.Invalid).message)
        val blank = parseDeveloperBotCommands(
            JsonObject(mapOf("commands" to JsonArray(listOf(JsonObject(mapOf("command" to JsonPrimitive(" "))))))),
        )
        assertEquals("invalid command (command and description required)", (blank as DeveloperBotCommandsParseResult.Invalid).message)
    }

    @Test
    fun `commands explicit empty array is a legal clear`() {
        val parsed = parseDeveloperBotCommands(JsonObject(mapOf("commands" to JsonArray(emptyList()))))
        assertTrue(parsed is DeveloperBotCommandsParseResult.Ok)
        assertEquals(emptyList(), (parsed as DeveloperBotCommandsParseResult.Ok).defs)
    }

    @Test
    fun `commands explicit null value becomes literal null string`() {
        val item = JsonObject(mapOf("command" to JsonNull, "description" to JsonPrimitive("d")))
        val parsed = parseDeveloperBotCommands(JsonObject(mapOf("commands" to JsonArray(listOf(item)))))
        val ok = parsed as DeveloperBotCommandsParseResult.Ok
        assertEquals(listOf("null" to "d"), ok.defs, "JsonNull.content is the literal \"null\", kept verbatim like the old way")
    }
}
