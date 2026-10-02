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
 * Bot `setChatPermissions` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，
 * G355 `sendMessage` 起至 `demoteChatMember` 之后第六十五块）。
 *
 * 本测试直接钉住纯函数 ([parseBotSetChatPermissionsFields]) 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。已知字段含两对 camel/snake 别名（`canSendMessages`/
 *   `can_send_messages`、`until`/`untilDate`），全部避开。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 吸取第四十一块（`sendTable`）的 CI 教训：fuzz 基 payload 的**必填字段必须
 *   确定性合法**——全随机时已知字段可能被丢弃/变空 → `Invalid` →
 *   `okOf` 的 `as Ok` 强转抛 `ClassCastException`。这里 chatId 恒为 `"c" + i`（无空白、
 *   无需 trim 干预）、`canSendMessages` 恒为 JSON 布尔 true、`until` 恒为 JSON 整数。
 * - 吸取第五十七块（`setChatTitle`）的 CI 教训：**双字段端点的每个 `okOf` 用例都必须
 *   携带全部必填字段**——缺任一字段 → `Invalid` → `as Ok` 强转抛 `ClassCastException`。
 * - 钉住 **chatId 无 trim**（`" c1 "` 原样保留，原处理器逐字如此）。
 * - 钉住 **canSend 严格布尔 + 别名优先级**：camel `canSendMessages` 优先、
 *   snake `can_send_messages` 兜底；布尔语义是
 *   `content.toBooleanStrictOrNull()`：JSON 布尔 true/false、或字符串字面量
 *   `"true"`/`"false"`（大小写**不**敏感——`"TRUE"`/`"True"` 同样得 `true`；
 *   "严格"指非法输入得 `null` 而非大小写敏感），其它（数字/显式 null/
 *   空字符串）一律落空到别名或 `Invalid`；显式 null camel 不抛、等同缺席（继续看别名）。
 * - 钉住 **until 别名与回落**：camel `until` 优先、camel `untilDate` 兜底、缺省 `0L`；
 *   非数字字符串/显式 null 回落（不抛）。
 * - 钉住 **抽取先于必填校验**：原处理器里三处 `?.jsonPrimitive` 都在空白/布尔判空之前执行——
 *   `until` 对象型 + chatId 空白时先抛 [IllegalArgumentException]，不走 `Invalid`。
 * - 钉住 **合并必填校验**：chatId 缺/空/纯空白，或 canSend 给不出布尔 → `Invalid`。
 * - 反证坏类型大声失败：对象 / 数组型在 `?.jsonPrimitive`
 *   处抛 [IllegalArgumentException]（路由层 `StatusPages` 映射为 400「参数无效」，
 *   不是 500）；显式 null 不抛的反证（chatId 得字面量 `"null"`→`Ok`）。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
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
