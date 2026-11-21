package com.maodouchat.domain.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineDirectChatIdPolicyTest {

    @Test
    fun directChatIdUsesDirectPrefix() {
        assertEquals(
            "direct_owner1_peer2",
            OfflineDirectChatIdPolicy.offlineDirectChatId("owner1", "peer2", false),
        )
    }

    @Test
    fun secretChatIdUsesSecretPrefix() {
        assertEquals(
            "secret_owner1_peer2",
            OfflineDirectChatIdPolicy.offlineDirectChatId("owner1", "peer2", true),
        )
    }

    @Test
    fun ownerComesBeforePeer() {
        assertEquals(
            "direct_alice_bob",
            OfflineDirectChatIdPolicy.offlineDirectChatId("alice", "bob", false),
        )
    }
}
