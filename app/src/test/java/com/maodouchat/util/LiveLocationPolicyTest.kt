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

    @Test
    fun `remainingMs ignores the live flag and open-ended means zero`() {
        val openEnded = LocationPayload(latitude = 31.2, longitude = 121.5, live = true, liveUntil = null)
        assertEquals(0L, LiveLocationPolicy.remainingMs(openEnded, now = 1_000L))
        val nonLive = LocationPayload(latitude = 31.2, longitude = 121.5, live = false, liveUntil = 1_500L)
        assertEquals(500L, LiveLocationPolicy.remainingMs(nonLive, now = 1_000L))
    }

    @Test
    fun `formatRemaining truncates sub-second values and hour boundaries`() {
        assertEquals("1s", LiveLocationPolicy.formatRemaining(1_999L))
        assertEquals("1h 0m", LiveLocationPolicy.formatRemaining(3_600_000L))
        assertEquals("59m", LiveLocationPolicy.formatRemaining(3_599_000L))
        assertEquals("1m", LiveLocationPolicy.formatRemaining(61_000L))
    }

    @Test
    fun `isLiveMessage rejects unparsable and out-of-range LOCATION content`() {
        val garbage = Message(id = "m3", chatId = "c1", senderId = "u1", content = "not-json", type = MessageType.LOCATION)
        assertFalse(LiveLocationPolicy.isLiveMessage(garbage, now = 1_999L))
        val outOfRangeJson = Json.encodeToString(
            LocationPayload.serializer(),
            LocationPayload(latitude = 91.0, longitude = 121.5, live = true, liveUntil = 2_000L, sessionId = "s-2")
        )
        val outOfRange = Message(id = "m4", chatId = "c1", senderId = "u1", content = outOfRangeJson, type = MessageType.LOCATION)
        assertFalse(LiveLocationPolicy.isLiveMessage(outOfRange, now = 1_999L))
    }

    @Test
    fun `open-ended live messages stay live via the message path`() {
        val openJson = Json.encodeToString(
            LocationPayload.serializer(),
            LocationPayload(latitude = 31.2, longitude = 121.5, live = true, liveUntil = null, sessionId = "s-3")
        )
        val openMsg = Message(id = "m5", chatId = "c1", senderId = "u1", content = openJson, type = MessageType.LOCATION)
        assertTrue(LiveLocationPolicy.isLiveMessage(openMsg, now = 9_999_999L))
    }

    @Test
    fun `remaining clamps to zero exactly at expiry`() {
        val payload = LocationPayload(latitude = 31.2, longitude = 121.5, live = true, liveUntil = 2_000L, sessionId = "live-2")
        assertEquals(0L, LiveLocationPolicy.remainingMs(payload, now = 2_000L))
        assertEquals(0L, LiveLocationPolicy.remainingFromUntil(1_000L, now = 1_000L))
        assertEquals(1L, LiveLocationPolicy.remainingFromUntil(1_001L, now = 1_000L))
    }

    @Test
    fun `duration constants are sane`() {
        assertEquals(12_000L, LiveLocationPolicy.MIN_UPDATE_INTERVAL_MS)
        assertEquals(3, LiveLocationPolicy.DURATION_OPTIONS_MS.size)
        assertTrue(LiveLocationPolicy.DURATION_OPTIONS_MS.contains(LiveLocationPolicy.DEFAULT_DURATION_MS))
        assertTrue(LiveLocationPolicy.MIN_UPDATE_INTERVAL_MS < LiveLocationPolicy.DEFAULT_DURATION_MS)
    }

    @Test
    fun `formatRemaining handles day-scale hours`() {
        assertEquals("24h 0m", LiveLocationPolicy.formatRemaining(86_400_000L))
        assertEquals("25h 0m", LiveLocationPolicy.formatRemaining(90_000_000L))
        assertEquals("25h 1m", LiveLocationPolicy.formatRemaining(90_060_000L))
    }

    @Test
    fun `remainingMs is the exact millisecond difference`() {
        val payload = LocationPayload(latitude = 31.2, longitude = 121.5, live = true, liveUntil = 5_000L, sessionId = "live-3")
        assertEquals(3_766L, LiveLocationPolicy.remainingMs(payload, now = 1_234L))
    }

    @Test
    fun `remainingFromUntil handles extreme values`() {
        assertEquals(Long.MAX_VALUE, LiveLocationPolicy.remainingFromUntil(Long.MAX_VALUE, now = 0L))
        assertEquals(0L, LiveLocationPolicy.remainingFromUntil(Long.MIN_VALUE, now = 0L))
    }

    @Test
    fun `non-live payload with future until is still not live`() {
        val payload = LocationPayload(latitude = 31.2, longitude = 121.5, live = false, liveUntil = 99_999L)
        assertFalse(LiveLocationPolicy.isLive(payload, now = 1_000L))
    }


    @Test
    fun `formatRemaining handles day-boundary and sub-minute edges`() {
        assertEquals("23h 59m", LiveLocationPolicy.formatRemaining(86_399_000L))
        assertEquals("1m", LiveLocationPolicy.formatRemaining(61_999L))
        assertEquals("59m", LiveLocationPolicy.formatRemaining(3_599_999L))
        assertEquals("59s", LiveLocationPolicy.formatRemaining(59_999L))
    }

    @Test
    fun `remaining with negative now measures from before epoch`() {
        val payload = LocationPayload(latitude = 31.2, longitude = 121.5, live = true, liveUntil = 1_000L, sessionId = "n-1")
        assertEquals(1_500L, LiveLocationPolicy.remainingMs(payload, now = -500L))
        assertEquals(1_000L, LiveLocationPolicy.remainingFromUntil(0L, now = -1_000L))
        assertEquals(500L, LiveLocationPolicy.remainingFromUntil(-500L, now = -1_000L))
    }

    @Test
    fun `isLive at Long extremes follows strict greater-than`() {
        val payload = LocationPayload(latitude = 31.2, longitude = 121.5, live = true, liveUntil = Long.MAX_VALUE, sessionId = "n-2")
        assertTrue(LiveLocationPolicy.isLive(payload, now = Long.MAX_VALUE - 1L))
        assertFalse(LiveLocationPolicy.isLive(payload, now = Long.MAX_VALUE))
        assertEquals(1L, LiveLocationPolicy.remainingMs(payload, now = Long.MAX_VALUE - 1L))
    }

    @Test
    fun `duration options read as minutes and minimum interval as seconds`() {
        assertEquals(listOf(15L, 60L, 480L), LiveLocationPolicy.DURATION_OPTIONS_MS.map { it / 60_000L })
        assertEquals(12L, LiveLocationPolicy.MIN_UPDATE_INTERVAL_MS / 1_000L)
        assertTrue(LiveLocationPolicy.DURATION_OPTIONS_MS.all { it > LiveLocationPolicy.MIN_UPDATE_INTERVAL_MS })
    }

    @Test
    fun `open-ended payload stays live even at max now`() {
        val openEnded = LocationPayload(latitude = 31.2, longitude = 121.5, live = true, liveUntil = null, sessionId = "n-3")
        assertTrue(LiveLocationPolicy.isLive(openEnded, now = Long.MAX_VALUE))
        assertEquals(0L, LiveLocationPolicy.remainingMs(openEnded, now = Long.MAX_VALUE))
    }

}
