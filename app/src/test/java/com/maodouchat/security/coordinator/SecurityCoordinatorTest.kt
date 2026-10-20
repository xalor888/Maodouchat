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
    fun evaluateScreenSecurity_chatSurfaceRouteAlone_doesNotSecure() {
        val secured = coordinator.evaluateScreenSecurity(
            appLockShowing = false,
            globalEnabled = false,
            currentRoute = "chat_detail/user123"
        )
        assertFalse(secured)
    }

    @Test
    fun evaluateScreenSecurity_nullAndBlankRoute_doesNotSecure() {
        listOf(null, "").forEach { route ->
            val secured = coordinator.evaluateScreenSecurity(
                appLockShowing = false,
                globalEnabled = false,
                currentRoute = route
            )
            assertFalse("route=$route", secured)
        }
    }

    @Test
    fun assessDeviceRisk_existingSuFile_reportsRootedHighRisk() {
        val suFile = java.io.File.createTempFile("sufile", ".bin")
        try {
            val report = coordinator.assessDeviceRisk(checkFiles = listOf(suFile.absolutePath))
            assertTrue(report.isRooted)
            assertEquals(RiskLevel.HIGH, report.riskLevel)
            assertEquals(listOf("Device appears rooted"), report.riskReasons)
        } finally {
            suFile.delete()
        }
    }

    @Test
    fun assessDeviceRisk_debugBuildWithCleanFiles_reportsMediumNotHigh() {
        val report = coordinator.assessDeviceRisk(
            isDebugBuild = true,
            checkFiles = listOf("/path/that/does/not/exist/su")
        )
        assertFalse(report.isRooted)
        assertTrue(report.isDebuggable)
        assertEquals(RiskLevel.MEDIUM, report.riskLevel)
        assertEquals(listOf("Running in debuggable mode"), report.riskReasons)
    }

    @Test
    fun assessDeviceRisk_rootedDebugBuild_reportsHighWithBothReasons() {
        val suFile = java.io.File.createTempFile("sufile", ".bin")
        try {
            val report = coordinator.assessDeviceRisk(
                isDebugBuild = true,
                checkFiles = listOf(suFile.absolutePath)
            )
            assertTrue(report.isRooted)
            assertTrue(report.isDebuggable)
            assertEquals(RiskLevel.HIGH, report.riskLevel)
            assertTrue(report.riskReasons.contains("Device appears rooted"))
            assertTrue(report.riskReasons.contains("Running in debuggable mode"))
        } finally {
            suFile.delete()
        }
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
