package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Bot `sendSticker` 请求体手写解析的**模糊兼容性测试**（清单 Q01「协议模型向前/向后兼容与
 * fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、`editMessage`、`sendDocument`、
 * `sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation` 之后第八块）。
 *
 * Bot 路由没有 typed DTO——请求体是 `receiveBoundedTextOrEmpty()` 拿到的原始字符串，
 * 经 `Json.parseToJsonElement(body).jsonObject` 再手写抽取。本轮把原来内联在
 * `/api/bot/sendSticker` 处理器里的抽取 / 内容组装逻辑收敛为纯函数
 * ([parseBotSendStickerFields] / [buildBotStickerContent])
 * （生产侧零行为改动：校验顺序仍为 必填 → 成员检查（处理器）），本测试直接钉住这些函数的生产语义：
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
 *   例外：显式 JSON null 不是类型错（`JsonNull` 本就是 `JsonPrimitive` 的子类型），
 *   显式 null 的 `chatId`/`emoji` 走 `.content` 得到字符串 `"null"`——与原处理器逐字一致，
 *   特意钉住（别名链 `?:` 接在 `jsonPrimitive` **之前**：主字段存在即不再穿透别名，
 *   即使它是显式 null）。
 * - 钉住别名优先级（`emoji`→`sticker`→`text`）、缺省值（pack 缺省→`""`）、
 *   `trim()` + 截断上限（emoji 16 / pack 40）、双必填（`chatId/emoji required`）、
 *   内容模板形状（emoji + 可选 `\n[stickerPack:<pack>]`）。
 */
class BotSendStickerParseFuzzTest {

    private companion object {
        /** sendSticker 解析涉及的全部已知字段名（含别名）：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId", "emoji", "sticker", "text", "pack",
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

    private fun fieldsOf(obj: JsonObject): BotSendStickerFields {
        val result = parseBotSendStickerFields(obj)
        assertTrue(result is BotSendStickerFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    @Test
    fun `sendSticker parse survives seeded unknown-field fuzz`() {
        val random = Random(0x571C4E5)
        repeat(ITERATIONS) { i ->
            val base = buildJsonObject {
                put("chatId", "chat_$i")
                put("emoji", "😀")
                put("pack", "pack_$i")
            }
            val polluted = injectUnknownFields(base, random)
            // 未知键必须被忽略：污染前后解析结果恒等。
            assertEquals(
                parseBotSendStickerFields(base),
                parseBotSendStickerFields(polluted),
                "第 $i 个随机 payload：未知字段改变了解析结果",
            )
            val fields = fieldsOf(polluted)
            assertEquals("chat_$i", fields.chatId)
            assertEquals("😀", fields.emoji)
            assertEquals("pack_$i", fields.pack)
        }
    }

    @Test
    fun `sendSticker aliases resolve in priority order`() {
        // emoji 主字段优先。
        assertEquals(
            "main",
            fieldsOf(buildJsonObject {
                put("chatId", "c"); put("emoji", "main"); put("sticker", "alt1"); put("text", "alt2")
            }).emoji,
        )
        // 主字段缺席时 sticker 别名生效。
        assertEquals(
            "alt1",
            fieldsOf(buildJsonObject {
                put("chatId", "c"); put("sticker", "alt1"); put("text", "alt2")
            }).emoji,
        )
        // 只有 text 时 text 生效。
        assertEquals(
            "alt2",
            fieldsOf(buildJsonObject { put("chatId", "c"); put("text", "alt2") }).emoji,
        )
        // pack 缺省回 ""。
        assertEquals("", fieldsOf(buildJsonObject { put("chatId", "c"); put("emoji", "e") }).pack)
    }

    @Test
    fun `sendSticker truncation pins emoji 16 and pack 40 with trim`() {
        val fields = fieldsOf(buildJsonObject {
            put("chatId", "c")
            put("emoji", "  😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀  ")
            put("pack", "  abcdefghij0123456789ABCDEFGHIJ0123456789EXTRA  ")
        })
        // trim 后取前 16 个 char。
        assertEquals("😀".repeat(8), fields.emoji)
        // trim 后取前 40 个 char。
        assertEquals("abcdefghij0123456789ABCDEFGHIJ0123456789", fields.pack)
    }

    @Test
    fun `sendSticker requires chatId and emoji`() {
        assertTrue(parseBotSendStickerFields(buildJsonObject {}) is BotSendStickerFieldsResult.MissingRequired)
        assertTrue(
            parseBotSendStickerFields(buildJsonObject { put("emoji", "e") })
                is BotSendStickerFieldsResult.MissingRequired,
        )
        assertTrue(
            parseBotSendStickerFields(buildJsonObject { put("chatId", "c") })
                is BotSendStickerFieldsResult.MissingRequired,
        )
        // trim 后全空同样判缺（与原处理器逐字一致）。
        assertTrue(
            parseBotSendStickerFields(buildJsonObject { put("chatId", "c"); put("emoji", "   ") })
                is BotSendStickerFieldsResult.MissingRequired,
        )
        assertTrue(
            parseBotSendStickerFields(buildJsonObject { put("chatId", "  "); put("emoji", "e") })
                is BotSendStickerFieldsResult.MissingRequired,
        )
    }

    @Test
    fun `sendSticker wrong-typed known fields fail loudly`() {
        // 已知字段收对象 / 数组：?.jsonPrimitive 抛 IllegalArgumentException，
        // 路由层 StatusPages 映射为 400「参数无效」，不是 500。
        assertFailsWith<IllegalArgumentException> {
            parseBotSendStickerFields(buildJsonObject {
                put("chatId", buildJsonObject { put("nested", 1) })
                put("emoji", "e")
            })
        }
        assertFailsWith<IllegalArgumentException> {
            parseBotSendStickerFields(buildJsonObject {
                put("chatId", "c")
                put("emoji", JsonArray(listOf(JsonPrimitive("e"))))
            })
        }
        assertFailsWith<IllegalArgumentException> {
            parseBotSendStickerFields(buildJsonObject {
                put("chatId", "c"); put("emoji", "e")
                put("pack", buildJsonObject { put("nested", 1) })
            })
        }
        assertFailsWith<IllegalArgumentException> {
            parseBotSendStickerFields(buildJsonObject {
                put("chatId", "c"); put("text", JsonArray(emptyList()))
            })
        }
    }

    @Test
    fun `sendSticker explicit json null keeps legacy semantics`() {
        // JsonNull 本就是 JsonPrimitive 的子类型：显式 null 走 .content 得到 "null"，
        // 原处理器逐字如此（isBlank 判不住），这里零行为改动钉住。
        val chatNull = fieldsOf(buildJsonObject {
            put("chatId", JsonNull); put("emoji", "e")
        })
        assertEquals("null", chatNull.chatId)
        val emojiNull = fieldsOf(buildJsonObject {
            put("chatId", "c"); put("emoji", JsonNull)
        })
        assertEquals("null", emojiNull.emoji)
        // 别名链的 ?: 接在 jsonPrimitive 之前：主字段存在（哪怕显式 null）即不穿透别名。
        val noPenetration = fieldsOf(buildJsonObject {
            put("chatId", "c"); put("emoji", JsonNull); put("text", "alt")
        })
        assertEquals("null", noPenetration.emoji)
    }

    @Test
    fun `sendSticker near-miss field names are treated as unknown`() {
        val nearMisses = listOf("ChatId", "chatid", "CHATID", "chatId2", "Emoji", "EMOJI", "emoji2", "emoj", "packs", "pack_")
        for (name in nearMisses) {
            // 近似名永不替代已知字段：只有近似名时仍判缺
            // （pack 本就可选，emoji 缺席即 MissingRequired）。
            val onlyNearMiss = parseBotSendStickerFields(buildJsonObject {
                put("chatId", "c"); put(name, "x")
            })
            assertTrue(
                onlyNearMiss is BotSendStickerFieldsResult.MissingRequired,
                "近似字段名 $name 不应被当作 emoji",
            )
        }
        // 近似名不污染已知字段的值。
        val fields = fieldsOf(buildJsonObject {
            put("chatId", "c"); put("emoji", "e"); put("Emoji", "WRONG")
        })
        assertEquals("e", fields.emoji)
    }

    @Test
    fun `sendSticker content template shape`() {
        // 无 pack：只有 emoji 正文。
        assertEquals("😀", buildBotStickerContent("😀", ""))
        // 有 pack：换行拼 [stickerPack:<pack>]。
        assertEquals("😀\n[stickerPack:animals]", buildBotStickerContent("😀", "animals"))
        // 全空 pack（空格）视为无 pack 段。
        assertEquals("😀", buildBotStickerContent("😀", "  "))
    }
}
