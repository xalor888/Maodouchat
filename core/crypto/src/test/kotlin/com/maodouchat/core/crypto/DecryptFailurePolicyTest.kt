package com.maodouchat.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecryptFailurePolicyTest {

    @Test
    fun `terminal results stop and clear`() {
        listOf(
            DecryptResult.Success("ok"),
            DecryptResult.Duplicate,
            DecryptResult.UnsupportedEnvelope,
            DecryptResult.NotForThisDevice
        ).forEach { result ->
            assertEquals(Disposition.STOP, DecryptFailurePolicy.disposition(result))
            assertTrue(DecryptFailurePolicy.shouldStop(result, 0))
        }
        assertEquals(TrackingAction.CLEAR, DecryptFailurePolicy.trackingAction(DecryptResult.Success("ok")))
        assertEquals(TrackingAction.MARK_TERMINAL, DecryptFailurePolicy.trackingAction(DecryptResult.Duplicate))
    }

    @Test
    fun `recoverable results retry until the cap`() {
        listOf(
            DecryptResult.NoSession,
            DecryptResult.UntrustedIdentity,
            DecryptResult.FutureEpoch,
            DecryptResult.Failed
        ).forEach { result ->
            assertEquals(Disposition.RETRY, DecryptFailurePolicy.disposition(result))
            assertEquals(TrackingAction.RECORD_FAILURE, DecryptFailurePolicy.trackingAction(result))
            assertFalse(DecryptFailurePolicy.shouldStop(result, 4))
            assertTrue(DecryptFailurePolicy.shouldStop(result, 5))
        }
    }

    @Test
    fun `tracker acknowledges after max attempts`() {
        val tracker = DecryptRetryTracker()
        val id = "env-1"
        repeat(4) {
            assertFalse(tracker.shouldAcknowledge(id, DecryptResult.Failed))
        }
        assertTrue(tracker.shouldAcknowledge(id, DecryptResult.Failed))
    }

    @Test
    fun `failed result keeps the original wire row`() {
        assertEquals(
            "wire",
            DecryptFailurePolicy.persistDecryptResultToRoom(DecryptResult.Failed, "wire", "placeholder")
        )
        assertEquals(
            "plain",
            DecryptFailurePolicy.persistDecryptResultToRoom(DecryptResult.Success("plain"), "wire", "placeholder")
        )
    }
}
