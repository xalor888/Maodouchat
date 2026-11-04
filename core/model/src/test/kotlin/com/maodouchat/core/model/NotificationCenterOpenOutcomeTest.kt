package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NotificationCenterOpenOutcomeTest {

    @Test
    fun `navigate carries route`() {
        val outcome = NotificationCenterOpenOutcome.Navigate("chat/c-1")
        assertEquals("chat/c-1", (outcome as NotificationCenterOpenOutcome.Navigate).route)
    }

    @Test
    fun `navigate routes are distinct`() {
        assertNotEquals(
            NotificationCenterOpenOutcome.Navigate("chat/c-1"),
            NotificationCenterOpenOutcome.Navigate("chat/c-2")
        )
    }

    @Test
    fun `singleton outcomes stay apart`() {
        assertNotEquals(
            NotificationCenterOpenOutcome.PopBackStack,
            NotificationCenterOpenOutcome.GoLogin
        )
        assertNotEquals(
            NotificationCenterOpenOutcome.GoLogin,
            NotificationCenterOpenOutcome.None
        )
        assertNotEquals(
            NotificationCenterOpenOutcome.PopBackStack,
            NotificationCenterOpenOutcome.None
        )
    }
}
