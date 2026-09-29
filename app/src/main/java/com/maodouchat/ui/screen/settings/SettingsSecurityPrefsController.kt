package com.maodouchat.ui.screen.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.maodouchat.settings.repository.SecurityPreferencesPatch
import com.maodouchat.settings.repository.SettingsRepository

/**
 * G362：安全 UX 客户端偏好（多设备 blob 的推/拉）从 `SettingsViewModel` 抽出
 * （纯搬移不改判断）——两个方法逐字搬移；clientPrefsPushMutex / clientPrefsPullJob
 * 的所有权随之内聚。
 *
 * 依赖全经构造器注入：scope / 安全协调器 / 设置仓库（会话有效性判定）。
 */
internal class SettingsSecurityPrefsController(
    private val scope: CoroutineScope,
    private val securityCoordinator: SecurityCoordinator,
    private val settingsRepository: SettingsRepository,
) {
    private var clientPrefsPullJob: Job? = null
    private val clientPrefsPushMutex = Mutex()

    fun pushSecurityClientPrefs(
        appLockTimeoutMinutes: Long? = null,
        screenSecureEnabled: Boolean? = null,
        sensitiveGateEnabled: Boolean? = null
    ) {
        if (appLockTimeoutMinutes == null && screenSecureEnabled == null && sensitiveGateEnabled == null) return
        scope.launch {
            val session = securityCoordinator.currentSession() ?: return@launch
            clientPrefsPushMutex.withLock {
                securityCoordinator.push(
                    session,
                    SecurityPreferencesPatch(
                        appLockTimeoutMinutes = appLockTimeoutMinutes,
                        screenSecureEnabled = screenSecureEnabled,
                        sensitiveGateEnabled = sensitiveGateEnabled,
                    ),
                )
            }
        }
    }

    /** Pull security UX prefs when opening the security center (app-lock enable stays local). */
    fun pullSecurityClientPrefs(
        onApplied: (timeoutMinutes: Long, screenSecure: Boolean, sensitiveGate: Boolean) -> Unit = { _, _, _ -> }
    ) {
        clientPrefsPullJob?.cancel()
        clientPrefsPullJob = scope.launch {
            val session = securityCoordinator.currentSession() ?: return@launch
            securityCoordinator.pull(session).onSuccess { remote ->
                if (!settingsRepository.isCurrent(session)) return@onSuccess
                val lockTimeout = when (remote.appLockTimeoutMinutes) {
                    1L, 2L, 5L, 10L, 15L, 30L, 60L, 120L, 240L, 360L -> remote.appLockTimeoutMinutes
                    else -> 5L
                }
                onApplied(lockTimeout, remote.screenSecureEnabled, remote.sensitiveGateEnabled)
            }
        }
    }
}
