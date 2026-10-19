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

    @Test
    fun `both control kinds are group controls, nothing else is`() {
        assertTrue(MessagingV2GroupControlPolicy.isGroupControl("SENDER_KEY"))
        assertTrue(MessagingV2GroupControlPolicy.isGroupControl("KEY_REQUEST"))
        assertFalse(MessagingV2GroupControlPolicy.isGroupControl("EVENT"))
        assertFalse(MessagingV2GroupControlPolicy.isGroupControl("RECEIPT"))
        assertFalse(MessagingV2GroupControlPolicy.isGroupControl(""))
    }

    @Test
    fun `kind matching is case sensitive`() {
        assertFalse(MessagingV2GroupControlPolicy.isGroupControl("sender_key"))
        assertFalse(MessagingV2GroupControlPolicy.isGroupControl("Key_Request"))
        assertFalse(MessagingV2GroupControlPolicy.isStale("sender_key", 4L, 5L))
    }

    @Test
    fun `stale when queued revision runs ahead of live revision`() {
        assertTrue(MessagingV2GroupControlPolicy.isStale("SENDER_KEY", 7L, 5L))
        assertTrue(MessagingV2GroupControlPolicy.isStale("KEY_REQUEST", 9L, 5L))
    }

    @Test
    fun `non-positive live revision never reports stale`() {
        assertFalse(MessagingV2GroupControlPolicy.isStale("SENDER_KEY", 4L, 0L))
        assertFalse(MessagingV2GroupControlPolicy.isStale("SENDER_KEY", 4L, -1L))
        assertFalse(MessagingV2GroupControlPolicy.isStale("KEY_REQUEST", null, 0L))
    }

    @Test
    fun `null queued revision on a non-control kind is not stale`() {
        assertFalse(MessagingV2GroupControlPolicy.isStale("DATA", null, 5L))
        assertFalse(MessagingV2GroupControlPolicy.isStale("EVENT", null, 9L))
    }
}
