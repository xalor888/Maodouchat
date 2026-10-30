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
    fun group_owner_and_admin_still_blocked_by_non_pinnable_type() {
        assertFalse(MessagePinPolicy.canPin(true, "OWNER", MessageType.SYSTEM))
        assertFalse(MessagePinPolicy.canPin(true, "ADMIN", MessageType.REVOKED))
        assertFalse(MessagePinPolicy.canPin(true, "OWNER", MessageType.NUDGE))
    }

    @Test
    fun isPinnableType_accepts_gif_and_rejects_system_types() {
        assertTrue(MessagePinPolicy.isPinnableType(MessageType.GIF))
        assertTrue(MessagePinPolicy.isPinnableType(MessageType.IMAGE))
        assertFalse(MessagePinPolicy.isPinnableType(MessageType.SYSTEM))
        assertFalse(MessagePinPolicy.isPinnableType(MessageType.SK_DIST))
    }

    @Test
    fun preview_kind_maps_revoked_and_nudge_to_generic() {
        assertEquals(PreviewKind.GENERIC, MessagePinPolicy.previewKind(MessageType.REVOKED))
        assertEquals(PreviewKind.GENERIC, MessagePinPolicy.previewKind(MessageType.NUDGE))
        assertEquals(PreviewKind.GENERIC, MessagePinPolicy.previewKind(MessageType.SK_DIST))
    }

    @Test
    fun text_preview_exact_length_kept_and_one_over_truncated() {
        val exact = "a".repeat(96)
        assertEquals(exact, MessagePinPolicy.textPreview(exact, 96))
        val over = "a".repeat(97)
        val preview = MessagePinPolicy.textPreview(over, 96)
        assertEquals("a".repeat(95) + "…", preview)
        assertEquals(96, preview.length)
    }

    @Test
    fun direct_chat_pin_ignores_role() {
        assertTrue(MessagePinPolicy.canPin(false, "OWNER", MessageType.TEXT))
        assertTrue(MessagePinPolicy.canPin(false, "", MessageType.TEXT))
        assertTrue(MessagePinPolicy.canPin(false, "ADMIN", MessageType.TEXT))
    }

    @Test
    fun text_preview_folds_newlines_and_keeps_literal_meta_text() {
        assertEquals("a b", MessagePinPolicy.textPreview("a\nb<meta>x"))
        assertEquals("a <meta> b", MessagePinPolicy.textPreview("a <meta> b<meta>{\"a\":1}"))
        assertEquals("", MessagePinPolicy.textPreview("   "))
    }

    @Test
    fun group_padded_and_unknown_role_cannot_pin() {
        assertFalse(MessagePinPolicy.canPin(true, " owner ", MessageType.TEXT))
        assertFalse(MessagePinPolicy.canPin(true, "SUPERADMIN", MessageType.TEXT))
        assertFalse(MessagePinPolicy.canPin(true, "", MessageType.TEXT))
    }

    @Test
    fun group_admin_can_pin_gif_markdown_and_sticker() {
        assertTrue(MessagePinPolicy.canPin(true, "ADMIN", MessageType.GIF))
        assertTrue(MessagePinPolicy.canPin(true, "ADMIN", MessageType.MARKDOWN))
        assertTrue(MessagePinPolicy.canPin(true, "OWNER", MessageType.STICKER))
    }

    @Test
    fun preview_kind_maps_gif_to_image() {
        assertEquals(PreviewKind.IMAGE, MessagePinPolicy.previewKind(MessageType.GIF))
    }

    @Test
    fun isPinnableType_accepts_markdown_location_voice_and_rejects_nudge() {
        assertTrue(MessagePinPolicy.isPinnableType(MessageType.MARKDOWN))
        assertTrue(MessagePinPolicy.isPinnableType(MessageType.LOCATION))
        assertTrue(MessagePinPolicy.isPinnableType(MessageType.VOICE))
        assertFalse(MessagePinPolicy.isPinnableType(MessageType.NUDGE))
    }

    @Test
    fun text_preview_trims_tabs_meta_only_and_double_newlines() {
        assertEquals("hello", MessagePinPolicy.textPreview("  hello  "))
        assertEquals("hello", MessagePinPolicy.textPreview("\thello\t"))
        assertEquals("", MessagePinPolicy.textPreview("<meta>{\"a\":1}"))
        assertEquals("a  b", MessagePinPolicy.textPreview("a\n\nb"))
    }

    @Test
    fun max_pins_constant_is_twenty_and_over_limit_blocked() {
        assertEquals(20, MessagePinPolicy.MAX_PINS)
        assertTrue(MessagePinPolicy.wouldExceedLimit(21, alreadyPinned = false))
        assertFalse(MessagePinPolicy.wouldExceedLimit(21, alreadyPinned = true))
        assertFalse(MessagePinPolicy.wouldExceedLimit(0, alreadyPinned = false))
    }

    @Test
    fun isPinnableType_accepts_all_nine_pinnable_types() {
        assertTrue(MessagePinPolicy.isPinnableType(MessageType.TEXT))
        assertTrue(MessagePinPolicy.isPinnableType(MessageType.MARKDOWN))
        assertTrue(MessagePinPolicy.isPinnableType(MessageType.IMAGE))
        assertTrue(MessagePinPolicy.isPinnableType(MessageType.GIF))
        assertTrue(MessagePinPolicy.isPinnableType(MessageType.STICKER))
        assertTrue(MessagePinPolicy.isPinnableType(MessageType.LOCATION))
        assertTrue(MessagePinPolicy.isPinnableType(MessageType.VOICE))
        assertTrue(MessagePinPolicy.isPinnableType(MessageType.VIDEO))
        assertTrue(MessagePinPolicy.isPinnableType(MessageType.FILE))
    }

    @Test
    fun text_preview_keeps_only_text_before_last_meta() {
        assertEquals("a<meta>b", MessagePinPolicy.textPreview("a<meta>b<meta>c"))
        assertEquals("x", MessagePinPolicy.textPreview("x<meta>y"))
    }

    @Test
    fun text_preview_empty_and_whitespace_meta_only_are_blank() {
        assertEquals("", MessagePinPolicy.textPreview(""))
        assertEquals("", MessagePinPolicy.textPreview(" <meta> "))
    }

    @Test
    fun text_preview_default_limit_truncates_at_96_chars() {
        val over = "a".repeat(100)
        val preview = MessagePinPolicy.textPreview(over)
        assertEquals("a".repeat(95) + "…", preview)
        assertEquals(96, preview.length)
        assertEquals("a".repeat(96), MessagePinPolicy.textPreview("a".repeat(96)))
    }

    @Test
    fun text_preview_tinyLimit_collapsesToEllipsis() {
        assertEquals("…", MessagePinPolicy.textPreview("hello", 1))
        assertEquals("h…", MessagePinPolicy.textPreview("hello", 2))
    }

    @Test
    fun text_preview_trailingEmptyMetaBlock_keepsEarlierLiteralMeta() {
        assertEquals("a<meta>b", MessagePinPolicy.textPreview("a<meta>b<meta>"))
        assertEquals("hello", MessagePinPolicy.textPreview("hello<meta>"))
    }

    @Test
    fun text_preview_metaStrippedBeforeTruncationBudget() {
        val preview = MessagePinPolicy.textPreview("a".repeat(100) + "<meta>{\"a\":1}")
        assertEquals("a".repeat(95) + "…", preview)
        assertEquals(96, preview.length)
    }

    @Test
    fun group_role_withTrailingWhitespace_rejected() {
        assertFalse(MessagePinPolicy.canPin(true, "ADMIN ", MessageType.TEXT))
        assertFalse(MessagePinPolicy.canPin(true, "OWNER\t", MessageType.TEXT))
    }

    @Test
    fun direct_chat_nonPinnableTypes_stillBlockedForOwner() {
        assertFalse(MessagePinPolicy.canPin(false, "OWNER", MessageType.SYSTEM))
        assertFalse(MessagePinPolicy.canPin(false, "OWNER", MessageType.SK_DIST))
        assertFalse(MessagePinPolicy.canPin(false, null, MessageType.REVOKED))
    }
}
