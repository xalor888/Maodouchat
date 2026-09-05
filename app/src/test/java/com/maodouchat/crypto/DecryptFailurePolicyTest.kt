package com.maodouchat.crypto

import com.maodouchat.core.crypto.DecryptResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecryptFailurePolicyTest {

    @Test
    fun terminalResultsStopImmediately() {
        val stop = listOf(
            DecryptResult.Success("ok"),
            DecryptResult.Duplicate,
            DecryptResult.UnsupportedEnvelope,
            DecryptResult.NotForThisDevice,
        )
        stop.forEach { result ->
            assertEquals(DecryptFailurePolicy.Disposition.STOP, DecryptFailurePolicy.disposition(result))
            assertTrue(DecryptFailurePolicy.shouldStop(result, previousAttempts = 0))
        }
    }

    @Test
    fun duplicateIsRememberedAsTerminalRatherThanCleared() {
        assertEquals(
            DecryptFailurePolicy.TrackingAction.MARK_TERMINAL,
            DecryptFailurePolicy.trackingAction(DecryptResult.Duplicate)
        )
        assertEquals(
            DecryptFailurePolicy.TrackingAction.CLEAR,
            DecryptFailurePolicy.trackingAction(DecryptResult.Success("ok"))
        )
    }

    @Test
    fun malformedSenderKeyDistributionIsTerminalButUnknownFailureCanRetry() {
        assertEquals(
            SenderKeyDistOutcome.Skipped,
            SenderKeyDistributionFailurePolicy.outcomeFor(IllegalArgumentException("bad base64"))
        )
        assertEquals(
            SenderKeyDistOutcome.Failed,
            SenderKeyDistributionFailurePolicy.outcomeFor(IllegalStateException("store unavailable"))
        )
    }

    @Test
    fun retryableResultsStopOnlyAfterCap() {
        val retry = listOf(
            DecryptResult.NoSession,
            DecryptResult.UntrustedIdentity,
            DecryptResult.FutureEpoch,
            DecryptResult.Failed,
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
        assertTrue(tracker.shouldAcknowledge("e1", DecryptResult.Duplicate))
        assertTrue(tracker.shouldAcknowledge("e2", DecryptResult.UnsupportedEnvelope))
        assertTrue(tracker.shouldAcknowledge("e3", DecryptResult.NotForThisDevice))

        repeat(4) {
            assertFalse(tracker.shouldAcknowledge("e4", DecryptResult.Failed))
        }
        assertTrue(tracker.shouldAcknowledge("e4", DecryptResult.Failed))
        // 达上限后计数已清，下一次重新计数
        assertFalse(tracker.shouldAcknowledge("e4", DecryptResult.Failed))
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
    fun successfulPeerRepairClearsOnlyThatSendersCappedFailures() {
        val tracker = DecryptRetryTracker()
        val repaired = DecryptFailurePolicy.envelopeFingerprint("u1", "cipher-a")
        val unrelated = DecryptFailurePolicy.envelopeFingerprint("u2", "cipher-b")
        repeat(5) { tracker.recordCryptoFailure("u1", repaired) }
        repeat(5) { tracker.recordCryptoFailure("u2", unrelated) }

        assertTrue(DecryptFailurePolicy.shouldSkipCryptoAttempt(tracker.failureCount(repaired)))
        assertTrue(DecryptFailurePolicy.shouldSkipCryptoAttempt(tracker.failureCount(unrelated)))

        tracker.clearForSender("u1")

        assertFalse(DecryptFailurePolicy.shouldSkipCryptoAttempt(tracker.failureCount(repaired)))
        assertTrue(DecryptFailurePolicy.shouldSkipCryptoAttempt(tracker.failureCount(unrelated)))
    }

    @Test
    fun terminalEnvelopeIsRememberedAndDoesNotRemainARecoverablePlaceholder() {
        val tracker = DecryptRetryTracker()
        val fingerprint = DecryptFailurePolicy.envelopeFingerprint("u1", "malformed")

        tracker.markTerminal(fingerprint)

        assertTrue(tracker.isTerminal(fingerprint))
        assertEquals(0, tracker.failureCount(fingerprint))
        tracker.clear(fingerprint)
        assertFalse(tracker.isTerminal(fingerprint))
    }

    @Test
    fun decryptFailNeverWritesPlaceholderToRoom() {
        val wire =
            """{"senderDeviceId":76,"payloadType":"TEXT","entries":[{"ciphertextType":"prekey","ciphertext":"NAgB"}]}"""
        val placeholder = "无法解密"
        val failures = listOf(
            DecryptResult.NoSession,
            DecryptResult.UntrustedIdentity,
            DecryptResult.FutureEpoch,
            DecryptResult.Failed,
            DecryptResult.NotForThisDevice,
            DecryptResult.Duplicate,
            DecryptResult.UnsupportedEnvelope,
        )
        failures.forEach { result ->
            assertTrue(DecryptFailurePolicy.neverPersistUiPlaceholder(result))
            assertEquals(
                wire,
                DecryptFailurePolicy.persistDecryptResultToRoom(result, wire, placeholder)
            )
        }
        assertEquals(
            "hello",
            DecryptFailurePolicy.persistDecryptResultToRoom(
                DecryptResult.Success("hello"),
                wire,
                placeholder
            )
        )
        assertFalse(DecryptFailurePolicy.neverPersistUiPlaceholder(DecryptResult.Success("hello")))
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
