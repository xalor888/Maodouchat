package com.maodouchat.ui.screen.chatlist

import com.maodouchat.data.model.Chat
import com.maodouchat.util.ChatFolderPolicy

/**
 * 会话列表多选纯状态机（U05 瘦身第 1 切片）。
 *
 * 自 2200+ 行 `ChatListViewModel` 抽出的第一个纯领域状态：进入/退出/勾选语义与
 * 批量已读目标计算。ViewModel 只做 `_uiState.update { reduceSelection(...) }`
 * 与副作用编排（出库/网络/通知），不再手写集合运算。
 *
 * 语义与旧实现逐字对齐：
 * - enter 仅开模式、保留已有勾选；exit 关模式并清空；
 * - toggle 增删一个 id；勾选集变空时自动退出模式。
 */
data class ChatListSelection(
    val selectionMode: Boolean = false,
    val selectedChatIds: Set<String> = emptySet(),
)

sealed interface ChatListSelectionEvent {
    data object Enter : ChatListSelectionEvent
    data object Exit : ChatListSelectionEvent
    data class Toggle(val chatId: String) : ChatListSelectionEvent
}

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

/**
 * 批量标已读目标计算（纯函数）：选中 id → 本地仍有未读的会话，
 * 按密聊/普通拆分（密聊不走出库已读回执，只清本地投影）。
 * `excludeArchived = true` 时跳过已归档会话（未读文件夹「全部已读」用）。
 */
data class UnreadBatchTargets(
    val ordinary: List<Chat> = emptyList(),
    val secret: List<Chat> = emptyList(),
)

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
