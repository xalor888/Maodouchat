package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.truncatedSenderId

fun senderDisplayName(
    state: ChatDetailUiState,
    message: Message,
    isOwn: Boolean,
    participantNamesById: Map<String, String>,
    unknownLabel: String = "",
    groupMemberLabel: String = "",
): String? {
    if (isOwn) return null
    val mapped = participantNamesById[message.senderId]?.trim()?.takeIf { it.isNotEmpty() }
    val truncatedId = truncatedSenderId(message.senderId)
    if (!state.chatIsGroup) {
        return firstNonBlank(
            state.contact.displayName,
            mapped,
            truncatedId,
            unknownLabel,
        )
    }
    return firstNonBlank(
        mapped,
        truncatedId,
        groupMemberLabel,
        unknownLabel,
    )
}

// Route 里内联的发信人名字解析只是 senderDisplayName 的薄封装：把隐式捕获的
// state/labels 显式化成参数，调用点传参语义逐字等价。
fun resolveChatDetailSenderName(
    message: Message,
    state: ChatDetailUiState,
    participantNamesById: Map<String, String>,
    unknownLabel: String,
    groupMemberLabel: String,
    isOwn: Boolean = message.senderId == state.currentUserId,
): String? = senderDisplayName(
    state = state,
    message = message,
    isOwn = isOwn,
    participantNamesById = participantNamesById,
    unknownLabel = unknownLabel,
    groupMemberLabel = groupMemberLabel,
)

private fun firstNonBlank(vararg values: String?): String? {
    for (value in values) {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isNotEmpty()) return trimmed
    }
    return null
}
