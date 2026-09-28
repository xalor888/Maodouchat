package com.maodouchat.server.messaging.v2

import com.maodouchat.server.plugins.messagingV2Json
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Messaging V2 协议模型的**模糊兼容性测试**（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的
 * fuzz 部分，第一块；此前该项明确登记「fuzz 测试仍缺」）。
 *
 * 设计要点：
 * - **固定种子** [Random]：CI 上确定性可复跑；send 请求 200 个、ack 请求 100 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 3）；
 *   **随机位置**：顶层 + 信封对象内部。
 * - payload 由「合法请求先序列化成 JsonObject，再程序化注入未知字段」构造（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解码侧。
 * - 反证 `wrong-typed known fields still fail loudly`：`ignoreUnknownKeys = true`
 *   只放行**未知键**；已知字段类型错必须继续抛 [SerializationException]，不能悄悄吞掉坏数据。
 *   （这是向前兼容最容易被误读的一半：只测「未知字段不炸」不测「坏数据仍炸」，门禁就是摆设。）
 *
 * 注意：测试直接引用路由的同一份 [messagingV2Json] 配置——如果有人把
 * `ignoreUnknownKeys` 改回 `false`，这里的 fuzz 用例会立刻变红。
 */
class MessagingV2FuzzCompatTest {

    private companion object {
        /** 所有已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "id", "conversationId", "kind", "clientTimestamp", "groupRevision",
            "attachmentIds", "envelopes",
            "recipientUserId", "recipientDeviceId", "ciphertextType", "ciphertext",
            "envelopeIds",
        )
        private const val SEND_ITERATIONS = 200
        private const val ACK_ITERATIONS = 100
    }

    private fun randomFieldName(random: Random): String {
        val stems = listOf(
            "future", "x", "v9", "extra", "unknown", "meta", "debug", "tmp",
            "clientExt", "srvExt", "exp", "flag",
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

    /** 返回（fuzz 后的 JSON 字符串，注入前的期望请求）。 */
    private fun fuzzedSendPayload(random: Random): Pair<String, SendMessageV2Request> {
        val expected = SendMessageV2Request(
            id = "m-fuzz",
            conversationId = "c-fuzz",
            kind = "DATA",
            clientTimestamp = 1727000000000L,
            groupRevision = 7L,
            attachmentIds = listOf("a1", "a2"),
            envelopes = listOf(
                EncryptedDeviceEnvelopeRequest("u2", 3, "SIGNAL", "aGVsbG8="),
                EncryptedDeviceEnvelopeRequest("u5", 1, "SENDER_KEY", "d29ybGQ="),
            ),
        )
        val base = messagingV2Json
            .parseToJsonElement(messagingV2Json.encodeToString(expected))
            .jsonObject
        val fuzzedEnvelopes = base.getValue("envelopes").jsonArray.map { element ->
            val envelopeFields = element.jsonObject.toMutableMap()
            repeat(random.nextInt(0, 4)) {
                envelopeFields[randomFieldName(random)] = randomJsonValue(random, 2)
            }
            JsonObject(envelopeFields)
        }
        val topFields = base.toMutableMap()
        repeat(random.nextInt(1, 5)) {
            topFields[randomFieldName(random)] = randomJsonValue(random, 3)
        }
        topFields["envelopes"] = JsonArray(fuzzedEnvelopes)
        return messagingV2Json.encodeToString(JsonElement.serializer(), JsonObject(topFields)) to expected
    }

    @Test
    fun `send request survives seeded unknown-field fuzz`() {
        val random = Random(0x5EED_2026)
        repeat(SEND_ITERATIONS) { i ->
            val (payload, expected) = fuzzedSendPayload(random)
            val decoded = messagingV2Json.decodeFromString<SendMessageV2Request>(payload)
            assertEquals(expected, decoded, "fuzz 迭代 #$i：已知字段必须与注入前完全一致")
        }
    }

    @Test
    fun `ack request survives seeded unknown-field fuzz`() {
        val random = Random(0xAC1_2026)
        repeat(ACK_ITERATIONS) { i ->
            val expectedIds = listOf("e1", "e2", "e3")
            val fields = mutableMapOf<String, JsonElement>(
                "envelopeIds" to JsonArray(expectedIds.map { JsonPrimitive(it) }),
            )
            repeat(random.nextInt(1, 5)) {
                fields[randomFieldName(random)] = randomJsonValue(random, 3)
            }
            val decoded = messagingV2Json.decodeFromString<AcknowledgeEnvelopesV2Request>(
                messagingV2Json.encodeToString(JsonElement.serializer(), JsonObject(fields)),
            )
            assertEquals(expectedIds, decoded.envelopeIds, "fuzz 迭代 #$i")
        }
    }

    @Test
    fun `near-miss field names are still treated as unknown`() {
        // 与已知字段仅大小写/前后缀之差的键仍是未知键：必须被忽略，不能误读
        val decoded = messagingV2Json.decodeFromString<SendMessageV2Request>(
            """
            {
              "id": "m-1", "conversationId": "c-1", "kind": "DATA",
              "clientTimestamp": 1727000000000,
              "ID": "forged", "Envelopes": [{"ciphertext": "forged"}],
              "envelopes2": [], "clienttimestamp": 0,
              "envelopes": [
                {"recipientUserId": "u2", "recipientDeviceId": 3,
                 "ciphertextType": "SIGNAL", "ciphertext": "aGVsbG8="}
              ]
            }
            """.trimIndent()
        )
        assertEquals("m-1", decoded.id)
        assertEquals(1, decoded.envelopes.size)
        assertEquals("aGVsbG8=", decoded.envelopes[0].ciphertext)
    }

    @Test
    fun `wrong-typed known fields still fail loudly`() {
        // clientTimestamp 是 Long：给字符串必须抛，不能被 ignoreUnknownKeys 吞掉
        assertFailsWith<SerializationException>("clientTimestamp 类型错必须抛") {
            messagingV2Json.decodeFromString<SendMessageV2Request>(
                """{"id":"m-1","conversationId":"c-1","kind":"DATA","clientTimestamp":"not-a-long","envelopes":[]}"""
            )
        }
        // envelopes 是数组：给对象必须抛
        assertFailsWith<SerializationException>("envelopes 类型错必须抛") {
            messagingV2Json.decodeFromString<SendMessageV2Request>(
                """{"id":"m-1","conversationId":"c-1","kind":"DATA","clientTimestamp":1,"envelopes":{}}"""
            )
        }
        // 信封内的已知字段类型错也必须抛
        assertFailsWith<SerializationException>("信封内 recipientDeviceId 类型错必须抛") {
            messagingV2Json.decodeFromString<SendMessageV2Request>(
                """{"id":"m-1","conversationId":"c-1","kind":"DATA","clientTimestamp":1,"envelopes":[{"recipientUserId":"u2","recipientDeviceId":"three","ciphertextType":"SIGNAL","ciphertext":"eA=="}]}"""
            )
        }
    }
}
