package com.maodouchat.security

import org.junit.Assert.assertEquals
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
    fun `each action is pinned by name when both gates on`() {
        // 枚举增删时这里先红：逐名钉死六个敏感动作都要二次验证
        assertTrue(stepUp(SensitiveAction.LOGOUT))
        assertTrue(stepUp(SensitiveAction.DELETE_ACCOUNT))
        assertTrue(stepUp(SensitiveAction.EXPORT_CHAT))
        assertTrue(stepUp(SensitiveAction.CLEAR_CHAT_HISTORY))
        assertTrue(stepUp(SensitiveAction.DISABLE_APP_LOCK))
        assertTrue(stepUp(SensitiveAction.DISABLE_TOTP))
    }

    @Test
    fun `either gate off disables step up for every action`() {
        SensitiveAction.entries.forEach { action ->
            assertFalse(
                action.name,
                SensitiveActionPolicy.requiresStepUp(
                    appLockEnabled = true,
                    sensitiveGateEnabled = false,
                    action = action
                )
            )
            assertFalse(
                action.name,
                SensitiveActionPolicy.requiresStepUp(
                    appLockEnabled = false,
                    sensitiveGateEnabled = true,
                    action = action
                )
            )
            assertFalse(
                action.name,
                SensitiveActionPolicy.requiresStepUp(
                    appLockEnabled = false,
                    sensitiveGateEnabled = false,
                    action = action
                )
            )
        }
    }

    @Test
    fun `sensitive action set has exactly six members`() {
        assertEquals(
            listOf(
                "LOGOUT",
                "DELETE_ACCOUNT",
                "EXPORT_CHAT",
                "CLEAR_CHAT_HISTORY",
                "DISABLE_APP_LOCK",
                "DISABLE_TOTP"
            ),
            SensitiveAction.entries.map { it.name }
        )
    }

    @Test
    fun `export chat follows the full gate truth table`() {
        // 门禁是纯 AND：两真才验证，其余三组合一律放行
        assertTrue(
            SensitiveActionPolicy.requiresStepUp(
                appLockEnabled = true,
                sensitiveGateEnabled = true,
                action = SensitiveAction.EXPORT_CHAT
            )
        )
        assertFalse(
            SensitiveActionPolicy.requiresStepUp(
                appLockEnabled = true,
                sensitiveGateEnabled = false,
                action = SensitiveAction.EXPORT_CHAT
            )
        )
        assertFalse(
            SensitiveActionPolicy.requiresStepUp(
                appLockEnabled = false,
                sensitiveGateEnabled = true,
                action = SensitiveAction.EXPORT_CHAT
            )
        )
        assertFalse(
            SensitiveActionPolicy.requiresStepUp(
                appLockEnabled = false,
                sensitiveGateEnabled = false,
                action = SensitiveAction.EXPORT_CHAT
            )
        )
    }

    @Test
    fun `step up decision is deterministic across calls`() {
        repeat(3) {
            SensitiveAction.entries.forEach { action ->
                assertEquals(
                    action.name,
                    true,
                    stepUp(action)
                )
                assertEquals(
                    action.name,
                    false,
                    SensitiveActionPolicy.requiresStepUp(
                        appLockEnabled = false,
                        sensitiveGateEnabled = false,
                        action = action
                    )
                )
            }
        }
    }

    private fun stepUp(action: SensitiveAction): Boolean =
        SensitiveActionPolicy.requiresStepUp(
            appLockEnabled = true,
            sensitiveGateEnabled = true,
            action = action
        )

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

    @Test
    fun `disable totp follows the full gate truth table`() {
        assertTrue(stepUp(SensitiveAction.DISABLE_TOTP))
        assertFalse(
            SensitiveActionPolicy.requiresStepUp(
                appLockEnabled = true,
                sensitiveGateEnabled = false,
                action = SensitiveAction.DISABLE_TOTP
            )
        )
        assertFalse(
            SensitiveActionPolicy.requiresStepUp(
                appLockEnabled = false,
                sensitiveGateEnabled = true,
                action = SensitiveAction.DISABLE_TOTP
            )
        )
        assertFalse(
            SensitiveActionPolicy.requiresStepUp(
                appLockEnabled = false,
                sensitiveGateEnabled = false,
                action = SensitiveAction.DISABLE_TOTP
            )
        )
    }

    @Test
    fun `disable app lock follows the full gate truth table`() {
        assertTrue(stepUp(SensitiveAction.DISABLE_APP_LOCK))
        assertFalse(
            SensitiveActionPolicy.requiresStepUp(
                appLockEnabled = true,
                sensitiveGateEnabled = false,
                action = SensitiveAction.DISABLE_APP_LOCK
            )
        )
        assertFalse(
            SensitiveActionPolicy.requiresStepUp(
                appLockEnabled = false,
                sensitiveGateEnabled = true,
                action = SensitiveAction.DISABLE_APP_LOCK
            )
        )
        assertFalse(
            SensitiveActionPolicy.requiresStepUp(
                appLockEnabled = false,
                sensitiveGateEnabled = false,
                action = SensitiveAction.DISABLE_APP_LOCK
            )
        )
    }

    @Test
    fun `clear chat history follows the full gate truth table`() {
        assertTrue(stepUp(SensitiveAction.CLEAR_CHAT_HISTORY))
        assertFalse(
            SensitiveActionPolicy.requiresStepUp(
                appLockEnabled = true,
                sensitiveGateEnabled = false,
                action = SensitiveAction.CLEAR_CHAT_HISTORY
            )
        )
        assertFalse(
            SensitiveActionPolicy.requiresStepUp(
                appLockEnabled = false,
                sensitiveGateEnabled = true,
                action = SensitiveAction.CLEAR_CHAT_HISTORY
            )
        )
        assertFalse(
            SensitiveActionPolicy.requiresStepUp(
                appLockEnabled = false,
                sensitiveGateEnabled = false,
                action = SensitiveAction.CLEAR_CHAT_HISTORY
            )
        )
    }

    @Test
    fun `logout follows the full gate truth table`() {
        assertTrue(stepUp(SensitiveAction.LOGOUT))
        assertFalse(
            SensitiveActionPolicy.requiresStepUp(
                appLockEnabled = true,
                sensitiveGateEnabled = false,
                action = SensitiveAction.LOGOUT
            )
        )
        assertFalse(
            SensitiveActionPolicy.requiresStepUp(
                appLockEnabled = false,
                sensitiveGateEnabled = true,
                action = SensitiveAction.LOGOUT
            )
        )
        assertFalse(
            SensitiveActionPolicy.requiresStepUp(
                appLockEnabled = false,
                sensitiveGateEnabled = false,
                action = SensitiveAction.LOGOUT
            )
        )
    }

    @Test
    fun `delete account follows the full gate truth table`() {
        assertTrue(stepUp(SensitiveAction.DELETE_ACCOUNT))
        assertFalse(
            SensitiveActionPolicy.requiresStepUp(
                appLockEnabled = true,
                sensitiveGateEnabled = false,
                action = SensitiveAction.DELETE_ACCOUNT
            )
        )
        assertFalse(
            SensitiveActionPolicy.requiresStepUp(
                appLockEnabled = false,
                sensitiveGateEnabled = true,
                action = SensitiveAction.DELETE_ACCOUNT
            )
        )
        assertFalse(
            SensitiveActionPolicy.requiresStepUp(
                appLockEnabled = false,
                sensitiveGateEnabled = false,
                action = SensitiveAction.DELETE_ACCOUNT
            )
        )
    }
}
