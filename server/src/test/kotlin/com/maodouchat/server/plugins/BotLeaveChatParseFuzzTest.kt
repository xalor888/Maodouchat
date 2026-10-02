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
 * Bot `leaveChat` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，G355
 * `sendMessage` 起至 `promoteChatMember` 之后第六十三块）。
 *
 * 本测试直接钉住纯函数 ([parseBotLeaveChatFields]) 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 吸取第四十一块（`sendTable`）的 CI 教训：fuzz 基 payload 的**必填字段必须
 *   确定性合法**——全随机时已知字段可能被丢弃/变空 → `MissingRequired` →
 *   `okOf` 的 `as Ok` 强转抛 `ClassCastException`。这里 chatId 恒为 `"c" + i`；
 *   合并必填语义由 `missingRequiredSemantics` 钉住。
 * - 钉住 **chatId 无 `trim()`**（`" c1 "` 原样通过、原样进下游）。
 * - 反证坏类型大声失败：对象 / 数组型 `chatId` 在 `?.jsonPrimitive` 处抛
 *   [IllegalArgumentException]（路由层 `StatusPages` 映射为 400「参数无效」，
 *   不是 500）；显式 null 不抛的反证（得字面量 `"null"`→`Ok`）。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
class BotLeaveChatParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("chatId")
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotLeaveChatFieldsResult =
        parseBotLeaveChatFields(obj)

    private fun okOf(obj: JsonObject): BotLeaveChatFields =
        (parseOf(obj) as BotLeaveChatFieldsResult.Ok).fields

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
        val random = Random(2026100263)
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
        assertTrue(parseOf(JsonObject(emptyMap())) is BotLeaveChatFieldsResult.MissingRequired)
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("")))) is BotLeaveChatFieldsResult.MissingRequired
        )
        assertTrue(
            parseOf(JsonObject(mapOf("chatId" to JsonPrimitive("   ")))) is BotLeaveChatFieldsResult.MissingRequired
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
        assertTrue(parseOf(nearMiss) is BotLeaveChatFieldsResult.MissingRequired)
    }
}
