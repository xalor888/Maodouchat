package com.maodouchat.ai

import android.content.Context

// AI 隐私偏好门面：账号隔离的本地 AI 同意与端侧安全偏好。实现已按簇拆到
// AiPrivacyKeys（键名）、AiPrivacyAccountScope（账号隔离/迁移）、
// AiPrivacyConsentReads（读取）、AiPrivacyConsentWrites（写入），这里只保留统一入口，全部透传，行为不变。
object AiPrivacyPreferences {
    const val KEY_CONSENT = AiPrivacyKeys.CONSENT
    const val KEY_USER_ENABLED = AiPrivacyKeys.USER_ENABLED
    const val KEY_LOCAL_SAFETY = AiPrivacyKeys.LOCAL_SAFETY
    const val KEY_DISMISSED_SAFETY_IDS = AiPrivacyKeys.DISMISSED_SAFETY_IDS
    const val KEY_AUTO_TRANSLATE = AiPrivacyKeys.AUTO_TRANSLATE

    internal fun scopedKey(base: String, userId: String): String =
        AiPrivacyKeys.scopedKey(base, userId)

    fun consentAccepted(context: Context): Boolean =
        AiPrivacyConsentReads.consentAccepted(context)

    fun userEnabled(context: Context): Boolean =
        AiPrivacyConsentReads.userEnabled(context)

    fun mayUploadCloudContext(context: Context): Boolean =
        AiPrivacyConsentReads.mayUploadCloudContext(context)

    fun localSafetyEnabled(context: Context): Boolean =
        AiPrivacyConsentReads.localSafetyEnabled(context)

    fun dismissedSafetyMessageIds(context: Context): Set<String> =
        AiPrivacyConsentReads.dismissedSafetyMessageIds(context)

    fun autoTranslateIncoming(context: Context): Boolean =
        AiPrivacyConsentReads.autoTranslateIncoming(context)

    fun setConsentAccepted(context: Context, accepted: Boolean) =
        AiPrivacyConsentWrites.setConsentAccepted(context, accepted)

    fun setUserEnabled(context: Context, enabled: Boolean) =
        AiPrivacyConsentWrites.setUserEnabled(context, enabled)

    fun setLocalSafetyEnabled(context: Context, enabled: Boolean) =
        AiPrivacyConsentWrites.setLocalSafetyEnabled(context, enabled)

    fun setAutoTranslateIncoming(context: Context, enabled: Boolean) =
        AiPrivacyConsentWrites.setAutoTranslateIncoming(context, enabled)

    fun setDismissedSafetyMessageIds(context: Context, messageIds: Set<String>) =
        AiPrivacyConsentWrites.setDismissedSafetyMessageIds(context, messageIds)

    fun enableAllDefaults(context: Context) =
        AiPrivacyConsentWrites.enableAllDefaults(context)

    fun revoke(context: Context) =
        AiPrivacyConsentWrites.revoke(context)
}
