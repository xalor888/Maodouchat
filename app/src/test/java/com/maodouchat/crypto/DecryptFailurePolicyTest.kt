package com.maodouchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecryptFailurePolicyTest {

    @Test
    fun terminalResultsStopImmediately() {
        val stop = listOf(
            SignalProtocol.DecryptResult.Success("ok"),
            SignalProtocol.DecryptResult.Duplicate,
            SignalProtocol.DecryptResult.UnsupportedEnvelope,
            SignalProtocol.DecryptResult.NotForThisDevice,
        )
        stop.forEach { result ->
            assertEquals(DecryptFailurePolicy.Disposition.STOP, DecryptFailurePolicy.disposition(result))
            assertTrue(DecryptFailurePolicy.shouldStop(result, previousAttempts = 0))
        }
    }

    @Test
    fun retryableResultsStopOnlyAfterCap() {
        val retry = listOf(
            SignalProtocol.DecryptResult.NoSession,
            SignalProtocol.DecryptResult.UntrustedIdentity,
            SignalProtocol.DecryptResult.FutureEpoch,
            SignalProtocol.DecryptResult.Failed,
        )
        retry.forEach { result ->
            assertEquals(DecryptFailurePolicy.Disposition.RETRY, DecryptFailurePolicy.disposition(result))
            assertFalse(DecryptFailurePolicy.shouldStop(result, previousAttempts = 0))
            assertFalse(DecryptFailurePolicy.shouldStop(result, previousAttempts = 4))
            assertTrue(DecryptFailurePolicy.shouldStop(result, previousAttempts = 5))
        }
        assertFalse(DecryptFailurePolicy.shouldSkipCryptoAttempt(4))
        assertTrue(DecryptFailurePolicy.shouldSkipCryptoAttempt(5))
    }

    @Test
    fun trackerAcksTerminalAndCapsRetry() {
        val tracker = DecryptRetryTracker(maxAttempts = 5, maxTracked = 8)
        assertTrue(tracker.shouldAcknowledge("e1", SignalProtocol.DecryptResult.Duplicate))
        assertTrue(tracker.shouldAcknowledge("e2", SignalProtocol.DecryptResult.UnsupportedEnvelope))
        assertTrue(tracker.shouldAcknowledge("e3", SignalProtocol.DecryptResult.NotForThisDevice))

        repeat(4) {
            assertFalse(tracker.shouldAcknowledge("e4", SignalProtocol.DecryptResult.Failed))
        }
        assertTrue(tracker.shouldAcknowledge("e4", SignalProtocol.DecryptResult.Failed))
        // 达上限后计数已清，下一次重新计数
        assertFalse(tracker.shouldAcknowledge("e4", SignalProtocol.DecryptResult.Failed))
    }

    @Test
    fun cryptoFailureCountSkipsAfterCap() {
        val tracker = DecryptRetryTracker()
        val fp = DecryptFailurePolicy.envelopeFingerprint("u1", "cipher")
        repeat(5) { tracker.recordCryptoFailure(fp) }
        assertTrue(DecryptFailurePolicy.shouldSkipCryptoAttempt(tracker.failureCount(fp)))
        tracker.clear(fp)
        assertFalse(DecryptFailurePolicy.shouldSkipCryptoAttempt(tracker.failureCount(fp)))
    }

    @Test
    fun fingerprintIsStableAndDoesNotStoreCiphertext() {
        val a = DecryptFailurePolicy.envelopeFingerprint("alice", "ciphertext-payload")
        val b = DecryptFailurePolicy.envelopeFingerprint("alice", "ciphertext-payload")
        val c = DecryptFailurePolicy.envelopeFingerprint("bob", "ciphertext-payload")
        assertEquals(a, b)
        assertTrue(a != c)
        assertFalse(a.contains("ciphertext-payload"))
    }
}
