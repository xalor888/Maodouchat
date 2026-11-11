package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatListSelection
import com.maodouchat.core.model.ChatListSelectionEvent
import com.maodouchat.core.model.reduceSelection

/**
 * 会话列表多选模式（ChatList 瘦身：进入/退出/勾选/批量置顶/批量删除）。
 *
 * ViewModel 只做薄转发；状态机纯函数见 [reduceSelection]。
 * 多选状态经端口读写，`ChatListUiState` 仍在 app 侧。
 */
class ChatListSelectionCoordinator(
    private val getSelection: () -> ChatListSelection,
    private val updateSelection: (ChatListSelection) -> Unit,
    private val togglePinned: (chatId: String) -> Unit,
    private val deleteChat: (chatId: String) -> Unit,
) {
    /** 1.368：进入会话列表多选模式（长按任一会话） */
    fun enterSelectionMode() {
        updateSelection(reduceSelection(getSelection(), ChatListSelectionEvent.Enter))
    }

    /** 1.368：退出多选模式并清空勾选 */
    fun exitSelectionMode() {
        updateSelection(reduceSelection(getSelection(), ChatListSelectionEvent.Exit))
    }

    /** 1.368：勾选/取消勾选一个会话（最后一个取消时自动退出多选） */
    fun toggleSelectChat(chatId: String) {
        updateSelection(reduceSelection(getSelection(), ChatListSelectionEvent.Toggle(chatId)))
    }

    /** 1.368：批量置顶/取消置顶选中会话（复用单会话置顶，含 RuntimeFlags 门控） */
    fun batchTogglePinSelected() {
        val selected = getSelection().selectedChatIds
        if (selected.isEmpty()) return
        selected.forEach(togglePinned)
    }

    /** 1.368：批量删除选中会话（逐个走 deleteChat，结束后退出多选） */
    fun batchDeleteSelected() {
        val selected = getSelection().selectedChatIds
        if (selected.isEmpty()) return
        selected.forEach(deleteChat)
        exitSelectionMode()
    }
}
