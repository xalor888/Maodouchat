package com.maodouchat.crypto

import com.maodouchat.core.crypto.DecryptResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecryptHistoryPolicyTest {

    @Test
    fun recoverableFailuresKeepWireIncludingNotForThisDevice() {
        val keep = listOf(
            DecryptResult.NotForThisDevice,
            DecryptResult.NoSession,
            DecryptResult.UntrustedIdentity,
            DecryptResult.FutureEpoch,
            DecryptResult.Failed,
            DecryptResult.Duplicate,
            DecryptResult.UnsupportedEnvelope,
        )
        keep.forEach { result ->
            assertTrue(result.toString(), DecryptHistoryPolicy.shouldKeepWire(result))
        }
        assertFalse(DecryptHistoryPolicy.shouldKeepWire(DecryptResult.Success("ok")))
    }

    @Test
    fun sessionRepairIsDeferredAndDeduped() {
        assertTrue(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.NoSession))
        assertTrue(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.UntrustedIdentity))
        assertFalse(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.Failed))
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
