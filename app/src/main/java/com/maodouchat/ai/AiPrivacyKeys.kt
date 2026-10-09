package com.maodouchat.ai

// AI 隐私偏好键簇：SharedPreferences 键名与账号隔离后缀规则。
internal object AiPrivacyKeys {
    internal const val PREFS_NAME = "ai_settings"
    internal const val CONSENT = "ai_consent_accepted"
    internal const val USER_ENABLED = "ai_user_enabled"
    internal const val LOCAL_SAFETY = "ai_local_safety_enabled"
    internal const val DISMISSED_SAFETY_IDS = "ai_local_safety_dismissed_ids"
    internal const val AUTO_TRANSLATE = "ai_auto_translate_incoming"
    internal const val MIGRATED = "account_scope_migrated"

    internal fun scopedKey(base: String, userId: String): String = "$base:$userId"
}
