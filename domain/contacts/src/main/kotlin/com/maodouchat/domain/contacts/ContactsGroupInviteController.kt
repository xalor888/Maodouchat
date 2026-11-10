package com.maodouchat.domain.contacts

import com.maodouchat.core.model.ContactsUiState
import com.maodouchat.core.model.GroupInvitationDto
import com.maodouchat.core.model.GroupInviteAcceptResponse
import com.maodouchat.core.model.GroupInviteItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 群邀请流程一族：列表加载、接受/拒绝。从 ContactsViewModel 纯搬移；
// VM 只留同签名委托，状态经 lambda 注入（与 Chat*Controller 同一装配模式）。
class ContactsGroupInviteController(
    private val scope: CoroutineScope,
    private val updateState: ((ContactsUiState) -> ContactsUiState) -> Unit,
    private val isGroupInviteBusy: () -> Boolean,
    private val hasSession: () -> Boolean,
    private val sessionOwnerUserId: () -> String,
    private val sessionMayContinue: (String) -> Boolean,
    private val text: (ContactsMessage, Array<out Any>) -> String,
    private val groupInviteLoader: suspend () -> Result<List<GroupInvitationDto>>,
    private val groupInviteAcceptor: suspend (inviteId: String) -> Result<GroupInviteAcceptResponse>,
    private val groupInviteDecliner: suspend (inviteId: String) -> Result<GroupInviteAcceptResponse>,
    private val onInviteAccepted: () -> Unit,
) {
    fun loadGroupInvites() {
        val ownerUserId = sessionOwnerUserId()
        if (!hasSession() || ownerUserId.isBlank()) return
        scope.launch {
            if (!sessionMayContinue(ownerUserId)) return@launch
            val result = groupInviteLoader()
            if (!sessionMayContinue(ownerUserId)) return@launch
            val invites = result.getOrNull() ?: return@launch
            updateState {
                it.copy(
                    groupInvites = invites.map { dto ->
                        GroupInviteItem(
                            id = dto.id,
                            chatId = dto.chatId,
                            chatName = dto.chatName.ifBlank { text(ContactsMessage.CONTACTS_GROUP_UNNAMED, emptyArray()) },
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
        val ownerUserId = sessionOwnerUserId()
        if (!hasSession() || ownerUserId.isBlank() || isGroupInviteBusy()) return
        scope.launch {
            if (!sessionMayContinue(ownerUserId)) return@launch
            updateState { it.copy(isGroupInviteBusy = true, errorMessage = null, infoMessage = null) }
            try {
                val result = if (accept) {
                    groupInviteAcceptor(inviteId)
                } else {
                    groupInviteDecliner(inviteId)
                }
                if (!sessionMayContinue(ownerUserId)) return@launch
                result.fold(
                    onSuccess = {
                        updateState { state ->
                            state.copy(
                                isGroupInviteBusy = false,
                                infoMessage = text(
                                    if (accept) ContactsMessage.CONTACTS_GROUP_INVITE_ACCEPTED else ContactsMessage.CONTACTS_GROUP_INVITE_DECLINED,
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
                                errorMessage = error.message ?: text(ContactsMessage.OPERATION_FAILED, emptyArray())
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
                        errorMessage = error.message ?: text(ContactsMessage.OPERATION_FAILED, emptyArray())
                    )
                }
            }
        }
    }
}
