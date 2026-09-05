package com.maodouchat.security.coordinator

import android.content.Context
import android.os.Build
import com.maodouchat.security.AppLockManager
import com.maodouchat.security.ScreenSecurePolicy
import com.maodouchat.security.SensitiveAction
import com.maodouchat.security.SensitiveActionGate
import java.io.File

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

/**
 * 统一安全协调器。
 *
 * 聚合全应用的安全域能力：
 * 1. 应用锁（AppLockManager & 生物凭据）
 * 2. 敏感操作二次验证（SensitiveActionGate）
 * 3. 窗口防截屏与录屏策略（ScreenSecurePolicy）
 * 4. 设备运行环境风险评估（Root / 调试 / 模拟器检测）
 */
open class SecurityCoordinator(
    private val appLockManager: AppLockManager = AppLockManager,
    private val sensitiveActionGate: SensitiveActionGate = SensitiveActionGate
) {
    companion object {
        val DEFAULT_SU_PATHS = listOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
    }

    fun checkAppLock(context: Context): AppLockStatus {
        val enabled = appLockManager.isEnabled(context)
        val timeout = appLockManager.getTimeoutMinutes(context)
        val shouldLock = appLockManager.shouldLock(context)
        val available = appLockManager.isAuthenticationAvailable(context)
        return AppLockStatus(
            isEnabled = enabled,
            timeoutMinutes = timeout,
            shouldLock = shouldLock,
            isBiometricAvailable = available
        )
    }

    fun markAppUnlocked(context: Context) {
        appLockManager.markUnlocked(context)
    }

    fun noteAppBackground(context: Context) {
        appLockManager.noteBackground(context)
    }

    fun noteAppForeground(context: Context) {
        // AppLock uses shouldLock(context) on foreground entry and markAppUnlocked upon auth
    }

    fun setAppLockEnabled(context: Context, enabled: Boolean) {
        appLockManager.setEnabled(context, enabled)
    }

    fun setAppLockTimeout(context: Context, minutes: Long) {
        appLockManager.setTimeoutMinutes(context, minutes)
    }

    fun requiresSensitiveStepUp(context: Context, action: SensitiveAction): Boolean =
        sensitiveActionGate.requiresStepUp(context, action)

    fun confirmSensitiveAction(
        context: Context,
        action: SensitiveAction,
        title: String,
        subtitle: String? = null,
        onSuccess: () -> Unit,
        onFailure: (String?) -> Unit = {}
    ) {
        sensitiveActionGate.confirm(
            context = context,
            action = action,
            title = title,
            subtitle = subtitle,
            onSuccess = onSuccess,
            onFailure = onFailure
        )
    }

    fun evaluateScreenSecurity(
        appLockShowing: Boolean,
        globalEnabled: Boolean,
        currentRoute: String?,
        isSecretChatActive: Boolean = false,
        isChatLockShowing: Boolean = false
    ): Boolean {
        val onChatSurface = ScreenSecurePolicy.isChatSurfaceRoute(currentRoute)
        return ScreenSecurePolicy.shouldSecureWindow(
            appLockShowing = appLockShowing,
            globalEnabled = globalEnabled,
            onChatSurface = onChatSurface,
            secretChatSurfaceActive = isSecretChatActive,
            chatLockSurfaceActive = isChatLockShowing
        )
    }

    fun assessDeviceRisk(
        isDebugBuild: Boolean = false,
        checkFiles: List<String> = DEFAULT_SU_PATHS
    ): DeviceRiskReport {
        val tags = Build.TAGS.orEmpty()
        val isRooted = checkFiles.any { path ->
            runCatching { File(path).exists() }.getOrDefault(false)
        } || tags.contains("test-keys")

        val fingerprint = Build.FINGERPRINT.orEmpty()
        val model = Build.MODEL.orEmpty()
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val brand = Build.BRAND.orEmpty()
        val device = Build.DEVICE.orEmpty()
        val product = Build.PRODUCT.orEmpty()

        val isEmulator = fingerprint.startsWith("generic") ||
            fingerprint.startsWith("unknown") ||
            model.contains("google_sdk") ||
            model.contains("Emulator") ||
            model.contains("Android SDK built for x86") ||
            manufacturer.contains("Genymotion") ||
            (brand.startsWith("generic") && device.startsWith("generic")) ||
            product == "google_sdk"

        val reasons = mutableListOf<String>()
        if (isRooted) reasons.add("Device appears rooted")
        if (isDebugBuild) reasons.add("Running in debuggable mode")
        if (isEmulator) reasons.add("Running on emulator")

        val riskLevel = when {
            isRooted -> RiskLevel.HIGH
            isDebugBuild || isEmulator -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }

        return DeviceRiskReport(
            isRooted = isRooted,
            isDebuggable = isDebugBuild,
            isEmulator = isEmulator,
            riskLevel = riskLevel,
            riskReasons = reasons
        )
    }
}
