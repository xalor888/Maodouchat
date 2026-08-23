package com.maodouchat.ai

import android.content.Context
import com.maodouchat.util.RuntimeFlags

/**
 * AI 入口信息架构（纯函数）。
 * 聊天内一主入口（附件菜单 ✨）+ 长按消息场景动作；设置总开关在 AI 隐私页。
 * 输入栏主行不再放 AI 星。
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
     * 长按消息可用的 AI 动作（不含复制结果等非 AI 项）。
     * 总开关关闭时仍返回列表，由 UI 决定灰显或提示——不要在这里把入口抹掉，
     * 否则用户会感觉「点不了」。
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

    /**
     * 主入口是否应高亮为「当前会话 AI 已开」。
     * 关闭时入口仍必须可点（由 UI toast / 同意框解释），不要把按钮 disable 掉。
     */
    fun isComposerEntryActive(chatAiEnabled: Boolean): Boolean = chatAiEnabled

    /**
     * 主入口高亮（服务端 AI 总开关 + 本地会话开关）。
     * 总开关关闭时只取消高亮，不折叠按钮本身。
     */
    fun isComposerEntryActive(context: Context, chatAiEnabled: Boolean): Boolean =
        RuntimeFlags.isEnabled(context, RuntimeFlags.AI_MASTER) && chatAiEnabled

    /**
     * 输入栏 ✨ 是否允许打开菜单。进行中才挡住；开关关闭仍可打开，
     * 以便 UI 给出「已关闭」提示，而不是死按钮。
     */
    fun canOpenComposerMenu(isBusy: Boolean, isUpdatingSetting: Boolean = false): Boolean =
        !isBusy && !isUpdatingSetting

    /**
     * 场景动作是否允许点击。进行中才挡住点击；会话 AI 关闭时仍返回 true，
     * 由调用方 toast（ChatDetail 已有 `chat_ai_disabled_short`）。
     */
    fun canRunContextAction(
        chatAiEnabled: Boolean,
        isBusy: Boolean
    ): Boolean = canRunContextAction(masterEnabled = true, chatAiEnabled = chatAiEnabled, isBusy = isBusy)

    /** 场景动作是否允许点击（服务端总开关仅影响高亮，不吞掉点击）。 */
    fun canRunContextAction(
        context: Context,
        chatAiEnabled: Boolean,
        isBusy: Boolean
    ): Boolean = canRunContextAction(
        masterEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.AI_MASTER),
        chatAiEnabled = chatAiEnabled,
        isBusy = isBusy
    )

    @Suppress("UNUSED_PARAMETER")
    fun canRunContextAction(
        masterEnabled: Boolean,
        chatAiEnabled: Boolean,
        isBusy: Boolean
    ): Boolean {
        // 开关只决定高亮与 toast，不吞掉点击。进行中才挡住。
        return !isBusy
    }
}
