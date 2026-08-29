package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.User
import com.maodouchat.network.GroupMemberDto

/** Maps group and identity responses into the stable chat-detail UI contract. */
internal class ChatGroupSecurityStateController {
    fun presentGroup(
        state: ChatDetailUiState,
        chat: Chat,
        contact: User,
        warning: String? = null,
    ): ChatDetailUiState = state.copy(
        chat = chat,
        chatIsGroup = true,
        isSecretChat = false,
        contact = contact,
        groupEncryptionWarning = warning ?: state.groupEncryptionWarning,
        disappearingMessageSeconds = 0,
    )

    fun presentMembers(
        state: ChatDetailUiState,
        members: List<GroupMemberDto>,
        currentUserId: String,
    ): ChatDetailUiState {
        val selfMember = members.firstOrNull { it.userId == currentUserId }
        return state.copy(
            myMemberRole = selfMember?.role,
            myMutedUntil = selfMember?.mutedUntil ?: 0L,
            memberRoleByUser = members.associate { it.userId to it.role },
            memberNicknameByUser = members
                .filter { !it.groupNickname.isNullOrBlank() }
                .associate { it.userId to it.groupNickname!! },
        )
    }

    fun resetDirectIdentityForGroup(state: ChatDetailUiState): ChatDetailUiState = state.copy(
        identityWarning = null,
        safetyCode = null,
        trustState = SignalProtocol.IdentityTrustState.TRUSTED,
        deviceSafetyStates = emptyList(),
        isLoadingDeviceSafety = false,
        deviceSafetyWarning = null,
        canVerifyIdentity = false,
    )
}
