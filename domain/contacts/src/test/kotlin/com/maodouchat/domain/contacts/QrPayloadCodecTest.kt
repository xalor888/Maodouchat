package com.maodouchat.domain.contacts

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets

class QrPayloadCodecTest {

    private fun decodePart(part: String): String =
        String(java.util.Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8)

    @Test
    fun encodeUserPrefixesUserId() {
        assertEquals("maodouchat:user:u1", QrPayloadCodec.encodeUserQrPayload("u1"))
        assertEquals("maodouchat:user:", QrPayloadCodec.encodeUserQrPayload(""))
    }

    @Test
    fun encodeChatPrefixesChatId() {
        assertEquals("maodouchat:chat:c1", QrPayloadCodec.encodeChatQrPayload("c1"))
    }

    @Test
    fun encodeChatInviteTrimsToken() {
        assertEquals("maodouchat:chat-invite:v1:tok", QrPayloadCodec.encodeChatInviteQrPayload("  tok  "))
    }

    @Test
    fun encodeSafetyV1PartsRoundTrip() {
        val payload = QrPayloadCodec.encodeSafetyQrPayload("owner", 0, "peer", 1, "123 456")
        val parts = payload.split(":")
        assertEquals(listOf("maodouchat", "safety"), parts.take(2))
        assertEquals("owner", decodePart(parts[2]))
        assertEquals("0", parts[3])
        assertEquals("peer", decodePart(parts[4]))
        assertEquals("1", parts[5])
        assertEquals("123 456", decodePart(parts[6]))
    }

    @Test
    fun encodeSafetyV2StartsWithVersionMarker() {
        val payload = QrPayloadCodec.encodeSafetyQrPayload("owner", 0, "peer", 1, "fpA", "fpB")
        val parts = payload.split(":")
        assertEquals("v2", parts[2])
        assertEquals("fpA", decodePart(parts[7]))
        assertEquals("fpB", decodePart(parts[8]))
    }
}
