package com.maodouchat.ui.screen.chatlist

import android.util.Log
import com.maodouchat.R
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.ScheduledMessage
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.domain.conversation.ChatDraft
import com.maodouchat.domain.conversation.ChatListLocalProjectionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** ChatListViewModel 的会话列表本地投影接线：列表状态、草稿映射、文案与日志由 app 侧提供。 */
internal fun createChatListLocalProjectionCoordinator(
    scope: CoroutineScope,
    uiState: MutableStateFlow<ChatListUiState>,
    ownerUserId: () -> String,
    observeDraftsForOwner: (ownerUserId: String) -> Flow<List<ChatDraftEntity>>,
    getRecentMessages: suspend (chatId: String, limit: Int) -> List<Message>,
    listLockedChatIds: suspend () -> Set<String>,
    searchChatIdsByMessageContent: suspend (escaped: String) -> List<String>,
    listSecretChatIds: suspend () -> Set<String>,
    trustChangedRemoteIds: suspend (ownerUserId: String, remoteIds: Set<String>) -> Set<String>,
    text: (Int) -> String,
    listScheduled: () -> List<ScheduledMessage> = { emptyList() },
): ChatListLocalProjectionCoordinator = ChatListLocalProjectionCoordinator(
    scope = scope,
    getChats = { uiState.value.chats },
    chatsFlow = uiState.map { it.chats },
    getIdentityChangedUserIds = { uiState.value.identityChangedUserIds },
    updateIdentityChangedUserIds = { ids -> uiState.update { it.copy(identityChangedUserIds = ids) } },
    updateLockedChatIds = { ids -> uiState.update { it.copy(lockedChatIds = ids) } },
    updateSecretChatIds = { ids -> uiState.update { it.copy(secretChatIds = ids) } },
    updateScheduledByChat = { counts -> uiState.update { it.copy(scheduledByChat = counts) } },
    updateSelectedTab = { tab -> uiState.update { it.copy(selectedTab = tab) } },
    updateShowArchived = { show -> uiState.update { it.copy(showArchived = show) } },
    updateErrorMessage = { message -> uiState.update { it.copy(errorMessage = message) } },
    updateOwnerTransferRequiredChatId = { id -> uiState.update { it.copy(ownerTransferRequiredChatId = id) } },
    getSearchQuery = { uiState.value.searchQuery },
    updateSearchQuery = { query -> uiState.update { it.copy(searchQuery = query) } },
    updateMessageMatchedChatIds = { ids -> uiState.update { it.copy(messageMatchedChatIds = ids) } },
    updateDrafts = { drafts ->
        uiState.update { state ->
            state.copy(drafts = drafts.mapValues { (_, draft) ->
                ChatDraftEntity(
                    ownerUserId = ownerUserId(),
                    chatId = draft.chatId,
                    text = draft.text,
                    updatedAt = draft.updatedAt,
                )
            })
        }
    },
    updateReceiptsByChat = { receipts -> uiState.update { it.copy(receiptsByChat = receipts) } },
    ownerUserId = ownerUserId,
    observeDraftsForOwner = { owner ->
        observeDraftsForOwner(owner).map { list ->
            list.map { ChatDraft(chatId = it.chatId, text = it.text, updatedAt = it.updatedAt) }
        }
    },
    getRecentMessages = getRecentMessages,
    listLockedChatIds = listLockedChatIds,
    searchChatIdsByMessageContent = searchChatIdsByMessageContent,
    listSecretChatIds = listSecretChatIds,
    trustChangedRemoteIds = trustChangedRemoteIds,
    searchFailedText = { text(R.string.contacts_search_failed) },
    logWarning = { message, error -> Log.w(TAG, message, error) },
    listScheduled = listScheduled,
)

private const val TAG = "ChatListLocalProj"
