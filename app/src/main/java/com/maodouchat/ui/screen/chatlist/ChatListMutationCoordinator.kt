package com.maodouchat.ui.screen.chatlist

import android.util.Log
import com.maodouchat.R
import com.maodouchat.conversation.ConversationLocalCleanupSession
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.User
import com.maodouchat.network.ChatDto
import com.maodouchat.network.ChatSettingsResponse
import com.maodouchat.network.UpdateChatSettingsRequest
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.security.SecretChatPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 会话列表写路径协调器（ChatList 瘦身：设置 mutation / 删除 / 密聊创建）。
 *
 * 承接 `updateChatSettings`、打开会话清标未读、deleteChat、startSecretChat；
 * ApiService 经注入 lambda，ViewModel 保留 RuntimeFlags 门控与 toggle 构造。
 */
internal class ChatListMutationCoordinator(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatListUiState>,
    private val deletedChatIds: MutableSet<String>,
    private val settingsInFlight: MutableSet<String>,
    private val ownerUserId: () -> String,
    private val cacheChats: suspend (List<Chat>) -> Unit,
    private val updateChatSettingsRemote: suspend (
        chatId: String,
        request: UpdateChatSettingsRequest,
    ) -> Result<ChatSettingsResponse>,
    private val deleteChatRemote: suspend (chatId: String) -> Result<Unit>,
    private val createChatRemote: suspend (
        peerIds: List<String>,
        isGroup: Boolean,
        groupName: String?,
        chatType: String?,
    ) -> Result<ChatDto>,
    private val touchSecretChat: suspend (chatId: String) -> Unit,
    private val cleanupLocalChat: suspend (chatId: String, session: ConversationLocalCleanupSession) -> Unit,
    private val cleanupSessionFor: (ownerUserId: String) -> ConversationLocalCleanupSession,
    private val onMuteApplied: (chatId: String) -> Unit,
    private val text: (Int) -> String,
    private val secretChatFeatureEnabled: () -> Boolean,
    private val secretChatDisabledMessage: () -> String,
    private val secretChatStartFailedMessage: () -> String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun updateChatSettings(chat: Chat, optimistic: Chat, request: UpdateChatSettingsRequest) {
        if (!settingsInFlight.add(chat.id)) return
        val settingsOwnerUserId = ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession() || settingsOwnerUserId.isBlank()) {
            uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
            settingsInFlight.remove(chat.id)
            return
        }
        uiState.update { state -> state.copy(chats = state.chats.map { if (it.id == chat.id) optimistic else it }) }
        if (optimistic.notificationsMuted && !chat.notificationsMuted) {
            onMuteApplied(chat.id)
        }
        scope.launch {
            try {
                if (!BackgroundSessionGate.mayContinue(
                    expectedUserId = settingsOwnerUserId,
                )
                ) {
                    return@launch
                }
                try {
                    cacheChats(listOf(optimistic))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "optimistic cacheChats failed for ${chat.id}", error)
                }
                if (!BackgroundSessionGate.mayContinue(
                    expectedUserId = settingsOwnerUserId,
                )
                ) {
                    return@launch
                }
                updateChatSettingsRemote(chat.id, request).fold(
                    onSuccess = { settings ->
                        if (!BackgroundSessionGate.mayContinue(
                            expectedUserId = settingsOwnerUserId,
                        )
                        ) {
                            return@fold
                        }
                        val confirmed = applyConfirmedSettings(optimistic, settings)
                        cacheChats(listOf(confirmed))
                        if (!BackgroundSessionGate.mayContinue(
                            expectedUserId = settingsOwnerUserId,
                        )
                        ) {
                            return@fold
                        }
                        uiState.update { state ->
                            state.copy(chats = state.chats.map { if (it.id == chat.id) confirmed else it })
                        }
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        if (!BackgroundSessionGate.mayContinue(
                            expectedUserId = settingsOwnerUserId,
                        )
                        ) {
                            return@fold
                        }
                        try {
                            cacheChats(listOf(chat))
                        } catch (cacheError: CancellationException) {
                            throw cacheError
                        } catch (_: Exception) {
                        }
                        if (!BackgroundSessionGate.mayContinue(
                            expectedUserId = settingsOwnerUserId,
                        )
                        ) {
                            return@fold
                        }
                        uiState.update { state ->
                            state.copy(
                                chats = state.chats.map { if (it.id == chat.id) chat else it },
                                errorMessage = text(R.string.chat_settings_sync_failed),
                            )
                        }
                    },
                )
            } finally {
                settingsInFlight.remove(chat.id)
            }
        }
    }

    /**
     * Open-chat path: force markedUnread=false on server.
     * Unlike toggle, REST failure must not restore markedUnread=true.
     */
    fun clearMarkedUnreadAfterOpen(chat: Chat) {
        if (!chat.markedUnread) return
        val clearOwnerUserId = ownerUserId()
        if (clearOwnerUserId.isBlank()) return
        val optimistic = bumpOptimisticSettingsClock(
            chat.copy(unreadCount = 0, markedUnread = false),
            System.currentTimeMillis(),
        )
        scope.launch {
            if (!BackgroundSessionGate.mayContinue(
                expectedUserId = clearOwnerUserId,
            )
            ) {
                return@launch
            }
            try {
                cacheChats(listOf(optimistic))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "clearMarkedUnread cache failed for ${chat.id}", error)
            }
            if (!com.maodouchat.session.CurrentSession.hasSession() || clearOwnerUserId.isBlank()) return@launch
            if (!BackgroundSessionGate.mayContinue(
                expectedUserId = clearOwnerUserId,
            )
            ) {
                return@launch
            }
            updateChatSettingsRemote(
                chat.id,
                UpdateChatSettingsRequest(markedUnread = false),
            ).fold(
                onSuccess = { settings ->
                    if (!BackgroundSessionGate.mayContinue(
                        expectedUserId = clearOwnerUserId,
                    )
                    ) {
                        return@fold
                    }
                    val confirmed = optimistic.copy(
                        pinnedAt = settings.pinnedAt,
                        notificationsMuted = settings.notificationsMuted,
                        archived = settings.archived,
                        markedUnread = false,
                        settingsUpdatedAt = settings.updatedAt,
                    )
                    cacheChats(listOf(confirmed))
                    if (!BackgroundSessionGate.mayContinue(
                        expectedUserId = clearOwnerUserId,
                    )
                    ) {
                        return@fold
                    }
                    uiState.update { state ->
                        state.copy(
                            chats = state.chats.map {
                                if (it.id == chat.id) {
                                    it.copy(
                                        markedUnread = false,
                                        unreadCount = 0,
                                        pinnedAt = confirmed.pinnedAt,
                                        notificationsMuted = confirmed.notificationsMuted,
                                        archived = confirmed.archived,
                                        settingsUpdatedAt = confirmed.settingsUpdatedAt,
                                    )
                                } else {
                                    it
                                }
                            },
                        )
                    }
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    if (!BackgroundSessionGate.mayContinue(
                        expectedUserId = clearOwnerUserId,
                    )
                    ) {
                        return@fold
                    }
                    Log.w(TAG, "clearMarkedUnread REST failed for ${chat.id}: ${error.message}")
                },
            )
        }
    }

    fun deleteChat(chatId: String) {
        val deleteOwnerUserId = ownerUserId()
        val cleanupSession = cleanupSessionFor(deleteOwnerUserId)
        if (!com.maodouchat.session.CurrentSession.hasSession() || deleteOwnerUserId.isBlank()) {
            uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
            return
        }
        if (uiState.value.deletingChatIds.contains(chatId)) return
        uiState.update { it.copy(deletingChatIds = it.deletingChatIds + chatId) }
        scope.launch {
            try {
                if (!BackgroundSessionGate.mayContinue(
                    expectedUserId = deleteOwnerUserId,
                )
                ) {
                    return@launch
                }
                val previous = uiState.value.chats.find { it.id == chatId }
                uiState.update { state ->
                    state.copy(chats = state.chats.filterNot { it.id == chatId })
                }
                var leaveConfirmed = false
                try {
                    if (!BackgroundSessionGate.mayContinue(
                        expectedUserId = deleteOwnerUserId,
                    )
                    ) {
                        return@launch
                    }
                    val result = deleteChatRemote(chatId)
                    val resultError = result.exceptionOrNull()
                    if (resultError is CancellationException) throw resultError
                    if (!BackgroundSessionGate.mayContinue(
                        expectedUserId = deleteOwnerUserId,
                    )
                    ) {
                        return@launch
                    }
                    if (result.isFailure) {
                        Log.w(TAG, "deleteChat failed: " + (resultError?.message ?: "unknown"))
                        deletedChatIds.remove(chatId)
                        if (previous != null) {
                            uiState.update { st ->
                                if (st.chats.none { it.id == chatId }) {
                                    st.copy(chats = restoreChatSorted(st.chats, previous))
                                } else {
                                    st
                                }
                            }
                        }
                        if (requiresGroupOwnershipTransfer(resultError)) {
                            uiState.update {
                                it.copy(ownerTransferRequiredChatId = chatId, errorMessage = null)
                            }
                        } else {
                            uiState.update { it.copy(errorMessage = text(R.string.chat_leave_failed)) }
                        }
                        return@launch
                    }
                    leaveConfirmed = true
                    deletedChatIds.add(chatId)
                    withContext(ioDispatcher + NonCancellable) {
                        cleanupLocalChat(chatId, cleanupSession)
                    }
                } catch (error: CancellationException) {
                    deletedChatIds.remove(chatId)
                    if (!leaveConfirmed && previous != null &&
                        BackgroundSessionGate.mayContinue(
                            expectedUserId = deleteOwnerUserId,
                        ) &&
                        uiState.value.chats.none { it.id == chatId }
                    ) {
                        uiState.update { st ->
                            st.copy(chats = restoreChatSorted(st.chats, previous))
                        }
                    }
                    throw error
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "deleteChat cleanup failed", error)
            } finally {
                uiState.update { it.copy(deletingChatIds = it.deletingChatIds - chatId) }
            }
        }
    }

    fun startSecretChatWithPeer(peerId: String) {
        if (peerId.isBlank()) return
        if (!secretChatFeatureEnabled()) {
            uiState.update { it.copy(errorMessage = secretChatDisabledMessage()) }
            return
        }
        val owner = ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession() || owner.isBlank()) {
            uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
            return
        }
        scope.launch(ioDispatcher) {
            if (!BackgroundSessionGate.mayContinue(
                expectedUserId = owner,
            )
            ) {
                uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            val result = createChatRemote(
                listOf(peerId),
                false,
                null,
                SecretChatPolicy.CHAT_TYPE,
            )
            result.fold(
                onSuccess = { chat ->
                    runCatching { touchSecretChat(chat.id) }
                    runCatching {
                        cacheChats(
                            listOf(
                                Chat(
                                    id = chat.id,
                                    participants = chat.participants.map { p ->
                                        User(
                                            p.id,
                                            p.name,
                                            p.avatar,
                                            p.email,
                                            p.isOnline,
                                            p.status,
                                            lastSeen = p.lastSeen,
                                        )
                                    },
                                    lastMessage = chat.lastMessage,
                                    lastMessageType = MessageType.fromWire(chat.lastMessageType),
                                    lastMessageTime = chat.lastMessageTime,
                                    unreadCount = chat.unreadCount,
                                    isGroup = chat.isGroup,
                                    chatType = chat.chatType,
                                    groupName = chat.groupName,
                                    groupAnnouncement = chat.groupAnnouncement,
                                    groupAvatar = chat.groupAvatar,
                                    memberRevision = chat.memberRevision,
                                    disappearingMessageSeconds = chat.disappearingMessageSeconds,
                                ),
                            ),
                        )
                    }
                    uiState.update { it.copy(createdSecretChatId = chat.id) }
                },
                onFailure = { error ->
                    uiState.update {
                        it.copy(errorMessage = error.message ?: secretChatStartFailedMessage())
                    }
                },
            )
        }
    }

    private companion object {
        const val TAG = "ChatListMutation"
    }
}
