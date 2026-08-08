package com.maodouchat.security

/**
 * 敏感操作二次验证策略（纯函数）。
 * 当 App 锁开启且「敏感操作需验证」开启时，注销/导出/删号等需先过系统生物识别或设备凭据。
 */
enum class SensitiveAction {
    LOGOUT,
    DELETE_ACCOUNT,
    EXPORT_CHAT,
    CLEAR_CHAT_HISTORY,
    DISABLE_APP_LOCK,
}

object SensitiveActionPolicy {
    fun requiresStepUp(
        appLockEnabled: Boolean,
        sensitiveGateEnabled: Boolean,
        action: SensitiveAction
    ): Boolean {
        if (!appLockEnabled || !sensitiveGateEnabled) return false
        return when (action) {
            SensitiveAction.LOGOUT,
            SensitiveAction.DELETE_ACCOUNT,
            SensitiveAction.EXPORT_CHAT,
            SensitiveAction.CLEAR_CHAT_HISTORY,
            SensitiveAction.DISABLE_APP_LOCK -> true
        }
    }
}
