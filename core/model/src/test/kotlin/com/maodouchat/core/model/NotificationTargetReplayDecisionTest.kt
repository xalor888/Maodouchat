package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTargetReplayDecisionTest {

    @Test
    fun `when over all three decisions is exhaustive`() {
        fun tag(d: NotificationTargetReplayDecision): String = when (d) {
            NotificationTargetReplayDecision.ContinueWaiting -> "wait"
            NotificationTargetReplayDecision.Drop -> "drop"
            NotificationTargetReplayDecision.Navigate -> "go"
        }
        assertEquals("wait", tag(NotificationTargetReplayDecision.ContinueWaiting))
        assertEquals("drop", tag(NotificationTargetReplayDecision.Drop))
        assertEquals("go", tag(NotificationTargetReplayDecision.Navigate))
    }
}
