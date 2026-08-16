package com.maodouchat.attachment

import com.maodouchat.MaodouchatApp
import com.maodouchat.data.local.entity.AttachmentTransferEntity
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.network.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * 附件传输统计聚合：跨聊天给出当前活跃/排队/失败/已上传的数量，供聊天详情顶部的浮窗使用。
 */
object AttachmentTransferSummaryRepository {

    data class Summary(
        val active: Int = 0,
        val queued: Int = 0,
        val failed: Int = 0,
        val completed: Int = 0,
        val totalBytes: Long = 0L,
        val uploadedBytes: Long = 0L
    ) {
        val hasFailures: Boolean get() = failed > 0
        val hasActivity: Boolean get() = active > 0 || queued > 0
    }

    /**
     * 观察当前账号下所有 attachment 传输的最新统计。
     * 当 [chatIdFilter] 不为空时只统计该聊天，便于聊天页内嵌浮窗使用。
     *
     * 8.49 修复：Room Flow 直查 + distinctUntilChanged 替代 750ms 无条件全表轮询——
     * 旧实现即使完全无传输也持续唤醒 IO 线程查询；现在由 DB 变更驱动，
     * 仅保留 5s 低频心跳感知账号切换后重建查询。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(app: MaodouchatApp, chatIdFilter: String? = null): Flow<Summary> {
        val dao = app.database.attachmentTransferDao()
        return ownerUserIdHeartbeat(app)
            .distinctUntilChanged()
            .flatMapLatest { owner ->
                when {
                    owner.isBlank() -> flowOf(Summary())
                    chatIdFilter.isNullOrBlank() -> dao.observeAll(owner).map(::summarize)
                    else -> dao.observeByChat(chatIdFilter, owner).map(::summarize)
                }
            }
            .distinctUntilChanged()
    }

    private fun ownerUserIdHeartbeat(app: MaodouchatApp): Flow<String> = flow {
        while (true) {
            emit(TokenManager.getInstance(app).getUserId().orEmpty())
            delay(5_000L)
        }
    }.flowOn(Dispatchers.IO)

    private fun summarize(all: List<AttachmentTransferEntity>): Summary = Summary(
        active = all.count { it.state == AttachmentTransferState.UPLOADING || it.state == AttachmentTransferState.SENDING || it.state == AttachmentTransferState.PREPARING },
        queued = all.count { it.state == AttachmentTransferState.QUEUED || it.state == AttachmentTransferState.PAUSED },
        failed = all.count { it.state == AttachmentTransferState.FAILED },
        completed = all.count { it.state == AttachmentTransferState.READY },
        totalBytes = all.sumOf { it.cipherSize },
        uploadedBytes = all.sumOf { it.uploadedBytes }
    )

    /**
     * 一键"重试所有失败任务"。返回成功重新调度的条数。
     */
    suspend fun retryAll(app: MaodouchatApp, chatIdFilter: String? = null): Int {
        val context = app.applicationContext
        val dao = app.database.attachmentTransferDao()
        val ownerUserId = TokenManager.getInstance(app).getUserId().orEmpty()
        if (ownerUserId.isBlank()) return 0
        val failed = if (chatIdFilter.isNullOrBlank()) {
            dao.getByState(AttachmentTransferState.FAILED, ownerUserId = ownerUserId)
        } else {
            dao.getByChat(chatIdFilter, ownerUserId = ownerUserId)
                .filter { it.state == AttachmentTransferState.FAILED }
        }
        if (TokenManager.getInstance(app).getUserId().orEmpty() != ownerUserId) return 0
        var scheduled = 0
        failed.forEach { transfer ->
            if (AttachmentTransferCoordinator.resume(context, transfer.messageId, ownerUserId)) scheduled++
        }
        return scheduled
    }

    /**
     * 一键"清掉当前聊天所有失败/暂停任务"。已上传完成的不会动。
     */
    suspend fun cancelAll(app: MaodouchatApp, chatIdFilter: String? = null): Int {
        val context = app.applicationContext
        val dao = app.database.attachmentTransferDao()
        val ownerUserId = TokenManager.getInstance(app).getUserId().orEmpty()
        if (ownerUserId.isBlank()) return 0
        val items = if (chatIdFilter.isNullOrBlank()) {
            dao.getAll(ownerUserId = ownerUserId)
        } else {
            dao.getByChat(chatIdFilter, ownerUserId = ownerUserId)
        }
        if (TokenManager.getInstance(app).getUserId().orEmpty() != ownerUserId) return 0
        var cancelled = 0
        items.forEach { transfer ->
            if (transfer.state == AttachmentTransferState.SENDING) {
                // finalize 在途：不中断，但批量取消语义视为已处理，由 complete() 幂等收尾。
                cancelled++
            } else if (transfer.state == AttachmentTransferState.FAILED || transfer.state == AttachmentTransferState.PAUSED ||
                transfer.state == AttachmentTransferState.UPLOADING ||
                transfer.state == AttachmentTransferState.QUEUED || transfer.state == AttachmentTransferState.PREPARING
            ) {
                if (AttachmentTransferCoordinator.cancel(context, transfer.messageId, ownerUserId)) cancelled++
            }
        }
        return cancelled
    }
}
