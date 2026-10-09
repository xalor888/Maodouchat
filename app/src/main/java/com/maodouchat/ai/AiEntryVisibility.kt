package com.maodouchat.ai

import android.content.Context
import com.maodouchat.util.RuntimeFlags

/** AI 入口可见性簇：入口位置常量与「该不该画入口」的纯决策。 */
object AiEntryVisibility {

    /** 主入口位置：附件菜单里的 AutoAwesome */
    const val PRIMARY_SURFACE = "composer_menu"

    /** 长按消息场景入口 */
    const val CONTEXT_SURFACE = "message_actions"

    /** 设置总开关所在 */
    const val SETTINGS_SURFACE = "ai_privacy"

    enum class ComposerSection {
        DRAFT,
        CHAT,
        SETTINGS
    }

    /** 主菜单分区顺序（视觉统一） */
    val COMPOSER_SECTION_ORDER = listOf(
        ComposerSection.DRAFT,
        ComposerSection.CHAT,
        ComposerSection.SETTINGS
    )

    /**
     * 聊天/搜索里要不要画 AI 入口。默认关；须设置里打开本机授权，且当前会话 AI 有效。
     */
    fun shouldShowAiSurfaces(
        chatAiEnabled: Boolean,
        consentAccepted: Boolean,
        userEnabled: Boolean = false,
        masterEnabled: Boolean = true
    ): Boolean = masterEnabled && userEnabled && consentAccepted && chatAiEnabled

    /** 设置助手入口、全局搜索 AI 模式：未在设置打开则不画。 */
    fun shouldShowGlobalAiEntry(context: Context): Boolean =
        RuntimeFlags.isEnabled(context, RuntimeFlags.AI_MASTER) &&
            AiPrivacyPreferences.userEnabled(context) &&
            AiPrivacyPreferences.consentAccepted(context)

    /** 附件菜单 AI 项：只有已开启时才出现。 */
    fun isComposerEntryActive(chatAiEnabled: Boolean): Boolean = chatAiEnabled

    fun isComposerEntryActive(context: Context, chatAiEnabled: Boolean): Boolean =
        shouldShowAiSurfaces(
            chatAiEnabled = chatAiEnabled,
            consentAccepted = AiPrivacyPreferences.consentAccepted(context),
            userEnabled = AiPrivacyPreferences.userEnabled(context),
            masterEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.AI_MASTER)
        )
}
