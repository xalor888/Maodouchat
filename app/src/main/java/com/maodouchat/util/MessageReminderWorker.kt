package com.maodouchat.util

import com.maodouchat.notification.MessageNotificationService
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.maodouchat.MaodouchatApp
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.toModel
import com.maodouchat.security.BackgroundSessionGate

/**
 * 消息「稍后提醒」到点通知 Worker。
 * 到点后校验账号归属，发一条高优先级通知（点击直达聊天并高亮原消息），然后标记已提醒。
 */
class MessageReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val reminderId = inputData.getString(KEY_REMINDER_ID)?.takeIf { it.isNotBlank() }
            ?: return Result.success()
        val ownerUserId = inputData.getString(KEY_OWNER_USER_ID)?.takeIf { it.isNotBlank() }
            ?: return Result.success()
        val app = applicationContext as? MaodouchatApp
        val reminderDao = app?.database?.messageReminderDao()
            ?: AppDatabase.getInstance(applicationContext).messageReminderDao()
        if (!BackgroundSessionGate.mayContinue(
            expectedUserId = ownerUserId,
        )
        ) {
            // 账号已切换/登出：提醒作废
            reminderDao.deleteById(reminderId, ownerUserId)
            MessageReminderScheduler.cancel(applicationContext, reminderId)
            return Result.success()
        }
        val entity = reminderDao.getById(reminderId, ownerUserId)
            ?: return Result.success() // 已取消
        val reminder = entity.toModel()
        if (reminder.remindAtMillis > System.currentTimeMillis()) {
            // 时间被拨回：重排到剩余时间
            MessageReminderScheduler.reschedule(applicationContext, reminder)
            return Result.success()
        }
        val shown = MessageNotificationService.showMessageReminder(
            context = applicationContext,
            chatId = reminder.chatId,
            messageId = reminder.messageId,
            messagePreview = reminder.messagePreview,
            expectedUserId = ownerUserId,
        )
        if (shown) {
            reminderDao.deleteById(reminderId, ownerUserId)
        } else {
            // 通知未展示（权限撤销/会话门禁/前台清理）时明确放弃——删除存储行并取消作业，
            // 否则提醒成为「列表里看得见、永不触发」的孤儿；权限恢复后由用户重新设置。
            Log.w(TAG, "message reminder notification not shown; abandoning $reminderId")
            reminderDao.deleteById(reminderId, ownerUserId)
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

