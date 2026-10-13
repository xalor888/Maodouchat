package com.maodouchat.crypto

import com.maodouchat.data.local.dao.SenderKeyRetryDao
import com.maodouchat.data.local.entity.SenderKeyRetryEntity

class SenderKeyRetryTaskQueue(
    private val retryDao: SenderKeyRetryDao,
    private val sessionActive: (String) -> Boolean,
    private val refreshSchedule: suspend (String) -> Unit
) {
    /** 调用方必须已持有 processMutex（ensureCoverageNow 锁内失败路径直接复用，避免重入死锁）。 */
    suspend fun enqueueLocked(
        ownerUserId: String,
        chatId: String,
        epoch: Long,
        reason: String,
        delayMs: Long
    ) {
        val now = System.currentTimeMillis()
        val existing = retryDao.get(ownerUserId, chatId)
        // 8.41：同 epoch 重入不得把退避时间拉回 30s——覆盖问题持续期间每次发送都调 enqueue，
        // 无条件重置会冲掉指数退避造成 SKDM 风暴；仅新 epoch 或无任务时重置
        val freshEpoch = existing == null || existing.epoch != epoch
        val nextAttemptAt = if (freshEpoch) {
            now + delayMs
        } else {
            // existing 在 freshEpoch=false 时必非空（同 epoch），消除冗余 !!
            maxOf(existing.nextAttemptAt, now + delayMs)
        }
        retryDao.upsert(
            SenderKeyRetryEntity(
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

    suspend fun markFailure(task: SenderKeyRetryEntity, error: Throwable) {
        if (!sessionActive(task.ownerUserId)) return
        val attempts = task.attempts + 1
        // 弱网退避细化：区分网络错误与服务器/协议错误，网络错误用更短退避 + 更多重试
        val isNetworkError = isTransientNetworkError(error)
        val maxAttempts = if (isNetworkError) MAX_ATTEMPTS_NETWORK else MAX_ATTEMPTS
        if (attempts >= maxAttempts) {
            retryDao.upsert(
                task.copy(
                    attempts = attempts,
                    nextAttemptAt = Long.MAX_VALUE,
                    lastError = (error.message ?: "retry failed").take(200),
                    updatedAt = System.currentTimeMillis()
                )
            )
            if (sessionActive(task.ownerUserId)) refreshSchedule(task.ownerUserId)
            return
        }
        // 指数退避 + 抖动（±25%），避免弱网下多设备同时重试的惊群效应
        val baseDelay = if (isNetworkError) NETWORK_INITIAL_DELAY_MS else INITIAL_DELAY_MS
        val maxDelay = if (isNetworkError) NETWORK_MAX_DELAY_MS else MAX_DELAY_MS
        val expDelay = (baseDelay * (1L shl (attempts - 1))).coerceAtMost(maxDelay)
        val jitter = (expDelay * 0.25 * (Math.random() - 0.5) * 2).toLong()
        val delayMs = (expDelay + jitter).coerceAtLeast(baseDelay / 2)
        retryDao.upsert(
            task.copy(
                attempts = attempts,
                nextAttemptAt = System.currentTimeMillis() + delayMs,
                lastError = (error.message ?: "retry failed").take(200),
                updatedAt = System.currentTimeMillis()
            )
        )
        if (sessionActive(task.ownerUserId)) refreshSchedule(task.ownerUserId)
    }

    /** 瞬态网络错误判定（弱网退避与 8.37 破坏性操作防护共用）。 */
    fun isTransientNetworkError(error: Throwable): Boolean =
        error is java.io.IOException ||
            error is java.net.SocketTimeoutException ||
            (error.message?.contains("network", ignoreCase = true) == true) ||
            (error.message?.contains("timeout", ignoreCase = true) == true) ||
            (error.message?.contains("unreachable", ignoreCase = true) == true) ||
            (error.message?.contains("canceled", ignoreCase = true) == true)

    companion object {
        // 协议/服务器错误退避：30s -> 60s -> 120s -> 240s，最多 5 次
        const val INITIAL_DELAY_MS = 30_000L
        const val MAX_DELAY_MS = 30 * 60_000L
        const val MAX_ATTEMPTS = 5
        // 网络错误退避：更短初始 + 更多重试（弱网场景），10s -> 20s -> 40s -> 80s -> 160s -> 320s，最多 8 次
        const val NETWORK_INITIAL_DELAY_MS = 10_000L
        const val NETWORK_MAX_DELAY_MS = 5 * 60_000L
        const val MAX_ATTEMPTS_NETWORK = 8
    }
}
