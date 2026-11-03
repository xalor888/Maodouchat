package com.maodouchat.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingV2GroupControlPolicyTest {

    @Test
    fun `sender key controls are stale when membership revision changes`() {
        assertTrue(MessagingV2GroupControlPolicy.isStale("SENDER_KEY", 4L, 5L))
        assertTrue(MessagingV2GroupControlPolicy.isStale("KEY_REQUEST", null, 5L))
        assertFalse(MessagingV2GroupControlPolicy.isStale("SENDER_KEY", 5L, 5L))
    }

    @Test
    fun `only sender key and key request kinds are group controls`() {
        assertTrue(MessagingV2GroupControlPolicy.isGroupControl("SENDER_KEY"))
        assertTrue(MessagingV2GroupControlPolicy.isGroupControl("KEY_REQUEST"))
        assertFalse(MessagingV2GroupControlPolicy.isGroupControl("DATA"))
        assertFalse(MessagingV2GroupControlPolicy.isGroupControl("EVENT"))
        assertFalse(MessagingV2GroupControlPolicy.isGroupControl(""))
    }

    @Test
    fun `non-positive live revision never reports stale`() {
        assertFalse(MessagingV2GroupControlPolicy.isStale("SENDER_KEY", 4L, 0L))
        assertFalse(MessagingV2GroupControlPolicy.isStale("KEY_REQUEST", null, -1L))
    }
}
