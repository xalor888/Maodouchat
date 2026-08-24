package com.maodouchat.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecryptHistoryPolicyTest {

    @Test
    fun recoverableFailuresKeepWireIncludingNotForThisDevice() {
        val keep = listOf(
            SignalProtocol.DecryptResult.NotForThisDevice,
            SignalProtocol.DecryptResult.NoSession,
            SignalProtocol.DecryptResult.UntrustedIdentity,
            SignalProtocol.DecryptResult.FutureEpoch,
            SignalProtocol.DecryptResult.Failed,
            SignalProtocol.DecryptResult.Duplicate,
            SignalProtocol.DecryptResult.UnsupportedEnvelope,
        )
        keep.forEach { result ->
            assertTrue(result.toString(), DecryptHistoryPolicy.shouldKeepWire(result))
        }
        assertFalse(DecryptHistoryPolicy.shouldKeepWire(SignalProtocol.DecryptResult.Success("ok")))
    }

    @Test
    fun sessionRepairIsDeferredAndDeduped() {
        assertTrue(DecryptHistoryPolicy.shouldDeferSessionRepair(SignalProtocol.DecryptResult.NoSession))
        assertTrue(DecryptHistoryPolicy.shouldDeferSessionRepair(SignalProtocol.DecryptResult.UntrustedIdentity))
        assertFalse(DecryptHistoryPolicy.shouldDeferSessionRepair(SignalProtocol.DecryptResult.Failed))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("", emptySet()))
        assertTrue(DecryptHistoryPolicy.shouldAttemptSessionRepair("u2", emptySet()))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("u2", setOf("u2")))
    }

    @Test
    fun trueWipeWithoutRestoredIdentityCannotDecryptCloudHistory() {
        assertTrue(DecryptHistoryPolicy.newDeviceHistoryCannotDecrypt(identityRestoredFromStore = false))
        assertFalse(DecryptHistoryPolicy.newDeviceHistoryCannotDecrypt(identityRestoredFromStore = true))
    }
}
