package com.maodouchat.conversation

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.User
import com.maodouchat.network.ApiService
import com.maodouchat.network.ChatDto
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.security.SecretChatPolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 默认会话创建端口实现。
 *
 * 统一处理：
 * 1. 会话鉴权与账号状态门禁 (BackgroundSessionGate)
 * 2. 密聊开关特性校验 (RuntimeFlags / SecretChatPolicy)
 * 3. 并发/快速重复点击重入防抖 (Mutex)
 * 4. 远程 API 调用与领域模型映射
 * 5. 成功创建后的本地持久化回调
 */
class DefaultConversationCreationPort(
    private val sessionProvider: () -> Pair<String?, String?>,
    private val createChatApi: suspend (
        token: String,
        participantIds: List<String>,
        isGroup: Boolean,
        groupName: String?,
        chatType: String?
    ) -> Result<ChatDto> = { token, ids, isGroup, name, type ->
        ApiService.createChat(token, ids, isGroup, name, type)
    },
    private val onChatCreated: suspend (Chat) -> Unit = {},
    private val isSecretChatFeatureEnabled: () -> Boolean = { true }
) : ConversationCreationPort {

    private val mutex = Mutex()

    override suspend fun createDirectChat(peerUserId: String): Result<Chat> {
        val peerId = peerUserId.trim()
        if (peerId.isBlank()) {
            return Result.failure(IllegalArgumentException("Peer user ID cannot be blank"))
        }
        return executeCreation(
            participantIds = listOf(peerId),
            isGroup = false,
            groupName = null,
            chatType = "DIRECT"
        )
    }

    override suspend fun createSecretChat(peerUserId: String): Result<Chat> {
        val peerId = peerUserId.trim()
        if (peerId.isBlank()) {
            return Result.failure(IllegalArgumentException("Peer user ID cannot be blank"))
        }
        if (!isSecretChatFeatureEnabled()) {
            return Result.failure(IllegalStateException("Secret chat feature is currently disabled"))
        }
        return executeCreation(
            participantIds = listOf(peerId),
            isGroup = false,
            groupName = null,
            chatType = SecretChatPolicy.CHAT_TYPE
        )
    }

    override suspend fun createGroupChat(name: String, memberUserIds: List<String>): Result<Chat> {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            return Result.failure(IllegalArgumentException("Group name cannot be blank"))
        }
        val cleanMemberIds = memberUserIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        return executeCreation(
            participantIds = cleanMemberIds,
            isGroup = true,
            groupName = trimmedName,
            chatType = "GROUP"
        )
    }

    override suspend fun createChannelChat(name: String, memberUserIds: List<String>): Result<Chat> {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            return Result.failure(IllegalArgumentException("Channel name cannot be blank"))
        }
        val cleanMemberIds = memberUserIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        return executeCreation(
            participantIds = cleanMemberIds,
            isGroup = true,
            groupName = trimmedName,
            chatType = "CHANNEL"
        )
    }

    private suspend fun executeCreation(
        participantIds: List<String>,
        isGroup: Boolean,
        groupName: String?,
        chatType: String?
    ): Result<Chat> = mutex.withLock {
        val (ownerUserId, token) = sessionProvider()
        if (ownerUserId.isNullOrBlank() || token.isNullOrBlank()) {
            return@withLock Result.failure(IllegalStateException("Session expired or user not logged in"))
        }

        if (!BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
                liveToken = token,
                liveUserId = ownerUserId
            )
        ) {
            return@withLock Result.failure(IllegalStateException("Session validation failed"))
        }

        try {
            val apiResult = createChatApi(token, participantIds, isGroup, groupName, chatType)
            apiResult.fold(
                onSuccess = { dto ->
                    // 再次校验账号上下文防切号写入
                    val (currentUserId, currentToken) = sessionProvider()
                    if (currentUserId != ownerUserId || currentToken.isNullOrBlank()) {
                        return@fold Result.failure(IllegalStateException("Account switched during chat creation"))
                    }

                    val domainChat = dto.toDomainChat()
                    onChatCreated(domainChat)
                    Result.success(domainChat)
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}

internal fun ChatDto.toDomainChat(): Chat = Chat(
    id = id,
    participants = participants.map { dto ->
        User(
            id = dto.id,
            name = dto.name,
            avatar = dto.avatar,
            email = dto.email,
            isOnline = dto.isOnline,
            status = dto.status,
            lastSeen = dto.lastSeen
        )
    },
    lastMessage = lastMessage,
    lastMessageType = runCatching { MessageType.valueOf(lastMessageType) }.getOrDefault(MessageType.TEXT),
    lastMessageTime = if (lastMessageTime > 0) lastMessageTime else System.currentTimeMillis(),
    unreadCount = unreadCount,
    isGroup = isGroup,
    chatType = chatType,
    groupName = groupName,
    groupAnnouncement = groupAnnouncement,
    groupAvatar = groupAvatar,
    memberRevision = memberRevision,
    pinnedAt = pinnedAt,
    notificationsMuted = notificationsMuted,
    archived = archived,
    markedUnread = markedUnread,
    settingsUpdatedAt = settingsUpdatedAt,
    disappearingMessageSeconds = disappearingMessageSeconds
)
