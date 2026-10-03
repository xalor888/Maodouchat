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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotCreateParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "name",
            "username",
            "description",
        )
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotCreateFields =
        parseBotCreateFields(obj)

    private fun fieldsOf(name: String, username: String, description: String?): BotCreateFields =
        parseOf(JsonObject(buildMap {
            put("name", JsonPrimitive(name))
            put("username", JsonPrimitive(username))
            if (description != null) put("description", JsonPrimitive(description))
        }))

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
        val random = Random(2026100379)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            // 基 payload：三字段恒为 "n<i>" / "u<i>" / "d<i>"（恒等断言只看不被未知键污染）。
            val expectedName = "n" + i
            val expectedUsername = "u" + i
            val expectedDescription = "d" + i
            base["name"] = JsonPrimitive(expectedName)
            base["username"] = JsonPrimitive(expectedUsername)
            base["description"] = JsonPrimitive(expectedDescription)
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val fields = parseOf(JsonObject(base))
            assertEquals(expectedName, fields.name, "未知键不得污染 name，迭代 " + i)
            assertEquals(expectedUsername, fields.username, "未知键不得污染 username，迭代 " + i)
            assertEquals(expectedDescription, fields.description, "未知键不得污染 description，迭代 " + i)
        }
    }

    @Test
    fun missingSemantics() {
        // 全缺席 → name/username 空串、description null（原处理器逐字语义）。
        val fields = parseOf(JsonObject(emptyMap()))
        assertEquals("", fields.name, "name 缺席应为空串")
        assertEquals("", fields.username, "username 缺席应为空串")
        assertNull(fields.description, "description 缺席应为 null")
        // description 缺席而另两字段合法 → description 仍为 null。
        val partial = parseOf(JsonObject(mapOf(
            "name" to JsonPrimitive("n1"),
            "username" to JsonPrimitive("u1"),
        )))
        assertEquals("n1", partial.name)
        assertEquals("u1", partial.username)
        assertNull(partial.description, "description 缺席应为 null")
        // 空字符串原样保留。
        val empty = fieldsOf("", "", "")
        assertEquals("", empty.name)
        assertEquals("", empty.username)
        assertEquals("", empty.description)
    }

    @Test
    fun explicitNullIsLiteral() {
        // 三键显式 null → 字面量 "null"（原处理器逐字语义）。
        val viaParse = parseOf(JsonObject(mapOf(
            "name" to JsonNull,
            "username" to JsonNull,
            "description" to JsonNull,
        )))
        assertEquals("null", viaParse.name, "name 显式 null 应为字面量")
        assertEquals("null", viaParse.username, "username 显式 null 应为字面量")
        assertEquals("null", viaParse.description, "description 显式 null 应为字面量而非 null")
    }

    @Test
    fun noTrim() {
        // 前后空格原样保留（无 trim）。
        val fields = fieldsOf(" n1 ", " u1 ", " d1 ")
        assertEquals(" n1 ", fields.name)
        assertEquals(" u1 ", fields.username)
        assertEquals(" d1 ", fields.description)
        // 纯空白同样原样保留（抽取层不提前判空，判空是 BotRepository.create 的事）。
        val blank = fieldsOf("   ", "  ", "  ")
        assertEquals("   ", blank.name)
        assertEquals("  ", blank.username)
        assertEquals("  ", blank.description)
    }

    @Test
    fun nonStringPrimitiveContent() {
        // 非字符串 primitive 走 .content：数字/布尔原样通过（原处理器逐字语义）。
        val viaParse = parseOf(JsonObject(mapOf(
            "name" to JsonPrimitive(123),
            "username" to JsonPrimitive(true),
            "description" to JsonPrimitive(45.6),
        )))
        assertEquals("123", viaParse.name)
        assertEquals("true", viaParse.username)
        assertEquals("45.6", viaParse.description)
    }

    @Test
    fun badTypeLoudFail() {
        // 对象 / 数组型在 ?.jsonPrimitive 处抛 IllegalArgumentException（逐字语义）。
        listOf(
            "name" to JsonObject(mapOf("x" to JsonPrimitive(1))),
            "username" to JsonArray(listOf(JsonPrimitive(1))),
            "description" to JsonObject(emptyMap()),
        ).forEach { (badKey, badValue) ->
            val obj = JsonObject(mapOf(
                "name" to JsonPrimitive("n1"),
                "username" to JsonPrimitive("u1"),
                "description" to JsonPrimitive("d1"),
                badKey to badValue,
            ))
            assertFailsWith<IllegalArgumentException>("坏类型字段 " + badKey + " 应大声失败") {
                parseOf(obj)
            }
        }
    }

    @Test
    fun nearMissFieldNamesIgnored() {
        // 近似字段名仍按未知键忽略：真实字段缺席 → 空串 / null 语义。
        val fields = parseOf(JsonObject(mapOf(
            "Name" to JsonPrimitive("n1"),
            "NAME" to JsonPrimitive("n1"),
            "user_name" to JsonPrimitive("u1"),
            "Username" to JsonPrimitive("u1"),
            "USERNAME" to JsonPrimitive("u1"),
            "username2" to JsonPrimitive("u1"),
            "descriptions" to JsonPrimitive("d1"),
            "Description" to JsonPrimitive("d1"),
            "desc" to JsonPrimitive("d1"),
        )))
        assertEquals("", fields.name, "近似字段名应按未知键忽略，name 缺席应为空串")
        assertEquals("", fields.username, "近似字段名应按未知键忽略，username 缺席应为空串")
        assertNull(fields.description, "近似字段名应按未知键忽略，description 缺席应为 null")
    }
}
