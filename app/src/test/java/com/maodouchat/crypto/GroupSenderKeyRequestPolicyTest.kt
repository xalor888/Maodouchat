package com.maodouchat.crypto

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
        assertFalse(
            GroupSenderKeyRequestPolicy.shouldRequestRedistribution(
                true,
                SignalProtocol.DecryptResult.Failed
            )
        )
    }
}
