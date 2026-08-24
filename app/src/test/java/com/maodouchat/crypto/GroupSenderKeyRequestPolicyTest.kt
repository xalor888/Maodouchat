package com.maodouchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupSenderKeyRequestPolicyTest {
    @Test
    fun `group no-session asks for redistribution and advances sync`() {
        assertTrue(GroupSenderKeyRequestPolicy.shouldRequestRedistribution(true, true))
        assertTrue(GroupSenderKeyRequestPolicy.shouldAdvanceSyncPastMissingKey(true, true))
    }

    @Test
    fun `direct chat no-session does not request group sk`() {
        assertFalse(GroupSenderKeyRequestPolicy.shouldRequestRedistribution(false, true))
        assertFalse(GroupSenderKeyRequestPolicy.shouldAdvanceSyncPastMissingKey(false, true))
    }

    @Test
    fun `future epoch also requests redistribution`() {
        assertTrue(
            GroupSenderKeyRequestPolicy.shouldRequestRedistribution(
                true,
                SignalProtocol.DecryptResult.FutureEpoch
            )
        )
        assertTrue(GroupSenderKeyRequestPolicy.shouldKeepGroupWire(SignalProtocol.DecryptResult.FutureEpoch))
    }

    @Test
    fun `failed and untrusted keep wire so sk retry can redecrypt`() {
        assertTrue(GroupSenderKeyRequestPolicy.shouldKeepGroupWire(SignalProtocol.DecryptResult.Failed))
        assertTrue(GroupSenderKeyRequestPolicy.shouldKeepGroupWire(SignalProtocol.DecryptResult.UntrustedIdentity))
        assertTrue(GroupSenderKeyRequestPolicy.shouldKeepGroupWire(SignalProtocol.DecryptResult.NoSession))
        assertTrue(GroupSenderKeyRequestPolicy.shouldKeepGroupWire(SignalProtocol.DecryptResult.NotForThisDevice))
        assertFalse(
            GroupSenderKeyRequestPolicy.shouldRequestRedistribution(
                true,
                SignalProtocol.DecryptResult.Failed
            )
        )
    }

    @Test
    fun `not-for-this-device keeps wire so own-sent cloud history is not dropped`() {
        assertTrue(GroupSenderKeyRequestPolicy.shouldKeepGroupWire(SignalProtocol.DecryptResult.NotForThisDevice))
        assertFalse(
            GroupSenderKeyRequestPolicy.shouldRequestRedistribution(
                true,
                SignalProtocol.DecryptResult.NotForThisDevice
            )
        )
    }

    @Test
    fun `relogin requests missing keys from peers not from self`() {
        assertTrue(GroupSenderKeyRequestPolicy.shouldRequestFromSender("peer-1", "me"))
        assertFalse(GroupSenderKeyRequestPolicy.shouldRequestFromSender("me", "me"))
        assertFalse(GroupSenderKeyRequestPolicy.shouldRequestFromSender("  ", "me"))
        assertEquals(
            listOf("peer-1", "peer-2"),
            GroupSenderKeyRequestPolicy.sendersNeedingRedistribution(
                listOf("peer-1", "me", "peer-1", "peer-2", ""),
                "me"
            )
        )
    }

    @Test
    fun `per-sender throttle lets every missing peer request after relogin`() {
        val now = 1_000_000L
        val last = mutableMapOf("peer-1" to now)
        assertFalse(GroupSenderKeyRequestPolicy.shouldSendNow("peer-1", now + 1_000L, last))
        assertTrue(GroupSenderKeyRequestPolicy.shouldSendNow("peer-2", now + 1_000L, last))
        assertTrue(
            GroupSenderKeyRequestPolicy.shouldSendNow(
                "peer-1",
                now + GroupSenderKeyRequestPolicy.MIN_REQUEST_INTERVAL_MS,
                last
            )
        )
    }
}
