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

/**
 * Bot `editMessage` 请求体手写解析的**模糊兼容性测试**（清单 Q01「协议模型向前/向后兼容与
 * fuzz 测试」的 bot 侧专项评估，G355 `sendMessage` 之后第二块）。
 *
 * Bot 路由没有 typed DTO——请求体是 `receiveBoundedTextOrEmpty()` 拿到的原始字符串，
 * 经 `Json.parseToJsonElement(body).jsonObject` 再手写抽取。本轮把原来内联在
 * `/api/bot/editMessage` 处理器里的抽取逻辑收敛为纯函数 [parseBotEditMessage]
 * （生产侧零行为改动），本测试直接钉住该函数的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 反证 `wrong-typed known fields still fail loudly`：手写解析里 `?.jsonPrimitive`
 *   在类型错时抛 [IllegalArgumentException]，路由层 `StatusPages` 把它映射为 400
 *   「参数无效」（不是 500）——坏数据必须大声失败，不能悄悄吞掉。
 * - 钉住 snake_case 别名（`reply_markup`/`inline_keyboard`/`callback_data`/`force_reply`，
 *   Telegram 风格客户端的兼容面）与各处截断上限（text 4000 / 按钮文本 64 /
 *   callbackData 128 / 最多 8 行键盘）。
 *
 * `messageId`/`text` 非空校验（400 `messageId/text required`）留在处理器里，不属本函数。
 */
class BotEditMessageParseFuzzTest {

    private companion object {
        /** editMessage 解析涉及的全部已知字段名（含嵌套与别名）：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "messageId", "text",
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
    fun `editMessage parse survives seeded unknown-field fuzz`() {
        val random = Random(0xED17_2026)
        repeat(ITERATIONS) { i ->
            val messageId = "m-$i"
            val text = "edited-$i"
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
                put("messageId", JsonPrimitive(messageId))
                put("text", JsonPrimitive(text))
                put("replyMarkup", markup)
            }
            val obj = injectUnknownFields(JsonObject(base), random)
            val parsed = parseBotEditMessage(obj)
            val expectedRows = rows.map { row ->
                row.map { (t, d) -> mapOf("text" to t, "callbackData" to d) }
            }
            assertEquals(
                BotEditMessageParsed(
                    messageId = messageId,
                    text = text,
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
            BotEditMessageParsed(
                messageId = "",
                text = "",
                keyboardRows = null,
                forceReply = false,
            ),
            parseBotEditMessage(JsonObject(emptyMap())),
            "空对象：缺省字段必须回默认值",
        )
    }

    @Test
    fun `wrong-typed known fields still fail loudly`() {
        // messageId 收对象：?.jsonPrimitive 抛 IllegalArgumentException（路由层映射为 400，不是 500）
        assertFailsWith<IllegalArgumentException>("messageId 类型错必须大声失败") {
            parseBotEditMessage(
                JsonObject(mapOf("messageId" to JsonObject(mapOf("v" to JsonPrimitive(1)))))
            )
        }
        // text 收数组：同上
        assertFailsWith<IllegalArgumentException>("text 类型错必须大声失败") {
            parseBotEditMessage(
                JsonObject(mapOf("text" to JsonArray(listOf(JsonPrimitive("x")))))
            )
        }
        // replyMarkup 收字符串：?.jsonObject 抛 IllegalArgumentException，同上
        assertFailsWith<IllegalArgumentException>("replyMarkup 类型错必须大声失败") {
            parseBotEditMessage(
                JsonObject(mapOf("replyMarkup" to JsonPrimitive("nope")))
            )
        }
        // messageId 显式 null：JsonNull 本身是 JsonPrimitive（content == "null"），
        // ?.jsonPrimitive 不抛——这是搬移前的原有行为，按"零行为改动"原则钉住原有语义，不改源码。
        assertEquals(
            "null",
            parseBotEditMessage(
                JsonObject(mapOf("messageId" to JsonNull))
            ).messageId,
            "messageId 显式 null 沿用原有语义（JsonNull.content == \"null\"），不抛",
        )
    }

    @Test
    fun `snake_case aliases work and near-miss names are ignored`() {
        val parsed = parseBotEditMessage(
            JsonObject(
                mapOf(
                    "MessageId" to JsonPrimitive("WRONG"),
                    "messageId" to JsonPrimitive("m1"),
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
            )
        )
        assertEquals("m1", parsed.messageId, "近似字段名 MessageId 必须被忽略，只有精确命中的 messageId 生效")
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
            } + JsonPrimitive("row-as-string")
        )
        val parsed = parseBotEditMessage(
            JsonObject(
                mapOf(
                    "messageId" to JsonPrimitive("m"),
                    "text" to JsonPrimitive(longText),
                    "replyMarkup" to JsonObject(mapOf("inlineKeyboard" to rows)),
                )
            )
        )
        assertEquals(4000, parsed.text.length, "text 必须截断到 4000")
        assertEquals(8, parsed.keyboardRows!!.size, "键盘最多保留 8 行")
        parsed.keyboardRows.forEach { row ->
            assertEquals(1, row.size, "每行只剩合法按钮：空文本与非对象元素被丢掉，非数组行被丢掉")
            assertEquals(64, row[0]["text"]!!.length, "按钮文本截断到 64")
            assertEquals(128, row[0]["callbackData"]!!.length, "callbackData 截断到 128")
        }
    }

    @Test
    fun `forceReply variants are pinned`() {
        fun forceReplyOf(value: JsonElement?): Boolean =
            parseBotEditMessage(
                JsonObject(
                    mapOf(
                        "messageId" to JsonPrimitive("m"),
                        "text" to JsonPrimitive("t"),
                        "replyMarkup" to JsonObject(mapOf("forceReply" to (value ?: JsonNull))),
                    )
                )
            ).forceReply
        assertTrue(forceReplyOf(JsonPrimitive(true)), "forceReply=true → true")
        assertTrue(forceReplyOf(JsonPrimitive("true")), "forceReply=\"true\" 字符串 → true")
        assertTrue(forceReplyOf(JsonObject(emptyMap())), "forceReply={} 对象 → true")
        assertEquals(false, forceReplyOf(JsonPrimitive(false)), "forceReply=false → false")
        assertEquals(false, forceReplyOf(JsonArray(emptyList())), "forceReply=[] 数组 → false")
        assertNull(
            parseBotEditMessage(
                JsonObject(mapOf("messageId" to JsonPrimitive("m"), "text" to JsonPrimitive("t")))
            ).keyboardRows,
            "无 replyMarkup 时 keyboardRows 为 null",
        )
    }
}
