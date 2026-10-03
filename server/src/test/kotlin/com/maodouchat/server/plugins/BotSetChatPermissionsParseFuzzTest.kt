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

class BotSetChatPermissionsParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId", "canSendMessages", "can_send_messages", "until", "untilDate"
        )
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotSetChatPermissionsFieldsResult =
        parseBotSetChatPermissionsFields(obj)

    private fun okOf(obj: JsonObject): BotSetChatPermissionsFields =
        (parseOf(obj) as BotSetChatPermissionsFieldsResult.Ok).fields

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

    @Test
    fun fuzzUnknownKeysIgnored() {
        val random = Random(2026100265)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            // 必填字段确定性合法（第四十一块 CI 教训）：chatId 恒为 "c<i>"（无空白），
            // canSendMessages 恒为 JSON 布尔 true，until 恒为 JSON 整数（1000+i）。
            base["chatId"] = JsonPrimitive("c" + i)
            base["canSendMessages"] = JsonPrimitive(true)
            base["until"] = JsonPrimitive(1000L + i)
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val fields = okOf(JsonObject(base))
            assertEquals("c" + i, fields.chatId, "未知键不得污染 chatId，迭代 " + i)
            assertEquals(true, fields.canSend, "未知键不得污染 canSend，迭代 " + i)
            assertEquals(1000L + i, fields.until, "未知键不得污染 until，迭代 " + i)
        }
    }

    @Test
    fun requiredSemantics() {
        // 每个 okOf 用例都携带全部必填字段（第五十七块 CI 教训）。
        // chatId 缺 / 空 / 纯空白 → Invalid（无 trim，纯空白直接判空）。
        assertTrue(parseOf(JsonObject(emptyMap())) is BotSetChatPermissionsFieldsResult.Invalid)
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive(""),
                "canSendMessages" to JsonPrimitive(true))))
                is BotSetChatPermissionsFieldsResult.Invalid
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("   "),
                "canSendMessages" to JsonPrimitive(true))))
                is BotSetChatPermissionsFieldsResult.Invalid
        )
        // canSend 缺 → Invalid。
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"))))
                is BotSetChatPermissionsFieldsResult.Invalid
        )
        // canSend 非布尔 → Invalid：数字 / 空字符串 / 显式 null 皆落空。
        // 注意：content.toBooleanStrictOrNull() 大小写不敏感——"TRUE"/"True"
        // 同样得 true（第四十九块 CI 曾据此红过，见 BotExportChatInviteLinkParseFuzzTest），
        // 所以 "TRUE" → Ok(true)，见下面的 okOf 断言。
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
                "canSendMessages" to JsonPrimitive(1))))
                is BotSetChatPermissionsFieldsResult.Invalid
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
                "canSendMessages" to JsonNull)))
                is BotSetChatPermissionsFieldsResult.Invalid
        )
        // "TRUE" 大小写不敏感 → true → Ok（不是 Invalid）。
        assertEquals(true, okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "canSendMessages" to JsonPrimitive("TRUE")))).canSend)
        // 双合法 → Ok，until 缺省回 0L（下游 muteUntil 语义：0=24 小时静音，本轮不动语义）。
        val fields = okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "canSendMessages" to JsonPrimitive(false))))
        assertEquals("c1", fields.chatId)
        assertEquals(false, fields.canSend)
        assertEquals(0L, fields.until)
    }

    @Test
    fun canSendBooleanAndAlias() {
        // JSON 布尔 true/false 与 "true"/"false" 字符串均被接受；注意大小写不敏感：
        // content.toBooleanStrictOrNull() 下 "TRUE"/"True" 同样得 true
        // （第四十九块 CI 曾据此红过，见 BotExportChatInviteLinkParseFuzzTest）。
        assertEquals(true, okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "canSendMessages" to JsonPrimitive("true")))).canSend)
        assertEquals(false, okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "canSendMessages" to JsonPrimitive("false")))).canSend)
        assertEquals(true, okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "canSendMessages" to JsonPrimitive("TRUE")))).canSend)
        assertEquals(false, okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "canSendMessages" to JsonPrimitive("False")))).canSend)
        // snake 别名兜底：camel 缺席时生效。
        assertEquals(true, okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "can_send_messages" to JsonPrimitive(true)))).canSend)
        // camel 优先：camel 给出布尔时别名不覆盖。
        assertEquals(false, okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "canSendMessages" to JsonPrimitive(false),
            "can_send_messages" to JsonPrimitive(true)))).canSend)
        // 显式 null camel 等同缺席（booleanOrNull 落空，不抛），别名接管。
        assertEquals(true, okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "canSendMessages" to JsonNull,
            "can_send_messages" to JsonPrimitive(true)))).canSend)
        // camel 非布尔字符串落空 → 别名接管。
        assertEquals(true, okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "canSendMessages" to JsonPrimitive("maybe"),
            "can_send_messages" to JsonPrimitive(true)))).canSend)
    }

    @Test
    fun untilAliasAndFallback() {
        // until 数字字符串 → Long。
        assertEquals(1735689600L, okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "canSendMessages" to JsonPrimitive(true),
            "until" to JsonPrimitive("1735689600")))).until)
        // untilDate 别名：until 缺席时生效。
        assertEquals(200L, okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "canSendMessages" to JsonPrimitive(true),
            "untilDate" to JsonPrimitive(200L)))).until)
        // until 优先：until 给出数字时别名不覆盖。
        assertEquals(100L, okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "canSendMessages" to JsonPrimitive(true),
            "until" to JsonPrimitive(100L),
            "untilDate" to JsonPrimitive(200L)))).until)
        // until 非数字回落到别名。
        assertEquals(300L, okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "canSendMessages" to JsonPrimitive(true),
            "until" to JsonPrimitive("abc"),
            "untilDate" to JsonPrimitive(300L)))).until)
        // until 非数字且无别名 → 0L（不抛）。
        assertEquals(0L, okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "canSendMessages" to JsonPrimitive(true),
            "until" to JsonPrimitive("abc")))).until)
        // 显式 null until 回落（content="null"→toLongOrNull 落空，不抛）。
        assertEquals(0L, okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "canSendMessages" to JsonPrimitive(true),
            "until" to JsonNull))).until)
    }

    @Test
    fun chatIdNoTrim() {
        // chatId 无 trim：原样保留仍通过必填。
        val fields = okOf(JsonObject(mapOf("chatId" to JsonPrimitive(" c1 "),
            "canSendMessages" to JsonPrimitive(true))))
        assertEquals(" c1 ", fields.chatId)
    }

    @Test
    fun extractionBeforeValidationOrder() {
        // 三处抽取都在必填判空之前：until 对象型 + chatId 空白时先抛，不走 Invalid。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("   "),
                "canSendMessages" to JsonPrimitive(true),
                "until" to JsonObject(mapOf("x" to JsonPrimitive(1))))))
        }
        // canSend 对象型 + chatId 空白时同样先抛（抽取顺序 chatId 先、canSend 中、until 末）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive(""),
                "canSendMessages" to JsonObject(mapOf("x" to JsonPrimitive(1))))))
        }
        // chatId 对象型先抛（即使 canSend 合法）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonArray(listOf(JsonPrimitive(1))),
                "canSendMessages" to JsonPrimitive(true))))
        }
    }

    @Test
    fun loudFailureCounterProof() {
        // 对象/数组型在 ?.jsonPrimitive 处大声失败（路由层 StatusPages 映射 400，不是 500）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
                "canSendMessages" to JsonPrimitive(true),
                "untilDate" to JsonArray(listOf(JsonPrimitive(1))))))
        }
        // 显式 null 不抛的反证：chatId 得字面量 "null"→非空→Ok（原处理器逐字怪语义）。
        val fields = okOf(JsonObject(mapOf("chatId" to JsonNull,
            "canSendMessages" to JsonPrimitive(true))))
        assertEquals("null", fields.chatId)
    }

    @Test
    fun approximateFieldNamesIgnored() {
        // 近似字段名按未知键忽略：真字段缺席 → Invalid。
        assertTrue(
            parseOf(JsonObject(mapOf("ChatId" to JsonPrimitive("c1"),
                "canSendMessages" to JsonPrimitive(true))))
                is BotSetChatPermissionsFieldsResult.Invalid
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
                "canSendMessage" to JsonPrimitive(true))))
                is BotSetChatPermissionsFieldsResult.Invalid
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
                "canSendMessages" to JsonPrimitive(true),
                "Until" to JsonPrimitive(100L)))).let {
                (it as BotSetChatPermissionsFieldsResult.Ok).fields.until == 0L
            }
        )
    }
}
