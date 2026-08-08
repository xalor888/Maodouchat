package com.maodouchat.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveActionPolicyTest {
    @Test
    fun `requires both app lock and gate`() {
        assertFalse(
            SensitiveActionPolicy.requiresStepUp(
                appLockEnabled = false,
                sensitiveGateEnabled = true,
                action = SensitiveAction.LOGOUT
            )
        )
        assertFalse(
            SensitiveActionPolicy.requiresStepUp(
                appLockEnabled = true,
                sensitiveGateEnabled = false,
                action = SensitiveAction.LOGOUT
            )
        )
        assertTrue(
            SensitiveActionPolicy.requiresStepUp(
                appLockEnabled = true,
                sensitiveGateEnabled = true,
                action = SensitiveAction.EXPORT_CHAT
            )
        )
        assertTrue(
            SensitiveActionPolicy.requiresStepUp(
                appLockEnabled = true,
                sensitiveGateEnabled = true,
                action = SensitiveAction.DELETE_ACCOUNT
            )
        )
    }

    @Test
    fun `covers all sensitive actions when both gates on`() {
        SensitiveAction.entries.forEach { action ->
            assertTrue(
                SensitiveActionPolicy.requiresStepUp(
                    appLockEnabled = true,
                    sensitiveGateEnabled = true,
                    action = action
                )
            )
            assertFalse(
                SensitiveActionPolicy.requiresStepUp(
                    appLockEnabled = false,
                    sensitiveGateEnabled = false,
                    action = action
                )
            )
        }
    }
}
