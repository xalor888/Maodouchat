package com.maodouchat.core.session

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

data class DeviceRiskReport(
    val isRooted: Boolean,
    val isDebuggable: Boolean,
    val isEmulator: Boolean,
    val riskLevel: RiskLevel,
    val riskReasons: List<String>
)

data class AppLockStatus(
    val isEnabled: Boolean,
    val timeoutMinutes: Long,
    val shouldLock: Boolean,
    val isBiometricAvailable: Boolean
)
