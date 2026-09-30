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
 * Bot `sendChecklist` 请求体手写解析的**模糊兼容性测试**（清单 Q01「协议模型向前/向后兼容与
 * fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、
 * `sendContact`、`sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、
 * `forwardMessage`/`copyMessage`、`sendNudge`、`sendContactCard`、`sendPollQuiz`、
 * `answerCallbackQuery` 之后第十九块）。
 *
 * Bot 路由没有 typed DTO——请求体是 `receiveBoundedTextOrEmpty()` 拿到的原始字符串，
 * 经 `Json.parseToJsonElement(body).jsonObject` 再手写抽取。本轮把原来内联在
 * `/api/bot/sendChecklist` 处理器里的抽取 / 校验 / 内容组装逻辑收敛为纯函数
 * ([parseBotSendChecklistFields] / [buildBotChecklistContent])
 * （生产侧零行为改动：校验顺序仍为 markdown 开关（处理器，先）→ 必填（纯函数）→
 * 成员检查（处理器）），本测试直接钉住这些函数的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 钉住 `title` 的 `.orEmpty().take(80)` **不 trim**（前导空格计入 80 上限），
 *   以及 `title` 非必填（空白只影响组装前缀，不判缺）。
 * - 钉住 `items` 的 `as? JsonArray` 分支（非数组不是错误而是 `emptyList()`→判缺）、
 *   元素 `trim()` + `take(80)` + 空白剔除 + `take(20)` 的逐字顺序、
 *   显式 JSON null 元素保留为字面 `"null"` 选项、对象/数组元素被 `(as? JsonPrimitive)`
 *   **静默丢弃**（不是大声失败——与已知字段类型错的语义不同，特意钉住）。
 * - 钉住内容模板形状（标题非空 `"**title**\n"` 前缀 + 每项 `"- [ ] item"` 换行拼接，
 *   整体无截断）。
 * - 反证 `wrong-typed known fields still fail loudly`：手写解析里 `?.jsonPrimitive`
 *   在类型错时抛 [IllegalArgumentException]，路由层 `StatusPages` 把它映射为 400
 *   「参数无效」（不是 500）——坏数据必须大声失败，不能悄悄吞掉。
 *   例外：`items` **元素**的类型错是静默丢弃（见上），`items` 本体非数组是
 *   `emptyList()`→判缺（也不是抛错）。
 */
class BotSendChecklistParseFuzzTest {

    private companion object {
        /** sendChecklist 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId", "title", "items",
        )
        private const val ITERATIONS = 150
    }

    private fun randomFieldName(random: Random): String {
        val stems = listOf(
            "future", "x", "v9", "extra", "unknown", "meta", "debug", "tmp",
            "clientExt", "appExt", "exp", "flag",
        )
        var name = "${stems.random(random)}_${random.nextInt(10000)}"
        while (name in KNOWN_FIELD_NAMES) name = "z$name"
        return name
    }

    private fun randomJsonValue(random: Random, depth: Int): JsonElement {
        val leafKinds = 5 // bool / long / double / string / null
        return when (random.nextInt(if (depth <= 0) leafKinds else leafKinds + 2)) {
            0 -> JsonPrimitive(random.nextBoolean())
            1 -> JsonPrimitive(random.nextLong())
            2 -> JsonPrimitive(random.nextDouble())
            3 -> JsonPrimitive("str_${random.nextInt(100000)}_${random.nextLong()}")
            4 -> JsonNull
            5 -> JsonArray(List(random.nextInt(1, 4)) { randomJsonValue(random, depth - 1) })
            else -> JsonObject(
                (0 until random.nextInt(1, 4))
                    .associate { randomFieldName(random) to randomJsonValue(random, depth - 1) }
            )
        }
    }

    /** 顶层注入 1–5 个未知字段。 */
    private fun injectUnknownFields(base: JsonObject, random: Random): JsonObject {
        val fields = base.toMutableMap()
        repeat(random.nextInt(1, 6)) {
            fields[randomFieldName(random)] = randomJsonValue(random, 2)
        }
        return JsonObject(fields)
    }

    private fun fieldsOf(obj: JsonObject): BotSendChecklistFields {
        val result = parseBotSendChecklistFields(obj)
        assertTrue(result is BotSendChecklistFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    private fun validBase(
        chatId: String = "c1",
        title: String = "今晚清单",
        items: List<String> = listOf("火锅", "烧烤"),
    ): JsonObject = JsonObject(
        mapOf(
            "chatId" to JsonPrimitive(chatId),
            "title" to JsonPrimitive(title),
            "items" to JsonArray(items.map { JsonPrimitive(it) }),
        )
    )

    @Test
    fun `sendChecklist parse survives seeded unknown-field fuzz`() {
        val random = Random(0x5E_ED)
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val title = "t-$i"
            val items = listOf("item-a-$i", "item-b-$i", "item-c-$i")
            val base = JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive(chatId),
                    "title" to JsonPrimitive(title),
                    "items" to JsonArray(items.map { JsonPrimitive(it) }),
                )
            )
            val obj = injectUnknownFields(base, random)
            val fields = fieldsOf(obj)
            assertEquals(
                BotSendChecklistFields(chatId, title, items),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
        }
    }

    @Test
    fun `sendChecklist required fields are enforced`() {
        val twoItems = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))
        // chatId 缺失 → MissingRequired
        assertTrue(
            parseBotSendChecklistFields(
                JsonObject(
                    mapOf(
                        "title" to JsonPrimitive("t"),
                        "items" to twoItems,
                    )
                )
            ) is BotSendChecklistFieldsResult.MissingRequired,
            "chatId 缺失必须判缺",
        )
        // chatId 空白 → MissingRequired
        assertTrue(
            parseBotSendChecklistFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("  "),
                        "title" to JsonPrimitive("t"),
                        "items" to twoItems,
                    )
                )
            ) is BotSendChecklistFieldsResult.MissingRequired,
            "chatId 空白必须判缺",
        )
        // items 缺失 → MissingRequired
        assertTrue(
            parseBotSendChecklistFields(
                JsonObject(mapOf("chatId" to JsonPrimitive("c")))
            ) is BotSendChecklistFieldsResult.MissingRequired,
            "items 缺失必须判缺",
        )
        // items 全空白元素 → 剔除后为空 → MissingRequired
        assertTrue(
            parseBotSendChecklistFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "items" to JsonArray(
                            listOf(JsonPrimitive("  "), JsonPrimitive(""), JsonPrimitive("\t"))
                        ),
                    )
                )
            ) is BotSendChecklistFieldsResult.MissingRequired,
            "items 全部空白必须判缺",
        )
        // title 缺失/空白不判缺（title 非必填）
        assertTrue(
            parseBotSendChecklistFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "items" to twoItems,
                    )
                )
            ) is BotSendChecklistFieldsResult.Ok,
            "title 缺失不得判缺",
        )
        assertTrue(
            parseBotSendChecklistFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "title" to JsonPrimitive("   "),
                        "items" to twoItems,
                    )
                )
            ) is BotSendChecklistFieldsResult.Ok,
            "title 空白不得判缺",
        )
        // 合法请求 → Ok
        assertTrue(
            parseBotSendChecklistFields(validBase()) is BotSendChecklistFieldsResult.Ok,
            "合法请求必须通过",
        )
    }

    @Test
    fun `sendChecklist title and item extraction quirks`() {
        val twoItems = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))
        fun withTitle(vararg pairs: Pair<String, JsonElement>): BotSendChecklistFields =
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "items" to twoItems,
                    ) + pairs.toMap()
                )
            )
        // title 不 trim：首尾空格原样保留
        assertEquals(
            "  前后空格  ",
            withTitle("title" to JsonPrimitive("  前后空格  ")).title,
            "title 必须不 trim，原样保留",
        )
        // take(80)：前导空格计入上限
        val long = " ".repeat(60) + "y".repeat(100)
        val truncated = withTitle("title" to JsonPrimitive(long)).title
        assertEquals(80, truncated.length, "title 必须截断到 80 字符")
        assertEquals(" ".repeat(60) + "y".repeat(20), truncated, "前导空格必须计入 80 上限")
        // 显式 JSON null 是 JsonPrimitive：content 为 "null" 字面字符串（不是缺省）
        assertEquals(
            "null",
            withTitle("title" to JsonNull).title,
            "显式 null 的 title 是 JsonPrimitive、content 为 \"null\"",
        )
        // items 元素 trim + 空白剔除
        fun withItems(items: JsonElement): BotSendChecklistFields =
            fieldsOf(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "title" to JsonPrimitive("t"),
                        "items" to items,
                    )
                )
            )
        assertEquals(
            listOf("火锅", "烧烤"),
            withItems(
                JsonArray(
                    listOf(
                        JsonPrimitive("  火锅  "),
                        JsonPrimitive("   "),
                        JsonPrimitive("烧烤"),
                    )
                )
            ).items,
            "选项元素必须 trim 并剔除空白",
        )
        // 元素 take(80)：截断发生在 trim 之后、空白剔除之前
        assertEquals(
            listOf("x".repeat(80), "y"),
            withItems(JsonArray(listOf(JsonPrimitive("x".repeat(100)), JsonPrimitive("y")))).items,
            "选项元素必须截断到 80 字符",
        )
        // take(20)：空白剔除之后再取前 20（剔掉 2 个空白后 25 个剩 23 个，只取前 20）
        val many = (1..25).map { JsonPrimitive("o$it") } +
            listOf(JsonPrimitive("  "), JsonPrimitive(""))
        assertEquals(
            (1..20).map { "o$it" },
            withItems(JsonArray(many)).items,
            "空白剔除后多出的必须只取前 20",
        )
        // 显式 JSON null 是 JsonPrimitive：content 为 "null" 字符串，保留为字面选项
        assertEquals(
            listOf("a", "null", "b"),
            withItems(JsonArray(listOf(JsonPrimitive("a"), JsonNull, JsonPrimitive("b")))).items,
            "显式 null 必须保留为字面 \"null\" 选项",
        )
        // 数字元素：content 字符串化后保留
        assertEquals(
            listOf("42", "b"),
            withItems(JsonArray(listOf(JsonPrimitive(42), JsonPrimitive("b")))).items,
            "数字元素必须按 content 字符串保留",
        )
        // 非原始类型元素（对象/数组）被 (as? JsonPrimitive) 静默丢弃——不抛错
        assertEquals(
            listOf("a", "b"),
            withItems(
                JsonArray(
                    listOf(
                        JsonPrimitive("a"),
                        JsonObject(mapOf("k" to JsonPrimitive("v"))),
                        JsonArray(listOf(JsonPrimitive("x"))),
                        JsonPrimitive("b"),
                    )
                )
            ).items,
            "对象/数组型选项元素必须被静默丢弃",
        )
        // items 非数组不是错误：emptyList → 判缺（不是抛错）
        assertTrue(
            parseBotSendChecklistFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "title" to JsonPrimitive("t"),
                        "items" to JsonPrimitive("not-an-array"),
                    )
                )
            ) is BotSendChecklistFieldsResult.MissingRequired,
            "items 非数组必须走 emptyList → 判缺，而不是抛错",
        )
    }

    @Test
    fun `sendChecklist content assembly keeps production shape`() {
        // 标题非空：**title**\n 前缀 + 每项 - [ ] 前缀
        assertEquals(
            "**今晚清单**\n- [ ] 火锅\n- [ ] 烧烤",
            buildBotChecklistContent("今晚清单", listOf("火锅", "烧烤")),
            "内容模板必须逐字一致：标题加粗换行 + 每项 - [ ] 前缀",
        )
        // 标题空白：只出列表，无前缀
        assertEquals(
            "- [ ] a",
            buildBotChecklistContent("   ", listOf("a")),
            "标题空白时不得有前缀",
        )
        assertEquals(
            "- [ ] a\n- [ ] b",
            buildBotChecklistContent("", listOf("a", "b")),
            "标题缺省时只出列表",
        )
        // 整体无截断（原处理器逐字如此）
        val content = buildBotChecklistContent("t".repeat(80), List(20) { "x".repeat(80) })
        assertTrue(content.length > 80 + 20 * 80, "内容整体不得截断")
    }

    @Test
    fun `sendChecklist wrong-typed known fields fail loudly`() {
        val twoItems = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))
        val badChatId = JsonObject(
            mapOf(
                "chatId" to JsonObject(mapOf("nested" to JsonPrimitive("x"))),
                "title" to JsonPrimitive("t"),
                "items" to twoItems,
            )
        )
        assertFailsWith<IllegalArgumentException>("对象型 chatId 必须抛 IllegalArgumentException") {
            parseBotSendChecklistFields(badChatId)
        }
        val badTitle = JsonObject(
            mapOf(
                "chatId" to JsonPrimitive("c"),
                "title" to JsonArray(emptyList()),
                "items" to twoItems,
            )
        )
        assertFailsWith<IllegalArgumentException>("数组型 title 必须抛 IllegalArgumentException") {
            parseBotSendChecklistFields(badTitle)
        }
        // items 本体类型错不是抛错：as? JsonArray → null → 判缺
        assertTrue(
            parseBotSendChecklistFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "title" to JsonPrimitive("t"),
                        "items" to JsonObject(mapOf("nested" to JsonPrimitive(1))),
                    )
                )
            ) is BotSendChecklistFieldsResult.MissingRequired,
            "对象型 items 本体必须走判缺而不是抛错",
        )
    }
}
