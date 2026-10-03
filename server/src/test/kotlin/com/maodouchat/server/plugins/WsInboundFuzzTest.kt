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
