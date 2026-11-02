package com.maodouchat.core.model

// AI 隐私偏好键簇：SharedPreferences 键名与账号隔离后缀规则。
object AiPrivacyKeys {
    const val PREFS_NAME = "ai_settings"
    const val CONSENT = "ai_consent_accepted"
    const val USER_ENABLED = "ai_user_enabled"
    const val LOCAL_SAFETY = "ai_local_safety_enabled"
    const val DISMISSED_SAFETY_IDS = "ai_local_safety_dismissed_ids"
    const val AUTO_TRANSLATE = "ai_auto_translate_incoming"
    const val MIGRATED = "account_scope_migrated"

    fun scopedKey(base: String, userId: String): String = "$base:$userId"
}
