package com.maodouchat.crypto

import android.content.Context
import android.util.Log
import com.maodouchat.data.local.dao.SenderKeyRetryDao
import com.maodouchat.data.local.entity.SenderKeyRetryEntity
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.local.entity.hasCompletedUpload
import com.maodouchat.data.model.MessageType
import com.maodouchat.network.ApiService
import com.maodouchat.network.SenderKeyDistributionTargetDto
import com.maodouchat.network.SenderKeyDistributionTargetRequest
import com.maodouchat.network.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.UUID

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

    suspend fun enqueue(chatId: String, epoch: Long, reason: String, delayMs: Long = INITIAL_DELAY_MS) {
        if (chatId.isBlank()) return
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (ownerUserId.isBlank() || !sessionActive(ownerUserId)) return
        // 8.49 修复：读-改-写整段纳入互斥——UI 手动重发路径的 enqueue 与 60s 循环/Worker 的
        // markFailure 并发时，过期 existing 快照的整行 REPLACE 会回退 attempts 退避预算
        processMutex.withLock {
            enqueueLocked(ownerUserId, chatId, epoch, reason, delayMs)
        }
        refreshBackgroundSchedule(ownerUserId)
    }

    /** 调用方必须已持有 processMutex（ensureCoverageNow 锁内失败路径直接复用，避免重入死锁）。 */
    private suspend fun enqueueLocked(
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

    suspend fun processDueTasks(limit: Int = 5) {
        val token = tokenManager.getToken().orEmpty()
        val userId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || userId.isBlank()) {
            context?.let { SenderKeyRetryWorkScheduler.cancelAll(it) }
            return
        }
        ensureSignalReady(token, userId)
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
                val epoch = redistribute(task, liveToken, userId)
                if (!sessionActive(userId)) return
                if (verifyCoverageComplete(liveToken, task.chatId, epoch, userId)) {
                    if (!sessionActive(userId)) return
                    retryDao.delete(userId, task.chatId)
                } else {
                    markFailure(task, IllegalStateException("sender_key_coverage_incomplete"))
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!sessionActive(userId)) return
                markFailure(task, error)
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
        ensureSignalReady(token, userId)
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
            val epoch = redistribute(task, liveToken, userId)
            check(sessionActive(userId)) { "sender_key_session_changed" }
            check(verifyCoverageComplete(liveToken, chatId, epoch, userId)) { "sender_key_coverage_incomplete" }
            check(sessionActive(userId)) { "sender_key_session_changed" }
            retryDao.delete(userId, chatId)
            true
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            markFailure(task, error)
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
        ensureSignalReady(token, userId)
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
            clearAttachmentWireForChat(chatId)
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
                val actualEpoch = redistribute(task, sendToken, userId)
                check(actualEpoch == expectedEpoch) { "sender_key_epoch_changed" }
                check(verifyCoverageComplete(sendToken, chatId, actualEpoch, userId)) { "sender_key_coverage_incomplete" }
                check(sessionActive(userId)) { "sender_key_session_changed" }
                retryDao.delete(userId, chatId)
                Result.success(actualEpoch)
            } catch (error: kotlinx.coroutines.CancellationException) {
                // Cancel mid-coverage must not invalidate SK or clear READY attachment wire.
                throw error
            } catch (error: Exception) {
                if (!sessionActive(userId)) {
                    return@withLock Result.failure(IllegalStateException("sender_key_session_changed", error))
                }
                if (isTransientNetworkError(error)) {
                    // 8.37：网络瞬态失败（超时/断网）不得销毁群 SenderKey 或清空 READY 附件 wire——
                    // 其他设备已按旧 SKDM 加密的密文会在 key 轮换后无法解密；只入队退避重试。
                    // 8.41：以 TransientCoverageException 抛出，使发送路径保持 SENDING 待 flusher 重试
                    enqueueLocked(userId, chatId, expectedEpoch, "coverage_send_network_failed:${error.message.orEmpty()}", INITIAL_DELAY_MS)
                    refreshBackgroundSchedule(userId)
                    return@withLock Result.failure(TransientCoverageException("sender key coverage transient failure", error))
                }
                signalProtocol.invalidateGroupSenderKey(chatId)
                clearAttachmentWireForChat(chatId)
                enqueueLocked(userId, chatId, expectedEpoch, "coverage_send_failed:${error.message.orEmpty()}", INITIAL_DELAY_MS)
                refreshBackgroundSchedule(userId)
                Result.failure(error)
            }
        }
    }

    private suspend fun redistribute(task: SenderKeyRetryEntity, token: String, expectedOwnerUserId: String): Long {
        if (expectedOwnerUserId.isBlank() || task.ownerUserId != expectedOwnerUserId ||
            !SenderKeyRetrySessionPolicy.mayContinueBatch(
                expectedOwnerUserId,
                token,
                tokenManager.getUserId(),
            )
        ) {
            error("sender_key_session_changed")
        }
        var liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
        val chat = ApiService.getChats(liveToken).getOrThrow().firstOrNull { it.id == task.chatId }
            ?: run {
                check(sessionActive(expectedOwnerUserId)) { "sender_key_session_changed" }
                retryDao.delete(expectedOwnerUserId, task.chatId)
                return task.epoch
            }
        if (!chat.isGroup) {
            check(sessionActive(expectedOwnerUserId)) { "sender_key_session_changed" }
            retryDao.delete(expectedOwnerUserId, task.chatId)
            return chat.memberRevision
        }
        val epoch = chat.memberRevision
        // Do not send coverage under a different epoch than the retry task expected —
        // that mis-labels server coverage rows and leaves encrypt path on a stale distribution.
        // epoch 0 任务/未知 revision 一律 fail closed，禁止静默用 live epoch 顶替
        if (task.epoch <= 0L || epoch <= 0L || epoch != task.epoch) {
            error("sender_key_epoch_changed:task=${task.epoch},live=$epoch")
        }
        if (!SenderKeyRetrySessionPolicy.mayContinueBatch(
                expectedOwnerUserId,
                tokenManager.getToken().orEmpty().ifBlank { liveToken },
                tokenManager.getUserId(),
            )
        ) {
            error("sender_key_session_changed")
        }
        liveToken = tokenManager.getToken().orEmpty().ifBlank { liveToken }
        val members = ApiService.getGroupMembers(liveToken, task.chatId).getOrThrow()
        val recipientIds = members.map { it.userId }
            .filter { it.isNotBlank() && !com.maodouchat.bot.BotCommandPolicy.isBotUserId(it) }
            .distinct()
        // 始终先 mint 本地 distribution：单成员群 / 仅本机多设备也必须有本地 SK，
        // 否则 encrypt 路径 hasGroupDistributionId=false 会永久失败。
        val payload = signalProtocol.createGroupSenderKeyDistribution(task.chatId, epoch)
        val rawEnvelope = signalProtocol.buildSenderKeyDistributionEnvelope(
            task.chatId,
            payload.distributionId,
            payload.message,
            payload.epoch
        )
        if (!SenderKeyRetrySessionPolicy.mayContinueBatch(
                expectedOwnerUserId,
                tokenManager.getToken().orEmpty().ifBlank { liveToken },
                tokenManager.getUserId(),
            )
        ) {
            error("sender_key_session_changed")
        }
        liveToken = tokenManager.getToken().orEmpty().ifBlank { liveToken }
        val distribution = try {
            signalProtocol.encryptMultiRecipientContentEnvelopeWithTargets(
                token = liveToken,
                recipientIds = recipientIds,
                plaintext = rawEnvelope,
                payloadType = MessageType.SK_DIST.name,
                includeCurrentUserDevices = true
            ).getOrThrow()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: NoRecipientDevicesException) {
            // 9.3xx：群内还有其他成员、但他们的设备会话尚未建立时，绝不能把本 epoch 视为
            // "覆盖完成"——否则这些成员永远拿不到 Sender Key（用户侧表现为"缺少 senderKey"、
            // 群消息永远解密失败）。只有真正没有任何外部收件人（单人/仅自己设备）时才允许
            // 本地-only 覆盖完成；其余情况抛出并按退避重试（外层 catch 记失败）。
            val hasOtherRecipients = recipientIds.any { it.isNotBlank() && it != expectedOwnerUserId }
            if (!hasOtherRecipients) return epoch
            throw error
        }
        if (distribution.targets.isEmpty()) {
            // 本地 mint 成功且无 peer/其它设备目标：本地-only 覆盖
            return epoch
        }

        if (!SenderKeyRetrySessionPolicy.mayContinueBatch(
                expectedOwnerUserId,
                tokenManager.getToken().orEmpty().ifBlank { liveToken },
                tokenManager.getUserId(),
            )
        ) {
            error("sender_key_session_changed")
        }
        liveToken = tokenManager.getToken().orEmpty().ifBlank { liveToken }
        val messageId = "sk_${UUID.randomUUID()}"
        ApiService.sendMessage(liveToken, task.chatId, distribution.envelope, MessageType.SK_DIST.name, messageId).getOrThrow()
        check(sessionActive(expectedOwnerUserId)) { "sender_key_session_changed" }
        liveToken = tokenManager.getToken().orEmpty().ifBlank { liveToken }
        ApiService.reportSenderKeyDistribution(
            token = liveToken,
            chatId = task.chatId,
            epoch = epoch,
            messageId = messageId,
            targets = distribution.targets.map {
                SenderKeyDistributionTargetRequest(it.userId, it.deviceId, "SENT")
            }
        ).getOrThrow()
        check(sessionActive(expectedOwnerUserId)) { "sender_key_session_changed" }
        return epoch
    }

    /**
     * POST report 只反映本次 fan-out 的 targets；必须以 GET（含 expected 设备集合）判定是否真覆盖。
     */
    private suspend fun verifyCoverageComplete(token: String, chatId: String, epoch: Long, expectedOwnerUserId: String): Boolean {
        if (epoch <= 0L) return false
        val coverageResult = ApiService.getSenderKeyDistributionStatus(
            token = token,
            chatId = chatId,
            epoch = epoch,
            currentDeviceId = signalProtocol.getDeviceId()
        )
        coverageResult.exceptionOrNull()?.let { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
        }
        if (!sessionActive(expectedOwnerUserId)) return false
        val coverage = coverageResult.getOrNull() ?: return false
        return !SenderKeyCoveragePolicy.requiresDistribution(
            hasLocalDistribution = signalProtocol.groupDistributionUsable(chatId, epoch),
            requestedEpoch = epoch,
            statusEpoch = coverage.epoch,
            targetStatuses = coverage.targets.map(SenderKeyDistributionTargetDto::status)
        )
    }

    private suspend fun markFailure(task: SenderKeyRetryEntity, error: Throwable) {
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
            if (sessionActive(task.ownerUserId)) refreshBackgroundSchedule(task.ownerUserId)
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
        if (sessionActive(task.ownerUserId)) refreshBackgroundSchedule(task.ownerUserId)
    }

    private suspend fun ensureSignalReady(token: String, userId: String) {
        if (initializedUserId == userId && signalProtocol.isInitializedFor(userId)) return
        if (!signalProtocol.initialize(token, userId)) {
            initializedUserId = null
            error("Signal protocol not ready for sender-key retry")
        }
        initializedUserId = userId
    }

    private suspend fun clearAttachmentWireForChat(chatId: String) {
        val app = context as? com.maodouchat.MaodouchatApp ?: return
        try {
            val dao = app.database.attachmentTransferDao()
            val ownerUserId = initializedUserId ?: return
            dao.clearWireContentForChat(chatId, ownerUserId = ownerUserId)
            dao.getByChat(chatId, ownerUserId = ownerUserId)
                .filter { it.state == AttachmentTransferState.READY && it.hasCompletedUpload() }
                .forEach {
                    com.maodouchat.attachment.AttachmentTransferScheduler.schedule(
                        app,
                        it.messageId,
                        ownerUserId,
                        replace = true
                    )
                }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "clearAttachmentWireForChat failed for $chatId", error)
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
        // 协议/服务器错误退避：30s -> 60s -> 120s -> 240s，最多 5 次
        const val INITIAL_DELAY_MS = 30_000L
        const val MAX_DELAY_MS = 30 * 60_000L
        const val MAX_ATTEMPTS = 5
        // 网络错误退避：更短初始 + 更多重试（弱网场景），10s -> 20s -> 40s -> 80s -> 160s -> 320s，最多 8 次
        const val NETWORK_INITIAL_DELAY_MS = 10_000L
        const val NETWORK_MAX_DELAY_MS = 5 * 60_000L
        const val MAX_ATTEMPTS_NETWORK = 8
    }

    /** 瞬态网络错误判定（弱网退避与 8.37 破坏性操作防护共用）。 */
    private fun isTransientNetworkError(error: Throwable): Boolean =
        error is java.io.IOException ||
            error is java.net.SocketTimeoutException ||
            (error.message?.contains("network", ignoreCase = true) == true) ||
            (error.message?.contains("timeout", ignoreCase = true) == true) ||
            (error.message?.contains("unreachable", ignoreCase = true) == true) ||
            (error.message?.contains("canceled", ignoreCase = true) == true)
}

/**
 * 群 SenderKey 覆盖分发时的瞬态网络失败标记（8.41）。
 * 与 ApiException.NETWORK/TIMEOUT 同语义：发送路径应保持 SENDING 待 flusher 重试，
 * 不得标 FAILED（MessageMutationPolicy.shouldMarkOutboxFailed 已识别）。
 */
class TransientCoverageException(
    message: String,
    cause: Throwable? = null
) : java.io.IOException(message, cause)

/**
 * 聊天级 SenderKey 重试状态，用于 UI 显示。
 */
data class SenderKeyRetryView(
    val chatId: String,
    val pendingCount: Int,
    val attempts: Int,
    val lastError: String?,
    val epoch: Long
)
