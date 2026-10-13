package com.maodouchat.crypto

import android.content.Context
import android.util.Log
import com.maodouchat.data.local.dao.SenderKeyRetryDao
import com.maodouchat.data.local.entity.SenderKeyRetryEntity
import com.maodouchat.network.ApiService
import com.maodouchat.network.SenderKeyDistributionTargetDto
import com.maodouchat.network.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

class SenderKeyRetryManager(
    private val retryDao: SenderKeyRetryDao,
    private val signalProtocol: SignalProtocol,
    private val tokenManager: TokenManager,
    private val context: Context? = null // 非空时启用后台调度；为 null 时静默跳过（仅测试场景）
) {
    init {
        if (context == null) {
            android.util.Log.w("SenderKeyRetryManager", "Context is null; background scheduling disabled")
        }
    }

    private val distributor = SenderKeyRetryDistributor(retryDao, signalProtocol, tokenManager, context, currentUserId = { initializedUserId }, setCurrentUserId = { initializedUserId = it }, sessionActive = ::sessionActive)
    private val taskQueue = SenderKeyRetryTaskQueue(retryDao, sessionActive = ::sessionActive, refreshSchedule = ::refreshBackgroundSchedule)
    private var job: Job? = null
    private var initializedUserId: String? = null
    /** 8.41：processDueTasks 互斥，防 Worker/应用循环/发送路径并发处理同一任务。 */
    private val processMutex = kotlinx.coroutines.sync.Mutex()

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                try {
                    processDueTasks()
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "SenderKey retry loop failed", error)
                }
                delay(SCAN_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun enqueue(chatId: String, epoch: Long, reason: String, delayMs: Long = SenderKeyRetryTaskQueue.INITIAL_DELAY_MS) {
        if (chatId.isBlank()) return
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (ownerUserId.isBlank() || !sessionActive(ownerUserId)) return
        // 8.49 修复：读-改-写整段纳入互斥——UI 手动重发路径的 enqueue 与 60s 循环/Worker 的
        // markFailure 并发时，过期 existing 快照的整行 REPLACE 会回退 attempts 退避预算
        processMutex.withLock {
            taskQueue.enqueueLocked(ownerUserId, chatId, epoch, reason, delayMs)
        }
        refreshBackgroundSchedule(ownerUserId)
    }

    suspend fun processDueTasks(limit: Int = 5) {
        val token = tokenManager.getToken().orEmpty()
        val userId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || userId.isBlank()) {
            context?.let { SenderKeyRetryWorkScheduler.cancelAll(it) }
            return
        }
        distributor.ensureSignalReady(token, userId)
        if (!sessionActive(userId)) return
        // 8.41：互斥串行——Worker、应用内 60s 循环、发送路径可同时触发，无锁时
        // 同一任务被两个执行者并发 redistribute（双重 SKDM + 双重密钥重置）
        processMutex.withLock {
            // 8.48 修复：收养 v24→v25 迁移遗留的孤儿行（ownerUserId='' 永不被 getDue 命中）——
            // 否则升级前已排队的 SK 分发重试永久失联，受影响会话的群成员 SK 覆盖缺口不补齐
            try {
                retryDao.adoptOrphans(userId)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "adoptOrphans failed", error)
            }
            _processDueTasksLocked(userId, token, limit)
        }
    }

    private suspend fun _processDueTasksLocked(userId: String, token: String, limit: Int) {
        retryDao.getDue(userId, System.currentTimeMillis(), limit).forEach { task ->
            val liveToken = tokenManager.getToken().orEmpty()
            val liveUserId = tokenManager.getUserId().orEmpty()
            if (!SenderKeyRetrySessionPolicy.mayContinueBatch(userId, liveToken, liveUserId)) {
                Log.i(TAG, "SenderKey retry batch aborted: session changed")
                return
            }
            try {
                val epoch = distributor.redistribute(task, liveToken, userId)
                if (!sessionActive(userId)) return
                if (distributor.verifyCoverageComplete(liveToken, task.chatId, epoch, userId)) {
                    if (!sessionActive(userId)) return
                    retryDao.delete(userId, task.chatId)
                } else {
                    taskQueue.markFailure(task, IllegalStateException("sender_key_coverage_incomplete"))
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!sessionActive(userId)) return
                taskQueue.markFailure(task, error)
            }
        }
        if (sessionActive(userId)) refreshBackgroundSchedule(userId)
    }

    /**
     * 观察指定聊天是否还有未完成分发的 SenderKey 重试任务。
     * 聊天详情页使用这个 indicator 显示"密钥分发中…"或"密钥分发失败"。
     */
    fun observePendingForChat(chatId: String): Flow<SenderKeyRetryView> =
        tokenManager.getUserId().orEmpty().let { ownerUserId ->
            retryDao.observePendingCountForChat(ownerUserId, chatId).map { count ->
                val task = retryDao.get(ownerUserId, chatId)
                SenderKeyRetryView(
                    chatId = chatId,
                    pendingCount = count,
                    attempts = task?.attempts ?: 0,
                    lastError = task?.lastError,
                    epoch = task?.epoch ?: 0L
                )
            }
        }

    /**
     * 用户主动触发"立即重发 Sender Key"，绕过默认的 attempts 退避时间。
     * 调用前确保已加群且 token 有效。
     */
    suspend fun redistributeNow(chatId: String): Boolean {
        val token = tokenManager.getToken().orEmpty()
        val userId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || userId.isBlank() || chatId.isBlank()) return false
        distributor.ensureSignalReady(token, userId)
        if (!SenderKeyRetrySessionPolicy.mayContinueBatch(
                userId,
                tokenManager.getToken(),
                tokenManager.getUserId(),
            )
        ) {
            return false
        }
        val task = retryDao.get(userId, chatId) ?: return false
        return try {
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            val epoch = distributor.redistribute(task, liveToken, userId)
            check(sessionActive(userId)) { "sender_key_session_changed" }
            check(distributor.verifyCoverageComplete(liveToken, chatId, epoch, userId)) { "sender_key_coverage_incomplete" }
            check(sessionActive(userId)) { "sender_key_session_changed" }
            retryDao.delete(userId, chatId)
            true
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            taskQueue.markFailure(task, error)
            false
        }
    }

    /**
     * Ensures this device's Sender Key covers every currently confirmed group device.
     * Used by both foreground sends and background attachment finalization.
     */
    suspend fun ensureCoverageNow(chatId: String, expectedEpoch: Long): Result<Long> {
        val token = tokenManager.getToken().orEmpty()
        val userId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || userId.isBlank() || chatId.isBlank()) {
            return Result.failure(IllegalStateException("sender_key_session_missing"))
        }
        // epoch 0 = unknown；绝不能当真实群 revision 去 fan-out / 加密
        if (expectedEpoch <= 0L) {
            return Result.failure(IllegalStateException("group_epoch_unknown"))
        }
        distributor.ensureSignalReady(token, userId)
        if (!SenderKeyRetrySessionPolicy.mayContinueBatch(
                userId,
                tokenManager.getToken(),
                tokenManager.getUserId(),
            )
        ) {
            return Result.failure(IllegalStateException("sender_key_session_changed"))
        }
        if (signalProtocol.shouldRotateGroupSenderKey(chatId, expectedEpoch)) {
            signalProtocol.invalidateGroupSenderKey(chatId)
            // 旧 epoch 的附件密文 envelope 不可复用
            distributor.clearAttachmentWireForChat(chatId)
        }
        val hasLocalDistribution = signalProtocol.groupDistributionUsable(chatId, expectedEpoch)
        if (hasLocalDistribution) {
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            val coverageResult = ApiService.getSenderKeyDistributionStatus(
                token = liveToken,
                chatId = chatId,
                epoch = expectedEpoch,
                currentDeviceId = signalProtocol.getDeviceId()
            )
            coverageResult.exceptionOrNull()?.let { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
            if (!sessionActive(userId)) {
                return Result.failure(IllegalStateException("sender_key_session_changed"))
            }
            val coverage = coverageResult.getOrNull()
            if (coverage == null) {
                val reason = "coverage_check_failed:${coverageResult.exceptionOrNull()?.message.orEmpty()}"
                enqueue(chatId, expectedEpoch, reason)
                // 覆盖状态未知时不能当成功：否则新设备/未覆盖设备会永久解不开本条密文
                return Result.failure(IllegalStateException(reason))
            }
            val complete = !SenderKeyCoveragePolicy.requiresDistribution(
                hasLocalDistribution = true,
                requestedEpoch = expectedEpoch,
                statusEpoch = coverage.epoch,
                targetStatuses = coverage.targets.map(SenderKeyDistributionTargetDto::status)
            )
            if (complete) return Result.success(expectedEpoch)
        }

        val task = SenderKeyRetryEntity(
            ownerUserId = userId,
            chatId = chatId,
            epoch = expectedEpoch,
            reason = "coverage_required",
            attempts = 0,
            nextAttemptAt = 0,
            updatedAt = System.currentTimeMillis()
        )
        // 8.51：覆盖分发（redistribute→verify→delete）纳入互斥——此前锁只盖住
        // processDueTasks，发送路径的 ensureCoverageNow 仍可与 60s 循环/Worker 并发
        // redistribute 同一任务（双重 SKDM + 双重密钥重置）
        return processMutex.withLock {
            try {
                val sendToken = tokenManager.getToken().orEmpty().ifBlank { token }
                if (!SenderKeyRetrySessionPolicy.mayContinueBatch(
                        userId,
                        sendToken,
                        tokenManager.getUserId(),
                    )
                ) {
                    return@withLock Result.failure(IllegalStateException("sender_key_session_changed"))
                }
                val actualEpoch = distributor.redistribute(task, sendToken, userId)
                check(actualEpoch == expectedEpoch) { "sender_key_epoch_changed" }
                check(distributor.verifyCoverageComplete(sendToken, chatId, actualEpoch, userId)) { "sender_key_coverage_incomplete" }
                check(sessionActive(userId)) { "sender_key_session_changed" }
                retryDao.delete(userId, chatId)
                Result.success(actualEpoch)
            } catch (error: kotlinx.coroutines.CancellationException) {
                // Cancel mid-coverage must not invalidate SK or clear READY attachment wire.
                throw error
            } catch (error: SenderKeyCoveragePendingException) {
                if (!sessionActive(userId)) {
                    return@withLock Result.failure(IllegalStateException("sender_key_session_changed"))
                }
                taskQueue.enqueueLocked(userId, chatId, expectedEpoch, "coverage_mailbox_pending", 1_000L)
                refreshBackgroundSchedule(userId)
                Result.failure(error)
            } catch (error: Exception) {
                if (!sessionActive(userId)) {
                    return@withLock Result.failure(IllegalStateException("sender_key_session_changed", error))
                }
                if (taskQueue.isTransientNetworkError(error)) {
                    // 8.37：网络瞬态失败（超时/断网）不得销毁群 SenderKey 或清空 READY 附件 wire——
                    // 其他设备已按旧 SKDM 加密的密文会在 key 轮换后无法解密；只入队退避重试。
                    // 8.41：以 TransientCoverageException 抛出，使发送路径保持 SENDING 待 flusher 重试
                    taskQueue.enqueueLocked(userId, chatId, expectedEpoch, "coverage_send_network_failed:${error.message.orEmpty()}", SenderKeyRetryTaskQueue.INITIAL_DELAY_MS)
                    refreshBackgroundSchedule(userId)
                    return@withLock Result.failure(TransientCoverageException("sender key coverage transient failure", error))
                }
                signalProtocol.invalidateGroupSenderKey(chatId)
                distributor.clearAttachmentWireForChat(chatId)
                taskQueue.enqueueLocked(userId, chatId, expectedEpoch, "coverage_send_failed:${error.message.orEmpty()}", SenderKeyRetryTaskQueue.INITIAL_DELAY_MS)
                refreshBackgroundSchedule(userId)
                Result.failure(error)
            }
        }
    }

    private suspend fun refreshBackgroundSchedule(ownerUserId: String) {
        val appContext = context ?: return
        if (!sessionActive(ownerUserId)) return
        val nextAttemptAt = retryDao.getNextAttemptAt(ownerUserId)
        if (nextAttemptAt == null) {
            SenderKeyRetryWorkScheduler.cancelAll(appContext)
            return
        }
        SenderKeyRetryWorkScheduler.ensureScheduled(appContext)
        SenderKeyRetryWorkScheduler.scheduleSoon(
            context = appContext,
            delayMs = nextAttemptAt - System.currentTimeMillis()
        )
    }

    private fun sessionActive(expectedOwnerUserId: String): Boolean =
        SenderKeyRetrySessionPolicy.mayContinueBatch(
            expectedOwnerUserId,
            tokenManager.getToken(),
            tokenManager.getUserId(),
        )

    private companion object {
        const val TAG = "SenderKeyRetryManager"
        const val SCAN_INTERVAL_MS = 60_000L
    }
}
