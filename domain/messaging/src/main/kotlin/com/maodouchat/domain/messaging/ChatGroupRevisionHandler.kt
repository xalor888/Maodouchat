package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ConversationLocalCleanupMode
import com.maodouchat.core.model.ConversationLocalCleanupSession
import com.maodouchat.core.model.ConversationLocalStateCoordinator
import com.maodouchat.core.model.AiRequestGenerationGate
import com.maodouchat.core.model.GroupRevisionImpact
import com.maodouchat.core.model.groupRevisionImpact
import com.maodouchat.core.model.shouldInvalidateGroupKey
import com.maodouchat.core.model.GroupMessagingCoordinator
import com.maodouchat.core.model.WebSocketEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.maodouchat.core.model.ChatDetailUiState

// 群修订事件处理：被移出群 / 成员变更 / 改名等事件的清理与提示。
// BackgroundSessionGate/清理会话/实时打字清理/IO 调度/Log/R 文案走端口；其余逻辑逐字平移。
class ChatGroupRevisionHandler(
    private val ownerUserId: () -> String,
    private val activeChatId: () -> String,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val text: (ChatGroupRevisionText) -> String,
    private val sessionMayContinue: (String) -> Boolean,
    private val cleanupSessionFor: (String) -> ConversationLocalCleanupSession,
    private val ioDispatcher: CoroutineDispatcher,
    private val logWarning: (String, Throwable?) -> Unit,
    private val conversationLocalStateCoordinator: ConversationLocalStateCoordinator,
    private val clearRemoteTyping: () -> Unit,
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
    suspend fun handleGroupRevisionChanged(event: WebSocketEvent.GroupRevisionChanged) {
        val revisionOwnerUserId = ownerUserId()
        if (
            revisionOwnerUserId.isBlank() ||
            revisionOwnerUserId == "me" ||
            !sessionMayContinue(revisionOwnerUserId)
        ) {
            return
        }
        val cleanupSession = cleanupSessionFor(revisionOwnerUserId)
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
            if (!sessionMayContinue(revisionOwnerUserId)) {
                return
            }
            groupMessagingCoordinator.invalidateSenderKey(
                chatId = event.chatId,
                ownerUserId = revisionOwnerUserId,
                newRevision = event.memberRevision,
            )
        }
        if (removedFromGroup) {
            if (!sessionMayContinue(revisionOwnerUserId)) {
                return
            }
            clearRemoteTyping()
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
            val cleanup = withContext(ioDispatcher + NonCancellable) {
                conversationLocalStateCoordinator.cleanup(
                    chatId = event.chatId,
                    expectedSession = cleanupSession,
                    mode = ConversationLocalCleanupMode.DELETE_CONVERSATION,
                )
            }
            cleanup.failures.forEach { failure ->
                logWarning("group removal cleanup failed at ${failure.step} for ${event.chatId}", failure.error)
            }
            if (!cleanup.completed) return
            if (!sessionMayContinue(revisionOwnerUserId)) {
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
                    groupEncryptionWarning = text(ChatGroupRevisionText.LEFT_GROUP_KEY_CLEARED)
                )
            }
            return
        }
        if (!sessionMayContinue(revisionOwnerUserId)
        ) {
            return
        }
        val warning = when (event.reason) {
            "MEMBER_ADDED", "MEMBER_REMOVED", "MEMBER_LEFT" -> text(ChatGroupRevisionText.GROUP_MEMBERS_CHANGED_KEY)
            "GROUP_RENAMED" -> text(ChatGroupRevisionText.GROUP_NAME_UPDATED)
            "ROLE_UPDATED" -> text(ChatGroupRevisionText.GROUP_ROLE_UPDATED)
            "TITLE_UPDATED" -> text(ChatGroupRevisionText.GROUP_TITLE_UPDATED)
            "NICKNAME_UPDATED" -> text(ChatGroupRevisionText.GROUP_NICKNAME_UPDATED)
            "ANNOUNCEMENT_UPDATED" -> text(ChatGroupRevisionText.GROUP_ANNOUNCEMENT_UPDATED)
            "MUTE_UPDATED" -> text(ChatGroupRevisionText.GROUP_MUTE_UPDATED)
            else -> text(ChatGroupRevisionText.GROUP_INFO_UPDATED)
        }
        updateState { it.copy(groupEncryptionWarning = warning) }
        reloadChat()
        // 8.48：群变更（含禁言/解禁 MUTE_UPDATED）后刷新本机禁言状态提示——
        // 否则成员在 GroupDetail 被禁言/解禁后，聊天页提示不会更新直到重新进入
        refreshMyMemberRole(revisionOwnerUserId)
    }
}
