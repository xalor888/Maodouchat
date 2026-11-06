package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketEventsTest {
    @Test
    fun userOnlineDefaults() {
        val event = WebSocketEvent.UserOnline(userId = "u1", isOnline = true)
        assertEquals(0L, event.lastSeen)
        assertEquals(false, event.onlineRevoked)
        assertEquals(false, event.statusRevoked)
        assertEquals(WebSocketEvent.UserOnline(userId = "u1", isOnline = true), event)
    }

    @Test
    fun signalingReceivedDefaultsEmpty() {
        val event = WebSocketEvent.SignalingReceived(fromUserId = "u1", type = "OFFER", payload = "{}")
        assertEquals("", event.callId)
        assertEquals("", event.groupId)
        assertEquals(emptyList<String>(), event.groupMemberIds)
        assertEquals(false, event.groupInvite)
        assertEquals(0L, event.epoch)
        assertEquals(0L, event.sequence)
        assertEquals("", event.idempotencyKey)
    }

    @Test
    fun serverErrorDefaults() {
        val event = WebSocketEvent.ServerError()
        assertEquals(null, event.code)
        assertEquals(null, event.retryAfterSeconds)
        assertEquals("", event.message)
    }

    @Test
    fun singletonEventsAreObjects() {
        assertEquals(WebSocketEvent.Disconnected, WebSocketEvent.Disconnected)
        assertEquals(WebSocketEvent.InboxAvailableV2, WebSocketEvent.InboxAvailableV2)
    }

    @Test
    fun moreVariantsConstructWithExpectedFields() {
        val typing = WebSocketEvent.UserTyping(userId = "u1", chatId = "c1", isTyping = true)
        assertTrue(typing.isTyping)
        val connected = WebSocketEvent.Connected(success = true)
        assertTrue(connected.success)
        val broadcast = WebSocketEvent.AdminBroadcast(title = "t", text = "x")
        assertEquals(0L, broadcast.ts)
        val disappearing = WebSocketEvent.DisappearingMessagesUpdated(chatId = "c1", seconds = 60)
        assertEquals(0L, disappearing.updatedAt)
        val pins = WebSocketEvent.PinnedMessagesUpdated(chatId = "c1", actorId = "u1", pins = emptyList())
        assertTrue(pins.pins.isEmpty())
        val play = WebSocketEvent.GroupPlayUpdated(chatId = "c1", event = "pk_created", payloadJson = "{}")
        assertEquals("pk_created", play.event)
    }
}
