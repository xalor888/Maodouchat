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
