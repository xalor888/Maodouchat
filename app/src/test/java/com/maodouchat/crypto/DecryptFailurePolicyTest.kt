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
            assertEquals(Disposition.STOP, DecryptFailurePolicy.disposition(result))
            assertTrue(DecryptFailurePolicy.shouldStop(result, previousAttempts = 0))
        }
    }

    @Test
    fun duplicateIsRememberedAsTerminalRatherThanCleared() {
        assertEquals(
            TrackingAction.MARK_TERMINAL,
            DecryptFailurePolicy.trackingAction(DecryptResult.Duplicate)
        )
        assertEquals(
            TrackingAction.CLEAR,
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
            assertEquals(Disposition.RETRY, DecryptFailurePolicy.disposition(result))
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

    @Test
    fun retryableResultsRecordFailureForTracking() {
        listOf(
            DecryptResult.NoSession,
            DecryptResult.UntrustedIdentity,
            DecryptResult.FutureEpoch,
            DecryptResult.Failed,
        ).forEach { result ->
            assertEquals(TrackingAction.RECORD_FAILURE, DecryptFailurePolicy.trackingAction(result))
        }
    }

    @Test
    fun blankEnvelopeIdAcknowledgesOnlyTerminalResults() {
        val tracker = DecryptRetryTracker()
        assertTrue(tracker.shouldAcknowledge("", DecryptResult.Duplicate))
        assertTrue(tracker.shouldAcknowledge("  ", DecryptResult.Success("x")))
        assertFalse(tracker.shouldAcknowledge("", DecryptResult.Failed))
        assertFalse(tracker.shouldAcknowledge("  ", DecryptResult.NoSession))
    }

    @Test
    fun blankFingerprintsAreNoOpsAndNeverTerminal() {
        val tracker = DecryptRetryTracker()
        tracker.recordCryptoFailure("")
        tracker.markTerminal("")
        tracker.clear("")
        assertFalse(tracker.isTerminal(""))
        assertEquals(0, tracker.failureCount(""))
        // 空 senderId 只计数不建归属：clearForSender 影响不到它
        val fp = DecryptFailurePolicy.envelopeFingerprint("u9", "c")
        tracker.recordCryptoFailure("", fp)
        assertEquals(1, tracker.failureCount(fp))
        tracker.clearForSender("u9")
        assertEquals(1, tracker.failureCount(fp))
    }

    @Test
    fun trackerEvictsBeyondMaxTracked() {
        val tracker = DecryptRetryTracker(maxAttempts = 5, maxTracked = 3)
        val fps = (1..4).map { DecryptFailurePolicy.envelopeFingerprint("u$it", "c$it") }
        fps.forEach { tracker.recordCryptoFailure(it) }
        assertEquals(3, fps.sumOf { tracker.failureCount(it) })
    }

    @Test
    fun clearAllResetsTracker() {
        val tracker = DecryptRetryTracker()
        val fp = DecryptFailurePolicy.envelopeFingerprint("u1", "c")
        val terminalFp = DecryptFailurePolicy.envelopeFingerprint("u2", "d")
        repeat(3) { tracker.recordCryptoFailure("u1", fp) }
        tracker.markTerminal(terminalFp)
        tracker.clearAll()
        assertEquals(0, tracker.failureCount(fp))
        assertFalse(tracker.isTerminal(terminalFp))
        tracker.recordCryptoFailure("u1", fp)
        assertEquals(1, tracker.failureCount(fp))
        tracker.clearForSender("u1")
        assertEquals(0, tracker.failureCount(fp))
    }

    @Test
    fun negativeAttemptCountsNeverStop() {
        listOf(DecryptResult.NoSession, DecryptResult.Failed).forEach { result ->
            assertFalse(DecryptFailurePolicy.shouldStop(result, previousAttempts = -1))
        }
        assertFalse(DecryptFailurePolicy.shouldSkipCryptoAttempt(-1))
        assertTrue(DecryptFailurePolicy.shouldStop(DecryptResult.Duplicate, previousAttempts = -1))
    }

    @Test
    fun blankWireIsReturnedUntouchedEvenOnSuccess() {
        assertEquals(
            "",
            DecryptFailurePolicy.persistDecryptResultToRoom(DecryptResult.Success("hello"), "", "无法解密")
        )
        assertEquals(
            "   ",
            DecryptFailurePolicy.persistDecryptResultToRoom(DecryptResult.Success("hello"), "   ", "无法解密")
        )
        assertEquals(
            "",
            DecryptFailurePolicy.persistDecryptResultToRoom(DecryptResult.Failed, "", "无法解密")
        )
    }

    @Test
    fun newFailureRevivesTerminalFingerprint() {
        val tracker = DecryptRetryTracker()
        val fp = DecryptFailurePolicy.envelopeFingerprint("u1", "malformed")
        tracker.markTerminal(fp)
        assertTrue(tracker.isTerminal(fp))
        tracker.recordCryptoFailure(fp)
        assertFalse(tracker.isTerminal(fp))
        assertEquals(1, tracker.failureCount(fp))
    }

    @Test
    fun blankSenderClearIsNoOp() {
        val tracker = DecryptRetryTracker()
        val fp = DecryptFailurePolicy.envelopeFingerprint("u1", "c")
        tracker.recordCryptoFailure("u1", fp)
        tracker.clearForSender("")
        tracker.clearForSender("   ")
        assertEquals(1, tracker.failureCount(fp))
    }

    @Test
    fun fingerprintPrefixesEncodeLengths() {
        assertTrue(DecryptFailurePolicy.envelopeFingerprint("alice", "ciphertext-payload").startsWith("5:18:"))
        assertTrue(DecryptFailurePolicy.envelopeFingerprint("a", "b").startsWith("1:1:"))
    }

    @Test
    fun trackerHonorsCustomAttemptCap() {
        val tracker = DecryptRetryTracker(maxAttempts = 2)
        assertFalse(tracker.shouldAcknowledge("e1", DecryptResult.Failed))
        assertTrue(tracker.shouldAcknowledge("e1", DecryptResult.Failed))
        // 达上限后计数已清，下一次重新计数
        assertFalse(tracker.shouldAcknowledge("e1", DecryptResult.Failed))
    }

    @Test
    fun markTerminalDropsAccumulatedRetryCount() {
        val tracker = DecryptRetryTracker()
        val fp = DecryptFailurePolicy.envelopeFingerprint("u1", "c")
        repeat(3) { tracker.recordCryptoFailure(fp) }
        assertEquals(3, tracker.failureCount(fp))
        tracker.markTerminal(fp)
        assertEquals(0, tracker.failureCount(fp))
        assertTrue(tracker.isTerminal(fp))
    }

    @Test
    fun clearRemovesSenderOwnership() {
        val tracker = DecryptRetryTracker()
        val fp = DecryptFailurePolicy.envelopeFingerprint("u1", "c")
        tracker.recordCryptoFailure("u1", fp)
        tracker.clear(fp)
        tracker.recordCryptoFailure(fp)
        // clear 已剥离归属：之后按 sender 清理影响不到它
        tracker.clearForSender("u1")
        assertEquals(1, tracker.failureCount(fp))
    }

    @Test
    fun senderKeyDistOutcomeForOtherExceptionTypes() {
        assertEquals(
            SenderKeyDistOutcome.Failed,
            SenderKeyDistributionFailurePolicy.outcomeFor(NullPointerException("x"))
        )
        assertEquals(
            SenderKeyDistOutcome.Failed,
            SenderKeyDistributionFailurePolicy.outcomeFor(NoRecipientDevicesException())
        )
        assertEquals(
            SenderKeyDistOutcome.Failed,
            SenderKeyDistributionFailurePolicy.outcomeFor(
                SignalStorePersistenceException(IllegalStateException("x"))
            )
        )
        // IllegalArgumentException 子类同样跳过
        assertEquals(
            SenderKeyDistOutcome.Skipped,
            SenderKeyDistributionFailurePolicy.outcomeFor(NumberFormatException("x"))
        )
    }

    @Test
    fun terminalFingerprintsAreAlsoEvictedBeyondCap() {
        val tracker = DecryptRetryTracker(maxTracked = 2)
        val fps = (1..3).map { DecryptFailurePolicy.envelopeFingerprint("u$it", "c$it") }
        fps.forEach { tracker.markTerminal(it) }
        assertEquals(2, fps.count { tracker.isTerminal(it) })
    }

    @Test
    fun terminalFingerprintSurvivesSenderRepair() {
        val tracker = DecryptRetryTracker()
        val fp = DecryptFailurePolicy.envelopeFingerprint("u1", "malformed")
        tracker.recordCryptoFailure("u1", fp)
        tracker.markTerminal(fp)
        // markTerminal 即剥离归属：之后按 sender 修掉会话，terminal 仍保留，
        // 确定性失败不再走 crypto ratchet。
        tracker.clearForSender("u1")
        assertTrue(tracker.isTerminal(fp))
        assertEquals(0, tracker.failureCount(fp))
    }

    @Test
    fun stopResultAcknowledgmentResetsRetryCounter() {
        val tracker = DecryptRetryTracker()
        repeat(4) {
            assertFalse(tracker.shouldAcknowledge("e5", DecryptResult.Failed))
        }
        // terminal 结果 ACK 时清掉累计计数：下一次重试从头计。
        assertTrue(tracker.shouldAcknowledge("e5", DecryptResult.Duplicate))
        assertFalse(tracker.shouldAcknowledge("e5", DecryptResult.Failed))
    }

    @Test
    fun evictionKeepsTerminalBeforeAttempts() {
        val tracker = DecryptRetryTracker(maxTracked = 2)
        val fp1 = DecryptFailurePolicy.envelopeFingerprint("u1", "c1")
        val fp2 = DecryptFailurePolicy.envelopeFingerprint("u2", "c2")
        val terminalFp = DecryptFailurePolicy.envelopeFingerprint("u3", "c3")
        tracker.recordCryptoFailure(fp1)
        tracker.recordCryptoFailure(fp2)
        tracker.markTerminal(terminalFp)
        // 超限先驱逐可重试计数，terminal 保留。
        assertTrue(tracker.isTerminal(terminalFp))
        assertEquals(1, tracker.failureCount(fp1) + tracker.failureCount(fp2))
    }

    @Test
    fun fingerprintDistinguishesSameLengthPairs() {
        val a = DecryptFailurePolicy.envelopeFingerprint("ab", "cd")
        val b = DecryptFailurePolicy.envelopeFingerprint("xy", "zw")
        // 长度前缀相同（2:2:），但内容不同不能碰撞。
        assertTrue(a.startsWith("2:2:"))
        assertTrue(b.startsWith("2:2:"))
        assertTrue(a != b)
    }

    @Test
    fun successWithEmptyPlaintextPersistsEmpty() {
        assertEquals(
            "",
            DecryptFailurePolicy.persistDecryptResultToRoom(
                DecryptResult.Success(""),
                "wire123",
                "无法解密"
            )
        )
    }
}
