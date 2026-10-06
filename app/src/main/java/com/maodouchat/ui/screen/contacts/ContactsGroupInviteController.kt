package com.maodouchat.ui.screen.contacts

import com.maodouchat.R
import com.maodouchat.network.GroupInvitationDto
import com.maodouchat.network.GroupInviteAcceptResponse
import com.maodouchat.security.BackgroundSessionGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 群邀请流程一族：列表加载、接受/拒绝。从 ContactsViewModel 纯搬移；
// VM 只留同签名委托，状态经 lambda 注入（与 Chat*Controller 同一装配模式）。
internal class ContactsGroupInviteController(
    private val scope: CoroutineScope,
    private val updateState: ((ContactsUiState) -> ContactsUiState) -> Unit,
    private val isGroupInviteBusy: () -> Boolean,
    private val text: (Int, Array<out Any>) -> String,
    private val groupInviteLoader: suspend () -> Result<List<GroupInvitationDto>>,
    private val groupInviteAcceptor: suspend (inviteId: String) -> Result<GroupInviteAcceptResponse>,
    private val groupInviteDecliner: suspend (inviteId: String) -> Result<GroupInviteAcceptResponse>,
    private val onInviteAccepted: () -> Unit,
) {
    fun loadGroupInvites() {
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) return
        scope.launch {
            if (!BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
            ) return@launch
            val result = groupInviteLoader()
            if (!BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
            ) return@launch
            val invites = result.getOrNull() ?: return@launch
            updateState {
                it.copy(
                    groupInvites = invites.map { dto ->
                        GroupInviteItem(
                            id = dto.id,
                            chatId = dto.chatId,
                            chatName = dto.chatName.ifBlank { text(R.string.contacts_group_unnamed, emptyArray()) },
                            inviterName = dto.inviterName,
                            memberCount = dto.memberCount,
                            createdAt = dto.createdAt
                        )
                    }
                )
            }
        }
    }

    fun acceptGroupInvite(inviteId: String) {
        mutateGroupInvite(inviteId, accept = true)
    }

    fun declineGroupInvite(inviteId: String) {
        mutateGroupInvite(inviteId, accept = false)
    }

    private fun mutateGroupInvite(inviteId: String, accept: Boolean) {
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank() || isGroupInviteBusy()) return
        scope.launch {
            if (!BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
            ) return@launch
            updateState { it.copy(isGroupInviteBusy = true, errorMessage = null, infoMessage = null) }
            try {
                val result = if (accept) {
                    groupInviteAcceptor(inviteId)
                } else {
                    groupInviteDecliner(inviteId)
                }
                if (!BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
                ) return@launch
                result.fold(
                    onSuccess = {
                        updateState { state ->
                            state.copy(
                                isGroupInviteBusy = false,
                                infoMessage = text(
                                    if (accept) R.string.contacts_group_invite_accepted else R.string.contacts_group_invite_declined,
                                    emptyArray()
                                ),
                                groupInvites = state.groupInvites.filterNot { it.id == inviteId }
                            )
                        }
                        if (accept) onInviteAccepted()
                        loadGroupInvites()
                    },
                    onFailure = { error ->
                        updateState {
                            it.copy(
                                isGroupInviteBusy = false,
                                errorMessage = error.message ?: text(R.string.error_operation_failed, emptyArray())
                            )
                        }
                    }
                )
            } catch (error: CancellationException) {
                updateState { it.copy(isGroupInviteBusy = false) }
                throw error
            } catch (error: Exception) {
                updateState {
                    it.copy(
                        isGroupInviteBusy = false,
                        errorMessage = error.message ?: text(R.string.error_operation_failed, emptyArray())
                    )
                }
            }
        }
    }
}
