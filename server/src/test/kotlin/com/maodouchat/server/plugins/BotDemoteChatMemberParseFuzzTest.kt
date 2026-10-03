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

class BotDemoteChatMemberParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("chatId", "userId")
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotDemoteChatMemberFieldsResult =
        parseBotDemoteChatMemberFields(obj)

    private fun okOf(obj: JsonObject): BotDemoteChatMemberFields =
        (parseOf(obj) as BotDemoteChatMemberFieldsResult.Ok).fields

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
        val random = Random(2026100264)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            // 必填字段确定性合法（第四十一块 CI 教训）：chatId 恒为 "c<i>"、
            // userId 恒为 "u<i>"（均无空白），未知键注入永不触达必填。
            base["chatId"] = JsonPrimitive("c" + i)
            base["userId"] = JsonPrimitive("u" + i)
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val fields = okOf(JsonObject(base))
            assertEquals("c" + i, fields.chatId, "未知键不得污染 chatId，迭代 " + i)
            assertEquals("u" + i, fields.userId, "未知键不得污染 userId，迭代 " + i)
        }
    }

    @Test
    fun requiredSemantics() {
        // 每个 okOf 用例都携带全部必填字段（第五十七块 CI 教训）。
        // chatId 缺 / 空 / 纯空白 → Invalid（无 trim，纯空白直接判空）。
        assertTrue(parseOf(JsonObject(emptyMap())) is BotDemoteChatMemberFieldsResult.Invalid)
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive(""),
                "userId" to JsonPrimitive("u1")))) is BotDemoteChatMemberFieldsResult.Invalid
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("   "),
                "userId" to JsonPrimitive("u1")))) is BotDemoteChatMemberFieldsResult.Invalid
        )
        // userId 缺 / 空 / 纯空白 → Invalid。
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1")))) is BotDemoteChatMemberFieldsResult.Invalid
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
                "userId" to JsonPrimitive("")))) is BotDemoteChatMemberFieldsResult.Invalid
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
                "userId" to JsonPrimitive("\t \n")))) is BotDemoteChatMemberFieldsResult.Invalid
        )
        // 双字段都合法 → Ok。
        val ok = okOf(JsonObject(mapOf("chatId" to JsonPrimitive("c1"),
            "userId" to JsonPrimitive("u1"))))
        assertEquals("c1", ok.chatId)
        assertEquals("u1", ok.userId)
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
        // 抽取顺序 chatId 先、userId 后。
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
        // 显式 null 不抛的反证：JsonNull 是 JsonPrimitive。
        assertEquals("null", okOf(JsonObject(mapOf("chatId" to JsonNull,
            "userId" to JsonPrimitive("u1")))).chatId)
    }

    @Test
    fun extractionBeforeBlankCheck() {
        // 抽取先于必填校验：原处理器里两处 ?.jsonPrimitive 都在空白判空之前执行——
        // chatId 对象型 + userId 空白时，先抛 IllegalArgumentException，不走 Invalid。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonObject(mapOf("a" to JsonPrimitive(1))),
                "userId" to JsonPrimitive(""))))
        }
        // userId 对象型 + chatId 空白时，chatId 抽取先行（合法字符串），
        // 接着 userId 抽取抛错，仍先于必填校验。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("   "),
                "userId" to JsonArray(listOf(JsonPrimitive(1))))))
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
                "userid" to JsonPrimitive("u1")
            )
        )
        assertTrue(parseOf(obj) is BotDemoteChatMemberFieldsResult.Invalid)
    }
}
