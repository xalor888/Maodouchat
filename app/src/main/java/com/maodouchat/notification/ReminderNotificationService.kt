package com.maodouchat.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.maodouchat.MainActivity
import com.maodouchat.R
import com.maodouchat.data.repository.NotificationCenterItem
import com.maodouchat.ui.screen.chatlist.NotificationCenterType

import com.maodouchat.util.RuntimeFlags

/**
 * P07 服务拆分 1/4：Reminder（AI 任务提醒）通知服务。
 *
 * 已删除的 `AppNotifier` 巨型静态入口迁出的第一个垂直服务；调用方直连本服务。
 * 共享的渠道/账号门禁/post 能力由 [NotificationInfrastructure] 提供；
 * `PushTransport` 统一接入见 P07 后续步骤。
 */
object ReminderNotificationService {

    fun showAiTaskReminder(
        context: Context,
        taskId: String,
        chatId: String,
        taskTitle: String,
        dueAt: Long,
        showPreview: Boolean,
        soundEnabled: Boolean,
        expectedUserId: String,
    ): Boolean {
        if (!NotificationInfrastructure.notificationOwnerMatches(context, expectedUserId)) return false
        NotificationInfrastructure.ensureChannels(context)
        if (!NotificationInfrastructure.canPostNotifications(context)) return false
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(NotificationIntents.EXTRA_OPEN_AI_TASKS_CHAT_ID, chatId)
            data = Uri.parse(NotificationSlotPolicy.aiTaskDataUri(taskId))
        }
        with(NotificationInfrastructure) { tapIntent.putNotificationOwner(expectedUserId) }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NotificationSlotPolicy.aiTaskRequestCode(taskId),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val chatLocked = NotificationInfrastructure.isChatPinLocked(context, chatId)
        val secretChat = NotificationInfrastructure.isSecretChat(context, chatId)
        val hideTaskBody = NotificationInfrastructure.shouldHideSensitiveDetails(context, showPreview) || chatLocked || (secretChat && RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_NOTIF_PREVIEW_BLOCK))
        val body = if (!hideTaskBody) {
            taskTitle
        } else if (chatLocked) {
            context.getString(R.string.chat_lock_list_preview)
        } else if (secretChat) {
            context.getString(R.string.secret_chat_notification_preview)
        } else {
            context.getString(R.string.notification_ai_task_due)
        }
        val notification = NotificationCompat.Builder(context, NotificationInfrastructure.CHANNEL_AI_TASKS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_ai_task_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationInfrastructure.genericNotification(context, NotificationInfrastructure.CHANNEL_AI_TASKS, R.string.notification_ai_task_due))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setWhen(dueAt)
            .setShowWhen(true)
            .setGroup(NotificationSlotPolicy.aiTaskGroupKey(chatId))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(!NotificationInfrastructure.effectiveSoundEnabled(context, soundEnabled))
            .build()
        if (!NotificationInfrastructure.notificationOwnerMatches(context, expectedUserId)) return false
        NotificationInfrastructure.safeNotify(context, NotificationSlotPolicy.AI_TASK_TAG, NotificationSlotPolicy.aiTaskNotifyId(taskId), notification, expectedUserId)
        // 分组需要一条 summary 通知才能在所有 Android 版本（尤其 7.0+）正确折叠展示；
        // 与子通知共用 AI 任务 tag（见 NotificationSlotPolicy），现有 cancel* 方法会一并清理。
        showAiTaskGroupSummary(context, chatId, expectedUserId)
        // 同步到通知中心
        runCatching {
            com.maodouchat.MaodouchatApp.emitNotificationCenterItem(
                NotificationCenterItem(
                    id = "ai_task_$taskId",
                    type = NotificationCenterType.AI_TASK,
                    mergeKey = "ai_tasks_$chatId",
                    title = context.getString(R.string.notification_ai_task_title),
                    subtitle = if (hideTaskBody) null else taskTitle,
                    preview = body,
                    deeplink = "maodouchat:ai_tasks:$chatId",
                    extra = mapOf("taskId" to taskId, "chatId" to chatId, "dueAt" to dueAt.toString())
                ),
                expectedUserId = expectedUserId,
            )
        }
        return true
    }

    fun cancelAiTaskReminder(context: Context, taskId: String) {
        NotificationManagerCompat.from(context).cancel(NotificationSlotPolicy.AI_TASK_TAG, NotificationSlotPolicy.aiTaskNotifyId(taskId))
    }

    /**
     * 同一 chat 的所有 AI 任务提醒共享一个分组；Android 7.0+ 必须有一条 group-summary
     * 通知，否则分组内的子通知可能不完整展示。summary 用固定 id，随最后一个子通知被
     * cancelAiTaskRemindersForChat / cancelAllAiTaskReminders 一并移除。
     */
    private fun showAiTaskGroupSummary(context: Context, chatId: String, expectedUserId: String) {
        NotificationInfrastructure.ensureChannels(context)
        val groupKey = NotificationSlotPolicy.aiTaskGroupKey(chatId)
        val summary = NotificationCompat.Builder(context, NotificationInfrastructure.CHANNEL_AI_TASKS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_ai_task_group_summary))
            .setContentText(context.getString(R.string.notification_ai_task_due))
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(true)
            .build()
        NotificationInfrastructure.safeNotify(context, NotificationSlotPolicy.AI_TASK_TAG, NotificationSlotPolicy.aiTaskSummaryId(chatId), summary, expectedUserId)
    }

    /**
     * Drop tray reminders for one chat when the AI tasks screen (or center row) is opened.
     * Notifications are grouped as `ai_tasks_{chatId}` in [showAiTaskReminder].
     */
    fun cancelAiTaskRemindersForChat(context: Context, chatId: String) {
        if (chatId.isBlank() || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val groupKey = NotificationSlotPolicy.aiTaskGroupKey(chatId)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.activeNotifications
            .filter { it.tag == NotificationSlotPolicy.AI_TASK_TAG && it.notification.group == groupKey }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    fun cancelAllAiTaskReminders(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.activeNotifications
            .filter { it.tag == NotificationSlotPolicy.AI_TASK_TAG }
            .forEach { manager.cancel(it.tag, it.id) }
    }
}
