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
        // capturedAt 写死：它默认取 System.currentTimeMillis()，而 Json 默认不编码等于默认值的字段，
        // 构造与编码若落在同一毫秒，capturedAt 会被省掉、解码后变成另一毫秒，往返就不等了。
        val payload = LocationPayload(latitude = 31.2, longitude = 121.5, label = "Office", live = true, liveUntil = 123L, capturedAt = 1_700_000_000_000L)
        assertEquals(payload, json.decodeFromString<LocationPayload>(json.encodeToString(payload)))
    }
}
