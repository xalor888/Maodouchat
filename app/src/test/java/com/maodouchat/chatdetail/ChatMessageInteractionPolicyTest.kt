package com.maodouchat.chatdetail

import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.DEFAULT_QUICK_REACTION
import com.maodouchat.ui.screen.chatdetail.isMessageGestureEligible
import com.maodouchat.ui.screen.chatdetail.isMessageForwardable
import com.maodouchat.ui.screen.chatdetail.isMessageCopyable
import com.maodouchat.ui.screen.chatdetail.isMessageReplyable
import com.maodouchat.ui.screen.chatdetail.shouldTriggerSwipeReply
import com.maodouchat.ui.screen.chatdetail.toggleMessageSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatMessageInteractionPolicyTest {
    @Test
    fun swipeReplyOnlyTriggersPastLeftThreshold() {
        assertFalse(shouldTriggerSwipeReply(-51f, 52f))
        assertTrue(shouldTriggerSwipeReply(-52f, 52f))
        assertFalse(shouldTriggerSwipeReply(80f, 52f))
    }

    @Test
    fun systemMessagesDoNotExposeMessageGestures() {
        assertTrue(isMessageGestureEligible(MessageType.TEXT))
        assertTrue(isMessageGestureEligible(MessageType.IMAGE))
        assertFalse(isMessageGestureEligible(MessageType.SYSTEM))
        assertFalse(isMessageGestureEligible(MessageType.REVOKED))
        assertFalse(isMessageGestureEligible(MessageType.SK_DIST))
    }

    @Test
    fun onlyActionableMessagesCanBeForwarded() {
        assertTrue(isMessageForwardable(MessageType.TEXT))
        assertTrue(isMessageForwardable(MessageType.FILE))
        assertFalse(isMessageForwardable(MessageType.NUDGE))
        assertFalse(isMessageForwardable(MessageType.SYSTEM))
        assertFalse(isMessageForwardable(MessageType.REVOKED))
        assertFalse(isMessageForwardable(MessageType.TEXT, isSecretChat = true))
        assertFalse(isMessageForwardable(MessageType.IMAGE, isSecretChat = true))
    }

    @Test
    fun secretChatBlocksCopy() {
        assertTrue(isMessageCopyable(MessageType.TEXT))
        assertTrue(isMessageCopyable(MessageType.MARKDOWN))
        assertFalse(isMessageCopyable(MessageType.IMAGE))
        assertFalse(isMessageCopyable(MessageType.TEXT, isSecretChat = true))
    }

    @Test
    fun selectionToggleAddsAndRemovesTheSameMessage() {
        val selected = toggleMessageSelection(emptySet(), "m1")
        assertEquals(setOf("m1"), selected)
        assertEquals(emptySet(), toggleMessageSelection(selected, "m1"))
    }

    @Test
    fun replyableMirrorsGestureEligibility() {
        assertTrue(isMessageReplyable(MessageType.TEXT))
        assertTrue(isMessageReplyable(MessageType.IMAGE))
        assertFalse(isMessageReplyable(MessageType.SYSTEM))
        assertFalse(isMessageReplyable(MessageType.REVOKED))
        assertFalse(isMessageReplyable(MessageType.NUDGE))
    }

    @Test
    fun swipeReplyNeedsPositiveThresholdAndLeftOffset() {
        assertFalse(shouldTriggerSwipeReply(-52f, 0f))
        assertFalse(shouldTriggerSwipeReply(-52f, -10f))
        assertFalse(shouldTriggerSwipeReply(0f, 52f))
        assertTrue(shouldTriggerSwipeReply(-100f, 52f))
    }

    @Test
    fun secretChatBlocksForwardRegardlessOfFlag() {
        assertFalse(isMessageForwardable(MessageType.TEXT, isSecretChat = true, forwardBlockEnabled = false))
        assertTrue(isMessageForwardable(MessageType.TEXT, isSecretChat = false, forwardBlockEnabled = false))
        assertFalse(isMessageForwardable(MessageType.TEXT, isSecretChat = true, forwardBlockEnabled = true))
    }

    @Test
    fun selectionToggleKeepsOtherIds() {
        assertEquals(setOf("m1", "m2", "m3"), toggleMessageSelection(setOf("m1", "m2"), "m3"))
        assertEquals(setOf("m2"), toggleMessageSelection(setOf("m1", "m2"), "m1"))
    }

    @Test
    fun defaultQuickReactionIsPinned() {
        assertEquals("❤️", DEFAULT_QUICK_REACTION)
    }

    @Test
    fun copyableOnlyTextAndMarkdown() {
        assertFalse(isMessageCopyable(MessageType.VOICE))
        assertFalse(isMessageCopyable(MessageType.FILE))
        assertFalse(isMessageCopyable(MessageType.SYSTEM))
        assertFalse(isMessageCopyable(MessageType.NUDGE))
        assertFalse(isMessageCopyable(MessageType.IMAGE, isSecretChat = false))
        assertTrue(isMessageCopyable(MessageType.MARKDOWN, isSecretChat = false))
    }

    @Test
    fun secretChatBlocksCopyEvenWhenCopyBlockFlagOff() {
        // copyBlockEnabled is currently not consulted by the implementation.
        assertFalse(isMessageCopyable(MessageType.TEXT, isSecretChat = true, copyBlockEnabled = false))
        assertTrue(isMessageCopyable(MessageType.TEXT, isSecretChat = false, copyBlockEnabled = false))
    }

    @Test
    fun gestureEligibilityCoversMediaAndMarkdown() {
        assertTrue(isMessageGestureEligible(MessageType.MARKDOWN))
        assertTrue(isMessageGestureEligible(MessageType.VOICE))
        assertTrue(isMessageGestureEligible(MessageType.VIDEO))
        assertTrue(isMessageGestureEligible(MessageType.GIF))
        assertTrue(isMessageGestureEligible(MessageType.STICKER))
        assertTrue(isMessageGestureEligible(MessageType.LOCATION))
        assertTrue(isMessageGestureEligible(MessageType.FILE))
        assertFalse(isMessageGestureEligible(MessageType.NUDGE))
    }

    @Test
    fun selectionToggleBlankIdRoundTrips() {
        val selected = toggleMessageSelection(emptySet(), "")
        assertEquals(setOf(""), selected)
        assertEquals(emptySet(), toggleMessageSelection(selected, ""))
        assertEquals(setOf("m1", ""), toggleMessageSelection(setOf("m1"), ""))
    }

    @Test
    fun swipeReplyBoundaryIsInclusiveAtLargerThreshold() {
        assertFalse(shouldTriggerSwipeReply(-52f, 100f))
        assertTrue(shouldTriggerSwipeReply(-100f, 100f))
        assertTrue(shouldTriggerSwipeReply(-101f, 100f))
    }
}
