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

class BotSendMarkdownParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("chatId", "text", "markdown", "silent")
        private const val ITERATIONS = 150
        private const val TEXT_CAP = 4000
    }

    private fun parseOf(obj: JsonObject): BotMarkdownFieldsResult =
        parseBotMarkdownFields(obj)

    private fun okOf(obj: JsonObject): BotMarkdownFields =
        (parseOf(obj) as BotMarkdownFieldsResult.Ok).fields

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
    fun fuzzUnknownKeysIgnoredAndKnownSemanticsHold() {
        val random = Random(20261001)
        repeat(ITERATIONS) { i ->
            val mode = i % 3
            val base = mutableMapOf<String, JsonElement>()
            base["chatId"] = JsonPrimitive("chat-fuzz-" + i)
            // mode 0：text 缺席 → 钉住 markdown 回退；mode 1：text + markdown
            // 同时在场 → 钉住 text 分支（markdown 被忽略）；mode 2：全字段抽取。
            val expectedText: String
            when (mode) {
                0 -> {
                    base["markdown"] = JsonPrimitive("fallback-md-" + i)
                    expectedText = "fallback-md-" + i
                }
                1 -> {
                    base["text"] = JsonPrimitive("text-wins-" + i)
                    base["markdown"] = JsonPrimitive("ignored-md-" + i)
                    expectedText = "text-wins-" + i
                }
                else -> {
                    base["text"] = JsonPrimitive("plain-text-" + i)
                    expectedText = "plain-text-" + i
                }
            }
            // mode 2 的一半用例钉住 take(4000) 作用于 content 之后。
            val longText = mode == 2 && i % 2 == 0
            if (longText) {
                val over = "x".repeat(TEXT_CAP + 1234)
                base["text"] = JsonPrimitive(over)
            }
            base["silent"] = JsonPrimitive(i % 2 == 0)
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val fields = okOf(JsonObject(base))
            assertEquals("chat-fuzz-" + i, fields.chatId, "未知键不得污染 chatId，迭代 " + i)
            val wantText = if (longText) "x".repeat(TEXT_CAP) else expectedText
            assertEquals(wantText, fields.text, "未知键不得污染 text 抽取/回退/截断语义，迭代 " + i)
            assertEquals(i % 2 == 0, fields.silentRequested, "未知键不得污染 silent 抽取，迭代 " + i)
        }
    }

    @Test
    fun missingRequiredSemantics() {
        // chatId 缺 / 空 / 纯空白 → MissingRequired。
        assertTrue(parseOf(JsonObject(mapOf("text" to JsonPrimitive("hi")))) is BotMarkdownFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive(""), "text" to JsonPrimitive("hi")))) is BotMarkdownFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("   "), "text" to JsonPrimitive("hi")))) is BotMarkdownFieldsResult.MissingRequired)
        // text 与 markdown 均缺席 → MissingRequired。
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1")))) is BotMarkdownFieldsResult.MissingRequired)
        // text 空 / 纯空白 → MissingRequired（判的是 content 空白性）。
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "text" to JsonPrimitive("")))) is BotMarkdownFieldsResult.MissingRequired)
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "text" to JsonPrimitive("  \t ")))) is BotMarkdownFieldsResult.MissingRequired)
        // text 缺席但 markdown 纯空白 → MissingRequired。
        assertTrue(parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "markdown" to JsonPrimitive("   ")))) is BotMarkdownFieldsResult.MissingRequired)
        // 双合法 → Ok。
        val ok = okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"), "text" to JsonPrimitive("hello"))))
        assertEquals("c1", ok.chatId)
        assertEquals("hello", ok.text)
    }

    @Test
    fun capAndQuirks() {
        // take(4000) 作用于 orEmpty() 之后：超长 content 被裁到恰好 4000，不带引号。
        val long = "y".repeat(TEXT_CAP + 999)
        assertEquals("y".repeat(TEXT_CAP), okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "text" to JsonPrimitive(long)))).text)
        // 恰好 4000 不动。
        val exact = "z".repeat(TEXT_CAP)
        assertEquals(exact, okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "text" to JsonPrimitive(exact)))).text)
        // text 显式 null → 不抛、不回退：JsonNull.content 取到字面量 "null"
        // 字符串（原处理器逐字如此——怪语义，特意钉住）。
        assertEquals("null", okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "text" to JsonNull, "markdown" to JsonPrimitive("fallback")))).text)
        // text 缺席 + markdown 显式 null → 同样取到 "null"，不抛。
        assertEquals("null", okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "markdown" to JsonNull))).text)
        // chatId 显式 null → 同样取到 "null"（非空不断言必填），不抛。
        assertEquals("null", okOf(JsonObject(mapOf("chatId" to JsonNull, "text" to JsonPrimitive("hi")))).chatId)
        // 数字 / 布尔型 text 不抛：JsonPrimitive.content 是 toString()，特意钉住。
        assertEquals("5", okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "text" to JsonPrimitive(5)))).text)
        assertEquals("true", okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "text" to JsonPrimitive(true)))).text)
        // 数字型 markdown 回退同样不抛。
        assertEquals("42", okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "markdown" to JsonPrimitive(42)))).text)
    }

    @Test
    fun silentSemantics() {
        fun silentOf(obj: JsonObject): Boolean = okOf(obj).silentRequested
        fun base(silent: JsonElement?): JsonObject {
            val m = mutableMapOf<String, JsonElement>(
                "chatId" to JsonPrimitive("c"),
                "text" to JsonPrimitive("hi"),
            )
            if (silent != null) m["silent"] = silent
            return JsonObject(m)
        }
        // 缺键 / JSON null → false。
        assertEquals(false, silentOf(base(null)))
        assertEquals(false, silentOf(base(JsonNull)))
        // 布尔 true → true，false → false。
        assertEquals(true, silentOf(base(JsonPrimitive(true))))
        assertEquals(false, silentOf(base(JsonPrimitive(false))))
        // 字符串 "true"/"false" 按布尔语义解析（booleanOrNull 认 "true"）。
        assertEquals(true, silentOf(base(JsonPrimitive("true"))))
        assertEquals(false, silentOf(base(JsonPrimitive("false"))))
        // 数字 1 不认 → false。
        assertEquals(false, silentOf(base(JsonPrimitive(1))))
        // 对象 / 数组型 silent 大声失败。
        assertFailsWith<IllegalArgumentException> { parseOf(base(JsonObject(emptyMap()))) }
        assertFailsWith<IllegalArgumentException> { parseOf(base(JsonArray(emptyList()))) }
    }

    @Test
    fun wrongTypedKnownFieldsFailLoudly() {
        // 对象 / 数组型 chatId 在 .content 处抛。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonObject(emptyMap()), "text" to JsonPrimitive("hi"))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonArray(emptyList()), "text" to JsonPrimitive("hi"))))
        }
        // 对象 / 数组型 text 在 .content 处抛。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "text" to JsonObject(emptyMap()))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "text" to JsonArray(emptyList()))))
        }
        // 对象型 markdown（text 缺席走回退）同样在 .content 处抛。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c"), "markdown" to JsonObject(emptyMap()))))
        }
    }

    @Test
    fun extractionOrder() {
        // chatId 先于 text 抽取：chatId 坏类型 + text 乱值 → 抛错（不是先判必填）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonObject(emptyMap()), "text" to JsonObject(emptyMap()))))
        }
        // chatId 缺席 + text 乱值 → 抛错而非 MissingRequired：text 抽取抛在必填判断之前。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("text" to JsonArray(emptyList()))))
        }
        // chatId 纯空白 + text 乱值 → 仍抛错（抽取先于必填判断）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("  "), "text" to JsonObject(emptyMap()))))
        }
        // 反证：text 合法时 chatId 纯空白 → MissingRequired（不抛）。
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("  "), "text" to JsonPrimitive("hi"))))
                is BotMarkdownFieldsResult.MissingRequired,
        )
    }
}
