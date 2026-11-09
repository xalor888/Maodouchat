package com.maodouchat.attachment

import com.maodouchat.MaodouchatApp
import com.maodouchat.core.model.Summary
import com.maodouchat.data.local.entity.AttachmentTransferEntity
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.domain.messaging.AttachmentTransferCancelAction
import com.maodouchat.domain.messaging.AttachmentTransferSummaryPolicy
import com.maodouchat.domain.messaging.TransferSummaryRow
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

    /** 观察当前账号下所有 attachment 传输的最新统计；chatIdFilter 非空时只统计该聊天。 */
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

    private fun summarize(all: List<AttachmentTransferEntity>): Summary =
        AttachmentTransferSummaryPolicy.summarize(all.map {
            TransferSummaryRow(state = it.state, cipherSize = it.cipherSize, uploadedBytes = it.uploadedBytes)
        })

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
            when (AttachmentTransferSummaryPolicy.cancelAction(transfer.state)) {
                AttachmentTransferCancelAction.COUNTED_AS_HANDLED -> cancelled++
                AttachmentTransferCancelAction.CANCEL ->
                    if (AttachmentTransferCoordinator.cancel(context, transfer.messageId, ownerUserId)) cancelled++
                AttachmentTransferCancelAction.SKIP -> Unit
            }
        }
        return cancelled
    }

    // ui 不再持有 app 符号时的入口：与单例同一实例，行为与上面两函数一致。
    suspend fun retryAll(chatIdFilter: String? = null): Int =
        retryAll(MaodouchatApp.instance, chatIdFilter)

    suspend fun cancelAll(chatIdFilter: String? = null): Int =
        cancelAll(MaodouchatApp.instance, chatIdFilter)
}
