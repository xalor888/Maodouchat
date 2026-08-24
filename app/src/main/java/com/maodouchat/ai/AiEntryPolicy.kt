package com.maodouchat.ai

import android.content.Context
import com.maodouchat.util.RuntimeFlags

/**
 * AI 入口信息架构（纯函数）。
 * 聊天内一主入口（附件菜单 ✨）+ 长按消息场景动作；设置总开关在 AI 隐私页。
 * 输入栏主行不再放 AI 星。未在设置打开时不画聊天/搜索 AI 图标。
 */
object AiEntryPolicy {
    /** 主入口位置：附件菜单里的 AutoAwesome */
    const val PRIMARY_SURFACE = "composer_menu"

    /** 长按消息场景入口 */
    const val CONTEXT_SURFACE = "message_actions"

    /** 设置总开关所在 */
    const val SETTINGS_SURFACE = "ai_privacy"

    enum class MessageAiAction {
        TRANSLATE,
        TRANSCRIBE,
        ANALYZE_IMAGE,
        ANALYZE_FILE
    }

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

    /**
     * 长按消息可用的 AI 动作（不含复制结果等非 AI 项）。
     * 调用方在 [shouldShowAiSurfaces] 为 false 时不要渲染这一段。
     */
    fun contextActionsFor(
        messageType: String?,
        hasTranscript: Boolean = false
    ): List<MessageAiAction> {
        return when (messageType?.trim()?.uppercase()) {
            "TEXT", "MARKDOWN", "SYSTEM" -> listOf(MessageAiAction.TRANSLATE)
            "VOICE" -> if (hasTranscript) emptyList() else listOf(MessageAiAction.TRANSCRIBE)
            "IMAGE", "GIF" -> listOf(MessageAiAction.ANALYZE_IMAGE)
            "FILE" -> listOf(MessageAiAction.ANALYZE_FILE)
            else -> emptyList()
        }
    }

    fun hasContextAiActions(
        messageType: String?,
        hasTranscript: Boolean = false
    ): Boolean = contextActionsFor(messageType, hasTranscript).isNotEmpty()

    /** 附件菜单 AI 项：只有已开启时才出现。 */
    fun isComposerEntryActive(chatAiEnabled: Boolean): Boolean = chatAiEnabled

    fun isComposerEntryActive(context: Context, chatAiEnabled: Boolean): Boolean =
        shouldShowAiSurfaces(
            chatAiEnabled = chatAiEnabled,
            consentAccepted = AiPrivacyPreferences.consentAccepted(context),
            userEnabled = AiPrivacyPreferences.userEnabled(context),
            masterEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.AI_MASTER)
        )

    fun canOpenComposerMenu(isBusy: Boolean, isUpdatingSetting: Boolean = false): Boolean =
        !isBusy && !isUpdatingSetting

    fun canRunContextAction(
        chatAiEnabled: Boolean,
        isBusy: Boolean
    ): Boolean = canRunContextAction(masterEnabled = true, chatAiEnabled = chatAiEnabled, isBusy = isBusy)

    fun canRunContextAction(
        context: Context,
        chatAiEnabled: Boolean,
        isBusy: Boolean
    ): Boolean = canRunContextAction(
        masterEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.AI_MASTER),
        chatAiEnabled = chatAiEnabled,
        isBusy = isBusy
    )

    fun canRunContextAction(
        masterEnabled: Boolean,
        chatAiEnabled: Boolean,
        isBusy: Boolean
    ): Boolean = masterEnabled && chatAiEnabled && !isBusy
}
