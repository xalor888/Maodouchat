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

    @Test
    fun forwardableCoversMediaButNotSkDist() {
        assertTrue(isMessageForwardable(MessageType.VIDEO))
        assertTrue(isMessageForwardable(MessageType.LOCATION))
        assertTrue(isMessageForwardable(MessageType.GIF))
        assertFalse(isMessageForwardable(MessageType.SK_DIST))
    }

    @Test
    fun forwardBlockFlagIsNotConsulted() {
        // forwardBlockEnabled is currently not consulted by the implementation.
        assertTrue(isMessageForwardable(MessageType.TEXT, isSecretChat = false, forwardBlockEnabled = true))
        assertTrue(isMessageForwardable(MessageType.TEXT, isSecretChat = false, forwardBlockEnabled = false))
        assertFalse(isMessageForwardable(MessageType.NUDGE, isSecretChat = false, forwardBlockEnabled = false))
    }

    @Test
    fun replyableCoversMediaButNotSkDist() {
        assertTrue(isMessageReplyable(MessageType.VIDEO))
        assertTrue(isMessageReplyable(MessageType.LOCATION))
        assertFalse(isMessageReplyable(MessageType.SK_DIST))
    }

    @Test
    fun swipeReplyFractionalBoundary() {
        assertFalse(shouldTriggerSwipeReply(-51.9f, 52f))
        assertTrue(shouldTriggerSwipeReply(-52.1f, 52f))
    }

    @Test
    fun secretChatBlocksMarkdownCopy() {
        assertFalse(isMessageCopyable(MessageType.MARKDOWN, isSecretChat = true))
        assertTrue(isMessageCopyable(MessageType.MARKDOWN, isSecretChat = false))
    }

    @Test
    fun replyableCoversRemainingMediaTypes() {
        assertTrue(isMessageReplyable(MessageType.MARKDOWN))
        assertTrue(isMessageReplyable(MessageType.GIF))
        assertTrue(isMessageReplyable(MessageType.STICKER))
        assertTrue(isMessageReplyable(MessageType.VOICE))
        assertTrue(isMessageReplyable(MessageType.FILE))
    }

    @Test
    fun copyableRejectsRemainingNonTextTypes() {
        assertFalse(isMessageCopyable(MessageType.VIDEO))
        assertFalse(isMessageCopyable(MessageType.GIF))
        assertFalse(isMessageCopyable(MessageType.STICKER))
        assertFalse(isMessageCopyable(MessageType.LOCATION))
        assertFalse(isMessageCopyable(MessageType.SK_DIST))
        assertFalse(isMessageCopyable(MessageType.REVOKED))
    }

    @Test
    fun forwardableCoversRemainingTypes() {
        assertTrue(isMessageForwardable(MessageType.MARKDOWN))
        assertTrue(isMessageForwardable(MessageType.STICKER))
        assertTrue(isMessageForwardable(MessageType.VOICE))
        assertFalse(isMessageForwardable(MessageType.MARKDOWN, isSecretChat = true))
    }

    @Test
    fun swipeReplyExtremeOffsets() {
        assertFalse(shouldTriggerSwipeReply(Float.NaN, 52f))
        assertFalse(shouldTriggerSwipeReply(-52f, Float.NaN))
        assertTrue(shouldTriggerSwipeReply(Float.NEGATIVE_INFINITY, 52f))
        assertFalse(shouldTriggerSwipeReply(Float.POSITIVE_INFINITY, 52f))
        assertTrue(shouldTriggerSwipeReply(-0.5f, 0.5f))
        assertFalse(shouldTriggerSwipeReply(-0.49f, 0.5f))
    }

    @Test
    fun selectionToggleReAddMovesIdToEnd() {
        val afterRemove = toggleMessageSelection(setOf("m1", "m2"), "m1")
        assertEquals(setOf("m2"), afterRemove)
        assertEquals(listOf("m2", "m1"), toggleMessageSelection(afterRemove, "m1").toList())
        assertEquals(emptySet(), toggleMessageSelection(toggleMessageSelection(emptySet(), "m1"), "m1"))
    }
    @Test
    fun swipeReplyPositiveOffsetNeverTriggers() {
        assertFalse(shouldTriggerSwipeReply(52f, 52f))
        assertFalse(shouldTriggerSwipeReply(1000f, 1f))
        assertFalse(shouldTriggerSwipeReply(1f, 100f))
        assertTrue(shouldTriggerSwipeReply(-52f, 52f))
    }

    @Test
    fun secretChatBlocksForwardForMediaTypes() {
        assertFalse(isMessageForwardable(MessageType.VIDEO, isSecretChat = true))
        assertFalse(isMessageForwardable(MessageType.LOCATION, isSecretChat = true))
        assertFalse(isMessageForwardable(MessageType.GIF, isSecretChat = true))
        assertFalse(isMessageForwardable(MessageType.STICKER, isSecretChat = true))
        assertFalse(isMessageForwardable(MessageType.VOICE, isSecretChat = true))
        assertTrue(isMessageForwardable(MessageType.VIDEO, isSecretChat = false))
    }

    @Test
    fun secretChatBlocksCopyForNonTextTypes() {
        assertFalse(isMessageCopyable(MessageType.VOICE, isSecretChat = true))
        assertFalse(isMessageCopyable(MessageType.FILE, isSecretChat = true))
        assertFalse(isMessageCopyable(MessageType.IMAGE, isSecretChat = true))
        assertFalse(isMessageCopyable(MessageType.TEXT, isSecretChat = true))
    }

    @Test
    fun selectionToggleMiddleReAddGoesLast() {
        val removed = toggleMessageSelection(setOf("m1", "m2", "m3"), "m2")
        assertEquals(setOf("m1", "m3"), removed)
        assertEquals(listOf("m1", "m3", "m2"), toggleMessageSelection(removed, "m2").toList())
    }

    @Test
    fun replyableMatchesGestureEligibilityForEveryType() {
        for (type in MessageType.entries) {
            assertEquals(isMessageGestureEligible(type), isMessageReplyable(type))
        }
    }

    @Test
    fun forwardableMatchesGestureEligibilityForEveryNonSecretType() {
        for (type in MessageType.entries) {
            assertEquals(isMessageGestureEligible(type), isMessageForwardable(type))
        }
    }

    @Test
    fun secretChatBlocksForwardForEveryType() {
        for (type in MessageType.entries) {
            assertFalse(isMessageForwardable(type, isSecretChat = true))
        }
    }

    @Test
    fun copyableMatchesTextTypesForEveryType() {
        for (type in MessageType.entries) {
            assertEquals(
                type == MessageType.TEXT || type == MessageType.MARKDOWN,
                isMessageCopyable(type)
            )
        }
    }

    @Test
    fun swipeReplyUnitThresholdBoundary() {
        assertTrue(shouldTriggerSwipeReply(-1f, 1f))
        assertFalse(shouldTriggerSwipeReply(-0.99f, 1f))
        assertTrue(shouldTriggerSwipeReply(-1000f, 999f))
        assertFalse(shouldTriggerSwipeReply(-999f, 1000f))
        assertFalse(shouldTriggerSwipeReply(-52f, Float.POSITIVE_INFINITY))
        assertFalse(shouldTriggerSwipeReply(Float.NEGATIVE_INFINITY, 0f))
    }

    @Test
    fun selectionToggleAppendAndRemovePreserveOrder() {
        assertEquals(
            listOf("m1", "m2", "m3"),
            toggleMessageSelection(setOf("m1", "m2"), "m3").toList()
        )
        assertEquals(
            listOf("m1", "m3"),
            toggleMessageSelection(setOf("m1", "m2", "m3"), "m2").toList()
        )
    }

    @Test
    fun negativeThresholdNeverTriggersSwipeReply() {
        assertFalse(shouldTriggerSwipeReply(-1000f, -52f))
        assertFalse(shouldTriggerSwipeReply(-52f, -52f))
        assertFalse(shouldTriggerSwipeReply(-1000f, -1f))
    }

    @Test
    fun copyBlockFlagDoesNotAffectNonSecretTextCopy() {
        assertTrue(isMessageCopyable(MessageType.TEXT, isSecretChat = false, copyBlockEnabled = true))
        assertTrue(isMessageCopyable(MessageType.MARKDOWN, isSecretChat = false, copyBlockEnabled = true))
        assertFalse(isMessageCopyable(MessageType.TEXT, isSecretChat = true, copyBlockEnabled = true))
    }

    @Test
    fun forwardBlockFlagDoesNotAffectNonSecretMediaForward() {
        assertTrue(isMessageForwardable(MessageType.VIDEO, isSecretChat = false, forwardBlockEnabled = true))
        assertTrue(isMessageForwardable(MessageType.GIF, isSecretChat = false, forwardBlockEnabled = true))
    }

    @Test
    fun selectionToggleFiveIdsRemoveMiddleReAddGoesLast() {
        val removed = toggleMessageSelection(setOf("m1", "m2", "m3", "m4", "m5"), "m3")
        assertEquals(listOf("m1", "m2", "m4", "m5"), removed.toList())
        assertEquals(listOf("m1", "m2", "m4", "m5", "m3"), toggleMessageSelection(removed, "m3").toList())
    }

    @Test
    fun swipeReplySubPixelThresholdBoundary() {
        assertTrue(shouldTriggerSwipeReply(-0.001f, 0.001f))
        assertFalse(shouldTriggerSwipeReply(-0.0009f, 0.001f))
        assertTrue(shouldTriggerSwipeReply(-1f, 0.001f))
    }


    @Test
    fun swipeReplyBoundaryIsInclusiveAtThreePointFive() {
        assertTrue(shouldTriggerSwipeReply(-3.5f, 3.5f))
        assertFalse(shouldTriggerSwipeReply(-3.49f, 3.5f))
        assertFalse(shouldTriggerSwipeReply(3.5f, 3.5f))
    }

    @Test
    fun selectionToggleRemovingLastIdThenReAddingRestoresOrder() {
        val removed = toggleMessageSelection(setOf("m1", "m2", "m3"), "m3")
        assertEquals(listOf("m1", "m2"), removed.toList())
        assertEquals(listOf("m1", "m2", "m3"), toggleMessageSelection(removed, "m3").toList())
    }

    @Test
    fun selectionToggleAddingSecondIdPreservesOrder() {
        val first = toggleMessageSelection(emptySet(), "m1")
        assertEquals(listOf("m1", "m2"), toggleMessageSelection(first, "m2").toList())
    }

    @Test
    fun defaultQuickReactionLengthIsTwoUtf16Units() {
        assertEquals(2, DEFAULT_QUICK_REACTION.length)
    }

    @Test
    fun copyableRejectsStickerInSecretChatEvenWithCopyBlockOff() {
        assertFalse(isMessageCopyable(MessageType.STICKER, isSecretChat = true, copyBlockEnabled = false))
    }
}
