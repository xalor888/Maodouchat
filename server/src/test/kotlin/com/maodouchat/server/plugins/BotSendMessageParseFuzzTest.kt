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

class BotSendMessageParseFuzzTest {

    private companion object {
        /** sendMessage 解析涉及的全部已知字段名（含嵌套与别名）：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId", "text", "parseMode", "replyToMessageId", "silent",
            "replyMarkup", "reply_markup",
            "inlineKeyboard", "inline_keyboard",
            "forceReply", "force_reply",
            "callbackData", "callback_data",
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

    @Test
    fun `sendMessage parse survives seeded unknown-field fuzz`() {
        val random = Random(0xB055_2026)
        val parseModes = listOf("markdown", "MD", "Markdown", "", "HTML")
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val text = "hello-$i"
            val parseMode = parseModes[i % parseModes.size]
            val replyTo = if (i % 3 == 0) "msg-${i}x" else null
            val silent = i % 2 == 0
            // 键盘：1–3 行 × 1–3 按钮，全部合法形状，期望值可直接算出
            val rows = List(random.nextInt(1, 4)) { r ->
                List(random.nextInt(1, 4)) { c ->
                    "btn-${i}-${r}-${c}" to "cb-${i}-${r}-${c}"
                }
            }
            val markup = JsonObject(
                mapOf(
                    "inlineKeyboard" to JsonArray(
                        rows.map { row ->
                            JsonArray(
                                row.map { (t, d) ->
                                    JsonObject(
                                        mapOf(
                                            "text" to JsonPrimitive(t),
                                            "callbackData" to JsonPrimitive(d),
                                        )
                                    )
                                }
                            )
                        }
                    )
                )
            )
            val base = buildMap<String, JsonElement> {
                put("chatId", JsonPrimitive(chatId))
                put("text", JsonPrimitive(text))
                put("parseMode", JsonPrimitive(parseMode))
                if (replyTo != null) put("replyToMessageId", JsonPrimitive(replyTo))
                put("silent", JsonPrimitive(silent))
                put("replyMarkup", markup)
            }
            val obj = injectUnknownFields(JsonObject(base), random)
            val parsed = parseBotSendMessage(obj, silentSendEnabled = true)
            val expectedRows = rows.map { row ->
                row.map { (t, d) -> mapOf("text" to t, "callbackData" to d) }
            }
            assertEquals(
                BotSendMessageParsed(
                    chatId = chatId,
                    text = text,
                    parseMode = parseMode.uppercase(),
                    replyToId = replyTo,
                    silent = silent,
                    keyboardRows = expectedRows,
                    forceReply = false,
                ),
                parsed,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
        }
    }

    @Test
    fun `missing fields fall back to defaults`() {
        assertEquals(
            BotSendMessageParsed(
                chatId = "",
                text = "",
                parseMode = "",
                replyToId = null,
                silent = false,
                keyboardRows = null,
                forceReply = false,
            ),
            parseBotSendMessage(JsonObject(emptyMap()), silentSendEnabled = true),
            "空对象：缺省字段必须回默认值",
        )
    }

    @Test
    fun `wrong-typed known fields still fail loudly`() {
        // chatId 收对象：?.jsonPrimitive 抛 IllegalArgumentException（路由层映射为 400，不是 500）
        assertFailsWith<IllegalArgumentException>("chatId 类型错必须大声失败") {
            parseBotSendMessage(
                JsonObject(mapOf("chatId" to JsonObject(mapOf("v" to JsonPrimitive(1))))),
                silentSendEnabled = true,
            )
        }
        // text 收数组：同上
        assertFailsWith<IllegalArgumentException>("text 类型错必须大声失败") {
            parseBotSendMessage(
                JsonObject(mapOf("text" to JsonArray(listOf(JsonPrimitive("x"))))),
                silentSendEnabled = true,
            )
        }
        // parseMode 收对象：同上
        assertFailsWith<IllegalArgumentException>("parseMode 类型错必须大声失败") {
            parseBotSendMessage(
                JsonObject(mapOf("parseMode" to JsonObject(emptyMap()))),
                silentSendEnabled = true,
            )
        }
        // silent 收对象：booleanOrNull 之前先过 jsonPrimitive，同上
        assertFailsWith<IllegalArgumentException>("silent 类型错必须大声失败") {
            parseBotSendMessage(
                JsonObject(mapOf("silent" to JsonObject(emptyMap()))),
                silentSendEnabled = true,
            )
        }
        // replyMarkup 收字符串：?.jsonObject 抛 IllegalArgumentException，同上
        assertFailsWith<IllegalArgumentException>("replyMarkup 类型错必须大声失败") {
            parseBotSendMessage(
                JsonObject(mapOf("replyMarkup" to JsonPrimitive("nope"))),
                silentSendEnabled = true,
            )
        }
        // replyToMessageId 显式 null：JsonNull 本身是 JsonPrimitive（content == "null"），
        // ?.jsonPrimitive 不抛——这是搬移前的原有行为（main 上 BotCoreRouting.kt:57 同式），
        // 2026-09-29 CI 实证：assertFailsWith 在此失败。按"零行为改动"原则钉住原有语义，不改源码。
        assertEquals(
            "null",
            parseBotSendMessage(
                JsonObject(mapOf("replyToMessageId" to JsonNull)),
                silentSendEnabled = true,
            ).replyToId,
            "replyToMessageId 显式 null 沿用原有语义（JsonNull.content == \"null\"），不抛",
        )
    }

    @Test
    fun `snake_case aliases work and near-miss names are ignored`() {
        val parsed = parseBotSendMessage(
            JsonObject(
                mapOf(
                    "ChatId" to JsonPrimitive("WRONG"),
                    "chatId" to JsonPrimitive("c1"),
                    "text" to JsonPrimitive("hi"),
                    "reply_markup" to JsonObject(
                        mapOf(
                            "inline_keyboard" to JsonArray(
                                listOf(
                                    JsonArray(
                                        listOf(
                                            JsonObject(
                                                mapOf(
                                                    "text" to JsonPrimitive("ok"),
                                                    "callback_data" to JsonPrimitive("cb1"),
                                                )
                                            )
                                        )
                                    )
                                )
                            ),
                            "force_reply" to JsonPrimitive(true),
                        )
                    ),
                )
            ),
            silentSendEnabled = true,
        )
        assertEquals("c1", parsed.chatId, "近似字段名 ChatId 必须被忽略，只有精确命中的 chatId 生效")
        assertEquals(
            listOf(listOf(mapOf("text" to "ok", "callbackData" to "cb1"))),
            parsed.keyboardRows,
            "snake_case 别名 reply_markup/inline_keyboard/callback_data 必须生效",
        )
        assertTrue(parsed.forceReply, "snake_case 别名 force_reply 必须生效")
    }

    @Test
    fun `truncation caps are enforced`() {
        val longText = "t".repeat(5000)
        val longBtn = "b".repeat(100)
        val longCb = "d".repeat(200)
        val longReplyTo = "r".repeat(120)
        val rows = JsonArray(
            List(12) { r ->
                JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "text" to JsonPrimitive("$longBtn$r"),
                                "callbackData" to JsonPrimitive("$longCb$r"),
                            )
                        ),
                        // 空文本按钮整颗丢掉
                        JsonObject(mapOf("text" to JsonPrimitive("   "))),
                        // 非对象元素整颗丢掉
                        JsonPrimitive("junk"),
                    )
                )
            }
        )
        val parsed = parseBotSendMessage(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "text" to JsonPrimitive(longText),
                    "replyToMessageId" to JsonPrimitive(longReplyTo),
                    "replyMarkup" to JsonObject(mapOf("inlineKeyboard" to rows)),
                )
            ),
            silentSendEnabled = true,
        )
        assertEquals(4000, parsed.text.length, "text 必须截断到 4000")
        assertEquals(80, parsed.replyToId!!.length, "replyToMessageId 必须截断到 80")
        assertEquals(8, parsed.keyboardRows!!.size, "键盘最多保留 8 行")
        parsed.keyboardRows.forEach { row ->
            assertEquals(1, row.size, "每行只剩合法按钮：空文本与非对象元素被丢掉")
            assertEquals(64, row[0]["text"]!!.length, "按钮文本截断到 64")
            assertEquals(128, row[0]["callbackData"]!!.length, "callbackData 截断到 128")
        }
    }

    @Test
    fun `silent flag semantics are pinned`() {
        fun silentOf(value: JsonElement?, enabled: Boolean): Boolean =
            parseBotSendMessage(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "text" to JsonPrimitive("t"),
                    ) + if (value == null) emptyMap() else mapOf("silent" to value)
                ),
                silentSendEnabled = enabled,
            ).silent
        assertTrue(silentOf(JsonPrimitive(true), enabled = true), "silent=true 且开关开 → true")
        assertEquals(false, silentOf(JsonPrimitive(true), enabled = false), "开关关时 silent 被钳住为 false")
        assertEquals(false, silentOf(null, enabled = true), "缺省 silent → false")
        assertEquals(false, silentOf(JsonPrimitive(false), enabled = true), "silent=false → false")
        // booleanOrNull 对字符串 "true" 也认：宽容语义钉住（与搬移前一致）
        assertTrue(silentOf(JsonPrimitive("true"), enabled = true), "silent=\"true\" 字符串同样生效")
    }

    @Test
    fun `forceReply variants are pinned`() {
        fun forceReplyOf(value: JsonElement?): Boolean =
            parseBotSendMessage(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "text" to JsonPrimitive("t"),
                        "replyMarkup" to JsonObject(mapOf("forceReply" to (value ?: JsonNull))),
                    )
                ),
                silentSendEnabled = true,
            ).forceReply
        assertTrue(forceReplyOf(JsonPrimitive(true)), "forceReply=true → true")
        assertTrue(forceReplyOf(JsonPrimitive("true")), "forceReply=\"true\" 字符串 → true")
        assertTrue(forceReplyOf(JsonObject(emptyMap())), "forceReply={} 对象 → true")
        assertEquals(false, forceReplyOf(JsonPrimitive(false)), "forceReply=false → false")
        assertEquals(false, forceReplyOf(JsonArray(emptyList())), "forceReply=[] 数组 → false")
        assertNull(
            parseBotSendMessage(
                JsonObject(mapOf("chatId" to JsonPrimitive("c"), "text" to JsonPrimitive("t"))),
                silentSendEnabled = true,
            ).keyboardRows,
            "无 replyMarkup 时 keyboardRows 为 null",
        )
    }
}
