package com.maodouchat.domain.groups

import com.maodouchat.core.model.GroupDetailUiState
import com.maodouchat.core.model.GroupMutationAction
import com.maodouchat.core.model.GroupMutationFeedbackPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * G367：群邀请链接的拉取/轮换从 `GroupDetailViewModel` 抽出（纯搬移不改判断）——
 * 邀请控制器由调用方注入，pendingRetry 经 set lambda 共享。
 */
class GroupInviteLoadController(
    private val scope: CoroutineScope,
    private val currentState: () -> GroupDetailUiState,
    private val updateState: ((GroupDetailUiState) -> GroupDetailUiState) -> Unit,
    private val text: (GroupDetailLoadMessage) -> String,
    private val sessionActive: (String) -> Boolean,
    private val invitesEnabled: () -> Boolean,
    private val groupInviteController: GroupInviteController,
    private val chatId: () -> String,
    private val token: () -> String,
    private val ownerUserId: () -> String,
    private val pendingRetrySet: ((() -> Unit)?) -> Unit,
) {
    fun loadGroupInvite(rotate: Boolean = false, expiresInSeconds: Long = 7L * 24L * 60L * 60L, maxUses: Int = 100) {
        if (!invitesEnabled()) {
            return
        }
        if (chatId().isBlank() || token().isBlank()) {
            updateState {
                it.copy(
                    isLoadingInvite = false,
                    message = text(GroupDetailLoadMessage.SESSION_EXPIRED),
                    feedback = GroupMutationFeedbackPolicy.fromThrowable(
                        GroupMutationAction.INVITE,
                        IllegalStateException(text(GroupDetailLoadMessage.SESSION_EXPIRED))
                    )
                )
            }
            return
        }
        val inviteOwnerUserId = ownerUserId()
        scope.launch {
            updateState { it.copy(isLoadingInvite = true, message = null, feedback = null) }
            try {
                if (!sessionActive(inviteOwnerUserId)) {
                    updateState {
                        it.copy(
                            isLoadingInvite = false,
                            message = text(GroupDetailLoadMessage.SESSION_EXPIRED),
                            feedback = GroupMutationFeedbackPolicy.fromThrowable(
                                GroupMutationAction.INVITE,
                                IllegalStateException(text(GroupDetailLoadMessage.SESSION_EXPIRED))
                            )
                        )
                    }
                    return@launch
                }
                val result = if (rotate) {
                    groupInviteController.rotateInvite(chatId(), expiresInSeconds, maxUses)
                } else {
                    groupInviteController.fetchInvite(chatId(), rotate = false, expiresInSeconds, maxUses)
                }
                result.fold(
                    onSuccess = { res ->
                        if (!sessionActive(inviteOwnerUserId)) {
                            updateState { it.copy(isLoadingInvite = false) }
                            return@fold
                        }
                        pendingRetrySet(null)
                        val msg = if (rotate) text(GroupDetailLoadMessage.INVITE_REFRESHED) else null
                        updateState {
                            it.copy(
                                isLoadingInvite = false,
                                groupInvitePayload = res.payload,
                                inviteExpiresAt = res.expiresAt,
                                inviteMaxUses = res.maxUses,
                                inviteUsedCount = res.usedCount,
                                inviteRemainingUses = res.remainingUses,
                                message = msg,
                                feedback = if (msg != null) {
                                    GroupMutationFeedbackPolicy.success(GroupMutationAction.INVITE, msg)
                                } else null
                            )
                        }
                    },
                    onFailure = { error ->
                        val fb = GroupMutationFeedbackPolicy.fromThrowable(GroupMutationAction.INVITE, error)
                        pendingRetrySet { loadGroupInvite(rotate, expiresInSeconds, maxUses) }
                        updateState {
                            it.copy(
                                isLoadingInvite = false,
                                message = fb.detail ?: text(GroupDetailLoadMessage.INVITE_FAILED),
                                feedback = fb.copy(canRetry = true)
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                updateState { it.copy(isLoadingInvite = false) }
                throw error
            }
        }
    }
}
