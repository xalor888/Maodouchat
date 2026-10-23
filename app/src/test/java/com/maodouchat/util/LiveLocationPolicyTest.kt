package com.maodouchat.util

import com.maodouchat.data.model.LocationPayload
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLocationPolicyTest {
    @Test
    fun `live location expires at terminal edit timestamp`() {
        val payload = LocationPayload(
            latitude = 31.2,
            longitude = 121.5,
            live = true,
            liveUntil = 2_000L,
            sessionId = "live-1"
        )

        assertTrue(LiveLocationPolicy.isLive(payload, now = 1_999L))
        assertFalse(LiveLocationPolicy.isLive(payload, now = 2_000L))
        assertEquals(0L, LiveLocationPolicy.remainingMs(payload, now = 2_001L))
    }

    @Test
    fun `formatRemaining renders h m s tiers`() {
        assertEquals("1h 1m", LiveLocationPolicy.formatRemaining(3_661_000L))
        assertEquals("1m", LiveLocationPolicy.formatRemaining(90_000L))
        assertEquals("45s", LiveLocationPolicy.formatRemaining(45_000L))
        assertEquals("0s", LiveLocationPolicy.formatRemaining(0L))
        assertEquals("0s", LiveLocationPolicy.formatRemaining(-5_000L))
    }

    @Test
    fun `duration options ascend with 15min default first`() {
        val options = LiveLocationPolicy.DURATION_OPTIONS_MS
        assertEquals(listOf(15L * 60_000L, 60L * 60_000L, 8L * 60L * 60_000L), options)
        assertEquals(options.first(), LiveLocationPolicy.DEFAULT_DURATION_MS)
        assertTrue(options.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun `remainingFromUntil clamps null and expired to zero`() {
        assertEquals(0L, LiveLocationPolicy.remainingFromUntil(null, now = 1_000L))
        assertEquals(0L, LiveLocationPolicy.remainingFromUntil(500L, now = 1_000L))
        assertEquals(500L, LiveLocationPolicy.remainingFromUntil(1_500L, now = 1_000L))
    }

    @Test
    fun `isLive guards null non-live and open-ended payloads`() {
        assertFalse(LiveLocationPolicy.isLive(null, now = 1_000L))
        val nonLive = LocationPayload(latitude = 31.2, longitude = 121.5, live = false)
        assertFalse(LiveLocationPolicy.isLive(nonLive, now = 1_000L))
        val openEnded = LocationPayload(latitude = 31.2, longitude = 121.5, live = true, liveUntil = null)
        assertTrue(LiveLocationPolicy.isLive(openEnded, now = 9_999_999L))
    }

    @Test
    fun `isLiveMessage only true for live LOCATION messages`() {
        val liveJson = Json.encodeToString(
            LocationPayload.serializer(),
            LocationPayload(latitude = 31.2, longitude = 121.5, live = true, liveUntil = 2_000L, sessionId = "s-1")
        )
        val liveMsg = Message(
            id = "m1", chatId = "c1", senderId = "u1",
            content = liveJson, type = MessageType.LOCATION
        )
        assertTrue(LiveLocationPolicy.isLiveMessage(liveMsg, now = 1_999L))
        assertFalse(LiveLocationPolicy.isLiveMessage(liveMsg, now = 2_000L))
        assertFalse(LiveLocationPolicy.isLiveMessage(liveMsg.copy(type = MessageType.TEXT), now = 1_999L))

        val deadJson = Json.encodeToString(
            LocationPayload.serializer(),
            LocationPayload(latitude = 31.2, longitude = 121.5, live = false)
        )
        val deadMsg = Message(
            id = "m2", chatId = "c1", senderId = "u1",
            content = deadJson, type = MessageType.LOCATION
        )
        assertFalse(LiveLocationPolicy.isLiveMessage(deadMsg, now = 1_999L))
    }
}
