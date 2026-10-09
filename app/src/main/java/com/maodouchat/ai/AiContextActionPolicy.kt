package com.maodouchat.ai

import android.content.Context
import com.maodouchat.util.RuntimeFlags

/** 上下文动作簇：长按消息可用的 AI 动作与执行门闩。 */
object AiContextActionPolicy {

    enum class MessageAiAction {
        TRANSLATE,
        TRANSCRIBE,
        ANALYZE_IMAGE,
        ANALYZE_FILE
    }

    /**
     * 长按消息可用的 AI 动作（不含复制结果等非 AI 项）。
     * 调用方在入口不可见时不要渲染这一段。
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
