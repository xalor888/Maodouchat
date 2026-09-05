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
    private const val CHANNEL_GROUP_MESSAGES = "group_messages_v4"
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
    ) {
        if (!notificationOwnerMatches(context, expectedUserId)) return
        ensureChannels(context)
        if (!canPostNotifications(context)) return
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_CHAT_ID, chatId)
            putNotificationOwner(expectedUserId)
            // Unique data URI so two chatIds whose hashCode() collides still yield distinct
            // PendingIntents (extras are NOT part of PendingIntent identity); without this,
            // FLAG_UPDATE_CURRENT would overwrite one chat's tap target with the other's.
            data = Uri.parse(NotificationSlotPolicy.chatDataUri(chatId))
        }
        // notify 用真实 chatId 作 tag、id=0，使每个会话有独立通知槽位；PendingIntent 的唯一性
        // 则由上方 tapIntent 的 data URI 保证（requestCode=chatId.hashCode() 仍可能因碰撞复用同一
        // PendingIntent，故不能以 hashCode 单独区分会话）。
        val pi = PendingIntent.getActivity(
            context, NotificationSlotPolicy.chatRequestCode(chatId), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val chatLocked = isChatPinLocked(context, chatId)
        val secretChat = isSecretChat(context, chatId)
        val hideDetails = shouldHideSensitiveDetails(context) || chatLocked || (secretChat && RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_NOTIF_PREVIEW_BLOCK))
        val displayTitle = if (hideDetails) context.getString(R.string.app_name) else senderName
        val displayPreview = if (hideDetails) {
            when {
                chatLocked -> context.getString(R.string.chat_lock_list_preview)
                secretChat -> context.getString(R.string.secret_chat_notification_preview)
                else -> context.getString(R.string.notification_encrypted_message)
            }
        } else {
            // 1.23：名片裸标记不进通知正文（与会话列表预览 1.18 一致）
            com.maodouchat.ui.component.ChatMarkdown.stripContactCardMarker(preview)
        }
        // 0.72：群聊消息走独立渠道（独立铃声）
        val channelId = if (isGroup) CHANNEL_GROUP_MESSAGES else CHANNEL_MESSAGES
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(displayTitle)
            .setContentText(displayPreview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayPreview))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(genericNotification(context, channelId, R.string.notification_encrypted_message))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(
                !NotificationSoundPolicy.traySoundAllowed(
                    appInForeground = com.maodouchat.MaodouchatApp.appInForeground,
                    preferenceSoundEnabled = effectiveSoundEnabled(context, soundEnabled),
                )
            )
        // 9.209：第三方服务器模式标注服务器名——服务器身份非隐私内容，脱敏模式下也展示，
        // 避免同时连多个自建服务器的用户分不清通知来自哪台
        if (com.maodouchat.network.ServerIdentity.isThirdPartyServer) {
            com.maodouchat.network.ServerIdentity.current.value?.name
                ?.takeIf(String::isNotBlank)
                ?.let { builder.setSubText(it) }
        }

        // M11: 标记已读通知动作（仅非 PIN 锁定会话）
        if (!chatLocked) {
            val markReadIntent = Intent(context, com.maodouchat.quickreply.NotificationQuickReplyReceiver::class.java).apply {
                action = com.maodouchat.quickreply.NotificationQuickReplyReceiver.ACTION_MARK_READ
                putExtra(com.maodouchat.quickreply.NotificationQuickReplyReceiver.EXTRA_CHAT_ID, chatId)
                putNotificationOwner(expectedUserId)
                data = Uri.parse(NotificationSlotPolicy.markReadDataUri(chatId))
            }
            val markReadPendingIntent = PendingIntent.getBroadcast(
                context,
                NotificationSlotPolicy.markReadRequestCode(chatId),
                markReadIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val markReadAction = NotificationCompat.Action.Builder(
                R.drawable.ic_notification,
                context.getString(R.string.chat_mark_read),
                markReadPendingIntent,
            ).build()
            builder.addAction(markReadAction)
        }

        // M11: 行内快捷回复 RemoteInput（非脱敏且开启快捷回复时）
        if (!hideDetails && com.maodouchat.quickreply.QuickReplyPolicy.isEnabled(context)) {
            val remoteInput = androidx.core.app.RemoteInput.Builder(com.maodouchat.quickreply.NotificationQuickReplyReceiver.KEY_TEXT_REPLY)
                .setLabel(context.getString(R.string.quick_reply_hint))
                .build()

            val replyIntent = Intent(context, com.maodouchat.quickreply.NotificationQuickReplyReceiver::class.java).apply {
                action = com.maodouchat.quickreply.NotificationQuickReplyReceiver.ACTION_REPLY
                putExtra(com.maodouchat.quickreply.NotificationQuickReplyReceiver.EXTRA_CHAT_ID, chatId)
                putNotificationOwner(expectedUserId)
                data = Uri.parse(NotificationSlotPolicy.quickReplyDataUri(chatId))
            }

            val replyFlags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val replyPendingIntent = PendingIntent.getBroadcast(
                context,
                NotificationSlotPolicy.quickReplyRequestCode(chatId),
                replyIntent,
                replyFlags,
            )

            val replyAction = NotificationCompat.Action.Builder(
                R.drawable.ic_notification,
                context.getString(R.string.quick_reply_action),
                replyPendingIntent,
            )
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(true)
                .build()

            builder.addAction(replyAction)
        }

        val notification = builder.build()
        if (!notificationOwnerMatches(context, expectedUserId)) return
        // tag 用真实 chatId（而非其 hashCode），id 固定 0：每个会话独立通知槽位，彻底避免
        // (maodouchat_<chatId>).hashCode() 跨会话碰撞导致后到通知覆盖先到、点击跳错会话。
        safeNotify(context, NotificationSlotPolicy.messageTag(chatId), 0, notification, expectedUserId)
        // 同步到通知中心：App 锁开启时与系统通知同样脱敏，避免中心里仍显示发送者/预览
        runCatching {
            com.maodouchat.MaodouchatApp.emitNotificationCenterItem(
                NotificationCenterItem(
                    id = "msg_${chatId}_${messageId}",
                    type = NotificationCenterType.MESSAGE,
                    mergeKey = "msg_$chatId",
                    title = displayTitle,
                    subtitle = null,
                    preview = displayPreview,
                    deeplink = "maodouchat:chat:$chatId",
                    extra = mapOf("messageId" to messageId, "chatId" to chatId)
                ),
                expectedUserId = expectedUserId,
            )
        }
    }

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
    ): Boolean {
        if (!notificationOwnerMatches(context, expectedUserId)) return false
        ensureChannels(context)
        if (!canPostNotifications(context)) return false
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_CHAT_ID, chatId)
            putExtra(EXTRA_OPEN_MESSAGE_ID, messageId)
            putNotificationOwner(expectedUserId)
            data = Uri.parse(NotificationSlotPolicy.reminderDataUri(chatId, messageId))
        }
        val pi = PendingIntent.getActivity(
            context,
            NotificationSlotPolicy.reminderRequestCode(chatId),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val chatLocked = isChatPinLocked(context, chatId)
        val secretChat = isSecretChat(context, chatId)
        val hideDetails = shouldHideSensitiveDetails(context) || chatLocked ||
            (secretChat && RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_NOTIF_PREVIEW_BLOCK))
        val displayPreview = if (hideDetails) {
            when {
                chatLocked -> context.getString(R.string.chat_lock_list_preview)
                secretChat -> context.getString(R.string.secret_chat_notification_preview)
                else -> context.getString(R.string.notification_encrypted_message)
            }
        } else messagePreview
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.message_reminder_notification_title))
            .setContentText(displayPreview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayPreview))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(genericNotification(context, CHANNEL_MESSAGES, R.string.notification_encrypted_message))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!effectiveSoundEnabled(context, NotificationPreferences.soundEnabled(context)))
            .build()
        if (!notificationOwnerMatches(context, expectedUserId)) return false
        safeNotify(context, NotificationSlotPolicy.reminderTag(chatId), NotificationSlotPolicy.reminderNotifyId(messageId), notification, expectedUserId)
        return true
    }

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
    ) {
        if (!notificationOwnerMatches(context, expectedUserId)) return
        ensureChannels(context)
        if (!canPostNotifications(context)) return
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_POST_ID, postId)
            putNotificationOwner(expectedUserId)
            data = Uri.parse(NotificationSlotPolicy.postDataUri(postId))
        }
        val pi = PendingIntent.getActivity(
            context, NotificationSlotPolicy.postRequestCode(postId), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 1.113：评论被赞 → 独立文案；1.122：回复 → 独立文案
        val textRes = when (interaction) {
            "COMMENT_LIKE" -> R.string.notification_post_comment_like
            "REPLY" -> R.string.notification_post_reply
            "COMMENT" -> R.string.notification_post_comment
            else -> R.string.notification_post_like
        }
        val baseText = context.getString(textRes)
        // 9.137：互动通知预览与 showMessage 同口径脱敏——App 锁/隐藏通知内容开启时，
        // 锁屏与通知中心不得明文展示评论/回复正文（此前是唯一漏掉该检查的消息类通知路径）
        val hideDetails = shouldHideSensitiveDetails(context)
        // 1.130：有内容预览时追加到文案（通知栏一行）
        val contentText = if (hideDetails) baseText
        else preview?.takeIf(String::isNotBlank)?.let { "$baseText：$it" } ?: baseText
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_post_interaction))
            .setContentText(contentText)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(genericNotification(context, CHANNEL_MESSAGES, R.string.notification_post_interaction))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!effectiveSoundEnabled(context, soundEnabled))
            .build()
        if (!notificationOwnerMatches(context, expectedUserId)) return
        // 8.44：动态互动通知独立 tag
        safeNotify(context, NotificationSlotPolicy.POST_TAG, NotificationSlotPolicy.postNotifyId(postId), notification, expectedUserId)
        // 同步到通知中心
        runCatching {
            com.maodouchat.MaodouchatApp.emitNotificationCenterItem(
                NotificationCenterItem(
                    id = "post_${postId}_${if (interaction == "COMMENT_LIKE") "cl" else if (isComment) "c" else "l"}",
                    type = NotificationCenterType.POST_INTERACTION,
                    mergeKey = "post_$postId",
                    title = context.getString(R.string.notification_post_interaction),
                    subtitle = context.getString(textRes),
                    // 9.137：脱敏时通知中心同样不存评论/回复明文
                    preview = if (hideDetails) null else preview?.takeIf(String::isNotBlank),
                    // 1.132：评论 id 供详情页跳转
                    deeplink = if (commentId.isNullOrBlank()) "maodouchat:post:$postId" else "maodouchat:post:$postId?comment=${java.net.URLEncoder.encode(commentId, Charsets.UTF_8.name())}",
                    extra = mapOf(
                        "postId" to postId,
                        "kind" to if (interaction == "COMMENT_LIKE") "comment_like" else if (isComment) "comment" else "like",
                        "commentId" to (commentId ?: "")
                    )
                ),
                expectedUserId = expectedUserId,
            )
        }
    }

    /** Clear post interaction tray (id = `post_{postId}`.hashCode()). */
    fun cancelPostInteraction(context: Context, postId: String) {
        if (postId.isBlank()) return
        NotificationManagerCompat.from(context).cancel(NotificationSlotPolicy.POST_TAG, NotificationSlotPolicy.postNotifyId(postId))
    }

    /** 1.119：设置页「发送测试通知」——用当前通知偏好发一条本地通知验证铃声/震动。 */
    fun showTestNotification(context: Context) {
        ensureChannels(context)
        if (!canPostNotifications(context)) return
        val expectedUserId = com.maodouchat.network.TokenManager.getInstance(context.applicationContext).getUserId().orEmpty()
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notifications_test_title))
            .setContentText(context.getString(R.string.notifications_test_body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!effectiveSoundEnabled(context, NotificationPreferences.soundEnabled(context)))
            .build()
        safeNotify(context, NotificationSlotPolicy.TEST_TAG, System.currentTimeMillis().toInt(), notification, expectedUserId)
    }

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

    fun cancelMessage(context: Context, chatId: String) {
        // 与 showMessage 的 tag 化 notify 保持一致：按 (tag, id=0) 取消，而非旧的 hashCode id。
        NotificationManagerCompat.from(context).cancel(NotificationSlotPolicy.messageTag(chatId), 0)
        // 8.51：打开聊天一并清掉该会话已触发的「稍后提醒」通知（与 cancelAiTaskRemindersForChat 打开即清对齐）。
        // 提醒通知 id = messageId.hashCode()，无法预知，需遍历 activeNotifications 按 tag 过滤。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val reminderTag = NotificationSlotPolicy.reminderTag(chatId)
            runCatching {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.activeNotifications
                    .filter { it.tag == reminderTag }
                    .forEach { manager.cancel(it.tag, it.id) }
            }
        }
    }

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
    fun showScheduledMessageFailed(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.scheduled_message_failed_title))
            .setContentText(context.getString(R.string.scheduled_message_failed_body))
            .setAutoCancel(true)
            .build()
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NotificationSlotPolicy.scheduledMessageFailedNotifyId(), notification)
        }
    }
}
