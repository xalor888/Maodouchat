package com.maodouchat.ai

import android.content.Context
import com.maodouchat.util.RuntimeFlags

/**
 * AI 入口信息架构（纯函数）。
 * 聊天内一主入口（输入栏 ✨）+ 长按消息场景动作；设置总开关在 AI 隐私页。
 */
object AiEntryPolicy {
    /** 主入口位置：输入栏旁 AutoAwesome */
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
     * 长按消息可用的 AI 动作（不含复制结果等非 AI 项）。
     * 总开关关闭时仍返回列表，由 UI 决定灰显或提示。
     */
    fun contextActionsFor(
        messageType: String?,
        hasTranscript: Boolean = false
    ): List<MessageAiAction> {
        return when (messageType?.uppercase()) {
            "TEXT", "MARKDOWN" -> listOf(MessageAiAction.TRANSLATE)
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

    /**
     * 主入口是否应呈现为可用（本地会话开关）。
     * 全局服务端开关由设置页单独控制，不在此折叠。
     */
    fun isComposerEntryActive(chatAiEnabled: Boolean): Boolean = chatAiEnabled

    /**
     * 主入口可用性（服务端 AI 总开关 + 本地会话开关）。
     * 服务端 aiEnabled=false（RuntimeFlags.AI_MASTER）时整体折叠入口。
     */
    fun isComposerEntryActive(context: Context, chatAiEnabled: Boolean): Boolean =
        RuntimeFlags.isEnabled(context, RuntimeFlags.AI_MASTER) && chatAiEnabled

    /** 场景动作是否允许触发（会话 AI 开 + 非进行中） */
    fun canRunContextAction(
        chatAiEnabled: Boolean,
        isBusy: Boolean
    ): Boolean = chatAiEnabled && !isBusy

    /** 场景动作是否允许触发（服务端 AI 总开关 + 会话 AI 开 + 非进行中） */
    fun canRunContextAction(
        context: Context,
        chatAiEnabled: Boolean,
        isBusy: Boolean
    ): Boolean = RuntimeFlags.isEnabled(context, RuntimeFlags.AI_MASTER) && chatAiEnabled && !isBusy
}
