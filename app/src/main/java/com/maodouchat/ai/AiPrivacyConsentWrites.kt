package com.maodouchat.ai

import android.content.Context
import androidx.core.content.edit

// AI 隐私同意写入簇：开关写入、一键开启默认配置、撤销。
internal object AiPrivacyConsentWrites {

    fun setConsentAccepted(context: Context, accepted: Boolean) =
        putBoolean(context, AiPrivacyKeys.CONSENT, accepted)

    fun setUserEnabled(context: Context, enabled: Boolean) =
        putBoolean(context, AiPrivacyKeys.USER_ENABLED, enabled)

    fun setLocalSafetyEnabled(context: Context, enabled: Boolean) =
        putBoolean(context, AiPrivacyKeys.LOCAL_SAFETY, enabled)

    fun setAutoTranslateIncoming(context: Context, enabled: Boolean) =
        putBoolean(context, AiPrivacyKeys.AUTO_TRANSLATE, enabled)

    fun setDismissedSafetyMessageIds(context: Context, messageIds: Set<String>) {
        val account = AiPrivacyAccountScope.account(context) ?: return
        account.prefs.edit {
            putStringSet(AiPrivacyKeys.scopedKey(AiPrivacyKeys.DISMISSED_SAFETY_IDS, account.userId), messageIds.toSet())
        }
    }

    /** 一键开启 AI 核心能力与隐私授权：用户总开关、本机处理授权、自动翻译、端侧安全提醒与图片 OCR。 */
    fun enableAllDefaults(context: Context) {
        val account = AiPrivacyAccountScope.account(context) ?: return
        account.prefs.edit {
            putBoolean(AiPrivacyKeys.scopedKey(AiPrivacyKeys.CONSENT, account.userId), true)
            putBoolean(AiPrivacyKeys.scopedKey(AiPrivacyKeys.USER_ENABLED, account.userId), true)
            putBoolean(AiPrivacyKeys.scopedKey(AiPrivacyKeys.LOCAL_SAFETY, account.userId), true)
            putBoolean(AiPrivacyKeys.scopedKey(AiPrivacyKeys.AUTO_TRANSLATE, account.userId), true)
        }
        ImageOcrPreferences.setEnabled(context, true)
    }

    fun revoke(context: Context) {
        val account = AiPrivacyAccountScope.account(context) ?: return
        account.prefs.edit {
            putBoolean(AiPrivacyKeys.scopedKey(AiPrivacyKeys.CONSENT, account.userId), false)
            putBoolean(AiPrivacyKeys.scopedKey(AiPrivacyKeys.USER_ENABLED, account.userId), false)
            putBoolean(AiPrivacyKeys.scopedKey(AiPrivacyKeys.LOCAL_SAFETY, account.userId), false)
            remove(AiPrivacyKeys.scopedKey(AiPrivacyKeys.DISMISSED_SAFETY_IDS, account.userId))
        }
    }

    private fun putBoolean(context: Context, key: String, value: Boolean) {
        val account = AiPrivacyAccountScope.account(context) ?: return
        account.prefs.edit { putBoolean(AiPrivacyKeys.scopedKey(key, account.userId), value) }
    }
}
