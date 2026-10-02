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
 * Bot `deleteUpdates` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，
 * G355 `sendMessage` 起至 `setChatDescription` 之后第六十九块）。
 *
 * 本测试直接钉住纯函数 ([parseBotDeleteUpdatesFields]) 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 吸取第四十一块（`sendTable`）的 CI 教训：fuzz 基 payload 的**必填字段必须
 *   确定性合法**——基 payload 的 `upToId` 恒为 `1000L + i`（恒等断言只看它不被未知键污染）。
 * - 钉住 **三层回退按「能否解析出 Long」而非按存在**：body `upToId` 优先、
 *   body `offset` 兜底、query `upToId` 再兜底、缺省 `0L`；
 *   非数字字符串 / 浮点数字符串 / 显式 null（一律 `toLongOrNull()` 得 `null`）
 *   都继续回退，不算「给过值」。
 * - 钉住 **null body 没有 `"invalid json"` 400**：`obj` 为 null 时直接走 query 回退，
 *   全空则 `Invalid`（处理器侧 400 `"upToId required"`，文案逐字）。
 * - 钉住 **必填校验**：回退链全空 / 全非法→缺省 `0L`，或解析出 `<= 0L`，一律 `Invalid`。
 * - 反证坏类型大声失败：对象 / 数组型在 `?.jsonPrimitive`
 *   处抛 [IllegalArgumentException]（路由层 `StatusPages` 映射为 400「参数无效」，
 *   不是 500；抽取顺序 `upToId` 先，即使 `offset`/`query` 合法也先抛）。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
class BotDeleteUpdatesParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "upToId", "offset"
        )
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject?, queryUpToId: String? = null): BotDeleteUpdatesFieldsResult =
        parseBotDeleteUpdatesFields(obj, queryUpToId)

    private fun okOf(obj: JsonObject?, queryUpToId: String? = null): BotDeleteUpdatesFields =
        (parseOf(obj, queryUpToId) as BotDeleteUpdatesFieldsResult.Ok).fields

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
        val random = Random(2026100369)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            // 基 payload：upToId 恒为 1000L + i（必填校验确定性通过，恒等断言只看不被污染）。
            base["upToId"] = JsonPrimitive(1000L + i)
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val fields = okOf(JsonObject(base))
            assertEquals(1000L + i, fields.upTo, "未知键不得污染 upTo，迭代 " + i)
        }
    }

    @Test
    fun aliasPriorityByParseableLong() {
        // upToId 优先：双键并存时 offset 不覆盖。
        assertEquals(10L, okOf(JsonObject(mapOf("upToId" to JsonPrimitive(10L),
            "offset" to JsonPrimitive(20L)))).upTo)
        // offset 兜底：upToId 缺席时生效。
        assertEquals(20L, okOf(JsonObject(mapOf("offset" to JsonPrimitive(20L)))).upTo)
        // 按「能否解析出 Long」而非按存在：upToId 为非数字字符串时回退到 offset。
        assertEquals(30L, okOf(JsonObject(mapOf("upToId" to JsonPrimitive("abc"),
            "offset" to JsonPrimitive(30L)))).upTo)
        // 浮点数字符串同样落空（toLongOrNull 得 null），回退到 offset。
        assertEquals(40L, okOf(JsonObject(mapOf("upToId" to JsonPrimitive("5.9"),
            "offset" to JsonPrimitive(40L)))).upTo)
        // query 兜底：body 双键都给不出 Long 时走查询参数。
        assertEquals(50L, okOf(JsonObject(mapOf("upToId" to JsonPrimitive("abc"))), "50").upTo)
        // body 双键缺席 + query 缺席 → 缺省 0L → Invalid。
        assertTrue(parseOf(JsonObject(emptyMap())) is BotDeleteUpdatesFieldsResult.Invalid)
    }

    @Test
    fun nullBodyHasNoInvalidJsonError() {
        // body 非 JSON 对象（obj 为 null）→ 本端点无 "invalid json" 400，
        // 直接走 query 回退；query 合法则 Ok（原处理器逐字如此）。
        assertEquals(42L, okOf(null, "42").upTo)
        // query 也缺席 → 缺省 0L → Invalid（处理器侧 400 "upToId required"）。
        assertTrue(parseOf(null) is BotDeleteUpdatesFieldsResult.Invalid)
        // query 非数字 → 同样回落到 0L → Invalid。
        assertTrue(parseOf(null, "abc") is BotDeleteUpdatesFieldsResult.Invalid)
    }

    @Test
    fun explicitNullFallsThrough() {
        // 显式 null upToId → JsonNull 是 JsonPrimitive，content 得字面量 "null"，
        // toLongOrNull 得 null → 继续回退到 offset（不算给过值，不抛）。
        assertEquals(60L, okOf(JsonObject(mapOf("upToId" to JsonNull,
            "offset" to JsonPrimitive(60L)))).upTo)
        // 显式 null offset 同理回退到 query。
        assertEquals(70L, okOf(JsonObject(mapOf("offset" to JsonNull)), "70").upTo)
        // 显式 null 全链 → 0L → Invalid。
        assertTrue(parseOf(JsonObject(mapOf("upToId" to JsonNull,
            "offset" to JsonNull))) is BotDeleteUpdatesFieldsResult.Invalid)
    }

    @Test
    fun nonPositiveIsInvalid() {
        // 0 / 负数 → Invalid（400 "upToId required" 的全部前件）。
        assertTrue(parseOf(JsonObject(mapOf("upToId" to JsonPrimitive(0L)))) is BotDeleteUpdatesFieldsResult.Invalid)
        assertTrue(parseOf(JsonObject(mapOf("upToId" to JsonPrimitive(-5L)))) is BotDeleteUpdatesFieldsResult.Invalid)
        assertTrue(parseOf(JsonObject(mapOf("offset" to JsonPrimitive(-1L)))) is BotDeleteUpdatesFieldsResult.Invalid)
        // query 链路同样判：query "-1" → -1L → Invalid。
        assertTrue(parseOf(JsonObject(emptyMap()), "-1") is BotDeleteUpdatesFieldsResult.Invalid)
        // query "0" → 0L → Invalid。
        assertTrue(parseOf(null, "0") is BotDeleteUpdatesFieldsResult.Invalid)
    }

    @Test
    fun stringNumbersAccepted() {
        // 数字字符串 → Long（toLongOrNull 语义，原处理器逐字如此）。
        assertEquals(7L, okOf(JsonObject(mapOf("upToId" to JsonPrimitive("007")))).upTo)
        assertEquals(123L, okOf(JsonObject(emptyMap()), "123").upTo)
        // JSON 数字同样接受。
        assertEquals(9000L, okOf(JsonObject(mapOf("offset" to JsonPrimitive(9000)))).upTo)
    }

    @Test
    fun loudFailureCounterProof() {
        // 对象 / 数组型在 ?.jsonPrimitive 处大声失败（路由层 StatusPages 映射 400，不是 500）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("upToId" to JsonObject(mapOf("x" to JsonPrimitive(1))))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("upToId" to JsonArray(listOf(JsonPrimitive(1))))))
        }
        // offset 的坏类型同样大声失败。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("offset" to JsonObject(mapOf("x" to JsonPrimitive(1))))))
        }
        // 抽取顺序 upToId 先：即使 offset/query 合法，upToId 取对象型依然先抛，不走回退。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("upToId" to JsonObject(mapOf("x" to JsonPrimitive(1))),
                "offset" to JsonPrimitive(5L))), "6")
        }
    }

    @Test
    fun approximateFieldNamesIgnored() {
        // 近似字段名按未知键忽略：真字段缺席 + query 缺席 → Invalid。
        assertTrue(parseOf(JsonObject(mapOf("upToID" to JsonPrimitive(1L)))) is BotDeleteUpdatesFieldsResult.Invalid)
        assertTrue(parseOf(JsonObject(mapOf("uptoid" to JsonPrimitive(1L)))) is BotDeleteUpdatesFieldsResult.Invalid)
        assertTrue(parseOf(JsonObject(mapOf("up_to_id" to JsonPrimitive(1L)))) is BotDeleteUpdatesFieldsResult.Invalid)
        // 真字段在场时近似键不干扰。
        assertTrue(
            okOf(JsonObject(mapOf("upToId" to JsonPrimitive(8L),
                "upToID" to JsonPrimitive(999L)))).upTo == 8L
        )
    }
}
