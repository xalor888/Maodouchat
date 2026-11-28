package com.maodouchat.core.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MessageMetaJson 原始输入 fuzz（清单 931 行第十四块，簇 1）。
 *
 * 范围刻意收窄：往返已由在途 #2044 的 MessageJsonPolicyFuzzTest 覆盖（150 次随机
 * meta 恒等），本文件只补三处缺口——畸形/非对象顶层输入的抛错契约、单字段损坏
 * 只丢该字段（附件解密密钥不得随整段 meta 一起丢）、inlineKeyboard 截断边界。
 */
class MessageMetaJsonFuzzTest {

    private companion object {
        private const val RANDOM_ITERATIONS = 200
        private const val CORRUPT_ITERATIONS = 150
        private val json = Json { ignoreUnknownKeys = true }
        private const val TEXT_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?\"'\\/{}[]():;@#：中文"
        // 注：刻意不含 emoji——按 UTF-16 单元随机取字符会拆出孤立代理对；
        // emoji 截断只在下方手写用例里以 repeat() 构造的合法字符串钉住。
        private val corruptValues = listOf(
            JsonPrimitive(123),
            JsonPrimitive(true),
            JsonPrimitive("corrupt"),
            JsonObject(mapOf("x" to JsonPrimitive(1))),
            JsonArray(listOf(JsonPrimitive(1))),
            JsonNull
        )
        private val allFields = listOf(
            "mentions", "replyToId", "forwardedFrom", "voiceTranscript", "voiceDurationMs",
            "translations", "preferredTranslationLanguage", "aiImageAnalyses",
            "preferredImageAnalysisMode", "aiFileAnalyses", "preferredFileAnalysisMode",
            "aiFileLastQuestion", "aiAssisted", "aiAssistantMode", "fileName", "fileMimeType",
            "fileSizeBytes", "attachmentId", "attachmentKeyBase64", "attachmentIvBase64",
            "attachmentCipherSha256", "attachmentPlainSha256", "attachmentCipherSize",
            "markdown", "viewOnce", "viewOnceOpened", "silent", "spoilerMedia",
            "spoilerRevealed", "inlineKeyboard", "forceReply"
        )
    }

    private fun randomText(random: Random, maxLength: Int): String {
        val length = random.nextInt(maxLength + 1)
        return buildString { repeat(length) { append(TEXT_ALPHABET[random.nextInt(TEXT_ALPHABET.length)]) } }
    }

    private fun randomValue(random: Random, depth: Int): Any? {
        if (depth <= 0) {
            return when (random.nextInt(5)) {
                0 -> null
                1 -> random.nextBoolean()
                2 -> random.nextInt()
                3 -> random.nextDouble(-1000.0, 1000.0)
                else -> randomText(random, 20)
            }
        }
        return when (random.nextInt(7)) {
            0 -> null
            1 -> random.nextBoolean()
            2 -> random.nextLong()
            3 -> random.nextDouble(-1000.0, 1000.0)
            4 -> randomText(random, 20)
            5 -> List(random.nextInt(0, 4)) { randomValue(random, depth - 1) }
            else -> (0 until random.nextInt(0, 4)).associate {
                randomText(random, 8) to randomValue(random, depth - 1)
            }
        }
    }

    private fun baseMeta() = MessageMeta(
        mentions = listOf("u1", "u2"),
        replyToId = "m-123",
        forwardedFrom = "Alice",
        voiceTranscript = "你好",
        voiceDurationMs = 4_200L,
        translations = mapOf("en" to "hello", "ja" to "こんにちは"),
        preferredTranslationLanguage = "en",
        aiImageAnalyses = mapOf("general" to "a cat"),
        preferredImageAnalysisMode = "general",
        aiFileAnalyses = mapOf("summary" to "doc"),
        preferredFileAnalysisMode = "summary",
        aiFileLastQuestion = "what is this?",
        aiAssisted = true,
        aiAssistantMode = "rewrite",
        fileName = "a.pdf",
        fileMimeType = "application/pdf",
        fileSizeBytes = 2_048L,
        attachmentId = "att_" + "a".repeat(30),
        attachmentKeyBase64 = "k".repeat(44),
        attachmentIvBase64 = "i".repeat(24),
        attachmentCipherSha256 = "c".repeat(64),
        attachmentPlainSha256 = "d".repeat(64),
        attachmentCipherSize = 2_112L,
        markdown = true,
        viewOnce = true,
        viewOnceOpened = false,
        silent = true,
        spoilerMedia = true,
        spoilerRevealed = false,
        inlineKeyboard = listOf(
            listOf(InlineKeyboardButton("yes", "cb-yes"), InlineKeyboardButton("no", "cb-no")),
            listOf(InlineKeyboardButton("more", "cb-more"))
        ),
        forceReply = true
    )

    @Test
    fun `randomGarbageThrowsOnlyKnownExceptionsOrParsesDeterministically`() {
        val random = Random(0x6F_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val text = randomText(random, 40)
            val result = runCatching { MessageMetaJson.fromJsonString(text) }
            result.onFailure { e ->
                assertTrue(
                    e is SerializationException || e is IllegalStateException,
                    "fuzz 迭代 #$i：畸形输入只应抛 SerializationException/IllegalStateException，实际 ${e::class.simpleName}"
                )
            }
            result.onSuccess { first ->
                assertEquals(
                    first, MessageMetaJson.fromJsonString(text),
                    "fuzz 迭代 #$i：同一输入两次解析必须相同"
                )
            }
        }
    }

    @Test
    fun `nonObjectTopLevelThrowsIllegalState`() {
        // 合法 JSON 但顶层不是对象：.jsonObject 抛 IllegalStateException。
        listOf("123", "\"x\"", "[1]", "null", "true", "[]").forEach { text ->
            assertFailsWith<IllegalStateException>(
                "顶层 $text 不是对象，应抛 IllegalStateException"
            ) { MessageMetaJson.fromJsonString(text) }
        }
        assertEquals(MessageMeta(), MessageMetaJson.fromJsonString("{}"), "空对象应回默认 meta")
    }

    @Test
    fun `malformedJsonThrowsSerializationException`() {
        listOf("{", "[1,2", "{\"a\":}", "{\"a\":1", "not json", "{\"a\":}").forEach { text ->
            assertFailsWith<SerializationException>(
                "畸形 JSON $text 应抛 SerializationException"
            ) { MessageMetaJson.fromJsonString(text) }
        }
    }

    @Test
    fun `blankInputReturnsDefaultMeta`() {
        assertEquals(MessageMeta(), MessageMetaJson.fromJsonString(""))
        assertEquals(MessageMeta(), MessageMetaJson.fromJsonString("   \n\t "))
    }

    @Test
    fun `singleFieldCorruptionNeverDropsOtherFields`() {
        val random = Random(0x6F_2026)
        val base = baseMeta()
        val baseMap = MessageMetaJson.messageMetaMap(base)
        val baseObj = json.parseToJsonElement(MessageMetaJson.encodeMessageMeta(base)).jsonObject
        repeat(CORRUPT_ITERATIONS) { i ->
            val field = allFields[random.nextInt(allFields.size)]
            val corrupt = corruptValues[random.nextInt(corruptValues.size)]
            val corrupted = JsonObject(baseObj + (field to corrupt))
            val text = json.encodeToString(JsonObject.serializer(), corrupted)
            val back = MessageMetaJson.fromJsonString(text)
            val backMap = MessageMetaJson.messageMetaMap(back)
            assertEquals(
                baseMap - field, backMap - field,
                "fuzz 迭代 #$i：损坏字段 $field（值 $corrupt）时其余字段必须原样保留"
            )
        }
    }

    @Test
    fun `corruptedScalarFieldsCoerceSafely`() {
        // 字符串字段：数字/布尔按字面转字符串，对象/数组/null 回 null。
        fun parseField(field: String, value: kotlinx.serialization.json.JsonElement): MessageMeta {
            val text = json.encodeToString(
                JsonObject.serializer(),
                JsonObject(mapOf(field to value))
            )
            return MessageMetaJson.fromJsonString(text)
        }
        assertEquals("123", parseField("replyToId", JsonPrimitive(123)).replyToId)
        assertEquals("true", parseField("replyToId", JsonPrimitive(true)).replyToId)
        assertEquals(null, parseField("replyToId", JsonObject(mapOf("x" to JsonPrimitive(1)))).replyToId)
        assertEquals(null, parseField("replyToId", JsonArray(listOf(JsonPrimitive(1)))).replyToId)
        assertEquals(null, parseField("replyToId", JsonNull).replyToId)
        // Long 字段：数字字面可解析、非数字字符串回 null。
        assertEquals(123L, parseField("voiceDurationMs", JsonPrimitive(123)).voiceDurationMs)
        assertEquals(4_200L, parseField("voiceDurationMs", JsonPrimitive("4200")).voiceDurationMs)
        assertEquals(null, parseField("voiceDurationMs", JsonPrimitive("abc")).voiceDurationMs)
        assertEquals(null, parseField("voiceDurationMs", JsonPrimitive(true)).voiceDurationMs)
        // 布尔字段：只有 "true" 字面（或布尔 true）为真，其余回 false。
        assertTrue(parseField("aiAssisted", JsonPrimitive("true")).aiAssisted)
        assertTrue(parseField("aiAssisted", JsonPrimitive(true)).aiAssisted)
        assertEquals(false, parseField("aiAssisted", JsonPrimitive(123)).aiAssisted)
        assertEquals(false, parseField("aiAssisted", JsonPrimitive("yes")).aiAssisted)
        assertEquals(false, parseField("aiAssisted", JsonObject(mapOf("x" to JsonPrimitive(1)))).aiAssisted)
    }

    @Test
    fun `corruptedContainerFieldsDropOnlyThatField`() {
        // mentions 不是数组 → 空；数组内非字符串标量按字面转、对象/数组/null 丢弃。
        val mentionsText = "{\"mentions\":[1,true,null,\"a\",{\"x\":1},[2]]}"
        assertEquals(listOf("1", "true", "a"), MessageMetaJson.fromJsonString(mentionsText).mentions)
        assertEquals(emptyList(), MessageMetaJson.fromJsonString("{\"mentions\":\"u1\"}").mentions)
        assertEquals(emptyList(), MessageMetaJson.fromJsonString("{\"mentions\":{\"x\":1}}").mentions)
        // map 字段：数字值按字面转、null/空白值丢弃、整字段非对象回空。
        val translationsText = "{\"translations\":{\"en\":5,\"ja\":null,\"fr\":\"  \",\"de\":\"gut\"}}"
        assertEquals(
            mapOf("en" to "5", "de" to "gut"),
            MessageMetaJson.fromJsonString(translationsText).translations
        )
        assertEquals(emptyMap(), MessageMetaJson.fromJsonString("{\"translations\":\"x\"}").translations)
        // inlineKeyboard：非数组行、非对象按钮、空白文本按钮整项丢弃。
        val keyboardText = "{\"inlineKeyboard\":[[{\"text\":\"ok\",\"callbackData\":\"c\"}],{\"text\":\"x\"},[\"str\"],[{\"text\":\"  \"}]]}"
        assertEquals(
            listOf(listOf(InlineKeyboardButton("ok", "c"))),
            MessageMetaJson.fromJsonString(keyboardText).inlineKeyboard
        )
        // callback_data 是 callbackData 的别名；两者都在时 callbackData 优先。
        val aliasText = "{\"inlineKeyboard\":[[{\"text\":\"ok\",\"callback_data\":\"cb2\"}]]}"
        assertEquals("cb2", MessageMetaJson.fromJsonString(aliasText).inlineKeyboard[0][0].callbackData)
        val bothText = "{\"inlineKeyboard\":[[{\"text\":\"ok\",\"callbackData\":\"cb1\",\"callback_data\":\"cb2\"}]]}"
        assertEquals("cb1", MessageMetaJson.fromJsonString(bothText).inlineKeyboard[0][0].callbackData)
    }

    @Test
    fun `inlineKeyboardTruncationBoundaries`() {
        listOf(63 to 63, 64 to 64, 65 to 64).forEach { (textLen, expectedTextLen) ->
            listOf(127 to 127, 128 to 128, 129 to 128).forEach { (cbLen, expectedCbLen) ->
                val meta = MessageMeta(
                    inlineKeyboard = listOf(
                        listOf(InlineKeyboardButton("t".repeat(textLen), "c".repeat(cbLen)))
                    )
                )
                val back = MessageMetaJson.fromJsonString(MessageMetaJson.encodeMessageMeta(meta))
                val button = back.inlineKeyboard[0][0]
                assertEquals("t".repeat(expectedTextLen), button.text, "文本 $textLen 应截为 $expectedTextLen")
                assertEquals("c".repeat(expectedCbLen), button.callbackData, "回调 $cbLen 应截为 $expectedCbLen")
            }
        }
        // emoji 按 UTF-16 代码单元截断：80 单元截后恰为 64 单元（可能拆散代理对，只断言长度）。
        val emojiMeta = MessageMeta(
            inlineKeyboard = listOf(listOf(InlineKeyboardButton("🎉".repeat(40), "c")))
        )
        val emojiBack = MessageMetaJson.fromJsonString(MessageMetaJson.encodeMessageMeta(emojiMeta))
        assertEquals(64, emojiBack.inlineKeyboard[0][0].text.length, "emoji 文本截断后应为 64 个 UTF-16 单元")
    }

    @Test
    fun `parsingRandomValidObjectsIsDeterministicAndIdempotent`() {
        val random = Random(0x6F_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val keys = allFields.shuffled(random).take(random.nextInt(0, 6)) +
                List(random.nextInt(0, 3)) { "unknown${random.nextInt(100)}" }
            val obj = JsonObject(keys.associateWith { randomValue(random, 2) }.mapValues { (_, v) ->
                when (v) {
                    null -> JsonNull
                    is Boolean -> JsonPrimitive(v)
                    is Int -> JsonPrimitive(v)
                    is Long -> JsonPrimitive(v)
                    is Double -> JsonPrimitive(v)
                    is String -> JsonPrimitive(v)
                    is List<*> -> JsonArray(v.map { JsonPrimitive(it.toString()) })
                    is Map<*, *> -> JsonObject(v.mapKeys { it.key.toString() }.mapValues { JsonPrimitive(it.value.toString()) })
                    else -> JsonPrimitive(v.toString())
                }
            })
            val text = json.encodeToString(JsonObject.serializer(), obj)
            val first = MessageMetaJson.fromJsonString(text)
            assertEquals(first, MessageMetaJson.fromJsonString(text), "fuzz 迭代 #$i：同一对象两次解析必须相同")
            assertEquals(
                first,
                MessageMetaJson.fromJsonString(MessageMetaJson.encodeMessageMeta(first)),
                "fuzz 迭代 #$i：解析结果再编码解析必须稳定（有损面已收敛）"
            )
        }
    }
}
