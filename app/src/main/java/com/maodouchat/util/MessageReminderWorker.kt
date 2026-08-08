package com.maodouchat.util

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.maodouchat.network.TokenManager
import com.maodouchat.security.BackgroundSessionGate

/**
 * 消息「稍后提醒」到点通知 Worker。
 * 到点后校验账号归属，发一条高优先级通知（点击直达聊天并高亮原消息），然后标记已提醒。
 */
class MessageReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val reminderId = inputData.getString(KEY_REMINDER_ID)?.takeIf { it.isNotBlank() }
            ?: return Result.success()
        val ownerUserId = inputData.getString(KEY_OWNER_USER_ID)?.takeIf { it.isNotBlank() }
            ?: return Result.success()
        val tokenManager = TokenManager.getInstance(applicationContext)
        if (!BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            // 账号已切换/登出：提醒作废
            MessageReminderStore.remove(applicationContext, reminderId, ownerUserId)
            MessageReminderScheduler.cancel(applicationContext, reminderId)
            return Result.success()
        }
        val reminder = MessageReminderStore.get(applicationContext, reminderId, ownerUserId)
            ?: return Result.success() // 已取消
        if (reminder.remindAtMillis > System.currentTimeMillis()) {
            // 时间被拨回：重排到剩余时间
            MessageReminderScheduler.reschedule(applicationContext, reminder)
            return Result.success()
        }
        val shown = AppNotifier.showMessageReminder(
            context = applicationContext,
            chatId = reminder.chatId,
            messageId = reminder.messageId,
            messagePreview = reminder.messagePreview,
            expectedUserId = ownerUserId,
        )
        if (shown) {
            MessageReminderStore.remove(applicationContext, reminderId, ownerUserId)
        } else {
            // 8.51：通知未展示（权限撤销/会话门禁/前台清理）时明确放弃——删除存储行并取消作业，
            // 否则提醒成为「列表里看得见、永不触发」的孤儿；权限恢复后由用户重新设置。
            Log.w(TAG, "message reminder notification not shown; abandoning $reminderId")
            MessageReminderStore.remove(applicationContext, reminderId, ownerUserId)
            MessageReminderScheduler.cancel(applicationContext, reminderId)
        }
        return Result.success()
    }

    companion object {
        const val KEY_REMINDER_ID = "reminder_id"
        const val KEY_OWNER_USER_ID = "owner_user_id"
        private const val TAG = "MessageReminderWorker"
    }
}
