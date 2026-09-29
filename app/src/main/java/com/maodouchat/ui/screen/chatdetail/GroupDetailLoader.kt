package com.maodouchat.ui.screen.chatdetail

import android.util.Log
import com.maodouchat.R
import com.maodouchat.group.GroupAuditController
import com.maodouchat.group.GroupBotController
import com.maodouchat.group.GroupDetailUiState
import com.maodouchat.group.GroupEncryptionHealthController
import com.maodouchat.group.GroupLifecycleService
import com.maodouchat.group.toUi
import com.maodouchat.group.GroupMutationAction
import com.maodouchat.group.GroupMutationFeedbackPolicy
import com.maodouchat.messaging.v2.GroupMessagingCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * G369：群详情「整页加载」从 `GroupDetailViewModel` 抽出（纯搬移不改判断）——群信息/成员/
 * sender key 状态/审计首页/候选成员/机器人的 IO 拉取与状态写回，含 8.39/8.49 的既有修复。
 */
internal class GroupDetailLoader(
    private val scope: CoroutineScope,
    private val currentState: () -> GroupDetailUiState,
    private val updateState: ((GroupDetailUiState) -> GroupDetailUiState) -> Unit,
    private val textFn: (Int, Array<out Any>) -> String,
    private val chatId: () -> String,
    private val token: () -> String,
    private val ownerUserId: () -> String,
    private val groupLifecycleService: GroupLifecycleService,
    private val groupEncryptionHealthController: GroupEncryptionHealthController,
    private val groupAuditController: GroupAuditController,
    private val groupBotController: GroupBotController,
    private val groupMessagingCoordinator: GroupMessagingCoordinator,
    private val auditOffsetSet: (Int) -> Unit,
    private val pendingRetrySet: ((() -> Unit)?) -> Unit,
    private val onAutoRedistribute: (GroupDetailUiState) -> Unit,
) {
    fun load(feedbackMessage: String? = null) {
        val loadOwnerUserId = ownerUserId()
        if (chatId().isBlank() || token().isBlank() || loadOwnerUserId.isBlank()) {
            // Default isLoading=true; never leave the spinner stuck when session/chat is missing.
            updateState {
                it.copy(
                    isLoading = false,
                    message = text(R.string.error_session_expired),
                    feedback = GroupMutationFeedbackPolicy.fromThrowable(
                        GroupMutationAction.LOAD,
                        IllegalStateException(text(R.string.error_session_expired))
                    )
                )
            }
            return
        }
        scope.launch {
            updateState { it.copy(isLoading = true, message = feedbackMessage, feedback = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = loadOwnerUserId,
                )
                ) {
                    updateState {
                        it.copy(
                            isLoading = false,
                            message = text(R.string.error_session_expired),
                            feedback = GroupMutationFeedbackPolicy.fromThrowable(
                                GroupMutationAction.LOAD,
                                IllegalStateException(text(R.string.error_session_expired))
                            )
                        )
                    }
                    return@launch
                }
                val loaded = withContext(Dispatchers.IO) {
                    try {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = loadOwnerUserId,
                        )
                        ) {
                            throw kotlinx.coroutines.CancellationException("group_load_session_changed")
                        }
                        val chat = groupLifecycleService.fetchGroupDetails(chatId()).getOrThrow()
                        val members = groupLifecycleService.fetchGroupMembers(chatId()).getOrThrow().map { it.toUi() }
                        val senderKeyStatus = groupEncryptionHealthController.fetchSenderKeyStatus(chatId()).getOrNull()
                        // 8.39：显式传 limit=100（服务端上限）——此前不传走默认 50，
                        // UI「展开更多」阈值 80 永不触发，审计历史被静默截断
                        val auditLogs = groupAuditController.fetchAuditLogs(chatId(), limit = 100, offset = 0).getOrDefault(emptyList())
                        // 8.49：记录服务端已返回的原始条数（见 loadMoreAuditLogs 注释）
                        auditOffsetSet(auditLogs.size)
                        val memberIds = members.map { it.userId }.toSet()
                        val candidates = groupLifecycleService.fetchCandidates(memberIds, loadOwnerUserId).getOrDefault(emptyList())
                        val ownedBots = groupBotController.fetchCandidateBots().getOrDefault(emptyList())
                        val self = members.firstOrNull { it.userId == loadOwnerUserId }
                        val secret = try {
                            chat?.isSecret == true ||
                                com.maodouchat.security.SecretChatCapabilities.forChat(chatId()).isSecretChat
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            true
                        }
                        if (secret) {
                            com.maodouchat.security.SecretChatSession.markSurfaceActive(chatId())
                        }
                        Result.success(
                            GroupDetailUiState(
                                groupName = chat?.groupName?.takeIf { it.isNotBlank() } ?: text(R.string.chat_group),
                                groupAnnouncement = chat?.groupAnnouncement.orEmpty(),
                                groupAvatar = chat?.groupAvatar,
                                memberRevision = chat?.memberRevision ?: 0,
                                members = members,
                                candidates = candidates,
                                senderKeyStatus = senderKeyStatus,
                                auditLogs = auditLogs,
                                hasMoreAudit = auditLogs.size >= 100,
                                currentUserId = loadOwnerUserId,
                                myRole = self?.role ?: "MEMBER",
                                myNickname = self?.groupNickname.orEmpty(),
                                isLoading = false,
                                isSecretChat = secret,
                                isChannel = chat?.isChannel == true,
                                ownedBots = ownedBots,
                                // 8.48 修复 H1：本机是否实际持有分发（重装/换机后服务端记录仍在但本地无 key）
                                localHasSenderKey = runCatching {
                                    groupMessagingCoordinator.hasLocalSenderKey(
                                        chatId(),
                                        chat?.memberRevision ?: 0L,
                                    )
                                }.getOrDefault(false),
                            )
                        )
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                }
                loaded.fold(
                    onSuccess = { next ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = loadOwnerUserId,
                        )
                        ) {
                            return@fold
                        }
                        pendingRetrySet(null)
                        val successFeedback = feedbackMessage?.let {
                            GroupMutationFeedbackPolicy.success(GroupMutationAction.LOAD, it)
                        }
                        // 8.48 修复 H2：load() 重建状态不得清空在途/已生成的邀请字段——
                        // 此前 GroupRevisionChanged 或任意群操作后 load 会把 groupInvitePayload 等
                        // 重置为空（邀请对话框 QR 变失败、用量归零），且直赋值覆盖并发 loadGroupInvite 响应。
                        val prevInvite = currentState()
                        updateState {
                            next.copy(
                                message = feedbackMessage,
                                feedback = successFeedback,
                                groupInvitePayload = prevInvite.groupInvitePayload,
                                inviteExpiresAt = prevInvite.inviteExpiresAt,
                                inviteMaxUses = prevInvite.inviteMaxUses,
                                inviteUsedCount = prevInvite.inviteUsedCount,
                                inviteRemainingUses = prevInvite.inviteRemainingUses,
                                isLoadingInvite = prevInvite.isLoadingInvite
                            )
                        }
                        onAutoRedistribute(next)
                    },
                    onFailure = { error ->
                        val fb = GroupMutationFeedbackPolicy.fromThrowable(GroupMutationAction.LOAD, error)
                        pendingRetrySet { load() }
                        updateState {
                            it.copy(
                                isLoading = false,
                                message = fb.detail ?: text(R.string.group_detail_load_failed),
                                feedback = fb.copy(canRetry = true)
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                updateState { it.copy(isLoading = false) }
                throw error
            }
        }
    }

    private fun text(id: Int, vararg args: Any): String = textFn(id, args)
}
