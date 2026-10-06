package com.maodouchat.ui.screen.chatlist

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 会话列表多选模式（ChatList 瘦身：进入/退出/勾选/批量置顶/批量删除）。
 *
 * ViewModel 只做薄转发；状态机纯函数见 [reduceSelection]。
 */
internal class ChatListSelectionCoordinator(
    private val uiState: MutableStateFlow<ChatListUiState>,
    private val togglePinned: (chatId: String) -> Unit,
    private val deleteChat: (chatId: String) -> Unit,
) {
    /** 1.368：进入会话列表多选模式（长按任一会话） */
    fun enterSelectionMode() {
        uiState.update { state ->
            val next = reduceSelection(
                ChatListSelection(state.selectionMode, state.selectedChatIds),
                ChatListSelectionEvent.Enter,
            )
            state.copy(selectionMode = next.selectionMode, selectedChatIds = next.selectedChatIds)
        }
    }

    /** 1.368：退出多选模式并清空勾选 */
    fun exitSelectionMode() {
        uiState.update { state ->
            val next = reduceSelection(
                ChatListSelection(state.selectionMode, state.selectedChatIds),
                ChatListSelectionEvent.Exit,
            )
            state.copy(selectionMode = next.selectionMode, selectedChatIds = next.selectedChatIds)
        }
    }

    /** 1.368：勾选/取消勾选一个会话（最后一个取消时自动退出多选） */
    fun toggleSelectChat(chatId: String) {
        uiState.update { state ->
            val next = reduceSelection(
                ChatListSelection(state.selectionMode, state.selectedChatIds),
                ChatListSelectionEvent.Toggle(chatId),
            )
            state.copy(selectionMode = next.selectionMode, selectedChatIds = next.selectedChatIds)
        }
    }

    /** 1.368：批量置顶/取消置顶选中会话（复用单会话置顶，含 RuntimeFlags 门控） */
    fun batchTogglePinSelected() {
        val selected = uiState.value.selectedChatIds
        if (selected.isEmpty()) return
        selected.forEach(togglePinned)
    }

    /** 1.368：批量删除选中会话（逐个走 deleteChat，结束后退出多选） */
    fun batchDeleteSelected() {
        val selected = uiState.value.selectedChatIds
        if (selected.isEmpty()) return
        selected.forEach(deleteChat)
        exitSelectionMode()
    }
}
