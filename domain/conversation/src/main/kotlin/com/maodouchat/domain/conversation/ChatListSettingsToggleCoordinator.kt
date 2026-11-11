package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatSettingsToggle
import com.maodouchat.core.model.UpdateChatSettingsRequest
import com.maodouchat.core.model.buildSettingsToggle

/** 置顶/静音/归档/标未读编排（ChatList 瘦身：自 ChatListViewModel 抽出）。 */
class ChatListSettingsToggleCoordinator(
    private val getChats: () -> List<Chat>,
    private val showError: (String) -> Unit,
    private val pinEnabled: () -> Boolean,
    private val muteEnabled: () -> Boolean,
    private val archiveEnabled: () -> Boolean,
    private val markedUnreadEnabled: () -> Boolean,
    private val featureDisabledText: () -> String,
    private val updateChatSettings: (chat: Chat, optimistic: Chat, request: UpdateChatSettingsRequest) -> Unit,
) {
    // 9.150：按 chatId 现查最新快照取反，不再信任调用方传入的
    // Chat 快照（长按菜单 menuChat 可能在 WS 刷新后陈旧，反向操作会覆盖新值）
    fun togglePinned(chatId: String) =
        toggleSetting(chatId, ChatSettingsToggle.PIN, pinEnabled)

    fun toggleNotificationsMuted(chatId: String) =
        toggleSetting(chatId, ChatSettingsToggle.MUTE, muteEnabled)

    fun toggleArchived(chatId: String) =
        toggleSetting(chatId, ChatSettingsToggle.ARCHIVE, archiveEnabled)

    fun toggleMarkedUnread(chatId: String) =
        toggleSetting(chatId, ChatSettingsToggle.MARKED_UNREAD, markedUnreadEnabled)

    private fun toggleSetting(chatId: String, toggle: ChatSettingsToggle, enabled: () -> Boolean) {
        if (!enabled()) {
            showError(featureDisabledText())
            return
        }
        val chat = getChats().firstOrNull { it.id == chatId } ?: return
        val mutation = buildSettingsToggle(chat, toggle)
        updateChatSettings(chat, mutation.optimistic, mutation.request)
    }
}
