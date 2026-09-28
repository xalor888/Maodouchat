package com.maodouchat.ui.screen.chatdetail

import android.util.Log
import com.maodouchat.R
import com.maodouchat.conversation.ConversationLocalCleanupMode
import com.maodouchat.conversation.ConversationLocalStateCoordinator
import com.maodouchat.conversation.conversationLocalCleanupSession
import com.maodouchat.messaging.v2.GroupMessagingCoordinator
import com.maodouchat.network.WebSocketEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * G350：`handleGroupRevisionChanged()` 从 `ChatDetailViewModel` 抽出（纯搬移不改判断）——
 * 群修订事件的编排：准入守卫（属主/门禁）→ 纯决策 [groupRevisionImpact]（活跃会话判
 * 断/被移除判别）→ sender key 失效（修订号推进）→ 被移出群时本地清理（关 AI 门/取消
 * 全部任务/删除本地会话/状态归零）→ 普通变更时警告文案 + 重载会话 + 刷新禁言状态。
 *
 * 依赖全经构造器注入（同 [ChatAttachmentSender]/[ChatRetrySender] 一族）：会话身份/
 * 状态读写/文案/本地状态协调器/实时控制器/AI 门与任务表/sender key 失效/回掉 `loadChat`
 * 与 `refreshMyMemberRole`。组合期外不持有 VM 引用，不新增状态所有权；日志 tag 保持
 * `ChatDetailViewModel` 不变。
 */
internal class ChatGroupRevisionHandler(
    private val ownerUserId: () -> String,
    private val activeChatId: () -> String,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val text: (Int) -> String,
    private val conversationLocalStateCoordinator: ConversationLocalStateCoordinator,
    private val realtimeController: ChatRealtimeController,
    private val semanticSearchGate: AiRequestGenerationGate,
    private val aiRewriteGate: AiRequestGenerationGate,
    private val aiReplyGate: AiRequestGenerationGate,
    private val groupAiGate: AiRequestGenerationGate,
    private val manualSummaryGate: AiRequestGenerationGate,
    private val semanticSearchJob: () -> Job?,
    private val aiRewriteStreamJob: () -> Job?,
    private val aiReplyStreamJob: () -> Job?,
    private val groupAiJob: () -> Job?,
    private val manualSummaryJob: () -> Job?,
    private val unreadSummaryJob: () -> Job?,
    private val aiOperationJobs: MutableMap<String, Job>,
    private val aiAutoRetryJobs: MutableMap<String, Job>,
    private val aiAutoRetryAt: MutableMap<String, Long>,
    private val groupMessagingCoordinator: GroupMessagingCoordinator,
    private val reloadChat: () -> Unit,
    private val refreshMyMemberRole: suspend (String) -> Unit,
) {
    /**
     * 处理群修订变更事件。参数与调用语义与原 `ChatDetailViewModel.handleGroupRevisionChanged`
     * 完全一致（`internal suspend`，同一事件类型）。
     */
    suspend fun handleGroupRevisionChanged(event: WebSocketEvent.GroupRevisionChanged) {
        val revisionOwnerUserId = ownerUserId()
        if (
            revisionOwnerUserId.isBlank() ||
            revisionOwnerUserId == "me" ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = revisionOwnerUserId,
            )
        ) {
            return
        }
        val cleanupSession = conversationLocalCleanupSession(revisionOwnerUserId)
        val impact = groupRevisionImpact(
            activeChatId = activeChatId(),
            currentUserId = revisionOwnerUserId,
            eventChatId = event.chatId,
            targetUserId = event.targetUserId,
            reason = event.reason
        )
        if (impact == GroupRevisionImpact.IGNORE) return
        val currentRevision = currentState().chat?.memberRevision ?: -1L
        val removedFromGroup = impact == GroupRevisionImpact.CURRENT_USER_REMOVED
        if (shouldInvalidateGroupKey(currentRevision, event.memberRevision, impact)) {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = revisionOwnerUserId,
            )
            ) {
                return
            }
            // 原 VM 私有 `invalidateGroupSenderKey` 的逐字等价：属主取入口处捕获值。
            groupMessagingCoordinator.invalidateSenderKey(
                chatId = event.chatId,
                ownerUserId = revisionOwnerUserId,
                newRevision = event.memberRevision,
            )
        }
        if (removedFromGroup) {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = revisionOwnerUserId,
            )
            ) {
                return
            }
            realtimeController.clearRemoteTyping()
            semanticSearchGate.invalidate()
            aiRewriteGate.invalidate()
            aiReplyGate.invalidate()
            groupAiGate.invalidate()
            manualSummaryGate.invalidate()
            semanticSearchJob()?.cancel()
            aiRewriteStreamJob()?.cancel()
            aiReplyStreamJob()?.cancel()
            groupAiJob()?.cancel()
            manualSummaryJob()?.cancel()
            unreadSummaryJob()?.cancel()
            aiOperationJobs.values.forEach { it.cancel() }
            aiOperationJobs.clear()
            aiAutoRetryJobs.values.forEach { it.cancel() }
            aiAutoRetryJobs.clear()
            aiAutoRetryAt.clear()
            val cleanup = withContext(Dispatchers.IO + NonCancellable) {
                conversationLocalStateCoordinator.cleanup(
                    chatId = event.chatId,
                    expectedSession = cleanupSession,
                    mode = ConversationLocalCleanupMode.DELETE_CONVERSATION,
                )
            }
            cleanup.failures.forEach { failure ->
                Log.w(
                    "ChatDetailViewModel",
                    "group removal cleanup failed at ${failure.step} for ${event.chatId}",
                    failure.error,
                )
            }
            if (!cleanup.completed) return
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = revisionOwnerUserId,
            )
            ) {
                return
            }
            updateState {
                it.copy(
                    chat = null,
                    chatIsGroup = false,
                    messages = emptyList(),
                    isAiWorking = false,
                    isAiDraftStreaming = false,
                    isAiReplyStreaming = false,
                    isSemanticSearching = false,
                    isUnreadSummaryLoading = false,
                    groupEncryptionWarning = text(R.string.chat_left_group_key_cleared)
                )
            }
            return
        }
        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = revisionOwnerUserId,
        )
        ) {
            return
        }
        val warning = when (event.reason) {
            "MEMBER_ADDED", "MEMBER_REMOVED", "MEMBER_LEFT" -> text(R.string.chat_group_members_changed_key)
            "GROUP_RENAMED" -> text(R.string.chat_group_name_updated)
            "ROLE_UPDATED" -> text(R.string.chat_group_role_updated)
            "TITLE_UPDATED" -> text(R.string.chat_group_title_updated)
            "NICKNAME_UPDATED" -> text(R.string.chat_group_nickname_updated)
            "ANNOUNCEMENT_UPDATED" -> text(R.string.chat_group_announcement_updated)
            "MUTE_UPDATED" -> text(R.string.chat_group_mute_updated)
            else -> text(R.string.chat_group_info_updated)
        }
        updateState { it.copy(groupEncryptionWarning = warning) }
        reloadChat()
        // 8.48：群变更（含禁言/解禁 MUTE_UPDATED）后刷新本机禁言状态提示——
        // 否则成员在 GroupDetail 被禁言/解禁后，聊天页提示不会更新直到重新进入
        refreshMyMemberRole(revisionOwnerUserId)
    }
}
