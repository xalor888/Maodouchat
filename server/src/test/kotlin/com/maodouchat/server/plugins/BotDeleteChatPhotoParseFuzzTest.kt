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

class BotDeleteChatPhotoParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("chatId")
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotDeleteChatPhotoFieldsResult =
        parseBotDeleteChatPhotoFields(obj)

    private fun okOf(obj: JsonObject): BotDeleteChatPhotoFields =
        (parseOf(obj) as BotDeleteChatPhotoFieldsResult.Ok).fields

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
        val random = Random(2026100213)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            // 必填字段确定性合法（第四十一块 CI 教训）：chatId 恒为 "c<i>"，
            // 未知键注入永不触达必填。
            base["chatId"] = JsonPrimitive("c" + i)
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val fields = okOf(JsonObject(base))
            assertEquals("c" + i, fields.chatId, "未知键不得污染 chatId，迭代 " + i)
        }
    }

    @Test
    fun missingRequiredSemantics() {
        // chatId 缺 / 空 / 纯空白 → MissingRequired。
        assertTrue(parseOf(JsonObject(emptyMap())) is BotDeleteChatPhotoFieldsResult.MissingRequired)
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("")))) is BotDeleteChatPhotoFieldsResult.MissingRequired
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("   ")))) is BotDeleteChatPhotoFieldsResult.MissingRequired
        )
        // 显式 null 得字面量 "null"→非空→Ok 的逐字怪语义。
        val nullChatId = okOf(JsonObject(mapOf("chatId" to JsonNull)))
        assertEquals("null", nullChatId.chatId)
    }

    @Test
    fun noTrimOnChatId() {
        // 无 trim：首尾空白原样保留。
        val spaced = okOf(JsonObject(mapOf("chatId" to JsonPrimitive(" c1 "))))
        assertEquals(" c1 ", spaced.chatId)
    }

    @Test
    fun loudFailureCounterexamples() {
        val objVal = JsonObject(mapOf("a" to JsonPrimitive(1)))
        val arrVal = JsonArray(listOf(JsonPrimitive("c")))
        // 对象 / 数组型 chatId 在 ?.jsonPrimitive 处大声失败（路由层映射 400，不是 500）。
        assertFailsWith<IllegalArgumentException>("对象型 chatId 应大声失败") {
            parseOf(JsonObject(mapOf("chatId" to objVal)))
        }
        assertFailsWith<IllegalArgumentException>("数组型 chatId 应大声失败") {
            parseOf(JsonObject(mapOf("chatId" to arrVal)))
        }
        // 反证：显式 null 不抛——得字面量 "null"→Ok。
        val nullChatId = okOf(JsonObject(mapOf("chatId" to JsonNull)))
        assertEquals("null", nullChatId.chatId)
    }

    @Test
    fun nearMissFieldNamesAreIgnored() {
        // 近似字段名按未知键忽略：真字段缺席→MissingRequired。
        val nearMiss = JsonObject(
            mapOf(
                "ChatId" to JsonPrimitive("c1"),
                "chatid2" to JsonPrimitive("c1"),
                "chat_id" to JsonPrimitive("c1"),
            )
        )
        assertTrue(parseOf(nearMiss) is BotDeleteChatPhotoFieldsResult.MissingRequired)
    }
}
