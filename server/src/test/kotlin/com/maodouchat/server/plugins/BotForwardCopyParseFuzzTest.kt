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
 * Bot `forwardMessage` / `copyMessage` 请求体手写解析的**模糊兼容性测试**（清单 Q01「协议模型向前/向后
 * 兼容与 fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、
 * `sendContact`、`sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom` 之后**第十四块**）。
 *
 * Bot 路由没有 typed DTO——请求体是 `receiveBoundedTextOrEmpty()` 拿到的原始字符串，
 * 经 `Json.parseToJsonElement(body).jsonObject` 再手写抽取。本轮把原来逐字相同地内联在
 * 两个处理器里的抽取 / 三必填校验逻辑收敛为同一个纯函数 [parseBotForwardCopyFields]
 * （生产侧零行为改动：功能门 `isMessageForwardingEnabled` 与双成员检查仍在处理器里），
 * 本测试直接钉住这个函数的生产语义：
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
 *   例外：显式 JSON null 不是类型错（`JsonNull` 本就是 `JsonPrimitive` 的子类型，
 *   `.content` 为 `"null"` 字符串）——对别名链而言 `?:` 接在 `jsonPrimitive`
 *   **之前**，主字段存在（哪怕显式 null）即不穿透别名，与原处理器逐字一致，特意钉住。
 * - 钉住三条别名链的优先级（`fromChatId`→`from_chat_id`、`chatId`→`toChatId`→`to_chat_id`、
 *   `messageId`→`message_id`）、三必填语义（`fromChatId/chatId/messageId required`）。
 */
class BotForwardCopyParseFuzzTest {

    private companion object {
        /** forward/copy 解析涉及的全部已知字段名（含别名）：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "fromChatId", "from_chat_id",
            "chatId", "toChatId", "to_chat_id",
            "messageId", "message_id",
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

    private fun fieldsOf(obj: JsonObject): BotForwardCopyFields {
        val result = parseBotForwardCopyFields(obj)
        assertTrue(result is BotForwardCopyFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    @Test
    fun `forwardCopy parse survives seeded unknown-field fuzz`() {
        val random = Random(0xF0CD_0E55)
        repeat(ITERATIONS) { i ->
            val fromChatId = "from-$i"
            val toChatId = "to-$i"
            val messageId = "m-$i"
            val base = JsonObject(
                mapOf(
                    "fromChatId" to JsonPrimitive(fromChatId),
                    "chatId" to JsonPrimitive(toChatId),
                    "messageId" to JsonPrimitive(messageId),
                )
            )
            val obj = injectUnknownFields(base, random)
            val fields = fieldsOf(obj)
            assertEquals(
                BotForwardCopyFields(fromChatId, toChatId, messageId),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
        }
    }

    @Test
    fun `forwardCopy required fields are enforced`() {
        // 三字段逐个缺失 → MissingRequired
        assertTrue(
            parseBotForwardCopyFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("to"),
                        "messageId" to JsonPrimitive("m"),
                    )
                )
            ) is BotForwardCopyFieldsResult.MissingRequired,
            "fromChatId 缺失必须判缺",
        )
        assertTrue(
            parseBotForwardCopyFields(
                JsonObject(
                    mapOf(
                        "fromChatId" to JsonPrimitive("from"),
                        "messageId" to JsonPrimitive("m"),
                    )
                )
            ) is BotForwardCopyFieldsResult.MissingRequired,
            "toChatId 缺失必须判缺",
        )
        assertTrue(
            parseBotForwardCopyFields(
                JsonObject(
                    mapOf(
                        "fromChatId" to JsonPrimitive("from"),
                        "chatId" to JsonPrimitive("to"),
                    )
                )
            ) is BotForwardCopyFieldsResult.MissingRequired,
            "messageId 缺失必须判缺",
        )
        // trim 后全空 → MissingRequired
        assertTrue(
            parseBotForwardCopyFields(
                JsonObject(
                    mapOf(
                        "fromChatId" to JsonPrimitive("  "),
                        "chatId" to JsonPrimitive("to"),
                        "messageId" to JsonPrimitive("m"),
                    )
                )
            ) is BotForwardCopyFieldsResult.MissingRequired,
            "空白 fromChatId 必须判缺",
        )
        // 别名同样能满足必填（from_chat_id / to_chat_id / message_id）
        fieldsOf(
            JsonObject(
                mapOf(
                    "from_chat_id" to JsonPrimitive("from"),
                    "to_chat_id" to JsonPrimitive("to"),
                    "message_id" to JsonPrimitive("m"),
                )
            )
        )
        // 三字段全给即合法
        fieldsOf(
            JsonObject(
                mapOf(
                    "fromChatId" to JsonPrimitive("from"),
                    "chatId" to JsonPrimitive("to"),
                    "messageId" to JsonPrimitive("m"),
                )
            )
        )
    }

    @Test
    fun `forwardCopy alias priority is pinned`() {
        // fromChatId → from_chat_id：主字段优先
        val fromAlias = fieldsOf(
            JsonObject(
                mapOf(
                    "fromChatId" to JsonPrimitive("from-main"),
                    "from_chat_id" to JsonPrimitive("from-alias"),
                    "chatId" to JsonPrimitive("to"),
                    "messageId" to JsonPrimitive("m"),
                )
            )
        )
        assertEquals("from-main", fromAlias.fromChatId, "fromChatId 必须优先于 from_chat_id")
        // toChatId 三元链：chatId > toChatId > to_chat_id
        val toFirst = fieldsOf(
            JsonObject(
                mapOf(
                    "fromChatId" to JsonPrimitive("from"),
                    "chatId" to JsonPrimitive("to-main"),
                    "toChatId" to JsonPrimitive("to-2"),
                    "to_chat_id" to JsonPrimitive("to-3"),
                    "messageId" to JsonPrimitive("m"),
                )
            )
        )
        assertEquals("to-main", toFirst.toChatId, "chatId 必须优先于 toChatId / to_chat_id")
        val toSecond = fieldsOf(
            JsonObject(
                mapOf(
                    "fromChatId" to JsonPrimitive("from"),
                    "toChatId" to JsonPrimitive("to-2"),
                    "to_chat_id" to JsonPrimitive("to-3"),
                    "messageId" to JsonPrimitive("m"),
                )
            )
        )
        assertEquals("to-2", toSecond.toChatId, "toChatId 必须优先于 to_chat_id")
        // messageId → message_id：主字段优先
        val msgAlias = fieldsOf(
            JsonObject(
                mapOf(
                    "fromChatId" to JsonPrimitive("from"),
                    "chatId" to JsonPrimitive("to"),
                    "messageId" to JsonPrimitive("m-main"),
                    "message_id" to JsonPrimitive("m-alias"),
                )
            )
        )
        assertEquals("m-main", msgAlias.messageId, "messageId 必须优先于 message_id")
        // 近似字段名被忽略（不是别名）
        val approx = fieldsOf(
            JsonObject(
                mapOf(
                    "fromChatId" to JsonPrimitive("from"),
                    "ChatID" to JsonPrimitive("to-wrong"),
                    "toChatId" to JsonPrimitive("to-right"),
                    "messageId" to JsonPrimitive("m"),
                )
            )
        )
        assertEquals("to-right", approx.toChatId, "近似字段名 ChatID 必须被忽略")
    }

    @Test
    fun `forwardCopy explicit null does not fall through to alias`() {
        // 「怪」语义钉住：?: 接在 jsonPrimitive 之前，主字段显式 null 即不穿透别名——
        // JsonNull.content 为 "null" 字符串，isBlank 判不住，fromChatId 非空，请求判合法。
        val nullPrimary = fieldsOf(
            JsonObject(
                mapOf(
                    "fromChatId" to JsonNull,
                    "from_chat_id" to JsonPrimitive("from-alias"),
                    "chatId" to JsonPrimitive("to"),
                    "messageId" to JsonPrimitive("m"),
                )
            )
        )
        assertEquals("null", nullPrimary.fromChatId, "显式 null 的 fromChatId 必须得到字面 \"null\"，不穿透到 from_chat_id")
        // 三元链同理：chatId 显式 null → 字面 "null"，不穿透 toChatId / to_chat_id
        val nullTo = fieldsOf(
            JsonObject(
                mapOf(
                    "fromChatId" to JsonPrimitive("from"),
                    "chatId" to JsonNull,
                    "toChatId" to JsonPrimitive("to-alias"),
                    "messageId" to JsonPrimitive("m"),
                )
            )
        )
        assertEquals("null", nullTo.toChatId, "显式 null 的 chatId 必须得到字面 \"null\"，不穿透到 toChatId")
    }

    @Test
    fun `forwardCopy wrong-typed known fields fail loudly`() {
        // 已知字段类型错 → IllegalArgumentException（路由层 StatusPages 映射 400，不是 500）
        assertFailsWith<IllegalArgumentException>(
            "fromChatId 收对象必须大声失败",
        ) {
            parseBotForwardCopyFields(
                JsonObject(
                    mapOf(
                        "fromChatId" to JsonObject(mapOf("id" to JsonPrimitive("from"))),
                        "chatId" to JsonPrimitive("to"),
                        "messageId" to JsonPrimitive("m"),
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException>(
            "chatId 收数组必须大声失败",
        ) {
            parseBotForwardCopyFields(
                JsonObject(
                    mapOf(
                        "fromChatId" to JsonPrimitive("from"),
                        "chatId" to JsonArray(listOf(JsonPrimitive("to"))),
                        "messageId" to JsonPrimitive("m"),
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException>(
            "messageId 收对象必须大声失败（不是静默回空串）",
        ) {
            parseBotForwardCopyFields(
                JsonObject(
                    mapOf(
                        "fromChatId" to JsonPrimitive("from"),
                        "chatId" to JsonPrimitive("to"),
                        "messageId" to JsonObject(mapOf("id" to JsonPrimitive("m"))),
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException>(
            "别名 from_chat_id 收数组同样大声失败",
        ) {
            parseBotForwardCopyFields(
                JsonObject(
                    mapOf(
                        "from_chat_id" to JsonArray(listOf(JsonPrimitive("from"))),
                        "chatId" to JsonPrimitive("to"),
                        "messageId" to JsonPrimitive("m"),
                    )
                )
            )
        }
    }
}
