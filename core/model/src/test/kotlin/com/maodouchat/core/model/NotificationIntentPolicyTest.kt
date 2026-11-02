package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
