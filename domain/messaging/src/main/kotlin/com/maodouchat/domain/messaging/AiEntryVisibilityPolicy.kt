package com.maodouchat.domain.messaging

// AI 入口可见性纯判定，自 app 侧 AiEntryVisibility 收敛；
// 设置读取（RuntimeFlags / AiPrivacyPreferences）仍留在 app 侧。
object AiEntryVisibilityPolicy {

    fun shouldShowAiSurfaces(
        chatAiEnabled: Boolean,
        consentAccepted: Boolean,
        userEnabled: Boolean = false,
        masterEnabled: Boolean = true
    ): Boolean = masterEnabled && userEnabled && consentAccepted && chatAiEnabled

    fun isComposerEntryActive(chatAiEnabled: Boolean): Boolean = chatAiEnabled
}
