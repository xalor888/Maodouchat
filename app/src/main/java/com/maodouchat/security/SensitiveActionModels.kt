package com.maodouchat.security

enum class SensitiveAction {
    LOGOUT,
    DELETE_ACCOUNT,
    EXPORT_CHAT,
    CLEAR_CHAT_HISTORY,
    DISABLE_APP_LOCK,
    // 9.140：关闭 2FA 同样是破坏性安全操作（移除强认证因子），纳入 step-up 门
    DISABLE_TOTP,
}
