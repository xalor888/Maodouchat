package com.maodouchat.server.messaging.v2

import com.maodouchat.server.plugins.messagingV2Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessagingV2ForwardCompatTest {

    private fun sendRequestJson(vararg extraFields: String): String {
        val extras = if (extraFields.isEmpty()) "" else "," + extraFields.joinToString(",")
        return """
            {
              "id": "m-1", "conversationId": "c-1", "kind": "DATA",
              "clientTimestamp": 1727000000000,
              "envelopes": [
                {"recipientUserId": "u2", "recipientDeviceId": 3,
                 "ciphertextType": "SIGNAL", "ciphertext": "aGVsbG8="}
              ]$extras
            }
        """.trimIndent()
    }

    @Test
    fun `send request tolerates unknown fields`() {
        val decoded = messagingV2Json.decodeFromString<SendMessageV2Request>(
            sendRequestJson(
                """"futureFlag": true""",
                """"clientVersion": "9.9.9"""",
                """"nestedUnknown": {"a": [1, 2, 3]}""",
            )
        )
        assertEquals("m-1", decoded.id)
        assertEquals("c-1", decoded.conversationId)
        assertEquals("DATA", decoded.kind)
        assertEquals(1727000000000L, decoded.clientTimestamp)
        assertEquals(1, decoded.envelopes.size)
        assertEquals("u2", decoded.envelopes[0].recipientUserId)
        // 未知字段被忽略，已知字段不受影响
        assertEquals(emptyList(), decoded.attachmentIds)
    }

    @Test
    fun `ack request tolerates unknown fields`() {
        val decoded = messagingV2Json.decodeFromString<AcknowledgeEnvelopesV2Request>(
            """{"envelopeIds": ["e1", "e2"], "futureBatchToken": "zzz"}"""
        )
        assertEquals(listOf("e1", "e2"), decoded.envelopeIds)
    }

    @Test
    fun `missing optional fields fall back to defaults`() {
        // 旧版客户端的请求体：没有 groupRevision / attachmentIds
        val decoded = messagingV2Json.decodeFromString<SendMessageV2Request>(sendRequestJson())
        assertNull(decoded.groupRevision)
        assertEquals(emptyList(), decoded.attachmentIds)
    }

    @Test
    fun `send request round trip is stable`() {
        val original = SendMessageV2Request(
            id = "m-7",
            conversationId = "c-7",
            kind = "SENDER_KEY",
            clientTimestamp = 1727000007000L,
            groupRevision = 42L,
            attachmentIds = listOf("a1", "a2"),
            envelopes = listOf(
                EncryptedDeviceEnvelopeRequest("u9", 1, "SENDER_KEY", "Y2lwaGVy"),
            ),
        )
        val once = messagingV2Json.decodeFromString<SendMessageV2Request>(
            messagingV2Json.encodeToString(original)
        )
        assertEquals(original, once)
        assertEquals(
            messagingV2Json.encodeToString(original),
            messagingV2Json.encodeToString(once),
        )
    }

    @Test
    fun `envelope inside unknown wrapper field is ignored, not misread`() {
        // 未知字段里即使藏着形似信封的结构，也不得污染已知字段
        val decoded = messagingV2Json.decodeFromString<SendMessageV2Request>(
            sendRequestJson(""""envelopesV2": [{"ciphertext": "forged"}]""")
        )
        assertEquals(1, decoded.envelopes.size)
        assertEquals("aGVsbG8=", decoded.envelopes[0].ciphertext)
        assertTrue(decoded.envelopes.none { it.ciphertext == "forged" })
    }
}
