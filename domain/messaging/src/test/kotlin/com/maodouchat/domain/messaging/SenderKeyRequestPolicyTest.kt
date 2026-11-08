package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SenderKeyRequestPolicyTest {

    @Test
    fun `own sender requested by another user with valid epoch is admitted`() {
        assertTrue(isOwnSenderKeyRequest("owner", "owner", "alice", 7L))
    }

    @Test
    fun `blank owner is rejected`() {
        assertFalse(isOwnSenderKeyRequest("", "owner", "alice", 7L))
    }

    @Test
    fun `request for another user is rejected`() {
        assertFalse(isOwnSenderKeyRequest("owner", "bob", "alice", 7L))
    }

    @Test
    fun `missing requested sender is rejected`() {
        assertFalse(isOwnSenderKeyRequest("owner", null, "alice", 7L))
    }

    @Test
    fun `request from self is rejected`() {
        assertFalse(isOwnSenderKeyRequest("owner", "owner", "owner", 7L))
    }

    @Test
    fun `non positive epoch is rejected`() {
        assertFalse(isOwnSenderKeyRequest("owner", "owner", "alice", 0L))
        assertFalse(isOwnSenderKeyRequest("owner", "owner", "alice", -1L))
    }
}
