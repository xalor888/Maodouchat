package com.maodouchat.util

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.maodouchat.MaodouchatApp
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.messaging.v2.MessagingV2MessageGateway
import com.maodouchat.network.TokenManager
import com.maodouchat.quickreply.ChatGateVerdict
import com.maodouchat.quickreply.QuickReplyPolicy
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
        // 到点发送必须与手动发送同门禁：密聊/会话锁定的会话不得经定时路径绕过隐私边界。
        // 被拒时按 abandon 处理（重复项按既有策略重排下一次），不静默丢弃用户意图。
        when (val gate = QuickReplyPolicy.gateForChat(app, item.chatId, ownerUserId)) {
            is ChatGateVerdict.Allowed -> Unit
            is ChatGateVerdict.Rejected -> {
                android.util.Log.i(TAG, "scheduled send rejected by privacy gate (${gate.reason}) chat=${item.chatId}")
                return@withContext abandonScheduledMessage(item, scheduleId, expectedOwnerUserId)
            }
        }
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
        if (!BackgroundSessionGate.mayContinue(
                expectedUserId = expectedOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            app.database.messageDao().deleteMessageById(msgId)
            return@withContext Result.retry()
        }

        // Stage the message before mutating the schedule. If the process exits after this
        // point, the durable outbox can still converge and the deterministic message id makes
        // the next worker retry idempotent.
        runCatching {
            val messageStore = LocalMessageStore(app.database.messageDao(), app.database)
            val chat = app.database.chatDao().getChatById(item.chatId)
            MessagingV2MessageGateway(
                database = app.database,
                messageStore = messageStore,
                outbox = app.messagingV2Outbox,
            ).stageAndEnqueue(
                message = optimistic,
                body = item.text,
                type = optimistic.type,
                groupRevision = chat?.memberRevision?.takeIf { chat.isGroup },
            )
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            return@withContext Result.retry()
        }

        // 1.14 修复（1.07 遗留 bug）：此前成功发送路径只 remove 不重排，
        // 重复定时消息首轮发送后即终止，重复从未生效。现改为先重排下一次再移除当前到条目。
        // 1.21：repeatCount>0 时达上限（occurrencesSent+1 >= repeatCount）即停止重排。
        if (item.repeatIntervalMs > 0L && (item.repeatCount == 0 || item.occurrencesSent + 1 < item.repeatCount)) {
            try {
                ScheduledMessageStore.addForUser(
                    context = applicationContext,
                    ownerUserId = expectedOwnerUserId,
                    chatId = item.chatId,
                    peerUserId = item.peerUserId,
                    text = item.text,
                    sendAtMillis = nextRepeatSendAt(item),
                    isGroup = item.isGroup,
                    repeatIntervalMs = item.repeatIntervalMs,
                    repeatCount = item.repeatCount,
                    occurrencesSent = item.occurrencesSent + 1,
                    weekdaysOnly = item.weekdaysOnly,
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
        MaodouchatApp.emitMessageSent(
            item.chatId,
            item.text.take(200),
            optimistic.type.name,
        )
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
            // 重复定时重排前必须确认账号仍归属当前会话：purge 与运行中的 Worker 存在竞态，
            // 登出/换号后重排会给已登出账号复活一条待发行并触发离任通知。
            val ownerStillCurrent = com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = expectedOwnerUserId,
                liveToken = TokenManager.getInstance(applicationContext).getToken(),
                liveUserId = TokenManager.getInstance(applicationContext).getUserId(),
            )
            // 1.07：重复定时——发送前若配置了重复间隔，重新入队下一次（净增 1 条）
            // 1.21：与成功路径一致，达重复次数上限后不再重排
            if (ownerStillCurrent &&
                item.repeatIntervalMs > 0L && (item.repeatCount == 0 || item.occurrencesSent + 1 < item.repeatCount)
            ) {
                ScheduledMessageStore.addForUser(
                    context = applicationContext,
                    ownerUserId = expectedOwnerUserId,
                    chatId = item.chatId,
                    peerUserId = item.peerUserId,
                    text = item.text,
                    sendAtMillis = nextRepeatSendAt(item),
                    isGroup = item.isGroup,
                    repeatIntervalMs = item.repeatIntervalMs,
                    repeatCount = item.repeatCount,
                    occurrencesSent = item.occurrencesSent + 1,
                    weekdaysOnly = item.weekdaysOnly,
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
