package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationPayloadPolicyTest {

    @Test
    fun `static keeps accuracy and time when available`() {
        val payload = LocationPayloadPolicy.static(
            latitude = 39.9, longitude = 116.4, accuracy = 12f,
            hasAccuracy = true, time = 5000L, now = 9000L
        )
        assertEquals(39.9, payload.latitude)
        assertEquals(116.4, payload.longitude)
        assertEquals(12f, payload.accuracyMeters)
        assertEquals(5000L, payload.capturedAt)
        assertFalse(payload.live)
        assertNull(payload.liveUntil)
    }

    @Test
    fun `static drops accuracy when unavailable`() {
        val payload = LocationPayloadPolicy.static(
            latitude = 39.9, longitude = 116.4, accuracy = 12f,
            hasAccuracy = false, time = 5000L, now = 9000L
        )
        assertNull(payload.accuracyMeters)
        assertEquals(5000L, payload.capturedAt)
    }

    @Test
    fun `static falls back to now when time is zero`() {
        val payload = LocationPayloadPolicy.static(
            latitude = 39.9, longitude = 116.4, accuracy = 12f,
            hasAccuracy = true, time = 0L, now = 9000L
        )
        assertEquals(9000L, payload.capturedAt)
    }

    @Test
    fun `static falls back to now when time is negative`() {
        val payload = LocationPayloadPolicy.static(
            latitude = 39.9, longitude = 116.4, accuracy = 12f,
            hasAccuracy = true, time = -5L, now = 9000L
        )
        assertEquals(9000L, payload.capturedAt)
    }

    @Test
    fun `live sets label live flag until and session`() {
        val payload = LocationPayloadPolicy.live(
            latitude = 39.9, longitude = 116.4, accuracy = 12f,
            hasAccuracy = true, time = 5000L, now = 9000L,
            label = "实时位置", liveUntil = 20000L, sessionId = "live_abc"
        )
        assertEquals(12f, payload.accuracyMeters)
        assertEquals(5000L, payload.capturedAt)
        assertEquals("实时位置", payload.label)
        assertTrue(payload.live)
        assertEquals(20000L, payload.liveUntil)
        assertEquals("live_abc", payload.sessionId)
    }

    @Test
    fun `live drops accuracy when unavailable`() {
        val payload = LocationPayloadPolicy.live(
            latitude = 39.9, longitude = 116.4, accuracy = 12f,
            hasAccuracy = false, time = 5000L, now = 9000L,
            label = "实时位置", liveUntil = 20000L, sessionId = "live_abc"
        )
        assertNull(payload.accuracyMeters)
        assertTrue(payload.live)
    }

    @Test
    fun `live falls back capturedAt to now`() {
        val payload = LocationPayloadPolicy.live(
            latitude = 39.9, longitude = 116.4, accuracy = 12f,
            hasAccuracy = true, time = 0L, now = 9000L,
            label = "实时位置", liveUntil = 20000L, sessionId = "live_abc"
        )
        assertEquals(9000L, payload.capturedAt)
    }
}
