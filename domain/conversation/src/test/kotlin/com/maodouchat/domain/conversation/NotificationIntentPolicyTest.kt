package com.maodouchat.domain.conversation

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class NotificationIntentPolicyTest {

    @Test
    fun `purge or blank owner never belongs to current account`() {
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount("u1", "u1", true))
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount(null, "u1", false))
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount("u1", null, false))
        assertTrue(NotificationIntentPolicy.belongsToCurrentAccount("u1", "u1", false))
    }

    @Test
    fun `open chat id trims and drops blanks`() {
        assertNull(NotificationIntentPolicy.resolveOpenChatId(null))
        assertNull(NotificationIntentPolicy.resolveOpenChatId("   "))
        assertEquals("c1", NotificationIntentPolicy.resolveOpenChatId("  c1 "))
    }
}
