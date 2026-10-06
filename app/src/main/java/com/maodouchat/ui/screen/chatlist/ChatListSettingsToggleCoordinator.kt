package com.maodouchat.ui.screen.chatlist

import com.maodouchat.R
import com.maodouchat.data.model.Chat
import com.maodouchat.network.UpdateChatSettingsRequest
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** 置顶/静音/归档/标未读编排（ChatList 瘦身：自 ChatListViewModel 抽出）。 */
internal class ChatListSettingsToggleCoordinator(
    private val uiState: MutableStateFlow<ChatListUiState>,
    private val isFlagEnabled: (RuntimeFlags.Flag) -> Boolean,
    private val text: (Int) -> String,
    private val updateChatSettings: (chat: Chat, optimistic: Chat, request: UpdateChatSettingsRequest) -> Unit,
) {
    // 9.150：按 chatId 现查 _uiState 最新快照取反，不再信任调用方传入的
    // Chat 快照（长按菜单 menuChat 可能在 WS 刷新后陈旧，反向操作会覆盖新值）
    fun togglePinned(chatId: String) =
        toggleSetting(chatId, RuntimeFlags.CHAT_PIN, ChatSettingsToggle.PIN)

    fun toggleNotificationsMuted(chatId: String) =
        toggleSetting(chatId, RuntimeFlags.CHAT_MUTE, ChatSettingsToggle.MUTE)

    fun toggleArchived(chatId: String) =
        toggleSetting(chatId, RuntimeFlags.CHAT_ARCHIVE, ChatSettingsToggle.ARCHIVE)

    fun toggleMarkedUnread(chatId: String) =
        toggleSetting(chatId, RuntimeFlags.MARKED_UNREAD, ChatSettingsToggle.MARKED_UNREAD)

    private fun toggleSetting(chatId: String, flagKey: RuntimeFlags.Flag, toggle: ChatSettingsToggle) {
        if (!isFlagEnabled(flagKey)) {
            uiState.update { it.copy(errorMessage = text(R.string.feature_disabled_by_admin)) }
            return
        }
        val chat = uiState.value.chats.firstOrNull { it.id == chatId } ?: return
        val mutation = buildSettingsToggle(chat, toggle)
        updateChatSettings(chat, mutation.optimistic, mutation.request)
    }
}
