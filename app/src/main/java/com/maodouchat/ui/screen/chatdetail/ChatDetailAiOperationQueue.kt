package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import com.maodouchat.ai.AiPrivacyPreferences
import com.maodouchat.ai.AiRetryPolicy
import com.maodouchat.data.local.entity.AiOperationError
import com.maodouchat.data.local.entity.AiOperationState
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

internal suspend fun ChatDetailViewModel.pumpAiOperationQueue() {
    val app = getApplication<Application>()
    aiOperationQueueMutex.withLock {
        val ownerUserId = com.maodouchat.session.CurrentSession.snapshot().userId?.takeIf(String::isNotBlank) ?: return
        if (!_uiState.value.aiEnabled || !com.maodouchat.ai.AiPrivacyPreferences.consentAccepted(app)) return
        if (aiOperationRepo.getRunning(ownerUserId, activeChatId) != null) return
        val next = aiOperationRepo.getNextQueued(ownerUserId, activeChatId) ?: return
        val action = restoreAiAction(next)
        if (action == null) {
            aiOperationRepo.markFailed(next.id, AiOperationError.CONTEXT_MISSING)
        } else if (aiOperationRepo.markRunning(next.id)) {
            withContext(Dispatchers.Main) { dispatchAiAction(action, next.id) }
            return
        } else {
            return
        }
    }
    pumpAiOperationQueue()
}

internal fun ChatDetailViewModel.launchTrackedAiOperation(
    operationId: String?,
    startImmediately: Boolean = true,
    block: suspend () -> Unit
): kotlinx.coroutines.Job {
    val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
        try {
            if (operationId != null) {
                val operation = withContext(Dispatchers.IO) { aiOperationRepo.get(operationId) }
                if (operation?.state != AiOperationState.RUNNING) return@launch
            }
            block()
        } catch (error: kotlinx.coroutines.CancellationException) {
            // Job cancel must still persist CANCELLED + clear loading chips; plain withContext is cancelled.
            if (operationId != null) {
                val operation = withContext(Dispatchers.IO + NonCancellable) {
                    val current = aiOperationRepo.get(operationId)
                    if (current?.state == AiOperationState.RUNNING) {
                        aiOperationRepo.markCancelled(operationId)
                    }
                    current
                }
                if (operation != null) {
                    withContext(Dispatchers.Main.immediate + NonCancellable) {
                        clearAiOperationUi(operation)
                    }
                }
            }
            throw error
        } finally {
            if (operationId != null) {
                withContext(Dispatchers.IO + NonCancellable) { pumpAiOperationQueue() }
            }
        }
    }
    if (operationId != null) {
        aiOperationJobs[operationId] = job
        job.invokeOnCompletion { aiOperationJobs.remove(operationId, job) }
    }
    if (startImmediately) job.start()
    return job
}

internal suspend fun ChatDetailViewModel.completeAiOperation(operationId: String?): Boolean =
    operationId == null || withContext(Dispatchers.IO) {
        aiAutoRetryJobs.remove(operationId)?.cancel()
        aiAutoRetryAt.remove(operationId)
        aiOperationRepo.markSucceeded(operationId)
    }

internal suspend fun ChatDetailViewModel.failAiOperation(operationId: String?, errorCode: String) {
    if (operationId == null) return
    withContext(Dispatchers.IO) {
        val operation = aiOperationRepo.get(operationId) ?: return@withContext
        val decision = AiRetryPolicy.decide(errorCode, operation.attempts)
        if (decision.shouldRetry) {
            val retryAt = System.currentTimeMillis() + decision.delayMs
            aiAutoRetryAt[operationId] = retryAt
            _uiState.update { state ->
                state.copy(aiOperations = state.aiOperations.map {
                    if (it.id == operationId) {
                        it.copy(
                            state = AiOperationState.FAILED,
                            lastErrorCode = errorCode,
                            nextRetryAtMs = retryAt
                        )
                    } else it
                })
            }
            aiOperationRepo.markFailed(operationId, errorCode)
            val retryJob = viewModelScope.launch {
                delay(decision.delayMs)
                aiAutoRetryAt.remove(operationId)
                val requeued = withContext(Dispatchers.IO) { aiOperationRepo.markQueued(operationId) }
                if (requeued) withContext(Dispatchers.IO) { pumpAiOperationQueue() }
            }
            aiAutoRetryJobs.put(operationId, retryJob)?.cancel()
            retryJob.invokeOnCompletion { aiAutoRetryJobs.remove(operationId, retryJob) }
        } else {
            aiAutoRetryAt.remove(operationId)
            aiOperationRepo.markFailed(operationId, errorCode)
        }
    }
}

internal fun ChatDetailViewModel.aiOperationErrorCode(error: Throwable): String =
    classifyAiOperationErrorDetailed(error).persistedCode
