package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.MessageType

internal fun isMessageGestureEligible(type: MessageType): Boolean = type !in setOf(
    MessageType.NUDGE,
    MessageType.SK_DIST,
    MessageType.SYSTEM,
    MessageType.REVOKED
)

/** Forward/share eligibility. Secret chats block forward when runtime forward-block is on. */
internal fun isMessageForwardable(
    type: MessageType,
    isSecretChat: Boolean = false,
    forwardBlockEnabled: Boolean = true
): Boolean {
    val secretBlocksForward = isSecretChat && forwardBlockEnabled
    return !secretBlocksForward && isMessageGestureEligible(type)
}

/** Copy text out of secret chats is blocked to reduce plain-text exfiltration. */
internal fun isMessageCopyable(
    type: MessageType,
    isSecretChat: Boolean = false,
    copyBlockEnabled: Boolean = true
): Boolean {
    val secretBlocksCopy = isSecretChat && copyBlockEnabled
    return !secretBlocksCopy && type in setOf(MessageType.TEXT, MessageType.MARKDOWN)
}

/** 可回复的消息类型：与左滑回复手势一致。 */
internal fun isMessageReplyable(type: MessageType): Boolean = isMessageGestureEligible(type)

internal fun shouldTriggerSwipeReply(offsetPx: Float, thresholdPx: Float): Boolean =
    thresholdPx > 0f && offsetPx <= -thresholdPx

internal fun toggleMessageSelection(selectedIds: Set<String>, messageId: String): Set<String> =
    if (messageId in selectedIds) selectedIds - messageId else selectedIds + messageId

internal const val DEFAULT_QUICK_REACTION = "❤️"
