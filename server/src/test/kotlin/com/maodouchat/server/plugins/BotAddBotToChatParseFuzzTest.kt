package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bot 用户侧交互（`POST /api/chats/{chatId}/bots`，`BotInteractionRouting.kt`）
 * 请求体手写解析的**模糊兼容性测试**（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」
 * 的 bot 侧专项评估，G355 `sendMessage` 起至 `POST /api/bots` 之后第八十三块）。
 *
 * 本测试直接钉住纯函数 ([parseAddBotToChatBotId]) 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开已知字段 `botId`**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject 再程序化注入未知字段，最后 encode 成 body
 *   字符串」得到（不拼字符串）——注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 钉住 **吞异常怪语义**：坏 JSON / 顶层非对象 / `botId` 对象数组型 → `""`
 *   （与字段缺席同文案，处理器报 400 `"botId required"`；本端点没有
 *   `"invalid json"` 判定）。
 * - 钉住 **大声失败反证**：`botId` 为对象或数组时 `?.jsonPrimitive` 抛
 *   `IllegalArgumentException` 被 `runCatching` 吞掉 → `""` → 400——
 *   与 bot-inbox 块的 `as?` 静默不同，本端点大声。
 * - 钉住 **显式 null 字面量继续走**：`JsonNull` 是 `JsonPrimitive`，其 `content`
 *   为字面量 `"null"`（非空，不被 `isBlank()` 滤掉）→ 不走 400 文案，
 *   而是继续走下游 `addOwnedBot(..., "null", ...)` → `BOT_NOT_FOUND`
 *   → 404 `"bot not found"`（与 enabled 块的「显式 null 直接 400」不同——逐字怪语义）。
 * - 钉住 **数字 / 布尔字面量 content**：`7` → `"7"`，`true` → `"true"`。
 * - 钉住 **近似字段名忽略**：`botid` / `BOTID` / `botId2` / `bot_id` 一律忽略，
 *   真字段混在其中仍被识别。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
class BotAddBotToChatParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("botId")
        private const val ITERATIONS = 150
    }

    private fun bodyOf(obj: JsonObject): String = Json.encodeToString(obj)

    private fun botIdOf(body: String): String = parseAddBotToChatBotId(body)

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
        val random = Random(2026100383)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            val expected = "bot_" + randomString(random, random.nextInt(1, 20)).replace(" ", "_")
            base["botId"] = JsonPrimitive(expected)
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val got = botIdOf(bodyOf(JsonObject(base)))
            assertEquals(
                expected, got,
                "seed iteration " + i + ": unknown keys must not change botId",
            )
        }
    }

    @Test
    fun badJsonAndNonObjectSwallowed() {
        // 坏 JSON / 顶层非对象 → ""（吞掉，无单独 "invalid json" 判定——
        // 与字段缺席同文案，处理器报 400 "botId required"）。
        assertEquals("", botIdOf(""), "empty body -> empty")
        assertEquals("", botIdOf("{not json"), "malformed json -> empty")
        assertEquals("", botIdOf("[1,2,3]"), "top-level array -> empty")
        assertEquals("", botIdOf("\"botId\""), "top-level string -> empty")
        assertEquals("", botIdOf("42"), "top-level number -> empty")
        assertEquals("", botIdOf(bodyOf(JsonObject(emptyMap()))), "missing botId -> empty")
    }

    @Test
    fun loudFailureOnObjectOrArray() {
        // 大声失败：botId 为对象 / 数组时 ?.jsonPrimitive 抛 IllegalArgumentException，
        // 被 runCatching 吞掉 → "" → 400（与 as? 静默反证）。
        val obj = JsonObject(mapOf("botId" to JsonObject(mapOf("id" to JsonPrimitive("x")))))
        assertEquals("", botIdOf(bodyOf(obj)), "object botId -> empty")
        val arr = JsonObject(mapOf("botId" to JsonArray(listOf(JsonPrimitive("x")))))
        assertEquals("", botIdOf(bodyOf(arr)), "array botId -> empty")
    }

    @Test
    fun explicitNullLiteralContinuesDownstream() {
        // 显式 JSON null → 字面量 "null"（非空，不被 isBlank 滤掉）→ 继续走下游，
        // addOwnedBot 得 "null" → BOT_NOT_FOUND → 404 "bot not found"。
        // 纯函数只保证抽取出 "null"，下游 404 由处理器侧覆盖。
        val got = botIdOf(bodyOf(JsonObject(mapOf("botId" to JsonNull))))
        assertEquals("null", got, "explicit null -> literal null string")
        assertTrue(got.isNotBlank(), "literal null is not blank: does not take the 400 path")
    }

    @Test
    fun scalarContentsAndBlankSemantics() {
        // 数字 / 布尔型走 ?.jsonPrimitive?.content：content 为其字面量。
        assertEquals("7", botIdOf(bodyOf(JsonObject(mapOf("botId" to JsonPrimitive(7))))), "number -> content")
        assertEquals("true", botIdOf(bodyOf(JsonObject(mapOf("botId" to JsonPrimitive(true))))), "bool -> content")
        // 字符串型原样返回（含首尾空白：空白判在处理器 isBlank() 侧，纯函数不滤）。
        assertEquals("bot_1", botIdOf(bodyOf(JsonObject(mapOf("botId" to JsonPrimitive("bot_1"))))), "plain id")
        assertEquals("", botIdOf(bodyOf(JsonObject(mapOf("botId" to JsonPrimitive(""))))), "empty string -> empty")
        assertEquals("  ", botIdOf(bodyOf(JsonObject(mapOf("botId" to JsonPrimitive("  "))))), "whitespace kept verbatim")
        // 长度 > 80 的 400 判定在处理器侧，纯函数只抽取：80 字与 81 字都原样返回。
        val eighty = "b".repeat(80)
        val eightyOne = "b".repeat(81)
        assertEquals(eighty, botIdOf(bodyOf(JsonObject(mapOf("botId" to JsonPrimitive(eighty))))), "80 chars verbatim")
        assertEquals(eightyOne, botIdOf(bodyOf(JsonObject(mapOf("botId" to JsonPrimitive(eightyOne))))), "81 chars verbatim")
    }

    @Test
    fun nearMissFieldNamesIgnored() {
        // 近似字段名一律忽略（大小写 / 下划线 / 后缀均不匹配）。
        for (name in listOf("botid", "BOTID", "BotId", "botId2", "bot_id", "bot-id", " botId")) {
            val got = botIdOf(bodyOf(JsonObject(mapOf(name to JsonPrimitive("bot_x")))))
            assertEquals("", got, "near-miss name " + name + " ignored")
        }
        // 真字段混在近似名之中仍被识别。
        val mixed = JsonObject(
            mapOf(
                "botid" to JsonPrimitive("wrong"),
                "botId" to JsonPrimitive("right"),
                "bot_id" to JsonPrimitive("wrong2"),
            ),
        )
        assertEquals("right", botIdOf(bodyOf(mixed)), "true field wins among near misses")
        // 编解码往返恒等：encode → parse → 抽取，与直接从 JsonObject 抽取一致。
        val obj = JsonObject(mapOf("botId" to JsonPrimitive("bot_roundtrip")))
        assertEquals(
            botIdOf(bodyOf(obj)),
            parseAddBotToChatBotId(Json.encodeToString(obj)),
            "encode/decode round trip identity",
        )
    }
}
