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
 * Bot `promoteChatMember` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，
 * G355 `sendMessage` 起至 `unbanChatMember` 之后第六十二块）。
 *
 * 本测试直接钉住纯函数 ([parseBotPromoteChatMemberFields]) 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 吸取第四十一块（`sendTable`）的 CI 教训：fuzz 基 payload 的**必填字段必须
 *   确定性合法**——全随机时已知字段可能被丢弃/变空 → `Invalid` →
 *   `okOf` 的 `as Ok` 强转抛 `ClassCastException`。这里 chatId 恒为 `"c" + i`、
 *   userId 恒为 `"u" + i`（均无空白、无需 trim 干预）、role 恒为 `"MEMBER"`（合法值，
 *   不触发归一化歧义）。
 * - 吸取第五十七块（`setChatTitle`）的 CI 教训：**双字段端点的每个 `okOf` 用例都必须
 *   携带全部必填字段**——缺任一字段 → `Invalid` → `as Ok` 强转抛 `ClassCastException`。
 * - 钉住 **chatId/userId 均无 trim**（`" c1 "`/`" u1 "` 原样保留，原处理器逐字如此）。
 * - 钉住 **role 归一化**：缺席/空/纯空白/大小写不敏感 `"MEMBER"` 外的任意值→`"ADMIN"`；
 *   `"member"`/`"Member"`→`"MEMBER"`（靠 `.uppercase()`）；role 无 trim，
 *   `" member "`→`"MEMBER"` 不成立→`"ADMIN"`；显式 null→字面量 `"null"`→`"ADMIN"`。
 * - 钉住 **抽取先于必填校验**：原处理器里 role 的 `?.jsonPrimitive` 在空白判空之前执行——
 *   role 对象型 + chatId 空白时先抛 [IllegalArgumentException]，不走 `Invalid`。
 * - 钉住 **合并必填校验**：chatId 缺/空/纯空白，或 userId 缺/空/纯空白 → `Invalid`。
 * - 反证坏类型大声失败：对象 / 数组型在 `?.jsonPrimitive`
 *   处抛 [IllegalArgumentException]（路由层 `StatusPages` 映射为 400「参数无效」，
 *   不是 500）；显式 null 不抛的反证。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
class BotPromoteChatMemberParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("chatId", "userId", "role")
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotPromoteChatMemberFieldsResult =
        parseBotPromoteChatMemberFields(obj)

    private fun okOf(obj: JsonObject): BotPromoteChatMemberFields =
        (parseOf(obj) as BotPromoteChatMemberFieldsResult.Ok).fields

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
        val random = Random(2026100262)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            // 必填字段确定性合法（第四十一块 CI 教训）：chatId 恒为 "c<i>"、
            // userId 恒为 "u<i>"（均无空白），role 恒为 "MEMBER"（合法值），
            // 未知键注入永不触达必填与归一化。
            base["chatId"] = JsonPrimitive("c" + i)
            base["userId"] = JsonPrimitive("u" + i)
            base["role"] = JsonPrimitive("MEMBER")
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val fields = okOf(JsonObject(base))
            assertEquals("c" + i, fields.chatId, "未知键不得污染 chatId，迭代 " + i)
            assertEquals("u" + i, fields.userId, "未知键不得污染 userId，迭代 " + i)
            assertEquals("MEMBER", fields.role, "未知键不得污染 role，迭代 " + i)
        }
    }

    @Test
    fun requiredSemantics() {
        // 每个 okOf 用例都携带全部必填字段（第五十七块 CI 教训）。
        // chatId 缺 / 空 / 纯空白 → Invalid（无 trim，纯空白直接判空）。
        assertTrue(parseOf(JsonObject(emptyMap())) is BotPromoteChatMemberFieldsResult.Invalid)
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive(""),
                "userId" to JsonPrimitive("u1")))) is BotPromoteChatMemberFieldsResult.Invalid
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("   "),
                "userId" to JsonPrimitive("u1")))) is BotPromoteChatMemberFieldsResult.Invalid
        )
        // userId 缺 / 空 / 纯空白 → Invalid。
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1")))) is BotPromoteChatMemberFieldsResult.Invalid
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
                "userId" to JsonPrimitive("")))) is BotPromoteChatMemberFieldsResult.Invalid
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
                "userId" to JsonPrimitive("\t \n")))) is BotPromoteChatMemberFieldsResult.Invalid
        )
        // 双字段都合法 → Ok（role 缺席不参与必填）。
        val ok = okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "userId" to JsonPrimitive("u1"))))
        assertEquals("c1", ok.chatId)
        assertEquals("u1", ok.userId)
        assertEquals("ADMIN", ok.role)
    }

    @Test
    fun roleNormalization() {
        fun roleOf(role: JsonElement?): String =
            okOf(JsonObject(buildMap {
                put("chatId", JsonPrimitive("c1"))
                put("userId", JsonPrimitive("u1"))
                if (role != null) put("role", role)
            })).role
        // 缺席 / 空 / 纯空白 → "ADMIN"。
        assertEquals("ADMIN", roleOf(null))
        assertEquals("ADMIN", roleOf(JsonPrimitive("")))
        assertEquals("ADMIN", roleOf(JsonPrimitive("   ")))
        // 大小写不敏感的 MEMBER（靠 .uppercase()）→ "MEMBER"。
        assertEquals("MEMBER", roleOf(JsonPrimitive("MEMBER")))
        assertEquals("MEMBER", roleOf(JsonPrimitive("member")))
        assertEquals("MEMBER", roleOf(JsonPrimitive("Member")))
        assertEquals("MEMBER", roleOf(JsonPrimitive("mEmBeR")))
        // role 无 trim：带空白的 " member " ≠ "MEMBER" → "ADMIN"。
        assertEquals("ADMIN", roleOf(JsonPrimitive(" member ")))
        // 其它任意值全滑向 "ADMIN"。
        assertEquals("ADMIN", roleOf(JsonPrimitive("OWNER")))
        assertEquals("ADMIN", roleOf(JsonPrimitive("owner")))
        assertEquals("ADMIN", roleOf(JsonPrimitive("admin")))
        assertEquals("ADMIN", roleOf(JsonPrimitive("ADMINISTRATOR")))
        assertEquals("ADMIN", roleOf(JsonPrimitive(123)))
        assertEquals("ADMIN", roleOf(JsonPrimitive(true)))
        // 显式 null → 字面量 "null" → uppercase "NULL" ≠ "MEMBER" → "ADMIN"。
        assertEquals("ADMIN", roleOf(JsonNull))
    }

    @Test
    fun explicitNullLiteral() {
        // 显式 null 不判空（逐字怪语义）：得字面量 "null" → Ok。
        val nullChatId = okOf(JsonObject(mapOf("chatId" to JsonNull,
            "userId" to JsonPrimitive("u1"))))
        assertEquals("null", nullChatId.chatId)
        val nullUserId = okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "userId" to JsonNull)))
        assertEquals("null", nullUserId.userId)
        // 两个都显式 null → 双 "null" → Ok。
        val bothNull = okOf(JsonObject(mapOf("chatId" to JsonNull, "userId" to JsonNull)))
        assertEquals("null", bothNull.chatId)
        assertEquals("null", bothNull.userId)
    }

    @Test
    fun whitespaceNotTrimmed() {
        // chatId/userId 均无 trim：" c1 "/" u1 " 原样保留（原处理器逐字如此），但仍通过必填（非空）。
        val fields = okOf(JsonObject(mapOf("chatId" to JsonPrimitive(" c1 "),
            "userId" to JsonPrimitive(" u1 "))))
        assertEquals(" c1 ", fields.chatId)
        assertEquals(" u1 ", fields.userId)
    }

    @Test
    fun loudFailureOnBadTypes() {
        // 对象 / 数组型在 ?.jsonPrimitive 处大声失败（路由层 StatusPages → 400，不是 500）。
        // 抽取顺序 chatId 先、userId 后、role 末。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonObject(mapOf("a" to JsonPrimitive(1))),
                "userId" to JsonPrimitive("u1"))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonArray(listOf(JsonPrimitive(1))),
                "userId" to JsonPrimitive("u1"))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
                "userId" to JsonObject(emptyMap()))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
                "userId" to JsonArray(listOf(JsonPrimitive(1))))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
                "userId" to JsonPrimitive("u1"),
                "role" to JsonObject(emptyMap()))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
                "userId" to JsonPrimitive("u1"),
                "role" to JsonArray(listOf(JsonPrimitive(1))))))
        }
        // 显式 null 不抛的反证：JsonNull 是 JsonPrimitive。
        assertEquals("null", okOf(JsonObject(mapOf("chatId" to JsonNull,
            "userId" to JsonPrimitive("u1")))).chatId)
    }

    @Test
    fun extractionBeforeBlankCheck() {
        // 抽取先于必填校验：原处理器里 role 的 ?.jsonPrimitive 在空白判空之前执行——
        // role 对象型 + chatId 空白时，先抛 IllegalArgumentException，不走 Invalid。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive(""),
                "userId" to JsonPrimitive("u1"),
                "role" to JsonObject(mapOf("a" to JsonPrimitive(1))))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
                "userId" to JsonPrimitive(""),
                "role" to JsonArray(listOf(JsonPrimitive(1))))))
        }
    }

    @Test
    fun nearMissNamesIgnored() {
        // 近似字段名按未知键忽略，真字段缺席 → Invalid。
        val obj = JsonObject(
            mapOf(
                "ChatId" to JsonPrimitive("c1"),
                "chatid2" to JsonPrimitive("c1"),
                "chat_id" to JsonPrimitive("c1"),
                "UserId" to JsonPrimitive("u1"),
                "user_id" to JsonPrimitive("u1"),
                "userid" to JsonPrimitive("u1"),
                "Role" to JsonPrimitive("MEMBER")
            )
        )
        assertTrue(parseOf(obj) is BotPromoteChatMemberFieldsResult.Invalid)
    }
}
