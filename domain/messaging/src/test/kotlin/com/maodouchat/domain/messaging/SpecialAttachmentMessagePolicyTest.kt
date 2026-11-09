package com.maodouchat.domain.messaging

import com.maodouchat.core.model.LocationPayload
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpecialAttachmentMessagePolicyTest {

    private val nowMs = 1794000000000L

    @Test
    fun `resolveMessageId passes through non-blank key`() {
        assertEquals(
            "key-1",
            SpecialAttachmentMessagePolicy.resolveMessageId("key-1") { "m_fallback" },
        )
    }

    @Test
    fun `resolveMessageId falls back when key is blank`() {
        assertEquals(
            "m_fallback",
            SpecialAttachmentMessagePolicy.resolveMessageId("  ") { "m_fallback" },
        )
    }

    @Test
    fun `sticker message carries code as content`() {
        val message = SpecialAttachmentMessagePolicy.stickerMessage(
            "m1", "c1", "me", "sticker_42", nowMs,
        )
        assertEquals("m1", message.id)
        assertEquals("c1", message.chatId)
        assertEquals("me", message.senderId)
        assertEquals("sticker_42", message.content)
        assertEquals(MessageType.STICKER, message.type)
        assertEquals(nowMs, message.timestamp)
        assertEquals(MessageStatus.SENDING, message.status)
    }

    @Test
    fun `location message encodes payload json with same timestamp`() {
        val message = SpecialAttachmentMessagePolicy.locationMessage(
            "m2", "c1", "me", GeoLocation(31.23, 121.47), nowMs,
        )
        assertEquals(MessageType.LOCATION, message.type)
        assertEquals(nowMs, message.timestamp)
        assertEquals(MessageStatus.SENDING, message.status)
        val payload = Json.decodeFromString<LocationPayload>(message.content)
        assertEquals(31.23, payload.latitude)
        assertEquals(121.47, payload.longitude)
        assertEquals(nowMs, payload.capturedAt)
    }

    @Test
    fun `contact message renders card line`() {
        val message = SpecialAttachmentMessagePolicy.contactMessage(
            "m3", "c1", "me", ContactCard("u9", "小明"), nowMs,
        )
        assertEquals("👤 小明\n[contactUser:u9]", message.content)
        assertEquals(MessageType.TEXT, message.type)
        assertEquals(nowMs, message.timestamp)
        assertEquals(MessageStatus.SENDING, message.status)
    }
}
