package com.maodouchat.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Test
import org.signal.libsignal.protocol.InvalidMessageException

class SenderKeyDistributionFailurePolicyTest {

    @Test
    fun `malformed distribution message is skipped`() {
        assertEquals(
            SenderKeyDistOutcome.Skipped,
            SenderKeyDistributionFailurePolicy.outcomeFor(
                InvalidMessageException("bad distribution bytes")
            )
        )
        assertEquals(
            SenderKeyDistOutcome.Skipped,
            SenderKeyDistributionFailurePolicy.outcomeFor(
                IllegalArgumentException("bad base64")
            )
        )
        assertEquals(
            SenderKeyDistOutcome.Skipped,
            SenderKeyDistributionFailurePolicy.outcomeFor(
                NumberFormatException("not a number")
            )
        )
    }

    @Test
    fun `unknown failures are not skipped`() {
        assertEquals(
            SenderKeyDistOutcome.Failed,
            SenderKeyDistributionFailurePolicy.outcomeFor(
                IllegalStateException("store unavailable")
            )
        )
        assertEquals(
            SenderKeyDistOutcome.Failed,
            SenderKeyDistributionFailurePolicy.outcomeFor(
                NullPointerException("x")
            )
        )
    }
}
