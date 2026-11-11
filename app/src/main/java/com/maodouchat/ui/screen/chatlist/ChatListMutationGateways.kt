package com.maodouchat.ui.screen.chatlist

import android.util.Log
import com.maodouchat.R
import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDto
import com.maodouchat.core.model.ChatSettingsResponse
import com.maodouchat.core.model.ConversationLocalCleanupSession
import com.maodouchat.core.model.UpdateChatSettingsRequest
import com.maodouchat.domain.conversation.ChatListMutationCoordinator
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.session.CurrentSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** ChatListViewModel 的会话列表写路径接线：列表状态、会话门禁、文案与日志由 app 侧提供。 */
internal fun createChatListMutationCoordinator(
    scope: CoroutineScope,
    uiState: MutableStateFlow<ChatListUiState>,
    deletedChatIds: MutableSet<String>,
    settingsInFlight: MutableSet<String>,
    ownerUserId: () -> String,
    cacheChats: suspend (List<Chat>) -> Unit,
    updateChatSettingsRemote: suspend (
        chatId: String,
        request: UpdateChatSettingsRequest,
    ) -> Result<ChatSettingsResponse>,
    deleteChatRemote: suspend (chatId: String) -> Result<Unit>,
    createChatRemote: suspend (
        peerIds: List<String>,
        isGroup: Boolean,
        groupName: String?,
        chatType: String?,
    ) -> Result<ChatDto>,
    touchSecretChat: suspend (chatId: String) -> Unit,
    cleanupLocalChat: suspend (chatId: String, session: ConversationLocalCleanupSession) -> Unit,
    cleanupSessionFor: (ownerUserId: String) -> ConversationLocalCleanupSession,
    onMuteApplied: (chatId: String) -> Unit,
    text: (Int) -> String,
    secretChatFeatureEnabled: () -> Boolean,
    secretChatDisabledMessage: () -> String,
    secretChatStartFailedMessage: () -> String,
): ChatListMutationCoordinator = ChatListMutationCoordinator(
    scope = scope,
    getChats = { uiState.value.chats },
    updateChats = { transform -> uiState.update { it.copy(chats = transform(it.chats)) } },
    showError = { message -> uiState.update { it.copy(errorMessage = message) } },
    isDeletingChat = { chatId -> uiState.value.deletingChatIds.contains(chatId) },
    addDeletingChat = { chatId -> uiState.update { it.copy(deletingChatIds = it.deletingChatIds + chatId) } },
    removeDeletingChat = { chatId -> uiState.update { it.copy(deletingChatIds = it.deletingChatIds - chatId) } },
    setCreatedSecretChatId = { id -> uiState.update { it.copy(createdSecretChatId = id) } },
    showOwnerTransferRequired = { chatId ->
        uiState.update { it.copy(ownerTransferRequiredChatId = chatId, errorMessage = null) }
    },
    deletedChatIds = deletedChatIds,
    settingsInFlight = settingsInFlight,
    ownerUserId = ownerUserId,
    hasSession = { CurrentSession.hasSession() },
    sessionMayContinue = { userId -> BackgroundSessionGate.mayContinue(expectedUserId = userId) },
    cacheChats = cacheChats,
    updateChatSettingsRemote = updateChatSettingsRemote,
    deleteChatRemote = deleteChatRemote,
    createChatRemote = createChatRemote,
    touchSecretChat = touchSecretChat,
    cleanupLocalChat = cleanupLocalChat,
    cleanupSessionFor = cleanupSessionFor,
    onMuteApplied = onMuteApplied,
    sessionExpiredText = { text(R.string.error_session_expired) },
    settingsSyncFailedText = { text(R.string.chat_settings_sync_failed) },
    chatLeaveFailedText = { text(R.string.chat_leave_failed) },
    secretChatFeatureEnabled = secretChatFeatureEnabled,
    secretChatDisabledMessage = secretChatDisabledMessage,
    secretChatStartFailedMessage = secretChatStartFailedMessage,
    logWarning = { message, error -> Log.w("ChatListMutation", message, error) },
)
