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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BotEditMessageCaptionParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES =
            setOf(
                "messageId", "caption", "text",
            )
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotEditMessageCaptionFieldsResult =
        parseBotEditMessageCaptionFields(obj)

    private fun okOf(obj: JsonObject): BotEditMessageCaptionFields =
        (parseOf(obj) as BotEditMessageCaptionFieldsResult.Ok).fields

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
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?~\n"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomName(random: Random): String {
        var name: String
        do {
            name = "fuzz_" + randomString(random, random.nextInt(3, 12)).replace(" ", "_").replace("\n", "_")
        } while (name in KNOWN_FIELD_NAMES)
        return name
    }

    private fun basePayload(random: Random, i: Int): Pair<MutableMap<String, JsonElement>, Pair<String, String>> {
        val base = mutableMapOf<String, JsonElement>()
        // 必填字段确定性合法（第四十一块 CI 教训：全随机可能触发 MissingMessageId）。
        val messageId = "m" + i
        base["messageId"] = JsonPrimitive(messageId)
        // caption 双别名按 i 奇偶轮换；无 trim——首尾空白原样保留；部分轮取超长钉住截 1000。
        val rawCaption = if (i % 37 == 0) "c".repeat(1500) else " cap" + i + " "
        val aliasKey = if (i % 2 == 0) "caption" else "text"
        base[aliasKey] = JsonPrimitive(rawCaption)
        // 程序化注入未知字段：永不破坏 JSON 语法。
        repeat(random.nextInt(0, 6)) {
            base[randomName(random)] = randomScalar(random)
        }
        return base to (messageId to rawCaption.take(1000))
    }

    @Test
    fun fuzzUnknownKeysIgnoredAndKnownSemanticsHold() {
        val random = Random(0xE0417C)
        repeat(ITERATIONS) { i ->
            val (base, expected) = basePayload(random, i)
            val (expectedMessageId, expectedCaption) = expected
            val fields = okOf(JsonObject(base))
            assertEquals(expectedMessageId, fields.messageId, "iteration " + i + ": messageId")
            assertEquals(expectedCaption, fields.caption, "iteration " + i + ": caption (no trim, take 1000)")
        }
    }

    @Test
    fun missingMessageIdSemantics() {
        // 缺/空/纯空白 messageId → MissingMessageId。
        assertTrue(parseOf(JsonObject(mapOf())) is BotEditMessageCaptionFieldsResult.MissingMessageId)
        assertTrue(
            parseOf(JsonObject(mapOf("messageId" to JsonPrimitive(""))))
                is BotEditMessageCaptionFieldsResult.MissingMessageId
        )
        assertTrue(
            parseOf(JsonObject(mapOf("messageId" to JsonPrimitive("   "))))
                is BotEditMessageCaptionFieldsResult.MissingMessageId
        )
        // caption 缺省不影响 Ok。
        val fields = okOf(JsonObject(mapOf("messageId" to JsonPrimitive("m1"))))
        assertEquals("", fields.caption)
        // messageId 无 trim：首尾空白原样保留（非空白即 Ok）。
        val kept = okOf(JsonObject(mapOf("messageId" to JsonPrimitive(" m1 "))))
        assertEquals(" m1 ", kept.messageId)
    }

    @Test
    fun captionAliasAndTruncationSemantics() {
        // caption 压过 text（逐字顺序）。
        val both = okOf(
            JsonObject(
                mapOf(
                    "messageId" to JsonPrimitive("m1"),
                    "caption" to JsonPrimitive("c1"),
                    "text" to JsonPrimitive("t1"),
                )
            )
        )
        assertEquals("c1", both.caption)
        // 仅 text 别名同样生效。
        val textOnly = okOf(
            JsonObject(
                mapOf(
                    "messageId" to JsonPrimitive("m1"),
                    "text" to JsonPrimitive("t1"),
                )
            )
        )
        assertEquals("t1", textOnly.caption)
        // 无 trim：首尾空白原样保留。
        val untrimmed = okOf(
            JsonObject(
                mapOf(
                    "messageId" to JsonPrimitive("m1"),
                    "caption" to JsonPrimitive("  spaced  "),
                )
            )
        )
        assertEquals("  spaced  ", untrimmed.caption)
        // 截 1000：先取后截。
        val long = okOf(
            JsonObject(
                mapOf(
                    "messageId" to JsonPrimitive("m1"),
                    "caption" to JsonPrimitive("x".repeat(1200)),
                )
            )
        )
        assertEquals("x".repeat(1000), long.caption)
        assertEquals(1000, long.caption.length)
        // 显式 null 得字面量 "null"（非空，逐字怪语义）。
        val literalNull = okOf(
            JsonObject(
                mapOf(
                    "messageId" to JsonPrimitive("m1"),
                    "caption" to JsonNull,
                )
            )
        )
        assertEquals("null", literalNull.caption)
        // 显式 null 的 messageId 同样得字面量 "null"（非空→Ok，逐字怪语义）。
        val nullMessageId = okOf(JsonObject(mapOf("messageId" to JsonNull)))
        assertEquals("null", nullMessageId.messageId)
    }

    @Test
    fun loudFailureOnBadTypes() {
        val objMessageId = JsonObject(
            mapOf(
                "messageId" to JsonObject(mapOf("a" to JsonPrimitive(1))),
                "caption" to JsonPrimitive("c"),
            )
        )
        assertFailsWith<IllegalArgumentException> { parseOf(objMessageId) }
        val arrMessageId = JsonObject(
            mapOf(
                "messageId" to JsonArray(listOf(JsonPrimitive("x"))),
                "caption" to JsonPrimitive("c"),
            )
        )
        assertFailsWith<IllegalArgumentException> { parseOf(arrMessageId) }
        val objCaption = JsonObject(
            mapOf(
                "messageId" to JsonPrimitive("m1"),
                "caption" to JsonObject(mapOf("a" to JsonPrimitive(1))),
            )
        )
        assertFailsWith<IllegalArgumentException> { parseOf(objCaption) }
        val objAlias = JsonObject(
            mapOf(
                "messageId" to JsonPrimitive("m1"),
                "text" to JsonArray(listOf(JsonPrimitive("x"))),
            )
        )
        assertFailsWith<IllegalArgumentException> { parseOf(objAlias) }
        // 显式 null 不抛的反证（见 captionAliasAndTruncationSemantics 的字面量 "null" 断言）。
    }

    @Test
    fun nearNameFieldsAreUnknown() {
        val fields = okOf(
            JsonObject(
                mapOf(
                    "messageId" to JsonPrimitive("m1"),
                    "Caption" to JsonPrimitive("UPPER"),
                    "caption2" to JsonPrimitive("SUFFIX"),
                )
            )
        )
        // 近似字段名按未知键忽略：caption 取不到→缺省空串。
        assertEquals("", fields.caption)
    }

    @Test
    fun e2eeRefusalSemantics() {
        // 对端 E2EE 信封：拒绝（只认既有正文原文）。
        assertTrue(isPeerE2eeContent("E2EE:some-payload"))
        assertTrue(isPeerE2eeContent("E2EE:"))
        assertTrue(isPeerE2eeContent("{\"ciphertext\":\"abc\",\"meta\":1}"))
        assertTrue(isPeerE2eeContent("{ \"ciphertext\" : \"abc\" }"))
        // 非 E2EE：不拒绝。
        assertFalse(isPeerE2eeContent(""))
        assertFalse(isPeerE2eeContent("hello world"))
        assertFalse(isPeerE2eeContent("{\"text\":\"hi\"}"))
        assertFalse(isPeerE2eeContent("E2EE is not a prefix here"))
        assertFalse(isPeerE2eeContent("[{\"ciphertext\":\"abc\"}]"))
    }

    @Test
    fun contentAssemblySemantics() {
        // 纯空白 caption → 原正文不变（等于没改）。
        assertEquals("line1\nline2", buildBotEditCaptionContent("line1\nline2", "   "))
        assertEquals("line1", buildBotEditCaptionContent("line1", ""))
        // 单行正文：直接换成 caption。
        assertEquals("new cap", buildBotEditCaptionContent("old line", "new cap"))
        // 多行正文（媒体卡片）：首行保留，只重写 caption 部分（首行 + 换行 + caption，逐字）。
        assertEquals(
            "🎵 audio (3B)\nnew cap",
            buildBotEditCaptionContent("🎵 audio (3B)\nold cap", "new cap")
        )
        assertEquals(
            "first\nsecond",
            buildBotEditCaptionContent("first\nold\nmore", "second")
        )
        // caption 带首尾空白：无 trim，原样拼接。
        assertEquals(
            "first\n  spaced  ",
            buildBotEditCaptionContent("first\nold", "  spaced  ")
        )
        // caption 本身含换行：原样拼接（逐字语义，不再拆行处理）。
        assertEquals(
            "first\na\nb",
            buildBotEditCaptionContent("first\nold", "a\nb")
        )
    }
}
