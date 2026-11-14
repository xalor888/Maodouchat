package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MessageSearchContentHashTest {

    @Test
    fun contentHash_matchesKnownVector() {
        assertEquals(
            "8ebf8b2f52a946c5fc0db7760029c2410501f7afb2595d8edcaf252c6df23355",
            MessageSearchContentHash.contentHash("c1", "u1", 1600000000000L, "hello")
        )
    }

    @Test
    fun contentHash_isDeterministicAndSensitiveToEachField() {
        val base = MessageSearchContentHash.contentHash("c1", "u1", 1600000000000L, "hello")
        assertEquals(base, MessageSearchContentHash.contentHash("c1", "u1", 1600000000000L, "hello"))
        assertNotEquals(base, MessageSearchContentHash.contentHash("c1", "u1", 1600000000000L, "hello world"))
        assertNotEquals(base, MessageSearchContentHash.contentHash("c2", "u1", 1600000000000L, "hello"))
        assertNotEquals(base, MessageSearchContentHash.contentHash("c1", "u2", 1600000000000L, "hello"))
        assertNotEquals(base, MessageSearchContentHash.contentHash("c1", "u1", 1600000000001L, "hello"))
    }

    @Test
    fun searchableTypes_coversContentTypesOnly() {
        assertTrue(MessageSearchContentHash.SEARCHABLE_TYPES.contains(MessageType.TEXT))
        assertTrue(MessageSearchContentHash.SEARCHABLE_TYPES.contains(MessageType.VOICE))
        assertTrue(MessageSearchContentHash.SEARCHABLE_TYPES.contains(MessageType.FILE))
        assertFalse(MessageSearchContentHash.SEARCHABLE_TYPES.contains(MessageType.SYSTEM))
        assertFalse(MessageSearchContentHash.SEARCHABLE_TYPES.contains(MessageType.REVOKED))
        assertFalse(MessageSearchContentHash.SEARCHABLE_TYPES.contains(MessageType.SK_DIST))
    }
}
