package com.maodouchat.domain.messaging

class SenderKeyRetryTaskQueue(
    private val store: SenderKeyRetryTaskStore,
    private val sessionActive: (String) -> Boolean,
    private val refreshSchedule: suspend (String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val jitterFactor: () -> Double = { 0.5 }
) {
    /** 调用方必须已持有 processMutex（ensureCoverageNow 锁内失败路径直接复用，避免重入死锁）。 */
    suspend fun enqueueLocked(
        ownerUserId: String,
        chatId: String,
        epoch: Long,
        reason: String,
        delayMs: Long
    ) {
        val now = nowMs()
        val existing = store.get(ownerUserId, chatId)
        // 8.41：同 epoch 重入不得把退避时间拉回 30s——覆盖问题持续期间每次发送都调 enqueue，
        // 无条件重置会冲掉指数退避造成 SKDM 风暴；仅新 epoch 或无任务时重置
        val freshEpoch = existing == null || existing.epoch != epoch
        val nextAttemptAt = if (freshEpoch) {
            now + delayMs
        } else {
            // existing 在 freshEpoch=false 时必非空（同 epoch），消除冗余 !!
            maxOf(existing.nextAttemptAt, now + delayMs)
        }
        store.upsert(
            SenderKeyRetryTask(
                ownerUserId = ownerUserId,
                chatId = chatId,
                epoch = epoch,
                reason = reason.take(80),
                attempts = if (existing?.epoch == epoch) existing.attempts else 0,
                nextAttemptAt = nextAttemptAt,
                lastError = existing?.lastError,
                updatedAt = now
            )
        )
    }

    suspend fun markFailure(task: SenderKeyRetryTask, error: Throwable) {
        if (!sessionActive(task.ownerUserId)) return
        val now = nowMs()
        val attempts = task.attempts + 1
        // 弱网退避细化：区分网络错误与服务器/协议错误，网络错误用更短退避 + 更多重试
        val isNetworkError = SenderKeyRetryPolicy.isTransientNetworkError(error)
        if (SenderKeyRetryPolicy.shouldAbandon(attempts, isNetworkError)) {
            store.upsert(
                task.copy(
                    attempts = attempts,
                    nextAttemptAt = Long.MAX_VALUE,
                    lastError = (error.message ?: "retry failed").take(200),
                    updatedAt = now
                )
            )
            if (sessionActive(task.ownerUserId)) refreshSchedule(task.ownerUserId)
            return
        }
        // 指数退避 + 抖动（±25%），避免弱网下多设备同时重试的惊群效应
        val delayMs = SenderKeyRetryPolicy.retryDelayMs(attempts, isNetworkError, jitterFactor())
        store.upsert(
            task.copy(
                attempts = attempts,
                nextAttemptAt = now + delayMs,
                lastError = (error.message ?: "retry failed").take(200),
                updatedAt = now
            )
        )
        if (sessionActive(task.ownerUserId)) refreshSchedule(task.ownerUserId)
    }

    /** 瞬态网络错误判定（弱网退避与 8.37 破坏性操作防护共用）。 */
    fun isTransientNetworkError(error: Throwable): Boolean =
        SenderKeyRetryPolicy.isTransientNetworkError(error)
}
