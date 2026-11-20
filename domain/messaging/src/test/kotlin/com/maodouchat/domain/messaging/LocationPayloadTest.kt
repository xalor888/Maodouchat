package com.maodouchat.domain.messaging

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LocationPayloadTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `location payload defaults`() {
        val payload = LocationPayload(latitude = 31.2, longitude = 121.5)
        assertEquals("", payload.label)
        assertFalse(payload.live)
    }

    @Test
    fun `location payload round trips through json`() {
        val payload = LocationPayload(latitude = 31.2, longitude = 121.5, label = "Office", live = true, liveUntil = 123L)
        assertEquals(payload, json.decodeFromString<LocationPayload>(json.encodeToString(payload)))
    }
}
