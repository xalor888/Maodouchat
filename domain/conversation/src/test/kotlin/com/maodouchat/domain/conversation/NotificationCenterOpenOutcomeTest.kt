package com.maodouchat.domain.conversation

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.Test

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
        assertNotEquals<NotificationCenterOpenOutcome>(
            NotificationCenterOpenOutcome.PopBackStack,
            NotificationCenterOpenOutcome.GoLogin
        )
        assertNotEquals<NotificationCenterOpenOutcome>(
            NotificationCenterOpenOutcome.GoLogin,
            NotificationCenterOpenOutcome.None
        )
        assertNotEquals<NotificationCenterOpenOutcome>(
            NotificationCenterOpenOutcome.PopBackStack,
            NotificationCenterOpenOutcome.None
        )
    }
}
