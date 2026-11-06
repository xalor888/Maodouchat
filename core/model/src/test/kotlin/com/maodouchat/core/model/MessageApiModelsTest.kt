package com.maodouchat.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageApiModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun starMessageResponseRoundTrips() {
        val decoded = json.decodeFromString<StarMessageResponse>("""{"status":"ok","starred":true}""")
        assertEquals("ok", decoded.status)
        assertTrue(decoded.starred)
        // encodeDefaults=false omits the default "ok" status on the wire.
        val encoded = json.encodeToString(decoded)
        assertEquals("""{"starred":true}""", encoded)
        assertEquals(decoded, json.decodeFromString<StarMessageResponse>(encoded))
    }

    @Test
    fun starredMessageRefDtoDecodesDefaults() {
        val decoded = json.decodeFromString<StarredMessageRefDto>("""{"messageId":"m1","chatId":"c1","starredAt":42}""")
        assertEquals("m1", decoded.messageId)
        assertEquals("c1", decoded.chatId)
        assertEquals(42L, decoded.starredAt)
    }

    @Test
    fun disappearingMessagesRoundTrip() {
        val request = UpdateDisappearingMessagesRequest(seconds = 3600)
        assertEquals("""{"seconds":3600}""", json.encodeToString(request))
        val response = json.decodeFromString<DisappearingMessagesResponse>("""{"chatId":"c1","seconds":3600}""")
        assertEquals(0L, response.updatedAt)
    }

    @Test
    fun markReadRequestDefaultsNullThroughId() {
        val decoded = json.decodeFromString<MarkReadRequest>("""{}""")
        assertEquals(null, decoded.throughId)
        val response = json.decodeFromString<MarkReadResponse>("""{"status":"ok","updated":3}""")
        assertEquals(3, response.updated)
    }

    @Test
    fun pinnedMessagesListDefaultsEmpty() {
        val decoded = json.decodeFromString<PinnedMessagesListResponse>("""{"chatId":"c1"}""")
        assertTrue(decoded.pins.isEmpty())
        val toggle = json.decodeFromString<TogglePinResponse>("""{"status":"ok","pinned":true}""")
        assertTrue(toggle.pinned)
        assertTrue(toggle.pins.isEmpty())
    }
}
