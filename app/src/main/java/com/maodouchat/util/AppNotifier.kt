package com.maodouchat.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.maodouchat.MainActivity
import com.maodouchat.R
import com.maodouchat.notification.NotificationSlotPolicy
import com.maodouchat.data.repository.NotificationCenterItem
import com.maodouchat.notification.NotificationPreferences
import com.maodouchat.notification.NotificationSoundPolicy
import com.maodouchat.security.AppLockManager
import com.maodouchat.ui.screen.chatlist.NotificationCenterType

/**
 * 应用系统通知工具。
 *
 * - Android 8+ 必须先注册 channel，否则通知不显示
 * - 13+ 需要 POST_NOTIFICATIONS 运行时权限
 * - 设计目标：低打扰、不重复轰炸；同一 chatId/相同 ID 会先 cancel 再发
 */
object AppNotifier {

    internal const val CHANNEL_MESSAGES = "messages_v4"
    internal const val CHANNEL_GROUP_MESSAGES = "group_messages_v4"
    internal const val CHANNEL_CALLS = "calls_v4"
    internal const val CHANNEL_AI_TASKS = "ai_tasks_v4"
    private val LEGACY_CHANNEL_IDS = listOf("messages", "group_messages", "calls", "ai_tasks")
    private val notificationMutationLock = Any()

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 8.48：用户可选系统通知铃声（RingtoneManager picker）；未选时使用内置消息提示音
        // （9.3xx：此前未选时依赖系统默认铃声，部分厂商渠道建好后无声——现在显式设置内置音效）
        val builtinTick = android.net.Uri.parse("android.resource://${context.packageName}/raw/notify_message")
        val ringtoneUri = com.maodouchat.notification.NotificationPreferences.ringtoneUri(context)
            ?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
            ?: builtinTick
        // 0.72：群聊独立铃声（回退单聊铃声）
        val groupRingtoneUri = com.maodouchat.notification.NotificationPreferences.groupRingtoneUri(context)
            ?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
            ?: ringtoneUri
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        // 9.216：渠道配置指纹——Android 通知渠道的铃声/振动只在创建时生效，
        // 用户改设置后必须删除重建渠道才能生效（重建会重置系统侧对渠道的手动调整，预期内）。
        val vibrationOn = com.maodouchat.notification.NotificationPreferences.vibrationEnabled(context)
        val fingerprint = listOf(ringtoneUri, groupRingtoneUri, vibrationOn, "tick-v7").joinToString("|")
        val configPrefs = context.applicationContext.getSharedPreferences("notif_channel_config", Context.MODE_PRIVATE)
        val storedFingerprint = configPrefs.getString("channel_fingerprint", null)
        LEGACY_CHANNEL_IDS.forEach { nm.deleteNotificationChannel(it) }
        if (storedFingerprint != fingerprint) {
            nm.deleteNotificationChannel(CHANNEL_MESSAGES)
            nm.deleteNotificationChannel(CHANNEL_GROUP_MESSAGES)
            nm.deleteNotificationChannel(CHANNEL_CALLS)
            nm.deleteNotificationChannel(CHANNEL_AI_TASKS)
        }
        if (storedFingerprint != fingerprint) {
            configPrefs.edit().putString("channel_fingerprint", fingerprint).apply()
        }
        fun applySound(channel: NotificationChannel, uri: android.net.Uri?) {
            if (uri != null) channel.setSound(uri, attrs)
        }
        // 1.133：震动开关（渠道级）
        fun applyVibration(channel: NotificationChannel) {
            channel.enableVibration(vibrationOn)
        }
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.notification_channel_messages), NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = context.getString(R.string.notification_channel_messages_description)
                    applySound(this, ringtoneUri)
                    applyVibration(this)
                }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_GROUP_MESSAGES, context.getString(R.string.notification_channel_group_messages), NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = context.getString(R.string.notification_channel_group_messages_description)
                    applySound(this, groupRingtoneUri)
                    applyVibration(this)
                }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, context.getString(R.string.notification_channel_calls), NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = context.getString(R.string.notification_channel_calls_description)
                    applySound(this, ringtoneUri)
                    applyVibration(this)
                }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_AI_TASKS, context.getString(R.string.notification_channel_ai_tasks), NotificationManager.IMPORTANCE_DEFAULT)
                .apply {
                    description = context.getString(R.string.notification_channel_ai_tasks_description)
                    applySound(this, ringtoneUri)
                    applyVibration(this)
                }
        )
    }

    fun showMessage(
        context: Context,
        chatId: String,
        senderName: String,
        preview: String,
        messageId: String,
        soundEnabled: Boolean = true,
        expectedUserId: String,
        /** 0.72：群聊消息走独立渠道（独立铃声）。 */
        isGroup: Boolean = false,
    ) = com.maodouchat.notification.MessageNotificationService.showMessage(
        context, chatId, senderName, preview, messageId, soundEnabled, expectedUserId, isGroup
    )

    /**
     * 消息「稍后提醒」到点通知：点击直达聊天并高亮原消息。
     * 复用 messages 渠道；预览同样脱敏（密聊/PIN/全局 App 锁）。
     */
    fun showMessageReminder(
        context: Context,
        chatId: String,
        messageId: String,
        messagePreview: String,
        expectedUserId: String,
    ): Boolean = com.maodouchat.notification.MessageNotificationService.showMessageReminder(
        context, chatId, messageId, messagePreview, expectedUserId
    )

    fun showMissedCall(
        context: Context,
        callId: String,
        callerName: String,
        isVideo: Boolean,
        expectedUserId: String,
    ) = com.maodouchat.notification.CallNotificationService.showMissedCall(
        context, callId, callerName, isVideo, expectedUserId
    )

    fun showIncomingCall(
        context: Context,
        callId: String,
        isVideo: Boolean,
        soundEnabled: Boolean = true,
        senderId: String = "",
        expectedUserId: String,
    ) = com.maodouchat.notification.CallNotificationService.showIncomingCall(
        context, callId, isVideo, soundEnabled, senderId, expectedUserId
    )

    /** 对端已挂断 / 本地已接听或拒绝后清掉系统来电通知，避免幽灵响铃 */
    fun cancelIncomingCall(context: Context, callId: String) =
        com.maodouchat.notification.CallNotificationService.cancelIncomingCall(context, callId)

    /**
     * Missed-call tray uses [NotificationSlotPolicy.missedCallNotifyId] — independent of
     * [NotificationSlotPolicy.incomingCallNotifyId] so endCall / cancelIncoming never
     * erase a just-posted missed entry.
     */
    fun cancelMissedCall(context: Context, callId: String) =
        com.maodouchat.notification.CallNotificationService.cancelMissedCall(context, callId)

    // 槽位分配见 [NotificationSlotPolicy]（来电/未接 id 盐隔离）。

    fun showPostInteraction(
        context: Context,
        postId: String,
        isComment: Boolean,
        soundEnabled: Boolean = true,
        expectedUserId: String,
        // 1.113：互动类型（LIKE / COMMENT / COMMENT_LIKE），用于文案区分
        interaction: String = if (isComment) "COMMENT" else "LIKE",
        // 1.130：评论/回复/评论赞内容预览（非空时用于通知文本与中心 preview）
        preview: String? = null,
        // 1.132：评论 id（打开动态时跳转到该评论）
        commentId: String? = null,
    ) = com.maodouchat.notification.SocialNotificationService.showPostInteraction(
        context, postId, isComment, soundEnabled, expectedUserId, interaction, preview, commentId
    )

    /** Clear post interaction tray (id = `post_{postId}`.hashCode()). */
    fun cancelPostInteraction(context: Context, postId: String) =
        com.maodouchat.notification.SocialNotificationService.cancelPostInteraction(context, postId)

    /** 1.119：设置页「发送测试通知」——用当前通知偏好发一条本地通知验证铃声/震动。 */
    fun showTestNotification(context: Context) =
        com.maodouchat.notification.MessageNotificationService.showTestNotification(context)

    /**
     * Friend-request tray (routing metadata only).
     * Tap → main contacts tab via [EXTRA_OPEN_CONTACTS].
     */
    /** 系统公告通知（高优先级 EMERGENCY/MAINTENANCE）。点击打开 App，详情由公告中心拉取。 */
    fun showAnnouncement(
        context: Context,
        announcementId: String,
        title: String,
        level: String,
        soundEnabled: Boolean = true,
        expectedUserId: String,
    ) = com.maodouchat.notification.SocialNotificationService.showAnnouncement(
        context, announcementId, title, level, soundEnabled, expectedUserId
    )

    fun showFriendRequest(
        context: Context,
        requestId: String,
        action: String,
        soundEnabled: Boolean = true,
        expectedUserId: String,
    ) = com.maodouchat.notification.SocialNotificationService.showFriendRequest(
        context, requestId, action, soundEnabled, expectedUserId
    )

    /**
     * Group-invite tray (routing metadata only).
     * Tap → contacts tab via [EXTRA_OPEN_CONTACTS] (same surface as friend requests).
     */
    fun showGroupInvite(
        context: Context,
        inviteId: String,
        chatId: String,
        action: String,
        soundEnabled: Boolean = true,
        expectedUserId: String,
    ) = com.maodouchat.notification.SocialNotificationService.showGroupInvite(
        context, inviteId, chatId, action, soundEnabled, expectedUserId
    )

    fun showAiTaskReminder(
        context: Context,
        taskId: String,
        chatId: String,
        taskTitle: String,
        dueAt: Long,
        showPreview: Boolean,
        soundEnabled: Boolean,
        expectedUserId: String,
    ): Boolean = com.maodouchat.notification.ReminderNotificationService.showAiTaskReminder(
        context, taskId, chatId, taskTitle, dueAt, showPreview, soundEnabled, expectedUserId
    )

    fun cancelMessage(context: Context, chatId: String) =
        com.maodouchat.notification.MessageNotificationService.cancelMessage(context, chatId)

    fun cancelAiTaskReminder(context: Context, taskId: String) =
        com.maodouchat.notification.ReminderNotificationService.cancelAiTaskReminder(context, taskId)

    /**
     * Drop tray reminders for one chat when the AI tasks screen (or center row) is opened.
     * Notifications are grouped as `ai_tasks_{chatId}` in [showAiTaskReminder].
     */
    fun cancelAiTaskRemindersForChat(context: Context, chatId: String) =
        com.maodouchat.notification.ReminderNotificationService.cancelAiTaskRemindersForChat(context, chatId)

    fun cancelAllAiTaskReminders(context: Context) =
        com.maodouchat.notification.ReminderNotificationService.cancelAllAiTaskReminders(context)

    fun cancelAllFriendRequests(context: Context) =
        com.maodouchat.notification.SocialNotificationService.cancelAllFriendRequests(context)

    fun cancelAllGroupInvites(context: Context) =
        com.maodouchat.notification.SocialNotificationService.cancelAllGroupInvites(context)

    /** Logout / account switch: drop every posted tray notification for this app. */
    fun cancelAll(context: Context) {
        synchronized(notificationMutationLock) {
            NotificationManagerCompat.from(context).cancelAll()
        }
    }

    internal fun effectiveSoundEnabled(context: Context, preferenceEnabled: Boolean): Boolean =
        NotificationSoundPolicy.messageSoundEnabled(
            runtimeFlagEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.NOTIFICATION_SOUND),
            userPreferenceEnabled = preferenceEnabled,
        )

    internal fun effectiveRingtoneEnabled(context: Context, preferenceEnabled: Boolean): Boolean =
        NotificationSoundPolicy.ringtoneEnabled(
            runtimeFlagEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.RINGTONE),
            userPreferenceEnabled = preferenceEnabled,
        )

    internal fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    internal fun notificationOwnerMatches(context: Context, expectedUserId: String): Boolean {
        if (com.maodouchat.security.SecureSessionManager.isPurgeInProgress()) return false
        val tokenManager = com.maodouchat.network.TokenManager.getInstance(context.applicationContext)
        val liveUserId = tokenManager.getUserId()
        return com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = expectedUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = liveUserId,
        )
    }

    internal fun Intent.putNotificationOwner(expectedUserId: String) {
        putExtra(EXTRA_NOTIFICATION_OWNER_USER_ID, expectedUserId)
    }

    /**
     * Lint cannot prove [canPostNotifications] gates every notify site; callers already return early.
     * Catch SecurityException so a revoked runtime permission never crashes the process.
     */
    @SuppressLint("MissingPermission")
    internal fun safeNotify(
        context: Context,
        id: Int,
        notification: android.app.Notification,
        expectedUserId: String,
    ) {
        synchronized(notificationMutationLock) {
            if (!notificationOwnerMatches(context, expectedUserId) || !canPostNotifications(context)) {
                return@synchronized
            }
            val manager = NotificationManagerCompat.from(context)
            try {
                manager.notify(id, notification)
                if (!notificationOwnerMatches(context, expectedUserId)) manager.cancel(id)
            } catch (_: SecurityException) {
                // Permission revoked between check and post (Android 13+).
            }
        }
    }

    @SuppressLint("MissingPermission")
    internal fun safeNotify(
        context: Context,
        tag: String,
        id: Int,
        notification: android.app.Notification,
        expectedUserId: String,
    ) {
        synchronized(notificationMutationLock) {
            if (!notificationOwnerMatches(context, expectedUserId) || !canPostNotifications(context)) {
                return@synchronized
            }
            val manager = NotificationManagerCompat.from(context)
            try {
                manager.notify(tag, id, notification)
                if (!notificationOwnerMatches(context, expectedUserId)) manager.cancel(tag, id)
            } catch (_: SecurityException) {
                // Permission revoked between check and post (Android 13+).
            }
        }
    }

    internal fun shouldHideSensitiveDetails(context: Context, explicitPreviewEnabled: Boolean = true): Boolean {
        val userPreviewEnabled = NotificationPreferences.previewEnabled(context)
        return NotificationPrivacyPolicy.hideSensitiveDetails(
            appLockEnabled = AppLockManager.isEnabled(context),
            previewEnabled = explicitPreviewEnabled && userPreviewEnabled
        )
    }

    /** Local chat PIN: hide tray/notification-center body even when previews are enabled. */
    internal fun isChatPinLocked(context: Context, chatId: String): Boolean {
        if (chatId.isBlank()) return false
        val app = context.applicationContext as? com.maodouchat.MaodouchatApp ?: return false
        return try {
            app.secretConversationController.capabilities(chatId).isLocked
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** Local secret chat: hide tray/notification-center body like PIN lock. */
    internal fun isSecretChat(context: Context, chatId: String): Boolean {
        if (chatId.isBlank()) return false
        val app = context.applicationContext as? com.maodouchat.MaodouchatApp ?: return false
        return try {
            val caps = app.secretConversationController.capabilities(chatId)
            !com.maodouchat.domain.messaging.ConversationPrivacyPolicy.allows(
                caps,
                com.maodouchat.domain.messaging.PrivacyAction.NOTIFICATION_PREVIEW
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    internal fun genericNotification(context: Context, channelId: String, bodyRes: Int) =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(bodyRes))
            .build()

    const val EXTRA_OPEN_CHAT_ID = "maodouchat_open_chat_id"
    /** 消息「稍后提醒」点击：打开聊天后高亮指定消息。 */
    const val EXTRA_OPEN_MESSAGE_ID = "maodouchat_open_message_id"
    const val EXTRA_OPEN_AI_TASKS_CHAT_ID = "maodouchat_open_ai_tasks_chat_id"
    const val EXTRA_OPEN_POST_ID = "maodouchat_open_post_id"
    const val EXTRA_OPEN_MISSED_CALL = "maodouchat_open_missed_call"
    /** Friend-request / contacts deep-link from tray. */
    const val EXTRA_OPEN_CONTACTS = "maodouchat_open_contacts"
    const val EXTRA_NOTIFICATION_OWNER_USER_ID = "maodouchat_notification_owner_user_id"
    /** Tap from FCM/system call notification → open app and poll pending offers. */
    const val EXTRA_OPEN_INCOMING_CALL = "maodouchat_open_incoming_call"
    const val EXTRA_INCOMING_CALL_ID = "maodouchat_incoming_call_id"
    const val EXTRA_INCOMING_CALL_VIDEO = "maodouchat_incoming_call_video"
    const val EXTRA_INCOMING_CALL_SENDER_ID = "maodouchat_incoming_call_sender_id"

    /** 8.44：来电/未接/动态互动/测试通知独立 tag——槽位分配见 [NotificationSlotPolicy]，
     * 三者此前共用 null-tag id 空间，哈希碰撞时响应来电会被动态互动通知顶掉。 */

    /**
     * 8.48：定时消息发送失败通知（达重试上限后移除待发条目时提示，避免静默丢失）。
     */
    fun showScheduledMessageFailed(context: Context) =
        com.maodouchat.notification.MessageNotificationService.showScheduledMessageFailed(context)
}
