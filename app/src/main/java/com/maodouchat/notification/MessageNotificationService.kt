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
import com.maodouchat.network.TokenManager
import com.maodouchat.ui.component.ChatMarkdown
import com.maodouchat.ui.screen.chatlist.NotificationCenterType

import com.maodouchat.util.RuntimeFlags

/**
 * P07 服务拆分 4/4：Message（会话消息/稍后提醒/测试/定时失败）通知服务。
 *
 * 已删除的 `AppNotifier` 巨型静态入口迁出的最后一个垂直服务。M11 快捷回复/mark-read 动作、前台静音（traySoundAllowed）、群独立渠道、
 * 第三方服务器 subText 等行为逐行搬运。共享的渠道/门禁/post 能力由
 * [NotificationInfrastructure] 提供。
 */
object MessageNotificationService {

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
        if (!NotificationInfrastructure.notificationOwnerMatches(context, expectedUserId)) return
        NotificationInfrastructure.ensureChannels(context)
        if (!NotificationInfrastructure.canPostNotifications(context)) return
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(NotificationIntents.EXTRA_OPEN_CHAT_ID, chatId)
            // Unique data URI so two chatIds whose hashCode() collides still yield distinct
            // PendingIntents (extras are NOT part of PendingIntent identity); without this,
            // FLAG_UPDATE_CURRENT would overwrite one chat's tap target with the other's.
            data = Uri.parse(NotificationSlotPolicy.chatDataUri(chatId))
        }
        with(NotificationInfrastructure) { tapIntent.putNotificationOwner(expectedUserId) }
        // notify 用真实 chatId 作 tag、id 固定 0：每个会话独立通知槽位；PendingIntent 的唯一性
        // 则由上方 tapIntent 的 data URI 保证（requestCode=chatId.hashCode() 仍可能因碰撞复用同一
        // PendingIntent，故不能以 hashCode 单独区分会话）。
        val pi = PendingIntent.getActivity(
            context, NotificationSlotPolicy.chatRequestCode(chatId), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val chatLocked = NotificationInfrastructure.isChatPinLocked(context, chatId)
        val secretChat = NotificationInfrastructure.isSecretChat(context, chatId)
        val hideDetails = NotificationInfrastructure.shouldHideSensitiveDetails(context) || chatLocked || (secretChat && RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_NOTIF_PREVIEW_BLOCK))
        val displayTitle = if (hideDetails) context.getString(R.string.app_name) else senderName
        val displayPreview = if (hideDetails) {
            when {
                chatLocked -> context.getString(R.string.chat_lock_list_preview)
                secretChat -> context.getString(R.string.secret_chat_notification_preview)
                else -> context.getString(R.string.notification_encrypted_message)
            }
        } else {
            // 1.23：名片裸标记不进通知正文（与会话列表预览 1.18 一致）
            ChatMarkdown.stripContactCardMarker(preview)
        }
        // 0.72：群聊消息走独立渠道（独立铃声）
        val channelId = if (isGroup) NotificationInfrastructure.CHANNEL_GROUP_MESSAGES else NotificationInfrastructure.CHANNEL_MESSAGES
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(displayTitle)
            .setContentText(displayPreview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayPreview))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationInfrastructure.genericNotification(context, channelId, R.string.notification_encrypted_message))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(
                !NotificationSoundPolicy.traySoundAllowed(
                    appInForeground = com.maodouchat.MaodouchatApp.appInForeground,
                    preferenceSoundEnabled = NotificationInfrastructure.effectiveSoundEnabled(context, soundEnabled),
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
                data = Uri.parse(NotificationSlotPolicy.markReadDataUri(chatId))
            }
            with(NotificationInfrastructure) { markReadIntent.putNotificationOwner(expectedUserId) }
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
                data = Uri.parse(NotificationSlotPolicy.quickReplyDataUri(chatId))
            }
            with(NotificationInfrastructure) { replyIntent.putNotificationOwner(expectedUserId) }

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
        if (!NotificationInfrastructure.notificationOwnerMatches(context, expectedUserId)) return
        // tag 用真实 chatId（而非其 hashCode），id 固定 0：每个会话独立通知槽位，彻底避免
        // (maodouchat_<chatId>).hashCode() 跨会话碰撞导致后到通知覆盖先到、点击跳错会话。
        NotificationInfrastructure.safeNotify(context, NotificationSlotPolicy.messageTag(chatId), 0, notification, expectedUserId)
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
        if (!NotificationInfrastructure.notificationOwnerMatches(context, expectedUserId)) return false
        NotificationInfrastructure.ensureChannels(context)
        if (!NotificationInfrastructure.canPostNotifications(context)) return false
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(NotificationIntents.EXTRA_OPEN_CHAT_ID, chatId)
            putExtra(NotificationIntents.EXTRA_OPEN_MESSAGE_ID, messageId)
            data = Uri.parse(NotificationSlotPolicy.reminderDataUri(chatId, messageId))
        }
        with(NotificationInfrastructure) { tapIntent.putNotificationOwner(expectedUserId) }
        val pi = PendingIntent.getActivity(
            context,
            NotificationSlotPolicy.reminderRequestCode(chatId),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val chatLocked = NotificationInfrastructure.isChatPinLocked(context, chatId)
        val secretChat = NotificationInfrastructure.isSecretChat(context, chatId)
        val hideDetails = NotificationInfrastructure.shouldHideSensitiveDetails(context) || chatLocked ||
            (secretChat && RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_NOTIF_PREVIEW_BLOCK))
        val displayPreview = if (hideDetails) {
            when {
                chatLocked -> context.getString(R.string.chat_lock_list_preview)
                secretChat -> context.getString(R.string.secret_chat_notification_preview)
                else -> context.getString(R.string.notification_encrypted_message)
            }
        } else messagePreview
        val notification = NotificationCompat.Builder(context, NotificationInfrastructure.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.message_reminder_notification_title))
            .setContentText(displayPreview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayPreview))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationInfrastructure.genericNotification(context, NotificationInfrastructure.CHANNEL_MESSAGES, R.string.notification_encrypted_message))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!NotificationInfrastructure.effectiveSoundEnabled(context, NotificationPreferences.soundEnabled(context)))
            .build()
        if (!NotificationInfrastructure.notificationOwnerMatches(context, expectedUserId)) return false
        NotificationInfrastructure.safeNotify(context, NotificationSlotPolicy.reminderTag(chatId), NotificationSlotPolicy.reminderNotifyId(messageId), notification, expectedUserId)
        return true
    }

    /** 1.119：设置页「发送测试通知」——用当前通知偏好发一条本地通知验证铃声/震动。 */
    fun showTestNotification(context: Context) {
        NotificationInfrastructure.ensureChannels(context)
        if (!NotificationInfrastructure.canPostNotifications(context)) return
        val expectedUserId = TokenManager.getInstance(context.applicationContext).getUserId().orEmpty()
        val notification = NotificationCompat.Builder(context, NotificationInfrastructure.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notifications_test_title))
            .setContentText(context.getString(R.string.notifications_test_body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!NotificationInfrastructure.effectiveSoundEnabled(context, NotificationPreferences.soundEnabled(context)))
            .build()
        NotificationInfrastructure.safeNotify(context, NotificationSlotPolicy.TEST_TAG, System.currentTimeMillis().toInt(), notification, expectedUserId)
    }

    /**
     * 8.48：定时消息发送失败通知（达重试上限后移除待发条目时提示，避免静默丢失）。
     */
    fun showScheduledMessageFailed(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) NotificationInfrastructure.ensureChannels(context)
        val notification = NotificationCompat.Builder(context, NotificationInfrastructure.CHANNEL_MESSAGES)
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
}
