package com.maodouchat.chatdetail

import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.MessagePinPolicy
import com.maodouchat.ui.screen.chatdetail.PreviewKind
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

    @Test
    fun group_role_check_is_case_insensitive() {
        assertTrue(MessagePinPolicy.canPin(true, "owner", MessageType.TEXT))
        assertTrue(MessagePinPolicy.canPin(true, "Admin", MessageType.TEXT))
        assertFalse(MessagePinPolicy.canPin(true, "member", MessageType.TEXT))
    }

    @Test
    fun media_and_file_types_are_pinnable() {
        assertTrue(MessagePinPolicy.canPin(false, null, MessageType.VIDEO))
        assertTrue(MessagePinPolicy.canPin(false, null, MessageType.FILE))
        assertTrue(MessagePinPolicy.canPin(false, null, MessageType.VOICE))
        assertTrue(MessagePinPolicy.canPin(false, null, MessageType.STICKER))
        assertTrue(MessagePinPolicy.canPin(false, null, MessageType.LOCATION))
        assertTrue(MessagePinPolicy.canPin(false, null, MessageType.MARKDOWN))
    }

    @Test
    fun preview_kind_maps_each_media_type() {
        assertEquals(PreviewKind.TEXT, MessagePinPolicy.previewKind(MessageType.TEXT))
        assertEquals(PreviewKind.TEXT, MessagePinPolicy.previewKind(MessageType.MARKDOWN))
        assertEquals(PreviewKind.IMAGE, MessagePinPolicy.previewKind(MessageType.IMAGE))
        assertEquals(PreviewKind.VOICE, MessagePinPolicy.previewKind(MessageType.VOICE))
        assertEquals(PreviewKind.VIDEO, MessagePinPolicy.previewKind(MessageType.VIDEO))
        assertEquals(PreviewKind.FILE, MessagePinPolicy.previewKind(MessageType.FILE))
        assertEquals(PreviewKind.LOCATION, MessagePinPolicy.previewKind(MessageType.LOCATION))
        assertEquals(PreviewKind.STICKER, MessagePinPolicy.previewKind(MessageType.STICKER))
        assertEquals(PreviewKind.GENERIC, MessagePinPolicy.previewKind(MessageType.SYSTEM))
    }

    @Test
    fun limit_trips_exactly_at_max_pins() {
        assertTrue(MessagePinPolicy.wouldExceedLimit(MessagePinPolicy.MAX_PINS, alreadyPinned = false))
        assertFalse(MessagePinPolicy.wouldExceedLimit(MessagePinPolicy.MAX_PINS - 1, alreadyPinned = false))
        assertFalse(MessagePinPolicy.wouldExceedLimit(MessagePinPolicy.MAX_PINS, alreadyPinned = true))
    }

    @Test
    fun text_preview_folds_newlines_and_keeps_literal_meta_text() {
        assertEquals("a b", MessagePinPolicy.textPreview("a\nb<meta>x"))
        assertEquals("a <meta> b", MessagePinPolicy.textPreview("a <meta> b<meta>{\"a\":1}"))
        assertEquals("", MessagePinPolicy.textPreview("   "))
    }
}
