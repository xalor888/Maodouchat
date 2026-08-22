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
import com.maodouchat.data.repository.NotificationCenterItem
import com.maodouchat.notification.NotificationPreferences
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

    private const val CHANNEL_MESSAGES = "messages"
    private const val CHANNEL_GROUP_MESSAGES = "group_messages"
    private const val CHANNEL_CALLS = "calls"
    private const val CHANNEL_AI_TASKS = "ai_tasks"
    private const val NOTIFICATION_TAG_PREFIX = "maodouchat_"
    private const val AI_TASK_NOTIFICATION_TAG = "maodouchat_ai_task"
    private const val FRIEND_REQUEST_NOTIFICATION_TAG = "maodouchat_friend_request"
    private const val ANNOUNCEMENT_NOTIFICATION_TAG = "maodouchat_announcement"
    private val notificationMutationLock = Any()
    /** Distinct from [incomingCallNotifyId] so cancelIncoming never wipes a missed tray. */
    private const val MISSED_CALL_NOTIFY_SALT = 0x4D495353 // "MISS"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 8.48：用户可选系统通知铃声（RingtoneManager picker）；未选时使用内置消息提示音
        // （9.3xx：此前未选时依赖系统默认铃声，部分厂商渠道建好后无声——现在显式设置内置音效）
        val ringtoneUri = com.maodouchat.notification.NotificationPreferences.ringtoneUri(context)
            ?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
            ?: android.net.Uri.parse("android.resource://${context.packageName}/${R.raw.notify_message}")
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
        val fingerprint = listOf(ringtoneUri, groupRingtoneUri, vibrationOn).joinToString("|")
        val configPrefs = context.applicationContext.getSharedPreferences("notif_channel_config", Context.MODE_PRIVATE)
        val storedFingerprint = configPrefs.getString("channel_fingerprint", null)
        if (storedFingerprint != null && storedFingerprint != fingerprint) {
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
            data = Uri.parse("maodouchat-notify://chat/$chatId")
        }
        // notify 用真实 chatId 作 tag、id=0，使每个会话有独立通知槽位；PendingIntent 的唯一性
        // 则由上方 tapIntent 的 data URI 保证（requestCode=chatId.hashCode() 仍可能因碰撞复用同一
        // PendingIntent，故不能以 hashCode 单独区分会话）。
        val pi = PendingIntent.getActivity(
            context, chatId.hashCode(), tapIntent,
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
        // 9.4xx：服务端 notification_sound_enabled flag 此前只拦设置页开关、发声路径从未生效——
        // 管理员关闭后存量用户仍响铃。所有消息类通知统一经 NotificationSoundPolicy 判定。
        val effectiveSoundEnabled = com.maodouchat.notification.NotificationSoundPolicy.messageSoundEnabled(
            runtimeFlagEnabled = com.maodouchat.util.RuntimeFlags.isEnabled(context, com.maodouchat.util.RuntimeFlags.NOTIFICATION_SOUND),
            userPreferenceEnabled = soundEnabled
        )
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
            .setSilent(!effectiveSoundEnabled)
        // 9.209：第三方服务器模式标注服务器名——服务器身份非隐私内容，脱敏模式下也展示，
        // 避免同时连多个自建服务器的用户分不清通知来自哪台
        if (com.maodouchat.network.ServerIdentity.isThirdPartyServer) {
            com.maodouchat.network.ServerIdentity.current.value?.name
                ?.takeIf(String::isNotBlank)
                ?.let { builder.setSubText(it) }
        }
        val notification = builder.build()
        if (!notificationOwnerMatches(context, expectedUserId)) return
        // tag 用真实 chatId（而非其 hashCode），id 固定 0：每个会话独立通知槽位，彻底避免
        // (maodouchat_<chatId>).hashCode() 跨会话碰撞导致后到通知覆盖先到、点击跳错会话。
        safeNotify(context, NOTIFICATION_TAG_PREFIX + chatId, 0, notification, expectedUserId)
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
            data = Uri.parse("maodouchat-notify://reminder/$chatId/$messageId")
        }
        val pi = PendingIntent.getActivity(
            context,
            ("reminder_$chatId").hashCode(),
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
            .setSilent(false)
            .build()
        if (!notificationOwnerMatches(context, expectedUserId)) return false
        safeNotify(context, NOTIFICATION_TAG_PREFIX + "reminder_$chatId", messageId.hashCode(), notification, expectedUserId)
        return true
    }

    fun showMissedCall(
        context: Context,
        callId: String,
        callerName: String,
        isVideo: Boolean,
        expectedUserId: String,
    ) {
        if (!notificationOwnerMatches(context, expectedUserId)) return
        ensureChannels(context)
        if (!canPostNotifications(context)) return
        // Always drop the ringing tray first; ids are intentionally distinct so a later
        // cancelIncomingCall cannot erase this missed entry.
        cancelIncomingCall(context, callId)
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_MISSED_CALL, true)
            putNotificationOwner(expectedUserId)
            data = Uri.parse("maodouchat-notify://missed/$callId")
        }
        val pi = PendingIntent.getActivity(
            context, missedCallNotifyId(callId), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = context.getString(if (isVideo) R.string.notification_missed_video_call else R.string.notification_missed_audio_call)
        val body = if (shouldHideSensitiveDetails(context)) {
            context.getString(R.string.notification_missed_call_private)
        } else {
            context.getString(R.string.notification_missed_call_body, callerName)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(genericNotification(context, CHANNEL_CALLS, R.string.notification_missed_call_private))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        if (!notificationOwnerMatches(context, expectedUserId)) return
        // 8.44：未接来电通知用独立 tag，避免与来电/动态互动在 null-tag id 空间哈希碰撞互相顶掉
        safeNotify(context, NOTIFY_TAG_MISSED, missedCallNotifyId(callId), notification, expectedUserId)
        // 同步到通知中心：App 锁开启时隐藏联系人姓名
        runCatching {
            val hideDetails = shouldHideSensitiveDetails(context)
            com.maodouchat.MaodouchatApp.emitNotificationCenterItem(
                NotificationCenterItem(
                    id = "missed_${callId}",
                    type = NotificationCenterType.MISSED_CALL,
                    mergeKey = if (hideDetails) "missed_private" else "missed_${callerName}",
                    title = title,
                    subtitle = if (hideDetails) null else callerName,
                    preview = if (hideDetails) {
                        context.getString(R.string.notification_missed_call_private)
                    } else {
                        context.getString(if (isVideo) R.string.call_video else R.string.call_audio)
                    },
                    // Shared path with EXTRA_OPEN_MISSED_CALL tray tap.
                    deeplink = "maodouchat:missed_calls",
                    extra = mapOf("callId" to callId) + if (hideDetails) emptyMap() else mapOf("caller" to callerName)
                ),
                expectedUserId = expectedUserId,
            )
        }
    }

    fun showIncomingCall(
        context: Context,
        callId: String,
        isVideo: Boolean,
        soundEnabled: Boolean = true,
        senderId: String = "",
        expectedUserId: String,
    ) {
        if (!notificationOwnerMatches(context, expectedUserId)) return
        ensureChannels(context)
        if (!canPostNotifications(context)) return
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_OPEN_INCOMING_CALL, true)
            putExtra(EXTRA_INCOMING_CALL_ID, callId)
            putExtra(EXTRA_INCOMING_CALL_VIDEO, isVideo)
            if (senderId.isNotBlank()) putExtra(EXTRA_INCOMING_CALL_SENDER_ID, senderId)
            putNotificationOwner(expectedUserId)
            data = Uri.parse("maodouchat-notify://incoming/$callId")
        }
        val pi = PendingIntent.getActivity(
            context, incomingCallNotifyId(callId), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 9.4xx：来电铃声与设置页同门禁——服务端 ringtone flag 关闭时静音来电通知
        val effectiveSoundEnabled = com.maodouchat.notification.NotificationSoundPolicy.ringtoneEnabled(
            runtimeFlagEnabled = com.maodouchat.util.RuntimeFlags.isEnabled(context, com.maodouchat.util.RuntimeFlags.RINGTONE),
            userPreferenceEnabled = soundEnabled
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(if (isVideo) R.string.notification_encrypted_video_call else R.string.notification_encrypted_audio_call))
            .setContentText(context.getString(R.string.notification_open_to_answer))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(genericNotification(context, CHANNEL_CALLS, R.string.notification_open_to_answer))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setOngoing(true)
            .setTimeoutAfter(35_000L)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setSilent(!effectiveSoundEnabled)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        }
        if (!notificationOwnerMatches(context, expectedUserId)) return
        // 8.44：来电通知独立 tag（与前台服务 9001 / 动态互动隔离）
        safeNotify(context, NOTIFY_TAG_CALL, incomingCallNotifyId(callId), builder.build(), expectedUserId)
    }

    /** 对端已挂断 / 本地已接听或拒绝后清掉系统来电通知，避免幽灵响铃 */
    fun cancelIncomingCall(context: Context, callId: String) {
        if (callId.isBlank()) return
        NotificationManagerCompat.from(context).cancel(NOTIFY_TAG_CALL, incomingCallNotifyId(callId))
        // Also clear legacy same-as-callId slot from older builds that shared the id
        // with missed calls (harmless if empty).
        NotificationManagerCompat.from(context).cancel(callId.hashCode())
    }

    /**
     * Missed-call tray uses [missedCallNotifyId] — independent of [incomingCallNotifyId]
     * so endCall / cancelIncoming never erase a just-posted missed entry.
     */
    fun cancelMissedCall(context: Context, callId: String) {
        if (callId.isBlank()) return
        NotificationManagerCompat.from(context).cancel(NOTIFY_TAG_MISSED, missedCallNotifyId(callId))
        // Legacy slot (pre-split notify ids).
        NotificationManagerCompat.from(context).cancel(callId.hashCode())
    }

    private fun incomingCallNotifyId(callId: String): Int = callId.hashCode()

    private fun missedCallNotifyId(callId: String): Int =
        callId.hashCode() xor MISSED_CALL_NOTIFY_SALT

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
            data = Uri.parse("maodouchat-notify://post/$postId")
        }
        val pi = PendingIntent.getActivity(
            context, postId.hashCode(), tapIntent,
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
        // 9.4xx：与 showMessage 同门禁——服务端 flag 关闭时互动通知同样静音
        val effectiveSoundEnabled = com.maodouchat.notification.NotificationSoundPolicy.messageSoundEnabled(
            runtimeFlagEnabled = com.maodouchat.util.RuntimeFlags.isEnabled(context, com.maodouchat.util.RuntimeFlags.NOTIFICATION_SOUND),
            userPreferenceEnabled = soundEnabled
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_post_interaction))
            .setContentText(contentText)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(genericNotification(context, CHANNEL_MESSAGES, R.string.notification_post_interaction))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!effectiveSoundEnabled)
            .build()
        if (!notificationOwnerMatches(context, expectedUserId)) return
        // 8.44：动态互动通知独立 tag
        safeNotify(context, NOTIFY_TAG_POST, ("post_$postId").hashCode(), notification, expectedUserId)
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
        NotificationManagerCompat.from(context).cancel(NOTIFY_TAG_POST, ("post_$postId").hashCode())
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
            .setSilent(!NotificationPreferences.soundEnabled(context))
            .build()
        safeNotify(context, NOTIFY_TAG_TEST, System.currentTimeMillis().toInt(), notification, expectedUserId)
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
    ) {
        if (!notificationOwnerMatches(context, expectedUserId)) return
        ensureChannels(context)
        if (!canPostNotifications(context)) return
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putNotificationOwner(expectedUserId)
            data = Uri.parse("maodouchat-notify://announcement/$announcementId")
        }
        val pi = PendingIntent.getActivity(
            context, ("announcement_$announcementId").hashCode(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val levelLabel = when (level) {
            "EMERGENCY" -> context.getString(R.string.announcement_level_emergency)
            "MAINTENANCE" -> context.getString(R.string.announcement_level_maintenance)
            else -> context.getString(R.string.announcement_level_info)
        }
        val body = "$levelLabel · $title"
        // 9.4xx：与 showMessage 同门禁——服务端 flag 关闭时公告通知静音
        val effectiveSoundEnabled = com.maodouchat.notification.NotificationSoundPolicy.messageSoundEnabled(
            runtimeFlagEnabled = com.maodouchat.util.RuntimeFlags.isEnabled(context, com.maodouchat.util.RuntimeFlags.NOTIFICATION_SOUND),
            userPreferenceEnabled = soundEnabled
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_announcement_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(genericNotification(context, CHANNEL_MESSAGES, R.string.notification_announcement_title))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!effectiveSoundEnabled)
            .build()
        if (!notificationOwnerMatches(context, expectedUserId)) return
        safeNotify(context, ANNOUNCEMENT_NOTIFICATION_TAG, announcementId.hashCode(), notification, expectedUserId)
    }

    fun showFriendRequest(
        context: Context,
        requestId: String,
        action: String,
        soundEnabled: Boolean = true,
        expectedUserId: String,
    ) {
        if (!notificationOwnerMatches(context, expectedUserId)) return
        ensureChannels(context)
        if (!canPostNotifications(context)) return
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_CONTACTS, true)
            putNotificationOwner(expectedUserId)
            data = Uri.parse("maodouchat-notify://friend/$requestId")
        }
        val pi = PendingIntent.getActivity(
            context, ("friend_$requestId").hashCode(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val titleRes = if (action == "ACCEPTED") {
            R.string.notification_friend_accepted
        } else {
            R.string.notification_friend_request
        }
        val bodyRes = if (action == "ACCEPTED") {
            R.string.notification_friend_accepted_body
        } else {
            R.string.notification_friend_request_body
        }
        // 9.4xx：与 showMessage 同门禁——服务端 flag 关闭时好友请求通知静音
        val effectiveSoundEnabled = com.maodouchat.notification.NotificationSoundPolicy.messageSoundEnabled(
            runtimeFlagEnabled = com.maodouchat.util.RuntimeFlags.isEnabled(context, com.maodouchat.util.RuntimeFlags.NOTIFICATION_SOUND),
            userPreferenceEnabled = soundEnabled
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.getString(bodyRes))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(genericNotification(context, CHANNEL_MESSAGES, bodyRes))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!effectiveSoundEnabled)
            .build()
        if (!notificationOwnerMatches(context, expectedUserId)) return
        safeNotify(context, FRIEND_REQUEST_NOTIFICATION_TAG, requestId.hashCode(), notification, expectedUserId)
        runCatching {
            com.maodouchat.MaodouchatApp.emitNotificationCenterItem(
                NotificationCenterItem(
                    id = "friend_push_${requestId}_$action",
                    type = NotificationCenterType.FRIEND_REQUEST,
                    mergeKey = "friend_request",
                    title = context.getString(titleRes),
                    subtitle = context.getString(bodyRes),
                    preview = null,
                    deeplink = "maodouchat:contacts",
                    extra = mapOf("requestId" to requestId, "action" to action)
                ),
                expectedUserId = expectedUserId,
            )
        }
    }

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
        if (!notificationOwnerMatches(context, expectedUserId)) return false
        ensureChannels(context)
        if (!canPostNotifications(context)) return false
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_AI_TASKS_CHAT_ID, chatId)
            putNotificationOwner(expectedUserId)
            data = Uri.parse("maodouchat-notify://aitask/$taskId")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            taskId.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val chatLocked = isChatPinLocked(context, chatId)
        val secretChat = isSecretChat(context, chatId)
        val hideTaskBody = shouldHideSensitiveDetails(context, showPreview) || chatLocked || (secretChat && RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_NOTIF_PREVIEW_BLOCK))
        val body = if (!hideTaskBody) {
            taskTitle
        } else if (chatLocked) {
            context.getString(R.string.chat_lock_list_preview)
        } else if (secretChat) {
            context.getString(R.string.secret_chat_notification_preview)
        } else {
            context.getString(R.string.notification_ai_task_due)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_AI_TASKS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_ai_task_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(genericNotification(context, CHANNEL_AI_TASKS, R.string.notification_ai_task_due))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setWhen(dueAt)
            .setShowWhen(true)
            .setGroup("ai_tasks_$chatId")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(!soundEnabled)
            .build()
        if (!notificationOwnerMatches(context, expectedUserId)) return false
        safeNotify(context, AI_TASK_NOTIFICATION_TAG, taskId.hashCode(), notification, expectedUserId)
        // 分组需要一条 summary 通知才能在所有 Android 版本（尤其 7.0+）正确折叠展示；
        // 与子通知共用 AI_TASK_NOTIFICATION_TAG，现有 cancel* 方法会一并清理。
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

    fun cancelMessage(context: Context, chatId: String) {
        // 与 showMessage 的 tag 化 notify 保持一致：按 (tag, id=0) 取消，而非旧的 hashCode id。
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG_PREFIX + chatId, 0)
        // 8.51：打开聊天一并清掉该会话已触发的「稍后提醒」通知（与 cancelAiTaskRemindersForChat 打开即清对齐）。
        // 提醒通知 id = messageId.hashCode()，无法预知，需遍历 activeNotifications 按 tag 过滤。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val reminderTag = NOTIFICATION_TAG_PREFIX + "reminder_$chatId"
            runCatching {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.activeNotifications
                    .filter { it.tag == reminderTag }
                    .forEach { manager.cancel(it.tag, it.id) }
            }
        }
    }

    fun cancelAiTaskReminder(context: Context, taskId: String) {
        NotificationManagerCompat.from(context).cancel(AI_TASK_NOTIFICATION_TAG, taskId.hashCode())
    }

    /**
     * 同一 chat 的所有 AI 任务提醒共享一个分组；Android 7.0+ 必须有一条 group-summary
     * 通知，否则分组内的子通知可能不完整展示。summary 用固定 id，随最后一个子通知被
     * cancelAiTaskRemindersForChat / cancelAllAiTaskReminders 一并移除。
     */
    private fun showAiTaskGroupSummary(context: Context, chatId: String, expectedUserId: String) {
        ensureChannels(context)
        val groupKey = "ai_tasks_$chatId"
        val summary = NotificationCompat.Builder(context, CHANNEL_AI_TASKS)
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
        safeNotify(context, AI_TASK_NOTIFICATION_TAG, aiTaskGroupSummaryId(chatId), summary, expectedUserId)
    }

    private fun aiTaskGroupSummaryId(chatId: String): Int =
        ("ai_summary_$chatId").hashCode()

    /**
     * Drop tray reminders for one chat when the AI tasks screen (or center row) is opened.
     * Notifications are grouped as `ai_tasks_{chatId}` in [showAiTaskReminder].
     */
    fun cancelAiTaskRemindersForChat(context: Context, chatId: String) {
        if (chatId.isBlank() || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val groupKey = "ai_tasks_$chatId"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.activeNotifications
            .filter { it.tag == AI_TASK_NOTIFICATION_TAG && it.notification.group == groupKey }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    fun cancelAllAiTaskReminders(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.activeNotifications
            .filter { it.tag == AI_TASK_NOTIFICATION_TAG }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    fun cancelAllFriendRequests(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.activeNotifications
            .filter { it.tag == FRIEND_REQUEST_NOTIFICATION_TAG }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    /** Logout / account switch: drop every posted tray notification for this app. */
    fun cancelAll(context: Context) {
        synchronized(notificationMutationLock) {
            NotificationManagerCompat.from(context).cancelAll()
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun notificationOwnerMatches(context: Context, expectedUserId: String): Boolean {
        if (com.maodouchat.security.SecureSessionManager.isPurgeInProgress()) return false
        val tokenManager = com.maodouchat.network.TokenManager.getInstance(context.applicationContext)
        val liveUserId = tokenManager.getUserId()
        return com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = expectedUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = liveUserId,
        )
    }

    private fun Intent.putNotificationOwner(expectedUserId: String) {
        putExtra(EXTRA_NOTIFICATION_OWNER_USER_ID, expectedUserId)
    }

    /**
     * Lint cannot prove [canPostNotifications] gates every notify site; callers already return early.
     * Catch SecurityException so a revoked runtime permission never crashes the process.
     */
    @SuppressLint("MissingPermission")
    private fun safeNotify(
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
    private fun safeNotify(
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

    private fun shouldHideSensitiveDetails(context: Context, explicitPreviewEnabled: Boolean = true): Boolean {
        val userPreviewEnabled = NotificationPreferences.previewEnabled(context)
        return NotificationPrivacyPolicy.hideSensitiveDetails(
            appLockEnabled = AppLockManager.isEnabled(context),
            previewEnabled = explicitPreviewEnabled && userPreviewEnabled
        )
    }

    /** Local chat PIN: hide tray/notification-center body even when previews are enabled. */
    private fun isChatPinLocked(context: Context, chatId: String): Boolean {
        if (chatId.isBlank()) return false
        val app = context.applicationContext as? com.maodouchat.MaodouchatApp ?: return false
        return try {
            // Process-unlocked chats may still keep lock on disk; tray still hides body until user opens chat.
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                app.database.chatLockDao().get(chatId) != null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** Local secret chat: hide tray/notification-center body like PIN lock. */
    private fun isSecretChat(context: Context, chatId: String): Boolean {
        if (chatId.isBlank()) return false
        val app = context.applicationContext as? com.maodouchat.MaodouchatApp ?: return false
        return try {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                app.database.secretChatDao().isSecret(chatId)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private fun genericNotification(context: Context, channelId: String, bodyRes: Int) =
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

    /** 8.44：来电/未接/动态互动通知独立 tag——三者此前共用 null-tag id 空间，
     * 哈希碰撞时响应来电会被动态互动通知顶掉。消息通知已用 NOTIFICATION_TAG_PREFIX 隔离。 */
    private const val NOTIFY_TAG_CALL = "maodouchat_call"
    private const val NOTIFY_TAG_MISSED = "maodouchat_missed"
    private const val NOTIFY_TAG_POST = "maodouchat_post"
    private const val NOTIFY_TAG_TEST = "maodouchat_test"

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
                .notify("scheduled_message_failed".hashCode(), notification)
        }
    }
}
