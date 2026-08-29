package com.maodouchat.messaging.v2

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessagingV2GroupControlPolicyTest {
    @Test
    fun `sender key controls are stale when membership revision changes`() {
        assertTrue(MessagingV2GroupControlPolicy.isStale("SENDER_KEY", 4L, 5L))
        assertTrue(MessagingV2GroupControlPolicy.isStale("KEY_REQUEST", null, 5L))
        assertFalse(MessagingV2GroupControlPolicy.isStale("SENDER_KEY", 5L, 5L))
    }

    @Test
    fun `user data is re-prepared rather than discarded`() {
        assertFalse(MessagingV2GroupControlPolicy.isGroupControl("DATA"))
        assertFalse(MessagingV2GroupControlPolicy.isStale("DATA", 4L, 5L))
    }
}
