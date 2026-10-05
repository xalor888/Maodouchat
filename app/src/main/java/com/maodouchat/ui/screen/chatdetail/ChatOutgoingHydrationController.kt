package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.chatdetail.ChatDetailAccess
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.User
import com.maodouchat.data.repository.UserRepository
import com.maodouchat.ui.OwnerSessionPolicy
import com.maodouchat.ui.OwnerSessionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

// 会话水合一族：从 ChatDetailViewModel 纯搬移；VM 只留同签名委托。
internal class ChatOutgoingHydrationController(
    private val uiState: MutableStateFlow<ChatDetailUiState>,
    private val userRepo: UserRepository,
    private val cipherController: ChatSessionCipherController,
    private val setActiveChatId: (String) -> Unit,
    private val currentUserId: () -> String,
    private val text: (Int, Array<out Any>) -> String,
    private val quantityText: (Int, Int, Array<out Any>) -> String,
) {
    internal fun ownerSession(ownerUserId: String = currentUserId()): OwnerSessionSnapshot =
        OwnerSessionSnapshot(ownerUserId, ChatDetailAccess.currentSessionGeneration())

    internal fun isOwnerSessionCurrent(session: OwnerSessionSnapshot): Boolean =
        OwnerSessionPolicy.isCurrent(
            snapshot = session,
            liveUserId = com.maodouchat.session.CurrentSession.snapshot().userId,
            liveToken = com.maodouchat.session.CurrentSession.snapshot().token,
            liveSessionGeneration = ChatDetailAccess.currentSessionGeneration(),
            purgeInProgress = com.maodouchat.security.SecureSessionManager.isPurgeInProgress(),
        )

    internal suspend fun hydrateOutgoingChat(
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
            throw IllegalStateException(text(R.string.chat_recipient_not_ready, arrayOf()))
        }
        val contact = if (localized.isGroup) {
            User(
                id = localized.id,
                name = localized.groupName ?: text(R.string.chat_group, arrayOf()),
                avatar = localized.groupAvatar,
                status = quantityText(
                    R.plurals.chat_members_count,
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
    internal suspend fun withLocalNickname(user: User): User {
        if (!user.nickname.isNullOrBlank()) return user
        val nick = try {
            userRepo.getUserById(user.id)?.nickname
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        return if (nick.isNullOrBlank()) user else user.copy(nickname = nick)
    }
}
