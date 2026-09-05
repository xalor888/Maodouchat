package com.maodouchat.security.coordinator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SecurityCoordinatorTest {

    private lateinit var coordinator: SecurityCoordinator

    @Before
    fun setUp() {
        coordinator = SecurityCoordinator()
    }

    @Test
    fun evaluateScreenSecurity_appLockShowing_alwaysSecures() {
        val secured = coordinator.evaluateScreenSecurity(
            appLockShowing = true,
            globalEnabled = false,
            currentRoute = "settings"
        )
        assertTrue(secured)
    }

    @Test
    fun evaluateScreenSecurity_secretChatActive_alwaysSecures() {
        val secured = coordinator.evaluateScreenSecurity(
            appLockShowing = false,
            globalEnabled = false,
            currentRoute = "chat_detail/user123",
            isSecretChatActive = true
        )
        assertTrue(secured)
    }

    @Test
    fun evaluateScreenSecurity_chatLockShowing_alwaysSecures() {
        val secured = coordinator.evaluateScreenSecurity(
            appLockShowing = false,
            globalEnabled = false,
            currentRoute = "conversations",
            isChatLockShowing = true
        )
        assertTrue(secured)
    }

    @Test
    fun evaluateScreenSecurity_globalEnabled_alwaysSecures() {
        val secured = coordinator.evaluateScreenSecurity(
            appLockShowing = false,
            globalEnabled = true,
            currentRoute = "settings"
        )
        assertTrue(secured)
    }

    @Test
    fun evaluateScreenSecurity_nonChatNonLocked_doesNotSecure() {
        val secured = coordinator.evaluateScreenSecurity(
            appLockShowing = false,
            globalEnabled = false,
            currentRoute = "settings"
        )
        assertFalse(secured)
    }

    @Test
    fun assessDeviceRisk_whenNonRootAndNonDebug_reportsLowRisk() {
        val report = coordinator.assessDeviceRisk(
            isDebugBuild = false,
            checkFiles = emptyList() // no su files
        )
        // If not running on an emulator in CI/host
        if (!report.isEmulator) {
            assertEquals(RiskLevel.LOW, report.riskLevel)
            assertFalse(report.isRooted)
            assertFalse(report.isDebuggable)
        }
    }

    @Test
    fun assessDeviceRisk_whenDebugBuild_reportsMediumRisk() {
        val report = coordinator.assessDeviceRisk(
            isDebugBuild = true,
            checkFiles = emptyList()
        )
        assertTrue(report.isDebuggable)
        assertTrue(report.riskLevel == RiskLevel.MEDIUM || report.riskLevel == RiskLevel.HIGH)
    }
}
