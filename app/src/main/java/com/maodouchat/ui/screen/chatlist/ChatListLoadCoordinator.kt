package com.maodouchat.ui.screen.chatlist

import android.util.Log
import com.maodouchat.conversation.ConversationLocalCleanupSession
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.MissedCall
import com.maodouchat.network.ApiException
import com.maodouchat.network.ChatDto
import com.maodouchat.network.TokenManager
import com.maodouchat.security.BackgroundSessionGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chat list load + missed-call observation (ChatList 瘦身：网络/Room 编排切片).
 *
 * Owns getChats request coalescing, silent vs visible reload, stale cleanup orchestration,
 * and missed-call Room projection. Preview enrichment and identity refresh stay callbacks
 * so ViewModel keeps those domain helpers without owning the load job machine.
 */
internal class ChatListLoadCoordinator(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatListUiState>,
    private val tokenManager: TokenManager,
    private val deletedChatIds: MutableSet<String>,
    private val ownerUserId: () -> String,
    private val getAllChats: suspend () -> Flow<List<Chat>>,
    private val getChatById: suspend (String) -> Chat?,
    private val cacheChats: suspend (List<Chat>) -> Unit,
    private val fetchRemoteChats: suspend (token: String) -> Result<List<ChatDto>>,
    private val enrichServerChatPreview: suspend (Chat, ownerUserId: String) -> Chat,
    private val cleanupLocalChat: suspend (chatId: String, session: ConversationLocalCleanupSession) -> Unit,
    private val cleanupSessionFor: (ownerUserId: String) -> ConversationLocalCleanupSession,
    private val activeChatId: () -> String?,
    private val observeMissedCalls: () -> Flow<List<MissedCall>>,
    private val trimMissedCalls: suspend () -> Unit,
    private val text: (Int) -> String,
    private val onRefreshIdentityWarnings: () -> Unit,
    private val sessionExpiredMessageRes: Int,
    private val refreshFailedCachedMessageRes: Int,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var debouncedLoadChatsJob: Job? = null
    private var loadChatsJob: Job? = null
    private var loadChatsRequestId: Long = 0L

    fun startMissedCallObservation() {
        observeMissedCallsInternal()
    }

    fun requestLoadChats(trigger: ChatListReloadPolicy.Trigger) {
        val mode = ChatListReloadPolicy.modeFor(trigger)
        val wait = ChatListReloadPolicy.debounceMs(mode, trigger)
        if (wait > 0L) {
            debouncedLoadChatsJob?.cancel()
            debouncedLoadChatsJob = scope.launch {
                delay(wait)
                loadChats(showLoading = false)
            }
            return
        }
        debouncedLoadChatsJob?.cancel()
        loadChats(showLoading = ChatListReloadPolicy.shouldShowLoading(mode))
    }

    fun loadChats(showLoading: Boolean = true) {
        loadChatsJob?.cancel()
        val requestId = ++loadChatsRequestId
        val token = tokenManager.getToken().orEmpty()
        val loadOwnerUserId = ownerUserId()
        val loadCleanupSession = cleanupSessionFor(loadOwnerUserId)
        if (showLoading) {
            uiState.update { it.copy(isLoading = true, errorMessage = null) }
        } else {
            uiState.update { it.copy(isLoading = false) }
        }
        loadChatsJob = scope.launch {
            fun stillCurrent(): Boolean = requestId == loadChatsRequestId &&
                BackgroundSessionGate.mayContinue(
                    expectedUserId = loadOwnerUserId,
                )

            fun finishIfCurrent(errorMessage: String? = null, chats: List<Chat>? = null) {
                if (requestId != loadChatsRequestId) return
                uiState.update { state ->
                    state.copy(
                        chats = chats ?: state.chats,
                        isLoading = false,
                        errorMessage = errorMessage,
                    )
                }
            }

            try {
                if (token.isBlank() || loadOwnerUserId.isBlank()) {
                    val chats = getAllChats().firstOrNull() ?: emptyList()
                    if (requestId != loadChatsRequestId ||
                        tokenManager.getUserId().orEmpty() != loadOwnerUserId ||
                        !tokenManager.getToken().isNullOrBlank()
                    ) {
                        finishIfCurrent()
                        return@launch
                    }
                    uiState.update {
                        it.copy(
                            chats = chats,
                            isLoading = false,
                            errorMessage = if (chats.isEmpty()) text(sessionExpiredMessageRes) else null,
                        )
                    }
                    return@launch
                }

                if (requestId != loadChatsRequestId) {
                    return@launch
                }
                if (!stillCurrent()) {
                    finishIfCurrent(errorMessage = text(sessionExpiredMessageRes))
                    return@launch
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }

                val result = fetchRemoteChats(liveToken)
                result.fold(
                    onSuccess = { chatDtos ->
                        if (requestId != loadChatsRequestId) return@fold
                        if (!stillCurrent()) {
                            finishIfCurrent(errorMessage = text(sessionExpiredMessageRes))
                            return@fold
                        }
                        val currentUserId = tokenManager.getUserId().orEmpty()
                        val localById = getAllChats().firstOrNull().orEmpty().associateBy { it.id }
                        val uiById = uiState.value.chats.associateBy { it.id }
                        val activeId = activeChatId()
                        val chats = chatDtos.map { dto ->
                            val local = uiById[dto.id] ?: localById[dto.id]
                            val isActive = !activeId.isNullOrBlank() && activeId == dto.id
                            val base = ChatListRemoteMergePolicy.mergeRemoteChat(
                                dto = dto,
                                local = local,
                                currentUserId = currentUserId,
                                isActiveChat = isActive,
                            )
                            enrichServerChatPreview(base, loadOwnerUserId)
                        }
                        val filteredChats = ChatListRemoteMergePolicy.filterDeleted(chats, deletedChatIds)
                        val serverChatIds = filteredChats.mapTo(hashSetOf()) { it.id }
                        val staleChatIds = ChatListRemoteMergePolicy.staleChatIds(
                            localIds = getAllChats().firstOrNull().orEmpty().map { it.id },
                            serverChatIds = serverChatIds,
                        )
                        if (requestId != loadChatsRequestId) return@fold
                        if (!stillCurrent()) {
                            finishIfCurrent(errorMessage = text(sessionExpiredMessageRes))
                            return@fold
                        }
                        withContext(ioDispatcher + NonCancellable) {
                            try {
                                for (staleId in staleChatIds) {
                                    if (requestId != loadChatsRequestId) return@withContext
                                    if (!stillCurrent()) {
                                        finishIfCurrent(errorMessage = text(sessionExpiredMessageRes))
                                        return@withContext
                                    }
                                    cleanupLocalChat(staleId, loadCleanupSession)
                                }
                                if (requestId != loadChatsRequestId) return@withContext
                                if (!stillCurrent()) {
                                    finishIfCurrent(errorMessage = text(sessionExpiredMessageRes))
                                    return@withContext
                                }
                                cacheChats(filteredChats)
                            } catch (cleanupError: CancellationException) {
                                if (requestId == loadChatsRequestId) finishIfCurrent()
                                throw cleanupError
                            } catch (cleanupError: Exception) {
                                Log.w(TAG, "Chat cleanup failed", cleanupError)
                            }
                            if (requestId != loadChatsRequestId) return@withContext
                            if (!stillCurrent()) {
                                finishIfCurrent(errorMessage = text(sessionExpiredMessageRes))
                                return@withContext
                            }
                            val nickMerged = filteredChats.map { c ->
                                getChatById(c.id) ?: c
                            }
                            if (requestId != loadChatsRequestId) return@withContext
                            if (!stillCurrent()) {
                                finishIfCurrent(errorMessage = text(sessionExpiredMessageRes))
                                return@withContext
                            }
                            uiState.update {
                                it.copy(
                                    chats = nickMerged,
                                    isLoading = false,
                                    errorMessage = null,
                                    secretChatIds = nickMerged.filter { chat -> chat.isSecret }
                                        .map { chat -> chat.id }
                                        .toSet(),
                                )
                            }
                            onRefreshIdentityWarnings()
                        }
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        if (requestId != loadChatsRequestId) return@fold
                        if (!stillCurrent()) {
                            finishIfCurrent(errorMessage = text(sessionExpiredMessageRes))
                            return@fold
                        }
                        val chats = getAllChats().firstOrNull() ?: emptyList()
                        if (requestId != loadChatsRequestId) return@fold
                        val rateLimited = (error as? ApiException)?.statusCode == 429 ||
                            error.message.orEmpty().contains("频繁")
                        val nextError = ChatListRemoteMergePolicy.nextErrorOnFailure(
                            showLoading = showLoading,
                            rateLimited = rateLimited,
                            currentError = uiState.value.errorMessage,
                            failureMessage = error.message,
                            fallback = text(refreshFailedCachedMessageRes),
                        )
                        finishIfCurrent(errorMessage = nextError, chats = chats)
                        onRefreshIdentityWarnings()
                    },
                )
            } catch (error: CancellationException) {
                if (requestId == loadChatsRequestId) finishIfCurrent()
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "loadChats failed", error)
                if (requestId != loadChatsRequestId) throw error
                val chats = runCatching { getAllChats().firstOrNull() ?: emptyList() }
                    .getOrDefault(emptyList())
                finishIfCurrent(
                    errorMessage = error.message?.takeIf { message -> message.isNotBlank() }
                        ?: text(refreshFailedCachedMessageRes),
                    chats = chats,
                )
            }
        }
    }

    private fun observeMissedCallsInternal() {
        val missedOwnerUserId = ownerUserId()
        scope.launch {
            if (!BackgroundSessionGate.mayContinue(
                expectedUserId = missedOwnerUserId,
            )
            ) {
                return@launch
            }
            try {
                trimMissedCalls()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "missed-call trim failed", error)
            }
            observeMissedCalls().collect { list ->
                if (!BackgroundSessionGate.mayContinue(
                    expectedUserId = missedOwnerUserId,
                )
                ) {
                    return@collect
                }
                uiState.update { it.copy(missedCalls = list) }
            }
        }
    }

    companion object {
        private const val TAG = "ChatListLoad"
    }
}
