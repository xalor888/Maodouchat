package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageDecryptableTest {

    @Test
    fun contentTypesAreDecryptable() {
        listOf(
            MessageType.TEXT, MessageType.MARKDOWN, MessageType.IMAGE, MessageType.GIF,
            MessageType.STICKER, MessageType.LOCATION, MessageType.VIDEO,
            MessageType.VOICE, MessageType.FILE,
        ).forEach { assertTrue(it.isDecryptable(), "$it 应可解密") }
    }

    @Test
    fun controlTypesAreNotDecryptable() {
        listOf(
            MessageType.NUDGE, MessageType.SK_DIST, MessageType.SYSTEM, MessageType.REVOKED,
        ).forEach { assertFalse(it.isDecryptable(), "$it 不应可解密") }
    }

    @Test
    fun everyEntryIsClassified() {
        MessageType.entries.forEach { type ->
            val expected = type in setOf(
                MessageType.TEXT, MessageType.MARKDOWN, MessageType.IMAGE, MessageType.GIF,
                MessageType.STICKER, MessageType.LOCATION, MessageType.VIDEO,
                MessageType.VOICE, MessageType.FILE,
            )
            assertEquals(expected, type.isDecryptable(), "$type 的分类与预期不符")
        }
    }

    @Test
    fun decryptableSetHasExactlyNineMembers() {
        assertEquals(9, MessageType.entries.count { it.isDecryptable() })
    }

    @Test
    fun fromWireClassificationMatches() {
        assertTrue(MessageType.fromWire("image").isDecryptable())
        assertFalse(MessageType.fromWire("system").isDecryptable())
        assertTrue(MessageType.fromWire("bogus").isDecryptable())
    }
}
