package com.maodouchat.domain.messaging

import com.maodouchat.domain.messaging.MessageAiAction

// 长按消息可用的 AI 动作与执行门闩：纯核，自 app 侧 AiContextActionPolicy 收敛。
object MessageAiActionPolicy {

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
        masterEnabled: Boolean,
        chatAiEnabled: Boolean,
        isBusy: Boolean
    ): Boolean = masterEnabled && chatAiEnabled && !isBusy
}
