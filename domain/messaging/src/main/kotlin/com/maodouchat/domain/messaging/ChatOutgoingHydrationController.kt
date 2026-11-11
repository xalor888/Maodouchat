package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.SessionSnapshot
import com.maodouchat.core.model.User
import com.maodouchat.core.session.OwnerSessionPolicy
import com.maodouchat.core.session.OwnerSessionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

// 会话水合一族：app 侧纯搬移；VM 只留同签名委托。
class ChatOutgoingHydrationController(
    private val uiState: MutableStateFlow<ChatDetailUiState>,
    private val cipherController: ChatSessionCipherController,
    private val setActiveChatId: (String) -> Unit,
    private val currentUserId: () -> String,
    private val getUserById: suspend (String) -> User?,
    private val session: () -> SessionSnapshot,
    private val sessionGeneration: () -> Long,
    private val purgeInProgress: () -> Boolean,
    private val text: (ChatOutgoingHydrationText, Array<out Any>) -> String,
    private val quantityText: (ChatOutgoingHydrationText, Int, Array<out Any>) -> String,
) {
    fun ownerSession(ownerUserId: String = currentUserId()): OwnerSessionSnapshot =
        OwnerSessionSnapshot(ownerUserId, sessionGeneration())

    fun isOwnerSessionCurrent(session: OwnerSessionSnapshot): Boolean =
        OwnerSessionPolicy.isCurrent(
            snapshot = session,
            liveUserId = this.session().userId,
            liveToken = this.session().token,
            liveSessionGeneration = sessionGeneration(),
            purgeInProgress = purgeInProgress(),
        )

    suspend fun hydrateOutgoingChat(
        chat: Chat,
        ownerUserId: String,
        resolvedPeerId: String?,
    ): ResolvedOutgoingChat {
        val session = ownerSession(ownerUserId)
        val localized = chat.copy(participants = chat.participants.map { withLocalNickname(it) })
        if (!isOwnerSessionCurrent(session)) {
            throw kotlinx.coroutines.CancellationException("hydrate_outgoing_session_changed")
        }
        val previousContact = uiState.value.contact
        val peerId = resolvedPeerId
        if (!localized.isGroup && peerId == null) {
            throw IllegalStateException(text(ChatOutgoingHydrationText.RECIPIENT_NOT_READY, arrayOf()))
        }
        val contact = if (localized.isGroup) {
            User(
                id = localized.id,
                name = localized.groupName ?: text(ChatOutgoingHydrationText.GROUP_NAME, arrayOf()),
                avatar = localized.groupAvatar,
                status = quantityText(
                    ChatOutgoingHydrationText.MEMBERS_COUNT,
                    localized.participants.size,
                    arrayOf(localized.participants.size)
                )
            )
        } else {
            localized.participants.firstOrNull { it.id == peerId }
                ?.takeUnless { it.name.isBlank() && previousContact.id == peerId }
                ?: previousContact.takeIf { it.id == peerId }
                ?: User(id = peerId.orEmpty(), name = "")
        }

        setActiveChatId(localized.id)
        uiState.update {
            it.copy(
                chat = localized,
                chatIsGroup = localized.isGroup,
                isSecretChat = localized.isSecret,
                contact = contact,
                disappearingMessageSeconds = if (localized.isGroup) 0 else localized.disappearingMessageSeconds
            )
        }
        cipherController.occupySessionCipher(
            localized.id,
            peerUserId = peerId,
            updatePeer = true
        )
        return ResolvedOutgoingChat(localized.id, localized, peerId)
    }

    /** 合并本地备注名（服务端 UserDto 不含 nickname） */
    suspend fun withLocalNickname(user: User): User {
        if (!user.nickname.isNullOrBlank()) return user
        val nick = try {
            getUserById(user.id)?.nickname
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        return if (nick.isNullOrBlank()) user else user.copy(nickname = nick)
    }
}
