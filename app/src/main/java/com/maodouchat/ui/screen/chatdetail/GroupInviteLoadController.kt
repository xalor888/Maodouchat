package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.group.GroupDetailUiState
import com.maodouchat.group.GroupInviteController
import com.maodouchat.group.GroupMutationAction
import com.maodouchat.group.GroupMutationFeedbackPolicy
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * G367：群邀请链接的拉取/轮换从 `GroupDetailViewModel` 抽出（纯搬移不改判断）——
 * `groupInviteController` 的所有权随之内聚（原 VM 字段仅此一处使用）；
 * pendingRetry 经 set lambda 共享。
 */
internal class GroupInviteLoadController(
    private val scope: CoroutineScope,
    private val application: android.app.Application,
    private val currentState: () -> GroupDetailUiState,
    private val updateState: ((GroupDetailUiState) -> GroupDetailUiState) -> Unit,
    private val textFn: (Int, Array<out Any>) -> String,
    private val chatId: () -> String,
    private val token: () -> String,
    private val ownerUserId: () -> String,
    private val pendingRetrySet: ((() -> Unit)?) -> Unit,
) {
    private val groupInviteController = GroupInviteController(
        tokenProvider = { com.maodouchat.session.CurrentSession.snapshot().token.orEmpty() }
    )

    fun loadGroupInvite(rotate: Boolean = false, expiresInSeconds: Long = 7L * 24L * 60L * 60L, maxUses: Int = 100) {
        if (!RuntimeFlags.isEnabled(application, RuntimeFlags.GROUP_INVITES)) {
            return
        }
        if (chatId().isBlank() || token().isBlank()) {
            updateState {
                it.copy(
                    isLoadingInvite = false,
                    message = text(R.string.error_session_expired),
                    feedback = GroupMutationFeedbackPolicy.fromThrowable(
                        GroupMutationAction.INVITE,
                        IllegalStateException(text(R.string.error_session_expired))
                    )
                )
            }
            return
        }
        val inviteOwnerUserId = ownerUserId()
        scope.launch {
            updateState { it.copy(isLoadingInvite = true, message = null, feedback = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = inviteOwnerUserId,
                )
                ) {
                    updateState {
                        it.copy(
                            isLoadingInvite = false,
                            message = text(R.string.error_session_expired),
                            feedback = GroupMutationFeedbackPolicy.fromThrowable(
                                GroupMutationAction.INVITE,
                                IllegalStateException(text(R.string.error_session_expired))
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
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = inviteOwnerUserId,
                        )
                        ) {
                            updateState { it.copy(isLoadingInvite = false) }
                            return@fold
                        }
                        pendingRetrySet(null)
                        val msg = if (rotate) text(R.string.group_detail_invite_refreshed) else null
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
                                message = fb.detail ?: text(R.string.group_detail_invite_failed),
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

    private fun text(id: Int, vararg args: Any): String = textFn(id, args)
}
