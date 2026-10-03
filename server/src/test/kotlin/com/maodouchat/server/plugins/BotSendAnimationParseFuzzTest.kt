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

class BotSendAnimationParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES =
            setOf("chatId", "caption", "animationBase64", "gifBase64", "fileBase64", "data")
        private const val ITERATIONS = 150
        private val B64_ALIASES = listOf("animationBase64", "gifBase64", "fileBase64", "data")
    }

    private fun parseOf(obj: JsonObject): BotSendAnimationFieldsResult =
        parseBotSendAnimationFields(obj)

    private fun okOf(obj: JsonObject): BotSendAnimationFields =
        (parseOf(obj) as BotSendAnimationFieldsResult.Ok).fields

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

    private fun basePayload(random: Random, i: Int): Pair<MutableMap<String, JsonElement>, BotSendAnimationFields> {
        val base = mutableMapOf<String, JsonElement>()
        // 必填字段确定性合法（第四十一块 CI 教训：全随机可能触发 MissingRequired）。
        // b64 轮换四个别名键，钉住优先级语义在期望重算里自然覆盖。
        val raw = "anim" + i
        val b64 = java.util.Base64.getEncoder().encodeToString(raw.toByteArray())
        base[B64_ALIASES[i % B64_ALIASES.size]] = JsonPrimitive(b64)
        base["chatId"] = JsonPrimitive("c" + i)
        // caption 无 trim：首尾空格原样保留。
        val caption = " cap" + i + " "
        base["caption"] = JsonPrimitive(caption)
        // 程序化注入未知字段：永不破坏 JSON 语法。
        repeat(random.nextInt(0, 6)) {
            base[randomName(random)] = randomScalar(random)
        }
        return base to BotSendAnimationFields("c" + i, caption, b64)
    }

    @Test
    fun fuzzUnknownKeysIgnoredAndKnownSemanticsHold() {
        val random = Random(20261001)
        repeat(ITERATIONS) { i ->
            val (base, expected) = basePayload(random, i)
            val fields = okOf(JsonObject(base))
            assertEquals(expected.chatId, fields.chatId, "未知键不得污染 chatId，迭代 " + i)
            assertEquals(expected.caption, fields.caption, "未知键不得污染 caption，迭代 " + i)
            assertEquals(expected.fileBase64, fields.fileBase64, "未知键不得污染 b64，迭代 " + i)
            assertEquals(
                ("anim" + i).toByteArray().size,
                decodeBotAnimationSize(fields.fileBase64),
                "解码字节数须与原文一致，迭代 " + i,
            )
        }
    }

    @Test
    fun missingRequiredSemantics() {
        val b64 = mapOf("animationBase64" to JsonPrimitive("aGk="))
        val chatId = mapOf("chatId" to JsonPrimitive("c1"))
        // chatId 缺 / 空 / 纯空白 → MissingRequired（b64 合法时）。
        assertTrue(parseOf(JsonObject(b64)) is BotSendAnimationFieldsResult.MissingRequired)
        assertTrue(
            parseOf(JsonObject(b64 + ("chatId" to JsonPrimitive("")))) is BotSendAnimationFieldsResult.MissingRequired
        )
        assertTrue(
            parseOf(JsonObject(b64 + ("chatId" to JsonPrimitive("   ")))) is BotSendAnimationFieldsResult.MissingRequired
        )
        // b64 缺 / 空 / 纯空白 → MissingRequired（chatId 合法时）。
        assertTrue(parseOf(JsonObject(chatId)) is BotSendAnimationFieldsResult.MissingRequired)
        assertTrue(
            parseOf(JsonObject(chatId + ("animationBase64" to JsonPrimitive("")))) is BotSendAnimationFieldsResult.MissingRequired
        )
        assertTrue(
            parseOf(JsonObject(chatId + ("data" to JsonPrimitive("  ")))) is BotSendAnimationFieldsResult.MissingRequired
        )
        // 两端合法 → Ok，原样。
        val ok = okOf(JsonObject(chatId + b64))
        assertEquals("c1", ok.chatId)
        assertEquals("", ok.caption)
        assertEquals("aGk=", ok.fileBase64)
    }

    @Test
    fun chatIdAndCaptionHaveNoTrim() {
        val ok = okOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive(" c1 "),
                    "caption" to JsonPrimitive(" cap "),
                    "animationBase64" to JsonPrimitive("aGk="),
                )
            )
        )
        assertEquals(" c1 ", ok.chatId)
        assertEquals(" cap ", ok.caption)
    }

    @Test
    fun b64AliasPriorityAndExplicitNullNoFallback() {
        val chatId = "chatId" to JsonPrimitive("c1")
        fun withAliases(vararg pairs: Pair<String, JsonElement>) =
            okOf(JsonObject(mapOf(chatId) + pairs.toMap()))
        // 优先级：animationBase64 > gifBase64 > fileBase64 > data。
        val onlyData = withAliases("data" to JsonPrimitive("ZGF0YQ=="))
        assertEquals("ZGF0YQ==", onlyData.fileBase64)
        val twoAliases = withAliases(
            "data" to JsonPrimitive("ZGF0YQ=="),
            "fileBase64" to JsonPrimitive("ZmlsZQ=="),
        )
        assertEquals("ZmlsZQ==", twoAliases.fileBase64)
        val topAlias = withAliases(
            "data" to JsonPrimitive("ZGF0YQ=="),
            "gifBase64" to JsonPrimitive("Z2lm"),
            "animationBase64" to JsonPrimitive("YW5pbQ=="),
        )
        assertEquals("YW5pbQ==", topAlias.fileBase64)
        // 显式 JSON null 不回退：JsonNull 是 JsonPrimitive，得字面 "null"→Ok（逐字怪语义）。
        val nullBlocks = withAliases(
            "animationBase64" to JsonNull,
            "gifBase64" to JsonPrimitive("Z2lm"),
        )
        assertEquals("null", nullBlocks.fileBase64)
        // "null" 恰是合法 base64 字母表：解码得 3 字节。
        assertEquals(3, decodeBotAnimationSize(nullBlocks.fileBase64))
    }

    @Test
    fun base64DecodeSemantics() {
        val b64 = java.util.Base64.getEncoder().encodeToString("hello gif".toByteArray())
        // data-URI 前缀剥离。
        assertEquals(9, decodeBotAnimationSize("data:image/gif;base64," + b64))
        // 空白剔除。
        val spaced = b64.substring(0, 4) + " \n\t" + b64.substring(4)
        assertEquals(9, decodeBotAnimationSize(spaced))
        // 坏 base64 宽容判 0，不抛（与 sendPhoto 的 400 故意不同）。
        assertEquals(0, decodeBotAnimationSize("!!!not-base64!!!"))
        // 空串判 0。
        assertEquals(0, decodeBotAnimationSize(""))
        assertEquals(0, decodeBotAnimationSize("   "))
    }

    @Test
    fun animationContentAssembly() {
        // size>0：后缀 + caption 行 + 标记。
        assertEquals(
            "✨ gif/animation (9B)\nhi\n[botAnimSize:9]",
            buildBotAnimationContent("hi", 9)
        )
        // size=0：无 " (0B)" 后缀。
        assertEquals(
            "✨ gif/animation\nhi\n[botAnimSize:0]",
            buildBotAnimationContent("hi", 0)
        )
        // 空 caption 省行。
        assertEquals(
            "✨ gif/animation (9B)\n[botAnimSize:9]",
            buildBotAnimationContent("   ", 9)
        )
        // 整体 4000 截断。
        val long = buildBotAnimationContent("x".repeat(5000), 9)
        assertEquals(4000, long.length)
        assertTrue(long.startsWith("✨ gif/animation (9B)\n"))
    }

    @Test
    fun nullAndLoudFailures() {
        val b64 = mapOf("animationBase64" to JsonPrimitive("aGk="))
        val caption = mapOf("caption" to JsonPrimitive("cap"))
        // chatId 显式 null → 字面 "null"（不抛、非空）→ Ok。
        val ok = okOf(JsonObject(mapOf("chatId" to JsonNull) + b64 + caption))
        assertEquals("null", ok.chatId)
        // 对象 / 数组型 chatId 在 ?.jsonPrimitive 处抛 IllegalArgumentException。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonObject(mapOf())) + b64))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonArray(emptyList())) + b64))
        }
        // 对象型 caption 同样大声失败（?.jsonPrimitive）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "caption" to JsonObject(mapOf()),
                    ) + b64
                )
            )
        }
        // 对象型别名键同样大声失败（?: 不跳过非 null 的 JsonObject）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "animationBase64" to JsonObject(mapOf()),
                    )
                )
            )
        }
        // 反证：显式 null 不抛（上面 b64AliasPriorityAndExplicitNullNoFallback 已钉 Ok）。
        assertTrue(
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "animationBase64" to JsonNull,
                    )
                )
            ) is BotSendAnimationFieldsResult.Ok
        )
    }
}
