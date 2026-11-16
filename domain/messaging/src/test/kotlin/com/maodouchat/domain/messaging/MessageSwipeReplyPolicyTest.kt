package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageSwipeReplyPolicyTest {

    @Test
    fun `zero offset gives zero progress and base scale`() {
        assertEquals(0f, MessageSwipeReplyPolicy.replyProgress(0f, 52f))
        assertEquals(0.7f, MessageSwipeReplyPolicy.replyIconScale(0f, 52f))
        assertFalse(MessageSwipeReplyPolicy.isReplyIconVisible(0f, true))
    }

    @Test
    fun `half threshold gives half progress`() {
        assertEquals(0.5f, MessageSwipeReplyPolicy.replyProgress(26f, 52f))
        assertEquals(0.85f, MessageSwipeReplyPolicy.replyIconScale(26f, 52f), 0.0001f)
    }

    @Test
    fun `at threshold progress is one and scale is crossed`() {
        assertEquals(1f, MessageSwipeReplyPolicy.replyProgress(52f, 52f))
        assertEquals(1.12f, MessageSwipeReplyPolicy.replyIconScale(52f, 52f))
    }

    @Test
    fun `beyond threshold progress stays clamped at one`() {
        assertEquals(1f, MessageSwipeReplyPolicy.replyProgress(104f, 52f))
        assertEquals(1.12f, MessageSwipeReplyPolicy.replyIconScale(-104f, 52f))
    }

    @Test
    fun `icon visible only above 0_05 and when eligible`() {
        assertFalse(MessageSwipeReplyPolicy.isReplyIconVisible(0.04f, true))
        assertTrue(MessageSwipeReplyPolicy.isReplyIconVisible(0.06f, true))
        assertFalse(MessageSwipeReplyPolicy.isReplyIconVisible(0.5f, false))
    }

    @Test
    fun `swipe offset clamps to left only`() {
        assertEquals(-76f, MessageSwipeReplyPolicy.clampSwipeOffset(-100f, 76f))
        assertEquals(0f, MessageSwipeReplyPolicy.clampSwipeOffset(10f, 76f))
        assertEquals(-30f, MessageSwipeReplyPolicy.clampSwipeOffset(-30f, 76f))
    }
}
