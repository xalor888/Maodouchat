package com.maodouchat.domain.conversation

import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageInteractionPoliciesTest {

    @Test
    fun gestureEligibilityExcludesSystemAndKeyDistribution() {
        assertTrue(isMessageGestureEligible(MessageType.TEXT))
        assertTrue(isMessageGestureEligible(MessageType.IMAGE))
        assertFalse(isMessageGestureEligible(MessageType.SYSTEM))
        assertFalse(isMessageGestureEligible(MessageType.REVOKED))
        assertFalse(isMessageGestureEligible(MessageType.NUDGE))
        assertFalse(isMessageGestureEligible(MessageType.SK_DIST))
    }

    @Test
    fun forwardableMirrorsEligibilityExceptSecretChat() {
        assertTrue(isMessageForwardable(MessageType.TEXT))
        assertTrue(isMessageForwardable(MessageType.FILE))
        assertFalse(isMessageForwardable(MessageType.NUDGE))
        assertFalse(isMessageForwardable(MessageType.TEXT, isSecretChat = true))
        assertTrue(isMessageForwardable(MessageType.TEXT, isSecretChat = false))
    }

    @Test
    fun copyableOnlyTextAndMarkdownOutsideSecretChat() {
        assertTrue(isMessageCopyable(MessageType.TEXT))
        assertTrue(isMessageCopyable(MessageType.MARKDOWN))
        assertFalse(isMessageCopyable(MessageType.IMAGE))
        assertFalse(isMessageCopyable(MessageType.TEXT, isSecretChat = true))
    }

    @Test
    fun replyableAndSwipeThreshold() {
        assertTrue(isMessageReplyable(MessageType.TEXT))
        assertFalse(isMessageReplyable(MessageType.SYSTEM))
        assertTrue(shouldTriggerSwipeReply(-52f, 52f))
        assertFalse(shouldTriggerSwipeReply(-51f, 52f))
        assertFalse(shouldTriggerSwipeReply(-52f, 0f))
    }

    @Test
    fun selectionToggleAndQuickReaction() {
        assertEquals(setOf("m1"), toggleMessageSelection(emptySet(), "m1"))
        assertEquals(emptySet<String>(), toggleMessageSelection(setOf("m1"), "m1"))
        assertEquals("❤️", DEFAULT_QUICK_REACTION)
    }

    @Test
    fun pinPolicyGroupNeedsOwnerOrAdmin() {
        assertTrue(MessagePinPolicy.canPin(true, "OWNER", MessageType.TEXT))
        assertTrue(MessagePinPolicy.canPin(true, "admin", MessageType.TEXT))
        assertFalse(MessagePinPolicy.canPin(true, "MEMBER", MessageType.TEXT))
        assertTrue(MessagePinPolicy.canPin(false, null, MessageType.TEXT))
        assertFalse(MessagePinPolicy.canPin(false, null, MessageType.SYSTEM))
        assertEquals(20, MessagePinPolicy.MAX_PINS)
        assertFalse(MessagePinPolicy.wouldExceedLimit(19, alreadyPinned = false))
        assertTrue(MessagePinPolicy.wouldExceedLimit(20, alreadyPinned = false))
        assertEquals("hello", MessagePinPolicy.textPreview("hello<meta>{\"a\":1}"))
    }

}
