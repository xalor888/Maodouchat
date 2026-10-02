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
 * Bot `setMyDescription` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，
 * G355 `sendMessage` 起至 `setMyCommands` 之后第六十七块）。
 *
 * 本测试直接钉住纯函数 ([parseBotSetMyDescriptionFields]) 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 吸取第四十一块（`sendTable`）的 CI 教训：fuzz 基 payload 的**必填字段必须
 *   确定性合法**——本端点无必填校验，基 payload 的 `description` 恒为 `"d" + i`
 *   （恒等断言只看它不被未知键污染）。
 * - 钉住 **别名回退按存在而非按非空**：`description` 优先、`about` 回退；
 *   空字符串 / 显式 null 的 `description` 都不回退。
 * - 钉住 **显式 null 得字面量 `"null"`**（`JsonNull` 是 `JsonPrimitive`，不抛），
 *   双键缺席才得真 `null`（下游清简介语义，本轮不动）。
 * - 反证坏类型大声失败：对象 / 数组型在 `?.jsonPrimitive`
 *   处抛 [IllegalArgumentException]（路由层 `StatusPages` 映射为 400「参数无效」，
 *   不是 500）。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
class BotSetMyDescriptionParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "description", "about"
        )
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotSetMyDescriptionFieldsResult =
        parseBotSetMyDescriptionFields(obj)

    private fun okOf(obj: JsonObject): BotSetMyDescriptionFields =
        (parseOf(obj) as BotSetMyDescriptionFieldsResult.Ok).fields

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
        val random = Random(2026100267)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            // 基 payload：description 恒为 "d<i>"（本端点无必填校验，恒等断言只看不被污染）。
            base["description"] = JsonPrimitive("d" + i)
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val fields = okOf(JsonObject(base))
            assertEquals("d" + i, fields.description, "未知键不得污染 description，迭代 " + i)
        }
    }

    @Test
    fun aliasPriorityByPresence() {
        // description 优先：双键并存时 about 不覆盖。
        assertEquals("A", okOf(JsonObject(mapOf("description" to JsonPrimitive("A"),
            "about" to JsonPrimitive("B")))).description)
        // about 回退：description 缺席时生效。
        assertEquals("B", okOf(JsonObject(mapOf("about" to JsonPrimitive("B")))).description)
        // 按存在而非按非空：description 为空字符串时不回退（"" 是非空实例）。
        assertEquals("", okOf(JsonObject(mapOf("description" to JsonPrimitive(""),
            "about" to JsonPrimitive("B")))).description)
        // 显式 null 的 description 不回退：JsonNull 非空，?: 不生效。
        assertEquals("null", okOf(JsonObject(mapOf("description" to JsonNull,
            "about" to JsonPrimitive("B")))).description)
    }

    @Test
    fun nullSemantics() {
        // 双键缺席 → 真 null（下游 setMyDescription(botId, null) 清简介，本轮不动语义）。
        assertNull(okOf(JsonObject(emptyMap())).description)
        // 显式 null description → 字面量 "null"（JsonNull 是 JsonPrimitive，不抛）。
        assertEquals("null", okOf(JsonObject(mapOf("description" to JsonNull))).description)
        // about 显式 null → 同样字面量 "null"。
        assertEquals("null", okOf(JsonObject(mapOf("about" to JsonNull))).description)
    }

    @Test
    fun loudFailureCounterProof() {
        // 对象 / 数组型在 ?.jsonPrimitive 处大声失败（路由层 StatusPages 映射 400，不是 500）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("description" to JsonObject(mapOf("x" to JsonPrimitive(1))))))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("description" to JsonArray(listOf(JsonPrimitive(1))))))
        }
        // description 缺席时，about 的坏类型同样大声失败（回退键一样走 ?.jsonPrimitive）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(JsonObject(mapOf("about" to JsonObject(mapOf("x" to JsonPrimitive(1))))))
        }
    }

    @Test
    fun approximateFieldNamesIgnored() {
        // 近似字段名按未知键忽略：真字段缺席 → description 为 null。
        assertNull(okOf(JsonObject(mapOf("Description" to JsonPrimitive("x")))).description)
        assertNull(okOf(JsonObject(mapOf("about2" to JsonPrimitive("x")))).description)
        // 真字段在场时近似键不干扰。
        assertTrue(
            okOf(JsonObject(mapOf("description" to JsonPrimitive("d1"),
                "Description" to JsonPrimitive("x")))).description == "d1"
        )
    }
}
