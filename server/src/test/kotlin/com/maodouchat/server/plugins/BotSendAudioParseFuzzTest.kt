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

/**
 * Bot `sendAudio` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，G355
 * `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、`sendPhoto`、
 * `sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、`sendVenue`、
 * `sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、
 * `sendChecklist`、`sendAlert`、`sendCountdown`、`sendNotice`、`sendBadge`、
 * `sendToast`、`sendHr`、`sendDivider`、`sendProgress`、`send*Hint`、
 * `sendMentionCard`/`sendNudgeCard`、`sendMetric`/`sendCompare`、`sendKeyValue`、
 * `sendQuoteCard`、`sendBanner`、`sendJsonCard`、`sendMarkdown`、`sendQuote`、
 * `sendCode`、`setMessageReaction`、`starMessage`、`sendStatus`、`sendTable`、
 * `sendAnimation` 之后第四十三块）。
 *
 * 本测试直接钉住纯函数 ([parseBotSendAudioFields])、解码
 * ([decodeBotAudioSize]) 与组装 ([buildBotAudioContent]) 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 吸取第四十一块（`sendTable`）的 CI 教训：fuzz 基 payload 的**必填字段必须
 *   确定性合法**——全随机时已知字段可能被丢弃/变空 → `MissingRequired` →
 *   `okOf` 的 `as Ok` 强转抛 `ClassCastException`。这里 b64 用确定性 base64 编码恒合法，
 *   合并必填语义由 `missingRequiredSemantics` 钉住。
 * - 钉住 **chatId / caption 无 `trim()`**（`" c1 "` 原样通过、原样进下游），而
 *   **title 有 `trim()`**（`" t "`→`"t"`）——两处故意不同，逐字保留。
 * - 钉住 **title 双别名**（`title`→`fileName`）+ **截 80** 与 **duration 双别名**
 *   （`duration`→`durationSec`）+ **`toIntOrNull()` 回 0**（非数字不抛）。
 * - 钉住 **b64 三别名优先级**（`audioBase64`→`fileBase64`→`data`）与
 *   **显式 null 不回退**的怪语义（`JsonNull` 是 `JsonPrimitive`，得字面 `"null"`→Ok；
 *   `"null"` 恰是合法 base64 字母表，解码得 3 字节）。
 * - 钉住 **base64 解码**：data-URI 前缀剥离、空白剔除、坏 base64 宽容判 0（不抛，
 *   与 sendPhoto 的 400 故意不同）。
 * - 钉住**内容组装**：`"🎵 audio"` + 非空 title 段 + duration>0 的 `"Ns"` 段 +
 *   size>0 的 `"(NB)"` 段 + 非空 caption 行 + `"[botAudioSize:N]"`，整体 4000 截断。
 * - 钉住**合并必填**（`chatId` 或 b64 缺/空/纯空白 → `MissingRequired`）。
 * - 反证坏类型大声失败：对象 / 数组型 `chatId`、`title`、`duration`、`caption`、
 *   别名键在 `?.jsonPrimitive` 处抛 [IllegalArgumentException]（路由层 `StatusPages`
 *   映射为 400「参数无效」，不是 500）；显式 null 不抛的反证。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
class BotSendAudioParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES =
            setOf(
                "chatId", "title", "fileName", "duration", "durationSec",
                "caption", "audioBase64", "fileBase64", "data",
            )
        private const val ITERATIONS = 150
        private val B64_ALIASES = listOf("audioBase64", "fileBase64", "data")
    }

    private fun parseOf(obj: JsonObject): BotSendAudioFieldsResult =
        parseBotSendAudioFields(obj)

    private fun okOf(obj: JsonObject): BotSendAudioFields =
        (parseOf(obj) as BotSendAudioFieldsResult.Ok).fields

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

    private fun basePayload(random: Random, i: Int): Pair<MutableMap<String, JsonElement>, BotSendAudioFields> {
        val base = mutableMapOf<String, JsonElement>()
        // 必填字段确定性合法（第四十一块 CI 教训：全随机可能触发 MissingRequired）。
        // b64 轮换三个别名键，钉住优先级语义在期望重算里自然覆盖。
        val raw = "aud" + i
        val b64 = java.util.Base64.getEncoder().encodeToString(raw.toByteArray())
        base[B64_ALIASES[i % B64_ALIASES.size]] = JsonPrimitive(b64)
        base["chatId"] = JsonPrimitive("c" + i)
        // title 有 trim：首尾空格被吃掉；caption 无 trim：原样保留。
        val title = " t" + i + " "
        val caption = " cap" + i + " "
        base["title"] = JsonPrimitive(title)
        val duration = i % 301
        base["duration"] = JsonPrimitive(duration)
        base["caption"] = JsonPrimitive(caption)
        // 程序化注入未知字段：永不破坏 JSON 语法。
        repeat(random.nextInt(0, 6)) {
            base[randomName(random)] = randomScalar(random)
        }
        return base to BotSendAudioFields("c" + i, "t" + i, duration, caption, b64)
    }

    @Test
    fun fuzzUnknownKeysIgnoredAndKnownSemanticsHold() {
        val random = Random(20261002)
        repeat(ITERATIONS) { i ->
            val (base, expected) = basePayload(random, i)
            val fields = okOf(JsonObject(base))
            assertEquals(expected.chatId, fields.chatId, "未知键不得污染 chatId，迭代 " + i)
            assertEquals(expected.title, fields.title, "未知键不得污染 title，迭代 " + i)
            assertEquals(expected.duration, fields.duration, "未知键不得污染 duration，迭代 " + i)
            assertEquals(expected.caption, fields.caption, "未知键不得污染 caption，迭代 " + i)
            assertEquals(expected.fileBase64, fields.fileBase64, "未知键不得污染 b64，迭代 " + i)
            assertEquals(
                ("aud" + i).toByteArray().size,
                decodeBotAudioSize(fields.fileBase64),
                "解码字节数须与原文一致，迭代 " + i,
            )
        }
    }

    @Test
    fun missingRequiredSemantics() {
        val b64 = mapOf("audioBase64" to JsonPrimitive("aGk="))
        val chatId = mapOf("chatId" to JsonPrimitive("c1"))
        // chatId 缺 / 空 / 纯空白 → MissingRequired（b64 合法时）。
        assertTrue(parseOf(JsonObject(b64)) is BotSendAudioFieldsResult.MissingRequired)
        assertTrue(
            parseOf(JsonObject(b64 + ("chatId" to JsonPrimitive("")))) is BotSendAudioFieldsResult.MissingRequired
        )
        assertTrue(
            parseOf(JsonObject(b64 + ("chatId" to JsonPrimitive("   ")))) is BotSendAudioFieldsResult.MissingRequired
        )
        // b64 缺 / 空 / 纯空白 → MissingRequired（chatId 合法时）。
        assertTrue(parseOf(JsonObject(chatId)) is BotSendAudioFieldsResult.MissingRequired)
        assertTrue(
            parseOf(JsonObject(chatId + ("audioBase64" to JsonPrimitive("")))) is BotSendAudioFieldsResult.MissingRequired
        )
        assertTrue(
            parseOf(JsonObject(chatId + ("data" to JsonPrimitive("  ")))) is BotSendAudioFieldsResult.MissingRequired
        )
        // 两端合法 → Ok，原样（可选字段全缺省）。
        val ok = okOf(JsonObject(chatId + b64))
        assertEquals("c1", ok.chatId)
        assertEquals("", ok.title)
        assertEquals(0, ok.duration)
        assertEquals("", ok.caption)
        assertEquals("aGk=", ok.fileBase64)
    }

    @Test
    fun trimSemantics() {
        val ok = okOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive(" c1 "),
                    "title" to JsonPrimitive(" t1 "),
                    "caption" to JsonPrimitive(" cap "),
                    "audioBase64" to JsonPrimitive("aGk="),
                )
            )
        )
        // chatId / caption 无 trim：原样通过、原样进下游。
        assertEquals(" c1 ", ok.chatId)
        assertEquals(" cap ", ok.caption)
        // title 有 trim：逐字保留的故意不同语义。
        assertEquals("t1", ok.title)
    }

    @Test
    fun titleAliasPriorityAndTruncation() {
        val base = mapOf(
            "chatId" to JsonPrimitive("c1"),
            "audioBase64" to JsonPrimitive("aGk="),
        )
        // title 优先于 fileName。
        assertEquals(
            "real",
            okOf(JsonObject(base + mapOf("title" to JsonPrimitive("real"), "fileName" to JsonPrimitive("alt")))).title
        )
        // 只有 fileName 时回退。
        assertEquals(
            "alt",
            okOf(JsonObject(base + mapOf("fileName" to JsonPrimitive("alt")))).title
        )
        // 截 80（先 trim 后截，逐字顺序）。
        val long = okOf(JsonObject(base + mapOf("title" to JsonPrimitive("x".repeat(100))))).title
        assertEquals(80, long.length)
        // 显式 null 得字面量 "null"（trim 后仍非空，保留为标题）。
        assertEquals(
            "null",
            okOf(JsonObject(base + mapOf("title" to JsonNull))).title
        )
        // 纯空白 title → 空串（组装时省段）。
        assertEquals(
            "",
            okOf(JsonObject(base + mapOf("title" to JsonPrimitive("   ")))).title
        )
    }

    @Test
    fun durationAliasPriorityAndCoercion() {
        val base = mapOf(
            "chatId" to JsonPrimitive("c1"),
            "audioBase64" to JsonPrimitive("aGk="),
        )
        fun durationOf(vararg pairs: Pair<String, JsonElement>): Int =
            okOf(JsonObject(base + pairs.toMap())).duration
        // duration 优先于 durationSec。
        assertEquals(10, durationOf("duration" to JsonPrimitive(10), "durationSec" to JsonPrimitive(20)))
        // 只有 durationSec 时回退。
        assertEquals(20, durationOf("durationSec" to JsonPrimitive(20)))
        // 字符串数字照收。
        assertEquals(65, durationOf("duration" to JsonPrimitive("65")))
        // 非数字 → toIntOrNull 得 null → 回 0（不抛，逐字语义）。
        assertEquals(0, durationOf("duration" to JsonPrimitive("abc")))
        assertEquals(0, durationOf("duration" to JsonPrimitive("12.5")))
        assertEquals(0, durationOf("duration" to JsonPrimitive("")))
        // 显式 null → 字面 "null" → toIntOrNull null → 0。
        assertEquals(0, durationOf("duration" to JsonNull))
        // 缺省 → 0。
        assertEquals(0, durationOf())
        // 负数原样保留（组装时 duration>0 才拼时长段，逐字语义）。
        assertEquals(-5, durationOf("duration" to JsonPrimitive(-5)))
    }

    @Test
    fun b64AliasPriorityAndExplicitNullNoFallback() {
        val chatId = "chatId" to JsonPrimitive("c1")
        fun withAliases(vararg pairs: Pair<String, JsonElement>) =
            okOf(JsonObject(mapOf(chatId) + pairs.toMap()))
        // 优先级：audioBase64 > fileBase64 > data。
        val onlyData = withAliases("data" to JsonPrimitive("ZGF0YQ=="))
        assertEquals("ZGF0YQ==", onlyData.fileBase64)
        val twoAliases = withAliases(
            "data" to JsonPrimitive("ZGF0YQ=="),
            "fileBase64" to JsonPrimitive("ZmlsZQ=="),
        )
        assertEquals("ZmlsZQ==", twoAliases.fileBase64)
        val topAlias = withAliases(
            "data" to JsonPrimitive("ZGF0YQ=="),
            "fileBase64" to JsonPrimitive("ZmlsZQ=="),
            "audioBase64" to JsonPrimitive("YXVkaW8="),
        )
        assertEquals("YXVkaW8=", topAlias.fileBase64)
        // 显式 JSON null 不回退：JsonNull 是 JsonPrimitive，得字面 "null"→Ok（逐字怪语义）。
        val nullBlocks = withAliases(
            "audioBase64" to JsonNull,
            "fileBase64" to JsonPrimitive("ZmlsZQ=="),
        )
        assertEquals("null", nullBlocks.fileBase64)
        // "null" 恰是合法 base64 字母表：解码得 3 字节。
        assertEquals(3, decodeBotAudioSize(nullBlocks.fileBase64))
    }

    @Test
    fun base64DecodeSemantics() {
        val b64 = java.util.Base64.getEncoder().encodeToString("hello audio".toByteArray())
        // data-URI 前缀剥离。
        assertEquals(11, decodeBotAudioSize("data:audio/mpeg;base64," + b64))
        // 空白剔除。
        val spaced = b64.substring(0, 4) + " \n\t" + b64.substring(4)
        assertEquals(11, decodeBotAudioSize(spaced))
        // 坏 base64 宽容判 0，不抛（与 sendPhoto 的 400 故意不同）。
        assertEquals(0, decodeBotAudioSize("!!!not-base64!!!"))
        // 空串判 0。
        assertEquals(0, decodeBotAudioSize(""))
        assertEquals(0, decodeBotAudioSize("   "))
    }

    @Test
    fun audioContentAssembly() {
        // 全字段：title 段 + 时长段 + 体积段 + caption 行 + 标记。
        assertEquals(
            "🎵 audio t 65s (11B)\ncap\n[botAudioSize:11]",
            buildBotAudioContent("t", 65, "cap", 11)
        )
        // 全缺省：只剩头与标记。
        assertEquals(
            "🎵 audio\n[botAudioSize:0]",
            buildBotAudioContent("", 0, "   ", 0)
        )
        // duration ≤ 0 不拼时长段（负数逐字语义）；size=0 无 " (0B)" 后缀。
        assertEquals(
            "🎵 audio t\ncap\n[botAudioSize:0]",
            buildBotAudioContent("t", -5, "cap", 0)
        )
        // 整体 4000 截断。
        val long = buildBotAudioContent("t", 65, "x".repeat(5000), 11)
        assertEquals(4000, long.length)
        assertTrue(long.startsWith("🎵 audio t 65s (11B)\n"))
    }

    @Test
    fun nullAndLoudFailures() {
        val b64 = mapOf("audioBase64" to JsonPrimitive("aGk="))
        // chatId 显式 null → 字面 "null"（不抛、非空）→ Ok。
        val ok = okOf(JsonObject(mapOf("chatId" to JsonNull) + b64))
        assertEquals("null", ok.chatId)
        // 对象 / 数组型 chatId 在 ?.jsonPrimitive 处抛 IllegalArgumentException。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonObject(mapOf())) + b64))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonArray(emptyList())) + b64))
        }
        // 对象型 title / caption / duration 同样大声失败（?.jsonPrimitive）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "title" to JsonObject(mapOf()),
                    ) + b64
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "duration" to JsonObject(mapOf()),
                    ) + b64
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "caption" to JsonArray(emptyList()),
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
                        "audioBase64" to JsonObject(mapOf()),
                    )
                )
            )
        }
        // 反证：显式 null 不抛（b64AliasPriorityAndExplicitNullNoFallback 已钉 Ok）。
        assertTrue(
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "audioBase64" to JsonNull,
                    )
                )
            ) is BotSendAudioFieldsResult.Ok
        )
    }
}
