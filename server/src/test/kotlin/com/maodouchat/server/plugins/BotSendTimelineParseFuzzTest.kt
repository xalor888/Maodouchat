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
 * Bot `sendTimeline` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，G355
 * `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、
 * `sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、
 * `sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、
 * `sendChecklist`、`sendAlert`、`sendCountdown`、`sendNotice`、`sendBadge`、
 * `sendToast`、`sendHr`、`sendDivider`、`sendProgress`、`send*Hint`、
 * `sendMentionCard`/`sendNudgeCard`、`sendMetric`/`sendCompare`、`sendKeyValue`、
 * `sendQuoteCard`、`sendBanner`、`sendJsonCard`、`sendMarkdown`、`sendQuote`、
 * `sendCode`、`setMessageReaction`、`starMessage`、`sendStatus`、`sendTable`、
 * `sendAnimation`、`sendAudio`、`editMessageCaption` 之后第四十五块）。
 *
 * 本测试直接钉住纯函数 ([parseBotSendTimelineFields]) 与组装
 * ([buildBotTimelineContent]) 的生产语义：
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
 *   `okOf` 的 `as Ok` 强转抛 `ClassCastException`。这里 chatId 用 `"c" + i` 恒合法、
 *   items 用确定性字符串数组恒非空，合并必填语义由 `missingRequiredSemantics` 钉住。
 * - 钉住 **chatId / title 无 `trim()`**（`" c "` 原样通过、原样进下游）与
 *   **title 默认文案只在键缺席时回退**（显式 JSON null 得字面 `"null"`，不回退，特意钉住）、
 *   **截 80**（先取后截、前导空格计入上限）。
 * - 钉住 **items 的怪语义**：键缺席→空列表；显式 JSON null / 对象型在 `?.jsonArray`
 *   处大声失败；数组元素里非 primitive（对象/数组）**静默丢弃**（与坏类型大声失败的
 *   已知字段故意不同），`JsonNull` 元素得字面 `"null"`（保留）；逐项 `.take(120)`、
 *   全表 `.take(12)`（先逐项截、再取前 12，逐字顺序）。
 * - 钉住**合并必填**（`chatId` 空白或 items 为空→`MissingRequired`——items 为空
 *   可能是「全被静默丢弃」的结果，逐字语义）。
 * - 钉住**内容组装**：`"### " + title + "\n" + lines`，
 *   `lines` 为 `<序号>. <条目>` 逐行拼接（序号从 1 起）。
 * - 反证坏类型大声失败：对象 / 数组型 `chatId`、`title`、对象型 `items` 在
 *   `?.jsonPrimitive` / `?.jsonArray` 处抛 [IllegalArgumentException]（路由层
 *   `StatusPages` 映射为 400「参数无效」，不是 500）；显式 null 不抛的反证。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
class BotSendTimelineParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES =
            setOf(
                "chatId", "title", "items",
            )
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotSendTimelineFieldsResult =
        parseBotSendTimelineFields(obj)

    private fun okOf(obj: JsonObject): BotSendTimelineFields =
        (parseOf(obj) as BotSendTimelineFieldsResult.Ok).fields

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

    private fun basePayload(random: Random, i: Int): Pair<MutableMap<String, JsonElement>, BotSendTimelineFields> {
        val base = mutableMapOf<String, JsonElement>()
        // 必填字段确定性合法（第四十一块 CI 教训）：chatId 恒为 "c<i>"、items 恒为
        // 三个确定性字符串条目——未知键注入永不触达必填。
        base["chatId"] = JsonPrimitive("c" + i)
        // title 无 trim：首尾空格原样保留；取 " t<i> " 钉住该语义。
        val title = " t" + i + " "
        base["title"] = JsonPrimitive(title)
        // i % 3 == 0 时塞一个超长条目（150 字符→截 120），钉住逐项截断。
        val longItem = "x".repeat(150)
        val items = listOf("a" + i, if (i % 3 == 0) longItem else "b" + i, "d" + i)
        base["items"] = JsonArray(items.map { JsonPrimitive(it) })
        // 程序化注入未知字段：永不破坏 JSON 语法。
        repeat(random.nextInt(0, 6)) {
            base[randomName(random)] = randomScalar(random)
        }
        val expectedItems = items.map { it.take(120) }
        return base to BotSendTimelineFields("c" + i, title, expectedItems)
    }

    @Test
    fun fuzzUnknownKeysIgnoredAndKnownSemanticsHold() {
        val random = Random(20261003)
        repeat(ITERATIONS) { i ->
            val (base, expected) = basePayload(random, i)
            val fields = okOf(JsonObject(base))
            assertEquals(expected.chatId, fields.chatId, "未知键不得污染 chatId，迭代 " + i)
            assertEquals(expected.title, fields.title, "未知键不得污染 title，迭代 " + i)
            assertEquals(expected.items, fields.items, "未知键不得污染 items，迭代 " + i)
            // 组装恒等：未知键不得污染正文。
            val expectedContent = "### " + expected.title + "\n" +
                expected.items.mapIndexed { idx, t -> (idx + 1).toString() + ". " + t }
                    .joinToString("\n")
            assertEquals(
                expectedContent,
                buildBotTimelineContent(fields.title, fields.items),
                "未知键不得污染组装正文，迭代 " + i,
            )
        }
    }

    @Test
    fun missingRequiredSemantics() {
        val chatId = mapOf("chatId" to JsonPrimitive("c1"))
        val items = mapOf("items" to JsonArray(listOf(JsonPrimitive("x"))))
        // chatId 缺 / 空 / 纯空白 → MissingRequired（items 合法时）。
        assertTrue(parseOf(JsonObject(items)) is BotSendTimelineFieldsResult.MissingRequired)
        assertTrue(
            parseOf(JsonObject(items + ("chatId" to JsonPrimitive("")))) is BotSendTimelineFieldsResult.MissingRequired
        )
        assertTrue(
            parseOf(JsonObject(items + ("chatId" to JsonPrimitive("   ")))) is BotSendTimelineFieldsResult.MissingRequired
        )
        // items 缺 / 空数组 → MissingRequired（chatId 合法时）。
        assertTrue(parseOf(JsonObject(chatId)) is BotSendTimelineFieldsResult.MissingRequired)
        assertTrue(
            parseOf(JsonObject(chatId + ("items" to JsonArray(emptyList())))) is BotSendTimelineFieldsResult.MissingRequired
        )
        // items 全被静默丢弃（只剩对象/数组元素）→ 空列表 → MissingRequired（逐字语义）。
        val allDropped = JsonArray(
            listOf(
                JsonObject(mapOf("k" to JsonPrimitive("v"))),
                JsonArray(listOf(JsonPrimitive("z"))),
            )
        )
        assertTrue(
            parseOf(JsonObject(chatId + ("items" to allDropped))) is BotSendTimelineFieldsResult.MissingRequired
        )
        // 两端合法 → Ok：title 缺省回 "Timeline"。
        val ok = okOf(JsonObject(chatId + items))
        assertEquals("c1", ok.chatId)
        assertEquals("Timeline", ok.title)
        assertEquals(listOf("x"), ok.items)
        // 显式 JSON null 的 chatId 得字面 "null"（非空→Ok，逐字怪语义）。
        val nullChatId = okOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonNull,
                    "items" to JsonArray(listOf(JsonPrimitive("x"))),
                )
            )
        )
        assertEquals("null", nullChatId.chatId)
    }

    @Test
    fun titleDefaultTrimAndTruncation() {
        // 键缺席 → 默认文案 "Timeline"。
        val absent = okOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c1"),
                    "items" to JsonArray(listOf(JsonPrimitive("x"))),
                )
            )
        )
        assertEquals("Timeline", absent.title)
        // 显式 JSON null → 字面 "null"，不回退默认值（特意钉住）。
        val explicitNull = okOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c1"),
                    "title" to JsonNull,
                    "items" to JsonArray(listOf(JsonPrimitive("x"))),
                )
            )
        )
        assertEquals("null", explicitNull.title)
        // 无 trim：首尾空格原样保留；120 字符→截 80（前导空格计入上限）。
        val padded = okOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c1"),
                    "title" to JsonPrimitive("  " + "y".repeat(120)),
                    "items" to JsonArray(listOf(JsonPrimitive("x"))),
                )
            )
        )
        assertEquals(("  " + "y".repeat(120)).take(80), padded.title)
        assertEquals(80, padded.title.length)
    }

    @Test
    fun itemsElementSemantics() {
        fun parseItems(items: JsonElement): BotSendTimelineFieldsResult =
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c1"),
                        "items" to items,
                    )
                )
            )
        // 逐项截 120、全表取前 12（先逐项截、再取前 12，逐字顺序）。
        val many = JsonArray(List(20) { JsonPrimitive("i" + it + "_" + "z".repeat(150)) })
        val ok = (parseItems(many) as BotSendTimelineFieldsResult.Ok).fields
        assertEquals(12, ok.items.size)
        assertTrue(ok.items.all { it.length <= 120 }, "逐项须截 120")
        assertEquals(("i0_" + "z".repeat(150)).take(120), ok.items[0])
        assertEquals(("i11_" + "z".repeat(150)).take(120), ok.items[11])
        // 混合元素：布尔/数字/字符串/JsonNull 保留（字面量），对象/数组静默丢弃。
        val mixed = JsonArray(
            listOf(
                JsonPrimitive(true),
                JsonPrimitive(42),
                JsonPrimitive(1.5),
                JsonPrimitive("s"),
                JsonNull,
                JsonObject(mapOf("k" to JsonPrimitive("v"))),
                JsonArray(listOf(JsonPrimitive("z"))),
            )
        )
        val mixedOk = (parseItems(mixed) as BotSendTimelineFieldsResult.Ok).fields
        assertEquals(
            listOf("true", "42", "1.5", "s", "null"),
            mixedOk.items,
            "非 primitive 元素静默丢弃、JsonNull 得字面 null",
        )
        // 显式 JSON null 的 items 在 ?.jsonArray 处大声失败（逐字语义）。
        assertFailsWith<IllegalArgumentException> { parseItems(JsonNull) }
        // 对象型 items 同样大声失败。
        assertFailsWith<IllegalArgumentException> {
            parseItems(JsonObject(mapOf("k" to JsonPrimitive("v"))))
        }
    }

    @Test
    fun loudFailureOnBadKnownFieldTypes() {
        fun parseWith(chatId: JsonElement, title: JsonElement): BotSendTimelineFieldsResult =
            parseOf(
                JsonObject(
                    mapOf(
                        "chatId" to chatId,
                        "title" to title,
                        "items" to JsonArray(listOf(JsonPrimitive("x"))),
                    )
                )
            )
        // 对象 / 数组型 chatId、title 在 ?.jsonPrimitive 处抛 IllegalArgumentException
        //（路由层 StatusPages 映射为 400「参数无效」，不是 500）。
        assertFailsWith<IllegalArgumentException> {
            parseWith(JsonObject(mapOf("k" to JsonPrimitive("v"))), JsonPrimitive("t"))
        }
        assertFailsWith<IllegalArgumentException> {
            parseWith(JsonArray(listOf(JsonPrimitive("z"))), JsonPrimitive("t"))
        }
        assertFailsWith<IllegalArgumentException> {
            parseWith(JsonPrimitive("c"), JsonObject(mapOf("k" to JsonPrimitive("v"))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseWith(JsonPrimitive("c"), JsonArray(listOf(JsonPrimitive("z"))))
        }
        // 显式 null 不抛的反证：chatId 得字面 "null"、title 得字面 "null"。
        val ok = okOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonNull,
                    "title" to JsonNull,
                    "items" to JsonArray(listOf(JsonPrimitive("x"))),
                )
            )
        )
        assertEquals("null", ok.chatId)
        assertEquals("null", ok.title)
    }

    @Test
    fun contentAssembly() {
        // 单条目。
        assertEquals(
            "### T\n1. a",
            buildBotTimelineContent("T", listOf("a")),
        )
        // 多条目：序号从 1 起逐行。
        assertEquals(
            "### Timeline\n1. first\n2. second\n3. third",
            buildBotTimelineContent("Timeline", listOf("first", "second", "third")),
        )
        // title 与条目原样拼接：首尾空格、内嵌换行不做任何处理。
        assertEquals(
            "###   padded  \n1.  x  \n2. y\nz",
            buildBotTimelineContent("  padded  ", listOf("  x  ", "y\nz")),
        )
    }
}
