package com.maodouchat.util

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.maodouchat.MaodouchatApp
import com.maodouchat.data.local.entity.toEntity
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.TextOutboxFlusher
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.network.WebSocketClient
import com.maodouchat.security.BackgroundSessionGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 到期后把本地定时文本转成 SENDING 发件箱消息并尝试投递；失败则保留 SENDING 供 TextOutboxFlusher 重试。
 * 1:1 与群聊均支持纯文本（群聊走 TextOutboxFlusher 的 Sender Key 覆盖加密路径）。
 */
class ScheduledMessageWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val scheduleId = inputData.getString(KEY_SCHEDULE_ID) ?: return@withContext Result.success()
        val expectedOwnerUserId = inputData.getString(KEY_OWNER_USER_ID)
            ?.takeIf(String::isNotBlank)
            ?: ScheduledMessageStore.ownerOf(applicationContext, scheduleId)
            ?: return@withContext Result.success()
        val item = ScheduledMessageStore.getForUser(applicationContext, scheduleId, expectedOwnerUserId)
            ?: return@withContext Result.success()
        val tokenManager = TokenManager.getInstance(applicationContext)
        val ownerUserId = tokenManager.getUserId().orEmpty()
        val token = tokenManager.getToken().orEmpty()
        // 账号不匹配/无 token 通常是登出清理进行中的瞬态（purge 会取消本任务）；
        // 若 purge 部分失败导致任务残留，封顶重试次数避免无限 retry 耗电。
        // 8.48 修复：达上限后不再是 Result.success()（行保留 → 永久静默滞留），
        // 改为移除待发条目 + 失败通知，让用户明确感知。
        if (ownerUserId != expectedOwnerUserId || token.isBlank()) {
            return@withContext if (runAttemptCount >= MAX_TRANSIENT_RETRIES) {
                abandonScheduledMessage(item, scheduleId, expectedOwnerUserId)
            } else {
                Result.retry()
            }
        }
        if (!BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return@withContext if (runAttemptCount >= MAX_TRANSIENT_RETRIES) {
                abandonScheduledMessage(item, scheduleId, expectedOwnerUserId)
            } else {
                Result.retry()
            }
        }
        val app = applicationContext as? MaodouchatApp
            ?: return@withContext Result.retry()
        val msgId = "sm_${scheduleId.removePrefix("sch_")}"
        val now = System.currentTimeMillis()
        val optimistic = Message(
            id = msgId,
            chatId = item.chatId,
            senderId = ownerUserId,
            content = item.text,
            type = if (com.maodouchat.ui.component.ChatMarkdown.looksLikeMarkdown(item.text)) MessageType.MARKDOWN else MessageType.TEXT,
            timestamp = now,
            status = MessageStatus.SENDING
        )
        runCatching {
            app.database.messageDao().insertMessage(optimistic.toEntity())
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            return@withContext Result.retry()
        }

        if (!BackgroundSessionGate.mayContinue(
                expectedUserId = expectedOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            app.database.messageDao().deleteMessageById(msgId)
            return@withContext Result.retry()
        }

        // 1.14 修复（1.07 遗留 bug）：此前成功发送路径只 remove 不重排，
        // 重复定时消息首轮发送后即终止，重复从未生效。现改为先重排下一次再移除当前到条目。
        // 1.21：repeatCount>0 时达上限（occurrencesSent+1 >= repeatCount）即停止重排。
        if (item.repeatIntervalMs > 0L && (item.repeatCount == 0 || item.occurrencesSent + 1 < item.repeatCount)) {
            try {
                ScheduledMessageStore.add(
                    applicationContext,
                    item.chatId,
                    item.peerUserId,
                    item.text,
                    nextRepeatSendAt(item),
                    item.isGroup,
                    item.repeatIntervalMs,
                    item.repeatCount,
                    item.occurrencesSent + 1,
                    item.weekdaysOnly
                )?.let { rescheduled ->
                    ScheduledMessageScheduler.schedule(applicationContext, rescheduled)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w(TAG, "Reschedule recurring message failed", error)
            }
        }
        ScheduledMessageStore.removeForUser(applicationContext, scheduleId, expectedOwnerUserId)

        if (item.isGroup) {
            // Group encrypt needs epoch + Sender Key coverage; reuse durable outbox path.
            runCatching {
                TextOutboxFlusher.flush(app = app, activeChatId = item.chatId)
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            MaodouchatApp.emitMessageSent(item.chatId, item.text.take(200), if (com.maodouchat.ui.component.ChatMarkdown.looksLikeMarkdown(item.text)) MessageType.MARKDOWN.name else MessageType.TEXT.name)
            return@withContext Result.success()
        }

        val delivered = runCatching {
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            if (!BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@runCatching false
            }
            val peerId = item.peerUserId
            if (peerId.isBlank()) return@runCatching false
            if (!app.signalProtocol.isInitializedFor(ownerUserId)) {
                check(app.signalProtocol.initialize(liveToken, ownerUserId)) {
                    "signal_initialization_failed"
                }
            }
            val wire = app.signalProtocol.encryptSyncedContentEnvelope(liveToken, peerId, item.text, optimistic.type.name).getOrThrow()
            val wireMsg = optimistic.copy(content = wire)
            if (WebSocketClient.sendMessage(wireMsg)) {
                // WebSocket 直发成功也要本地落库为 SENT，否则消息会一直停在 SENDING。
                val sent = optimistic.copy(status = MessageStatus.SENT)
                app.database.messageDao().insertMessage(sent.toEntity())
                true
            } else {
                ApiService.sendMessage(liveToken, item.chatId, wire, optimistic.type.name, msgId).getOrThrow()
                val sent = optimistic.copy(status = MessageStatus.SENT)
                app.database.messageDao().insertMessage(sent.toEntity())
                true
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrDefault(false)

        if (!delivered) {
            TextOutboxFlusher.flush(app = app)
        } else {
            MaodouchatApp.emitMessageSent(item.chatId, item.text.take(200), if (com.maodouchat.ui.component.ChatMarkdown.looksLikeMarkdown(item.text)) MessageType.MARKDOWN.name else MessageType.TEXT.name)
        }
        Result.success()
    }

    companion object {
        private const val TAG = "ScheduledMessageWorker"
        const val KEY_SCHEDULE_ID = "schedule_id"
        const val KEY_OWNER_USER_ID = "owner_user_id"
        /** 默认指数退避 30s 起，5 次约覆盖 7~8 分钟瞬态窗口。 */
        private const val MAX_TRANSIENT_RETRIES = 5

        /** 1.62：计算下一次重复发送时间（工作日重复时跳过周末，保持当天时刻）。 */
        fun nextRepeatSendAt(item: ScheduledMessage): Long {
            val base = item.sendAtMillis + item.repeatIntervalMs
            if (!item.weekdaysOnly) return base
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = base }
            while (cal.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SATURDAY ||
                cal.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SUNDAY) {
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }
    }

    /** 8.48：达重试上限后移除待发条目并提示失败（避免消息永久静默滞留列表）。 */
    private suspend fun abandonScheduledMessage(
        item: com.maodouchat.util.ScheduledMessage,
        scheduleId: String,
        expectedOwnerUserId: String
    ): Result {
        try {
            // 1.07：重复定时——发送前若配置了重复间隔，重新入队下一次（净增 1 条）
            // 1.21：与成功路径一致，达重复次数上限后不再重排
            if (item.repeatIntervalMs > 0L && (item.repeatCount == 0 || item.occurrencesSent + 1 < item.repeatCount)) {
                ScheduledMessageStore.add(
                    applicationContext,
                    item.chatId,
                    item.peerUserId,
                    item.text,
                    nextRepeatSendAt(item),
                    item.isGroup,
                    item.repeatIntervalMs,
                    item.repeatCount,
                    item.occurrencesSent + 1,
                    item.weekdaysOnly
                )?.let { rescheduled ->
                    ScheduledMessageScheduler.schedule(applicationContext, rescheduled)
                }
            }

            ScheduledMessageStore.removeForUser(applicationContext, scheduleId, expectedOwnerUserId)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w(TAG, "Abandon scheduled message failed", error)
        }
        com.maodouchat.util.AppNotifier.showScheduledMessageFailed(applicationContext)
        return Result.success()
    }
}
