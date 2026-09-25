package com.maodouchat.ui.screen.chatlist

import android.util.Log
import com.maodouchat.R
import com.maodouchat.data.local.LikeQueryPolicy
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.data.model.Message
import com.maodouchat.session.CurrentSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 会话列表本地投影（ChatList 瘦身：草稿 / 回执 / 锁定密聊 / 消息搜索）。
 */
internal class ChatListLocalProjectionCoordinator(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatListUiState>,
    private val ownerUserId: () -> String,
    private val observeDraftsForOwner: (ownerUserId: String) -> Flow<List<ChatDraftEntity>>,
    private val getRecentMessages: suspend (chatId: String, limit: Int) -> List<Message>,
    private val listLockedChatIds: suspend () -> Set<String>,
    private val searchChatIdsByMessageContent: suspend (escaped: String) -> List<String>,
    private val listSecretChatIds: suspend () -> Set<String>,
    private val trustChangedRemoteIds: suspend (ownerUserId: String, remoteIds: Set<String>) -> Set<String>,
    private val text: (Int) -> String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val searchDebounceMs: Long = LIST_MESSAGE_SEARCH_DEBOUNCE_MS,
    private val searchMaxLength: Int = LIST_SEARCH_MAX_LENGTH,
) {
    private var messageSearchJob: Job? = null

    fun start() {
        observeDrafts()
        observeReceipts()
    }

    /** 1.165：扫描本地 identity_trust，标记身份密钥已变更（CHANGED）的对端用户（纯本地，无网络请求）。 */
    fun refreshIdentityWarnings() {
        val owner = ownerUserId()
        if (owner.isBlank() || owner == "me") return
        val remoteIds = uiState.value.chats
            .filter { !it.isGroup }
            .mapNotNull { chat -> chat.participants.firstOrNull { it.id != owner }?.id }
            .toSet()
        if (remoteIds.isEmpty()) return
        scope.launch(ioDispatcher) {
            val changed = try {
                trustChangedRemoteIds(owner, remoteIds)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptySet()
            }
            if (CurrentSession.ownerUserId() != owner) return@launch
            if (changed != uiState.value.identityChangedUserIds) {
                uiState.update { it.copy(identityChangedUserIds = changed) }
            }
        }
    }

    fun refreshLockedChats() {
        val owner = ownerUserId()
        if (owner.isBlank()) return
        scope.launch(ioDispatcher) {
            val ids = try {
                listLockedChatIds()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptySet()
            }
            if (CurrentSession.ownerUserId() != owner) return@launch
            uiState.update { it.copy(lockedChatIds = ids) }
        }
    }

    fun refreshSecretChats() {
        val owner = ownerUserId()
        if (owner.isBlank()) return
        scope.launch(ioDispatcher) {
            val ids = uiState.value.chats.filter { it.isSecret }.map { it.id }.toSet()
            if (CurrentSession.ownerUserId() != owner) return@launch
            uiState.update { it.copy(secretChatIds = ids) }
        }
    }

    fun onSearchQueryChange(query: String) {
        // 8.52 UX：列表搜索框长度上限（对齐其它搜索框，防超长 LIKE 查询）
        val clipped = if (query.length > searchMaxLength) query.take(searchMaxLength) else query
        uiState.update { it.copy(searchQuery = clipped) }
        messageSearchJob?.cancel()
        if (clipped.isBlank() || clipped.length < 2) {
            uiState.update { it.copy(messageMatchedChatIds = emptySet()) }
            return
        }
        // Debounce + single-flight: rapid typing must not apply a slower older LIKE result.
        messageSearchJob = scope.launch {
            delay(searchDebounceMs)
            val searchOwnerUserId = CurrentSession.ownerUserId()
            if (searchOwnerUserId.isBlank()) {
                uiState.update { it.copy(messageMatchedChatIds = emptySet()) }
                return@launch
            }
            // 9.156：转义与陈旧比对统一使用 clipped
            val escaped = LikeQueryPolicy.escapeForContains(clipped)
            if (escaped.isBlank()) {
                uiState.update { it.copy(messageMatchedChatIds = emptySet()) }
                return@launch
            }
            try {
                val matchedIds = withContext(ioDispatcher) {
                    val locked = listLockedChatIds()
                    val secret = listSecretChatIds()
                    searchChatIdsByMessageContent(escaped)
                        .filterNot { it in locked || it in secret }
                }
                // Drop if user kept typing past this snapshot or account switched mid-search.
                if (uiState.value.searchQuery != clipped) return@launch
                if (CurrentSession.ownerUserId() != searchOwnerUserId) return@launch
                uiState.update { it.copy(messageMatchedChatIds = matchedIds.toSet()) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (uiState.value.searchQuery != clipped) return@launch
                if (CurrentSession.ownerUserId() != searchOwnerUserId) return@launch
                Log.w(TAG, "list message search failed", error)
                uiState.update {
                    it.copy(
                        messageMatchedChatIds = emptySet(),
                        errorMessage = text(R.string.contacts_search_failed)
                    )
                }
            }
        }
    }

    private fun observeDrafts() {
        val owner = ownerUserId()
        if (owner.isBlank()) return
        scope.launch {
            observeDraftsForOwner(owner).collect { drafts ->
                // Drop if process-local session switched while Flow was still open.
                if (CurrentSession.ownerUserId() != owner) return@collect
                uiState.update { state -> state.copy(drafts = drafts.associateBy(ChatDraftEntity::chatId)) }
            }
        }
    }

    /** Telegram ticks: latest local message per chat, no schema change. */
    private fun observeReceipts() {
        val owner = ownerUserId()
        if (owner.isBlank()) return
        scope.launch(ioDispatcher) {
            uiState
                .map { state -> state.chats.map { it.id } to state.chats.map { it.lastMessageTime } }
                .distinctUntilChanged()
                .collect { (chatIds, _) ->
                    if (CurrentSession.ownerUserId() != owner) return@collect
                    val chatsById = uiState.value.chats.associateBy { it.id }
                    val receipts = chatIds.associateWith { chatId ->
                        val latest = runCatching {
                            getRecentMessages(chatId, 1).firstOrNull()
                        }.getOrNull()
                        ChatListReceiptPolicy.fromLatest(
                            latest = latest,
                            currentUserId = owner,
                            isGroup = chatsById[chatId]?.isGroup == true,
                        )
                    }.filterValues { it != null }.mapValues { it.value!! }
                    if (CurrentSession.ownerUserId() != owner) return@collect
                    uiState.update { it.copy(receiptsByChat = receipts) }
                }
        }
    }

    companion object {
        private const val TAG = "ChatListLocalProj"
        const val LIST_MESSAGE_SEARCH_DEBOUNCE_MS: Long = 250L
        const val LIST_SEARCH_MAX_LENGTH: Int = 200
    }
}
