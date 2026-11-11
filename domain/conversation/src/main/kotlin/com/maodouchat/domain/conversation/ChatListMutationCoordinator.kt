package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatSettingsResponse
import com.maodouchat.core.model.ConversationLocalCleanupSession
import com.maodouchat.core.model.UpdateChatSettingsRequest
import com.maodouchat.core.session.SecretChatPolicy
import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.User
import com.maodouchat.core.model.ChatDto
import com.maodouchat.core.model.requiresGroupOwnershipTransfer
import com.maodouchat.core.model.restoreChatSorted
import com.maodouchat.core.model.applyConfirmedSettings
import com.maodouchat.core.model.bumpOptimisticSettingsClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 会话列表写路径协调器：设置 mutation / 删除 / 密聊创建。 */
class ChatListMutationCoordinator(
    private val scope: CoroutineScope,
    private val getChats: () -> List<Chat>,
    private val updateChats: ((List<Chat>) -> List<Chat>) -> Unit,
    private val showError: (String?) -> Unit,
    private val isDeletingChat: (String) -> Boolean,
    private val addDeletingChat: (String) -> Unit,
    private val removeDeletingChat: (String) -> Unit,
    private val setCreatedSecretChatId: (String?) -> Unit,
    private val showOwnerTransferRequired: (String) -> Unit,
    private val deletedChatIds: MutableSet<String>,
    private val settingsInFlight: MutableSet<String>,
    private val ownerUserId: () -> String,
    private val hasSession: () -> Boolean,
    private val sessionMayContinue: (String) -> Boolean,
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
    private val sessionExpiredText: () -> String,
    private val settingsSyncFailedText: () -> String,
    private val chatLeaveFailedText: () -> String,
    private val secretChatFeatureEnabled: () -> Boolean,
    private val secretChatDisabledMessage: () -> String,
    private val secretChatStartFailedMessage: () -> String,
    private val logWarning: (String, Throwable?) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun updateChatSettings(chat: Chat, optimistic: Chat, request: UpdateChatSettingsRequest) {
        if (!settingsInFlight.add(chat.id)) return
        val settingsOwnerUserId = ownerUserId()
        if (!hasSession() || settingsOwnerUserId.isBlank()) {
            showError(sessionExpiredText())
            settingsInFlight.remove(chat.id)
            return
        }
        updateChats { chats -> chats.map { if (it.id == chat.id) optimistic else it } }
        if (optimistic.notificationsMuted && !chat.notificationsMuted) {
            onMuteApplied(chat.id)
        }
        scope.launch {
            try {
                if (!sessionMayContinue(settingsOwnerUserId)) {
                    return@launch
                }
                try {
                    cacheChats(listOf(optimistic))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logWarning("optimistic cacheChats failed for ${chat.id}", error)
                }
                if (!sessionMayContinue(settingsOwnerUserId)) {
                    return@launch
                }
                updateChatSettingsRemote(chat.id, request).fold(
                    onSuccess = { settings ->
                        if (!sessionMayContinue(settingsOwnerUserId)) {
                            return@fold
                        }
                        val confirmed = applyConfirmedSettings(optimistic, settings)
                        cacheChats(listOf(confirmed))
                        if (!sessionMayContinue(settingsOwnerUserId)) {
                            return@fold
                        }
                        updateChats { chats -> chats.map { if (it.id == chat.id) confirmed else it } }
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        if (!sessionMayContinue(settingsOwnerUserId)) {
                            return@fold
                        }
                        try {
                            cacheChats(listOf(chat))
                        } catch (cacheError: CancellationException) {
                            throw cacheError
                        } catch (_: Exception) {
                        }
                        if (!sessionMayContinue(settingsOwnerUserId)) {
                            return@fold
                        }
                        updateChats { chats -> chats.map { if (it.id == chat.id) chat else it } }
                        showError(settingsSyncFailedText())
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
            if (!sessionMayContinue(clearOwnerUserId)) {
                return@launch
            }
            try {
                cacheChats(listOf(optimistic))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logWarning("clearMarkedUnread cache failed for ${chat.id}", error)
            }
            if (!hasSession() || clearOwnerUserId.isBlank()) return@launch
            if (!sessionMayContinue(clearOwnerUserId)) {
                return@launch
            }
            updateChatSettingsRemote(
                chat.id,
                UpdateChatSettingsRequest(markedUnread = false),
            ).fold(
                onSuccess = { settings ->
                    if (!sessionMayContinue(clearOwnerUserId)) {
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
                    if (!sessionMayContinue(clearOwnerUserId)) {
                        return@fold
                    }
                    updateChats { chats ->
                        chats.map {
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
                        }
                    }
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    if (!sessionMayContinue(clearOwnerUserId)) {
                        return@fold
                    }
                    logWarning("clearMarkedUnread REST failed for ${chat.id}: ${error.message}", null)
                },
            )
        }
    }

    fun deleteChat(chatId: String) {
        val deleteOwnerUserId = ownerUserId()
        val cleanupSession = cleanupSessionFor(deleteOwnerUserId)
        if (!hasSession() || deleteOwnerUserId.isBlank()) {
            showError(sessionExpiredText())
            return
        }
        if (isDeletingChat(chatId)) return
        addDeletingChat(chatId)
        scope.launch {
            try {
                if (!sessionMayContinue(deleteOwnerUserId)) {
                    return@launch
                }
                val previous = getChats().find { it.id == chatId }
                updateChats { chats -> chats.filterNot { it.id == chatId } }
                var leaveConfirmed = false
                try {
                    if (!sessionMayContinue(deleteOwnerUserId)) {
                        return@launch
                    }
                    val result = deleteChatRemote(chatId)
                    val resultError = result.exceptionOrNull()
                    if (resultError is CancellationException) throw resultError
                    if (!sessionMayContinue(deleteOwnerUserId)) {
                        return@launch
                    }
                    if (result.isFailure) {
                        logWarning("deleteChat failed: " + (resultError?.message ?: "unknown"), null)
                        deletedChatIds.remove(chatId)
                        if (previous != null) {
                            updateChats { chats ->
                                if (chats.none { it.id == chatId }) restoreChatSorted(chats, previous) else chats
                            }
                        }
                        if (requiresGroupOwnershipTransfer(resultError)) {
                            showOwnerTransferRequired(chatId)
                        } else {
                            showError(chatLeaveFailedText())
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
                        sessionMayContinue(deleteOwnerUserId) &&
                        getChats().none { it.id == chatId }
                    ) {
                        updateChats { chats -> restoreChatSorted(chats, previous) }
                    }
                    throw error
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logWarning("deleteChat cleanup failed", error)
            } finally {
                removeDeletingChat(chatId)
            }
        }
    }

    fun startSecretChatWithPeer(peerId: String) {
        if (peerId.isBlank()) return
        if (!secretChatFeatureEnabled()) {
            showError(secretChatDisabledMessage())
            return
        }
        val owner = ownerUserId()
        if (!hasSession() || owner.isBlank()) {
            showError(sessionExpiredText())
            return
        }
        scope.launch(ioDispatcher) {
            if (!sessionMayContinue(owner)) {
                showError(sessionExpiredText())
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
                    setCreatedSecretChatId(chat.id)
                },
                onFailure = { error ->
                    showError(error.message ?: secretChatStartFailedMessage())
                },
            )
        }
    }

    /** 密聊建好并导航后清掉一次性跳转标记（与 startSecretChatWithPeer 配对）。 */
    fun clearCreatedSecretChat() {
        setCreatedSecretChatId(null)
    }

}
