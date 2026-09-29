package com.maodouchat.server.plugins

import com.maodouchat.server.model.WsMessage
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * WebSocket 入站消息 DTO 的**模糊兼容性测试**（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的
 * fuzz 部分，第四块；第一块 G344 覆盖 messaging-v2，第二块 G347 覆盖 admin，第三块 G353
 * 覆盖通用客户端 API 请求 DTO，这里补上经 `Sockets.kt` 的 `wsJson` 解码的 WS 入站面——
 * 客户端版本碎片化最严重的长连接入口：`WsMessage` 信封、`TypingPayload` 打字指示、
 * `OutgoingSignalingPayload` 通话/WebRTC 信令）。
 *
 * 设计要点（与 G344/G347/G353 同款）：
 * - **固定种子** [Random]：CI 上确定性可复跑；每个 DTO 100–150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2）；
 *   注入位置为顶层（`WsMessage.payload` 本身是字符串负载，其内部结构由各命令分支
 *   自行解释，不在本层做 JSON 语义）。
 * - payload 由「合法请求先序列化成 JsonObject，再程序化注入未知字段」构造（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解码侧。
 * - 反证 `wrong-typed known fields still fail loudly`：`ignoreUnknownKeys = true`
 *   只放行**未知键**；已知字段类型错必须继续抛 [SerializationException]，不能悄悄吞掉坏数据。
 *   这对 `OutgoingSignalingPayload` 尤其关键——通话信令坏数据静默通过即安全门洞
 *   （伪造的 `groupMemberIds`/`epoch` 会污染 fanout 目标与幂等判断）。
 *
 * 注意：测试直接引用生产侧同一份 [wsJson] 配置（`Sockets.kt` 的 `internal`
 * 配置，本轮由函数局部 val 提升为文件级 `internal`，唯一生产代码改动）——如果有人把
 * `ignoreUnknownKeys` 改回 `false`，这里的 fuzz 用例会立刻变红。
 */
class WsInboundFuzzTest {

    private companion object {
        /** 被 fuzz 的三个 DTO 的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "type", "payload",
            "userId", "chatId", "isTyping",
            "toUserId", "callId", "groupId", "groupMemberIds", "groupInvite",
            "epoch", "sequence", "idempotencyKey",
        )
        private const val ENVELOPE_ITERATIONS = 150
        private const val TYPING_ITERATIONS = 100
        private const val SIGNALING_ITERATIONS = 150
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

    /** 顶层注入 1–5 个未知字段，返回 fuzz 后的 JSON 字符串。 */
    private fun injectUnknownFields(base: JsonObject, random: Random): String {
        val fields = base.toMutableMap()
        repeat(random.nextInt(1, 6)) {
            fields[randomFieldName(random)] = randomJsonValue(random, 2)
        }
        return JsonObject(fields).toString()
    }

    private inline fun <reified T> fuzzedPayload(expected: T, random: Random): Pair<String, T> {
        val base = wsJson.parseToJsonElement(wsJson.encodeToString(expected)).jsonObject
        return injectUnknownFields(base, random) to expected
    }

    @Test
    fun `ws message envelope survives seeded unknown-field fuzz`() {
        val random = Random(0xC001_2026)
        val types = listOf("PING", "TYPING", "SIGNALING", "PRESENCE", "ACK")
        repeat(ENVELOPE_ITERATIONS) { i ->
            val expected = WsMessage(
                type = types[i % types.size],
                payload = if (i % 2 == 0) "{\"seq\":$i}" else "plain-$i",
            )
            val (payload, _) = fuzzedPayload(expected, random)
            val decoded = wsJson.decodeFromString<WsMessage>(payload)
            assertEquals(expected, decoded, "fuzz 迭代 #$i：信封已知字段必须与注入前完全一致")
        }
    }

    @Test
    fun `typing payload survives seeded unknown-field fuzz`() {
        val random = Random(0xC002_2026)
        repeat(TYPING_ITERATIONS) { i ->
            val expected = TypingPayload(
                userId = "u-$i",
                chatId = "c-${i % 7}",
                isTyping = i % 2 == 0,
            )
            val (payload, _) = fuzzedPayload(expected, random)
            val decoded = wsJson.decodeFromString<TypingPayload>(payload)
            assertEquals(expected, decoded, "fuzz 迭代 #$i：打字指示字段必须与注入前完全一致")
        }
    }

    @Test
    fun `outgoing signaling payload survives seeded unknown-field fuzz`() {
        val random = Random(0xC003_2026)
        repeat(SIGNALING_ITERATIONS) { i ->
            val expected = OutgoingSignalingPayload(
                toUserId = "u-$i",
                type = if (i % 2 == 0) "offer" else "answer",
                payload = "sdp-$i",
                callId = "call-$i",
                groupId = if (i % 3 == 0) "g-$i" else "",
                groupMemberIds = if (i % 4 == 0) listOf("m1-$i", "m2-$i") else emptyList(),
                groupInvite = i % 5 == 0,
                epoch = 1727000000000L + i,
                sequence = i.toLong(),
                idempotencyKey = if (i % 2 == 0) "idem-$i" else "",
            )
            val (payload, _) = fuzzedPayload(expected, random)
            val decoded = wsJson.decodeFromString<OutgoingSignalingPayload>(payload)
            assertEquals(expected, decoded, "fuzz 迭代 #$i：信令字段必须与注入前完全一致")
        }
        // 最小三字段：其余必须回默认值
        val decoded = wsJson.decodeFromString<OutgoingSignalingPayload>(
            """{"toUserId":"u","type":"offer","payload":"sdp"}"""
        )
        assertEquals(
            OutgoingSignalingPayload(toUserId = "u", type = "offer", payload = "sdp"),
            decoded,
            "缺省可选字段应回默认值",
        )
    }

    @Test
    fun `wrong-typed known fields still fail loudly`() {
        // isTyping 收字符串：Boolean 字段收字符串必须抛
        assertFailsWith<SerializationException>("isTyping 类型错必须大声失败") {
            wsJson.decodeFromString<TypingPayload>(
                """{"userId":"u","chatId":"c","isTyping":"yes"}"""
            )
        }
        // groupMemberIds 收字符串：List<String> 字段收字符串必须抛
        assertFailsWith<SerializationException>("groupMemberIds 类型错必须大声失败") {
            wsJson.decodeFromString<OutgoingSignalingPayload>(
                """{"toUserId":"u","type":"offer","payload":"p","groupMemberIds":"m1,m2"}"""
            )
        }
        // epoch 收对象：Long 字段收对象必须抛
        assertFailsWith<SerializationException>("epoch 类型错必须大声失败") {
            wsJson.decodeFromString<OutgoingSignalingPayload>(
                """{"toUserId":"u","type":"offer","payload":"p","epoch":{"v":1727000000000}}"""
            )
        }
    }

    @Test
    fun `near-miss field names are treated as unknown keys`() {
        // 大小写/前后缀只差一点的字段名必须按未知键忽略，不能误读进已知字段
        val decoded = wsJson.decodeFromString<TypingPayload>(
            """{"UserId":"WRONG","userId":"u1","chatId":"c1","isTyping":true,"isTyping2":false}"""
        )
        assertEquals(
            TypingPayload(userId = "u1", chatId = "c1", isTyping = true),
            decoded,
            "近似字段名必须被忽略，只有精确命中的字段生效",
        )
    }
}
