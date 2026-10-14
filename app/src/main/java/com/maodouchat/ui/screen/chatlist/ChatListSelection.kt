package com.maodouchat.ui.screen.chatlist

import com.maodouchat.data.model.Chat
import com.maodouchat.util.ChatFolderPolicy

fun reduceSelection(
    state: ChatListSelection,
    event: ChatListSelectionEvent,
): ChatListSelection = when (event) {
    ChatListSelectionEvent.Enter -> state.copy(selectionMode = true)
    ChatListSelectionEvent.Exit -> state.copy(selectionMode = false, selectedChatIds = emptySet())
    is ChatListSelectionEvent.Toggle -> {
        val next = if (event.chatId in state.selectedChatIds) {
            state.selectedChatIds - event.chatId
        } else {
            state.selectedChatIds + event.chatId
        }
        if (next.isEmpty()) {
            state.copy(selectionMode = false, selectedChatIds = emptySet())
        } else {
            // 与旧实现一致：非空时只换集合、不碰 mode（UI 恒先 enter 再 toggle）。
            state.copy(selectedChatIds = next)
        }
    }
}

fun selectUnreadBatchTargets(
    chats: List<Chat>,
    selectedIds: Set<String>,
    excludeArchived: Boolean = false,
): UnreadBatchTargets {
    if (selectedIds.isEmpty()) return UnreadBatchTargets()
    val chatsById = chats.associateBy { it.id }
    val toRead = selectedIds.mapNotNull { chatsById[it] }
        .filter { (!excludeArchived || !it.archived) && ChatFolderPolicy.isUnreadChat(it.unreadCount, it.markedUnread) }
    return UnreadBatchTargets(
        ordinary = toRead.filter { !it.isSecret },
        secret = toRead.filter { it.isSecret },
    )
}
