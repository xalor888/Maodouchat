package com.maodouchat.ai

import android.content.Context

// AI 隐私同意读取簇：开关/同意/免打扰等读取，外加云端上下文上传门闩。
internal object AiPrivacyConsentReads {

    fun consentAccepted(context: Context): Boolean =
        AiPrivacyAccountScope.account(context)
            ?.let { it.prefs.getBoolean(AiPrivacyKeys.scopedKey(AiPrivacyKeys.CONSENT, it.userId), false) }
            ?: false

    /** 设置里的用户总开关；缺省 false，须用户打开后聊天/搜索才画 AI 图标。 */
    fun userEnabled(context: Context): Boolean =
        AiPrivacyAccountScope.account(context)
            ?.let { it.prefs.getBoolean(AiPrivacyKeys.scopedKey(AiPrivacyKeys.USER_ENABLED, it.userId), false) }
            ?: false

    /**
     * 本机模型上下文门闩：无登录账号、未明确同意或已撤销时一律 false——后台 OCR / 画像 /
     * 情绪回复 / 周报不得把消息正文发给用户配置的模型；聊天明文也不经毛豆 /api/ai。
     */
    fun mayUploadCloudContext(context: Context): Boolean =
        userEnabled(context) && consentAccepted(context)

    fun localSafetyEnabled(context: Context): Boolean =
        AiPrivacyAccountScope.account(context)
            ?.let { it.prefs.getBoolean(AiPrivacyKeys.scopedKey(AiPrivacyKeys.LOCAL_SAFETY, it.userId), false) }
            ?: false

    fun dismissedSafetyMessageIds(context: Context): Set<String> =
        AiPrivacyAccountScope.account(context)?.let {
            it.prefs.getStringSet(
                AiPrivacyKeys.scopedKey(AiPrivacyKeys.DISMISSED_SAFETY_IDS, it.userId), emptySet()
            )?.toSet().orEmpty()
        }.orEmpty()

    fun autoTranslateIncoming(context: Context): Boolean =
        AiPrivacyAccountScope.account(context)
            ?.let { it.prefs.getBoolean(AiPrivacyKeys.scopedKey(AiPrivacyKeys.AUTO_TRANSLATE, it.userId), false) }
            ?: false
}
