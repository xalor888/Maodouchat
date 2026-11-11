package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiCostVisibilityPolicy
import com.maodouchat.core.model.AiOperationUi
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// AI 操作观察与结果提交：操作流投屏、会话切走时丢弃，以及中断恢复/过期清理。
// 从 ChatDetailViewModel 纯搬移；仓库/CurrentSession/会话门禁走端口，VM 只留同签名委托。
// 操作记录以 AiOperationRecord 过界（Room 实体不出 domain）。
data class AiOperationRecord(
    val id: String,
    val type: String,
    val state: String,
    val attempts: Int,
    val lastErrorCode: String?,
)

class ChatAiOperationsController(
    private val scope: CoroutineScope,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val activeChatId: () -> String,
    private val currentOwnerUserId: () -> String,
    private val sessionMayContinue: (String) -> Boolean,
    private val observeOperations: (String, String) -> Flow<List<AiOperationRecord>>,
    private val recoverInterrupted: suspend (String, String) -> Int,
    private val pruneTerminalOperations: suspend () -> Int,
    private val pruneSummariesOlderThan: suspend (Long) -> Unit,
    private val pruneCompletedTasksOlderThan: suspend (Long) -> Unit,
    private val commitMessageResult: suspend (String?, Message) -> Boolean,
    private val aiAutoRetryJobs: MutableMap<String, Job>,
    private val aiAutoRetryAt: MutableMap<String, Long>,
) {
    fun observeAiOperations() {
        val ownerUserId = currentOwnerUserId().takeIf(String::isNotBlank) ?: return
        observeOperations(ownerUserId, activeChatId())
            .onEach { operations ->
                // Drop if logout/switch happened while Room Flow was still open.
                if (currentOwnerUserId() != ownerUserId) return@onEach
                updateState { state ->
                    state.copy(
                        aiOperations = operations.map { operation ->
                            val waitSeconds = AiCostVisibilityPolicy
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
            if (currentOwnerUserId() != ownerUserId) return@launch
            recoverInterrupted(ownerUserId, activeChatId())
            pruneTerminalOperations()
            // AI 本地缓存保留期清理：总结缓存 90 天，已完成任务 90 天
            val cutoff = System.currentTimeMillis() - 90L * 24L * 60L * 60L * 1_000L
            try {
                pruneSummariesOlderThan(cutoff)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
            try {
                pruneCompletedTasksOlderThan(cutoff)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    suspend fun commitAiMessageResult(
        operationId: String?,
        message: Message,
        expectedUserId: String,
        expectedChatId: String,
    ): Boolean {
        if (message.chatId != expectedChatId || activeChatId() != expectedChatId ||
            !sessionMayContinue(expectedUserId)
        ) throw CancellationException("ai_result_context_changed")
        val committed = withContext(Dispatchers.IO) {
            commitMessageResult(operationId, message)
        }
        if (!committed) return false
        if (activeChatId() != expectedChatId ||
            !sessionMayContinue(expectedUserId)
        ) return false
        if (operationId != null) {
            aiAutoRetryJobs.remove(operationId)?.cancel()
            aiAutoRetryAt.remove(operationId)
        }
        return true
    }
}
