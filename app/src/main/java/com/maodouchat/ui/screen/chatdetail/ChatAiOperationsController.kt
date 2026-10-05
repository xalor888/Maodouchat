package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.repository.AiMessageResultStore
import com.maodouchat.data.repository.AiOperationRepository
import com.maodouchat.data.repository.AiSummaryRepository
import com.maodouchat.data.repository.AiTaskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// AI 操作观察与结果提交：操作流投屏、会话切走时丢弃，以及中断恢复/过期清理。
// 从 ChatDetailViewModel 纯搬移；VM 只留同签名委托。
internal class ChatAiOperationsController(
    private val scope: CoroutineScope,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val activeChatId: () -> String,
    private val aiOperationRepo: AiOperationRepository,
    private val aiMessageResultStore: AiMessageResultStore,
    private val aiSummaryRepo: AiSummaryRepository,
    private val aiTaskRepo: AiTaskRepository,
    private val aiAutoRetryJobs: MutableMap<String, Job>,
    private val aiAutoRetryAt: MutableMap<String, Long>,
) {
    internal fun observeAiOperations() {
        val ownerUserId = com.maodouchat.session.CurrentSession.snapshot().userId?.takeIf(String::isNotBlank) ?: return
        aiOperationRepo.observeActionable(ownerUserId, activeChatId())
            .onEach { operations ->
                // Drop if logout/switch happened while Room Flow was still open.
                if (com.maodouchat.session.CurrentSession.ownerUserId() != ownerUserId) return@onEach
                updateState { state ->
                    state.copy(
                        aiOperations = operations.map { operation ->
                            val waitSeconds = com.maodouchat.ai.AiCostVisibilityPolicy
                                .waitSecondsFor(operation.lastErrorCode)
                                .takeIf { it > 0L }
                            AiOperationUi(
                                id = operation.id,
                                type = operation.type,
                                state = operation.state,
                                attempts = operation.attempts,
                                lastErrorCode = operation.lastErrorCode,
                                nextRetryAtMs = aiAutoRetryAt[operation.id],
                                retryAfterSeconds = waitSeconds
                            )
                        }
                    )
                }
            }
            .launchIn(scope)
        scope.launch(Dispatchers.IO) {
            if (com.maodouchat.session.CurrentSession.ownerUserId() != ownerUserId) return@launch
            aiOperationRepo.recoverInterrupted(ownerUserId, activeChatId())
            aiOperationRepo.pruneTerminal()
            // AI 本地缓存保留期清理：总结缓存 90 天，已完成任务 90 天
            val cutoff = System.currentTimeMillis() - 90L * 24L * 60L * 60L * 1_000L
            try {
                aiSummaryRepo.pruneOlderThan(cutoff)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
            try {
                aiTaskRepo.pruneCompletedOlderThan(cutoff)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    internal suspend fun commitAiMessageResult(
        operationId: String?,
        message: Message,
        expectedUserId: String,
        expectedChatId: String,
    ): Boolean {
        if (message.chatId != expectedChatId || activeChatId() != expectedChatId ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = expectedUserId,
            )
        ) throw CancellationException("ai_result_context_changed")
        val committed = withContext(Dispatchers.IO) {
            aiMessageResultStore.commit(operationId, message)
        }
        if (!committed) return false
        if (activeChatId() != expectedChatId ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = expectedUserId,
            )
        ) return false
        if (operationId != null) {
            aiAutoRetryJobs.remove(operationId)?.cancel()
            aiAutoRetryAt.remove(operationId)
        }
        return true
    }
}
