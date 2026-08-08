package com.maodouchat.chatdetail

import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.MessagePinPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePinPolicyTest {

    @Test
    fun group_only_owner_or_admin_can_pin() {
        assertTrue(MessagePinPolicy.canPin(true, "OWNER", MessageType.TEXT))
        assertTrue(MessagePinPolicy.canPin(true, "ADMIN", MessageType.TEXT))
        assertFalse(MessagePinPolicy.canPin(true, "MEMBER", MessageType.TEXT))
        assertFalse(MessagePinPolicy.canPin(true, null, MessageType.TEXT))
    }

    @Test
    fun direct_chat_both_sides_can_pin() {
        assertTrue(MessagePinPolicy.canPin(false, null, MessageType.TEXT))
        assertTrue(MessagePinPolicy.canPin(false, "MEMBER", MessageType.IMAGE))
    }

    @Test
    fun system_and_revoked_not_pinnable() {
        assertFalse(MessagePinPolicy.canPin(false, null, MessageType.SYSTEM))
        assertFalse(MessagePinPolicy.canPin(false, null, MessageType.REVOKED))
        assertFalse(MessagePinPolicy.canPin(false, null, MessageType.NUDGE))
        assertFalse(MessagePinPolicy.canPin(false, null, MessageType.SK_DIST))
    }

    @Test
    fun limit_only_when_adding() {
        assertFalse(MessagePinPolicy.wouldExceedLimit(5, alreadyPinned = true))
        assertFalse(MessagePinPolicy.wouldExceedLimit(5, alreadyPinned = false))
        assertFalse(MessagePinPolicy.wouldExceedLimit(4, alreadyPinned = false))
    }

    @Test
    fun text_preview_trims_meta_and_ellipsis() {
        assertEquals("hello", MessagePinPolicy.textPreview("hello<meta>{\"a\":1}"))
        val long = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十"
        val preview = MessagePinPolicy.textPreview(long, 48)
        assertTrue(preview.endsWith("…"))
        assertTrue(preview.length <= 48)
    }
}
