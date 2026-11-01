package com.maodouchat.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityCoordinatorModelsTest {

    @Test
    fun riskLevelsResolveByName() {
        assertEquals(RiskLevel.HIGH, RiskLevel.valueOf("HIGH"))
        assertEquals(3, RiskLevel.entries.size)
    }

    @Test
    fun deviceRiskReportHoldsFields() {
        val report = DeviceRiskReport(
            isRooted = true,
            isDebuggable = false,
            isEmulator = false,
            riskLevel = RiskLevel.HIGH,
            riskReasons = listOf("Device appears rooted")
        )
        assertTrue(report.isRooted)
        assertEquals(RiskLevel.HIGH, report.riskLevel)
        assertEquals(1, report.riskReasons.size)
    }

    @Test
    fun appLockStatusHoldsFields() {
        val status = AppLockStatus(
            isEnabled = true,
            timeoutMinutes = 5,
            shouldLock = true,
            isBiometricAvailable = false
        )
        assertTrue(status.isEnabled)
        assertEquals(5L, status.timeoutMinutes)
        assertTrue(status.shouldLock)
    }
}
