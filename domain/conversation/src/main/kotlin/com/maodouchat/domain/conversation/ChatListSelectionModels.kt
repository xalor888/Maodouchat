package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat

data class ChatListSelection(
    val selectionMode: Boolean = false,
    val selectedChatIds: Set<String> = emptySet(),
)

sealed interface ChatListSelectionEvent {
    data object Enter : ChatListSelectionEvent
    data object Exit : ChatListSelectionEvent
    data class Toggle(val chatId: String) : ChatListSelectionEvent
}

data class UnreadBatchTargets(
    val ordinary: List<Chat> = emptyList(),
    val secret: List<Chat> = emptyList(),
)
