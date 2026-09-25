package com.maodouchat.util

import com.maodouchat.notification.MessageNotificationService
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.maodouchat.MaodouchatApp
import com.maodouchat.conversation.ConversationCommandOutcome
import com.maodouchat.data.local.dao.ScheduledMessageDao
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.local.entity.toEntity
import com.maodouchat.data.local.entity.toModel
import com.maodouchat.network.TokenManager
import com.maodouchat.quickreply.ChatGateVerdict
import com.maodouchat.quickreply.QuickReplyPolicy
import com.maodouchat.scheduling.ConversationScheduledMessageDispatcher
import com.maodouchat.scheduling.ScheduledTimeCalculation
import com.maodouchat.security.BackgroundSessionGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 到期后把本地定时文本转成 SENDING 消息并写入 messaging v2 持久发件箱。
 * 单聊和群聊共用同一条按设备投递、离线可收敛的发送管线。
 */
class ScheduledMessageWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val scheduleId = inputData.getString(KEY_SCHEDULE_ID) ?: return@withContext Result.success()
        val app = applicationContext as? MaodouchatApp
            ?: return@withContext Result.retry()
        val scheduledDao = app.database.scheduledMessageDao()
        val expectedOwnerUserId = inputData.getString(KEY_OWNER_USER_ID)
            ?.takeIf(String::isNotBlank)
            ?: scheduledDao.getByIdWithoutOwner(scheduleId)?.ownerUserId
            ?: return@withContext Result.success()
        val entity = scheduledDao.getById(scheduleId, expectedOwnerUserId)
            ?: return@withContext Result.success()
        val item = entity.toModel()
        val tokenManager = TokenManager.getInstance(applicationContext)
        val ownerUserId = tokenManager.getUserId().orEmpty()
        val token = tokenManager.getToken().orEmpty()
        // 账号不匹配/无 token 通常是登出清理进行中的瞬态（purge 会取消本任务）；
        // 若 purge 部分失败导致任务残留，封顶重试次数避免无限 retry 耗电。
        if (ownerUserId != expectedOwnerUserId || token.isBlank()) {
            return@withContext if (runAttemptCount >= MAX_TRANSIENT_RETRIES) {
                abandonScheduledMessage(scheduledDao, item, scheduleId, expectedOwnerUserId)
            } else {
                Result.retry()
            }
        }
        if (!BackgroundSessionGate.mayContinue(
            expectedUserId = ownerUserId,
        )
        ) {
            return@withContext if (runAttemptCount >= MAX_TRANSIENT_RETRIES) {
                abandonScheduledMessage(scheduledDao, item, scheduleId, expectedOwnerUserId)
            } else {
                Result.retry()
            }
        }
        // 到点发送必须与手动发送同门禁：密聊/会话锁定的会话不得经定时路径绕过隐私边界。
        when (val gate = QuickReplyPolicy.gateForChat(app, item.chatId, ownerUserId)) {
            is ChatGateVerdict.Allowed -> Unit
            is ChatGateVerdict.Rejected -> {
                Log.i(TAG, "scheduled send rejected by privacy gate (${gate.reason}) chat=${item.chatId}")
                return@withContext abandonScheduledMessage(scheduledDao, item, scheduleId, expectedOwnerUserId)
            }
        }

        // Stage via ConversationScheduledMessageDispatcher / ConversationCommandFacade
        val dispatcher = ConversationScheduledMessageDispatcher(
            facade = app.conversationCommandFacade,
            resolveChat = { chatId -> app.database.chatDao().getChatById(chatId)?.toDomain() },
        )
        val outcome = try {
            dispatcher.stage(item, expectedOwnerUserId)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Failed staging scheduled message", error)
            return@withContext Result.retry()
        }

        when (outcome) {
            is ConversationCommandOutcome.Staged -> Unit
            is ConversationCommandOutcome.Rejected -> {
                Log.w(TAG, "scheduled send rejected by command facade (${outcome.reason}) chat=${item.chatId}")
                return@withContext abandonScheduledMessage(scheduledDao, item, scheduleId, expectedOwnerUserId)
            }
        }

        // 重复定时消息：先重排下一次再移除当前到期条目
        if (item.repeatIntervalMs > 0L && (item.repeatCount == 0 || item.occurrencesSent + 1 < item.repeatCount)) {
            try {
                val nextSendAt = ScheduledTimeCalculation.nextRepeatSendAt(
                    sendAtMillis = item.sendAtMillis,
                    repeatIntervalMs = item.repeatIntervalMs,
                    weekdaysOnly = item.weekdaysOnly,
                    timeZoneId = item.timeZoneId,
                )
                val rescheduled = item.copy(
                    id = "sch_${UUID.randomUUID().toString().take(12)}",
                    sendAtMillis = nextSendAt,
                    createdAtMillis = System.currentTimeMillis(),
                    occurrencesSent = item.occurrencesSent + 1,
                    status = "PENDING",
                    attempt = 0,
                    idempotencyKey = UUID.randomUUID().toString(),
                )
                scheduledDao.upsert(rescheduled.toEntity())
                ScheduledMessageScheduler.schedule(applicationContext, rescheduled)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Reschedule recurring message failed", error)
            }
        }
        scheduledDao.deleteById(scheduleId, expectedOwnerUserId)
        MaodouchatApp.emitMessageSent(
            item.chatId,
            item.text.take(200),
            if (com.maodouchat.messaging.ChatMarkdown.looksLikeMarkdown(item.text)) "MARKDOWN" else "TEXT",
        )
        Result.success()
    }

    companion object {
        private const val TAG = "ScheduledMessageWorker"
        const val KEY_SCHEDULE_ID = "schedule_id"
        const val KEY_OWNER_USER_ID = "owner_user_id"
        /** 默认指数退避 30s 起，5 次约覆盖 7~8 分钟瞬态窗口。 */
        private const val MAX_TRANSIENT_RETRIES = 5

        /** 计算下一次重复发送时间（工作日重复时跳过周末，保持当天时刻，支持时区/夏令时）。 */
        fun nextRepeatSendAt(item: ScheduledMessage): Long =
            ScheduledTimeCalculation.nextRepeatSendAt(
                sendAtMillis = item.sendAtMillis,
                repeatIntervalMs = item.repeatIntervalMs,
                weekdaysOnly = item.weekdaysOnly,
                timeZoneId = item.timeZoneId,
            )
    }

    /** 达重试上限后移除待发条目并提示失败（避免消息永久静默滞留列表）。 */
    private suspend fun abandonScheduledMessage(
        dao: ScheduledMessageDao,
        item: ScheduledMessage,
        scheduleId: String,
        expectedOwnerUserId: String,
    ): Result {
        try {
            val ownerStillCurrent = BackgroundSessionGate.mayContinue(
                expectedUserId = expectedOwnerUserId,
                liveToken = TokenManager.getInstance(applicationContext).getToken(),
                liveUserId = TokenManager.getInstance(applicationContext).getUserId(),
            )
            if (ownerStillCurrent &&
                item.repeatIntervalMs > 0L && (item.repeatCount == 0 || item.occurrencesSent + 1 < item.repeatCount)
            ) {
                val nextSendAt = ScheduledTimeCalculation.nextRepeatSendAt(
                    sendAtMillis = item.sendAtMillis,
                    repeatIntervalMs = item.repeatIntervalMs,
                    weekdaysOnly = item.weekdaysOnly,
                    timeZoneId = item.timeZoneId,
                )
                val rescheduled = item.copy(
                    id = "sch_${UUID.randomUUID().toString().take(12)}",
                    sendAtMillis = nextSendAt,
                    createdAtMillis = System.currentTimeMillis(),
                    occurrencesSent = item.occurrencesSent + 1,
                    status = "PENDING",
                    attempt = 0,
                    idempotencyKey = UUID.randomUUID().toString(),
                )
                dao.upsert(rescheduled.toEntity())
                ScheduledMessageScheduler.schedule(applicationContext, rescheduled)
            }
            dao.deleteById(scheduleId, expectedOwnerUserId)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Abandon scheduled message failed", error)
        }
        MessageNotificationService.showScheduledMessageFailed(applicationContext)
        return Result.success()
    }
}

