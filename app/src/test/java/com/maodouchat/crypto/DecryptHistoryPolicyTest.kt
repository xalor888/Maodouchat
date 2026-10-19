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

    @Test
    fun deferSessionRepairIsNarrowToNoSessionAndUntrustedIdentity() {
        assertTrue(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.NoSession))
        assertTrue(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.UntrustedIdentity))
        listOf(
            DecryptResult.NotForThisDevice,
            DecryptResult.FutureEpoch,
            DecryptResult.Duplicate,
            DecryptResult.UnsupportedEnvelope,
            DecryptResult.Success("x"),
        ).forEach { result ->
            assertFalse(result.toString(), DecryptHistoryPolicy.shouldDeferSessionRepair(result))
        }
    }

    @Test
    fun sessionRepairAttemptTrimsWhitespaceBeforeDedup() {
        assertTrue(DecryptHistoryPolicy.shouldAttemptSessionRepair("  u2  ", emptySet()))
        // trim 先于去重：带空格的 u2 仍命中已尝试集合
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair(" u2 ", setOf("u2")))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("\t", emptySet()))
    }

    @Test
    fun sessionRepairAttemptDedupesPerSender() {
        assertTrue(DecryptHistoryPolicy.shouldAttemptSessionRepair("u2", setOf("u1", "u3")))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("u1", setOf("u1", "u3")))
    }

    @Test
    fun sessionRepairAttemptSenderIdIsCaseSensitive() {
        assertTrue(DecryptHistoryPolicy.shouldAttemptSessionRepair("U2", setOf("u2")))
    }

    @Test
    fun emptySuccessPayloadStillDropsWire() {
        assertFalse(DecryptHistoryPolicy.shouldKeepWire(DecryptResult.Success("")))
    }
}
