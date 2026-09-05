package com.maodouchat.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.maodouchat.R
import com.maodouchat.security.AppLockManager

import com.maodouchat.util.NotificationPrivacyPolicy
import com.maodouchat.util.RuntimeFlags

/**
 * P07 共享通知基础设施（仅模块内可见）。
 *
 * 由 Message/Call/Social/Reminder 四个垂直服务共享：渠道创建、账号门禁、
 * 安全 post、脱敏判断。逐字搬运已删除的 `AppNotifier` 旧私有实现。
 */
internal object NotificationInfrastructure {

    const val CHANNEL_MESSAGES = "messages_v4"
    const val CHANNEL_GROUP_MESSAGES = "group_messages_v4"
    const val CHANNEL_CALLS = "calls_v4"
    const val CHANNEL_AI_TASKS = "ai_tasks_v4"

    private val LEGACY_CHANNEL_IDS = listOf("messages", "group_messages", "calls", "ai_tasks")
    private val notificationMutationLock = Any()

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 8.48：用户可选系统通知铃声（RingtoneManager picker）；未选时使用内置消息提示音
        // （9.3xx：此前未选时依赖系统默认铃声，部分厂商渠道建好后无声——现在显式设置内置音效）
        val builtinTick = android.net.Uri.parse("android.resource://${context.packageName}/raw/notify_message")
        val ringtoneUri = NotificationPreferences.ringtoneUri(context)
            ?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
            ?: builtinTick
        // 0.72：群聊独立铃声（回退单聊铃声）
        val groupRingtoneUri = NotificationPreferences.groupRingtoneUri(context)
            ?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
            ?: ringtoneUri
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        // 9.216：渠道配置指纹——Android 通知渠道的铃声/振动只在创建时生效，
        // 用户改设置后必须删除重建渠道才能生效（重建会重置系统侧对渠道的手动调整，预期内）。
        val vibrationOn = NotificationPreferences.vibrationEnabled(context)
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

    /** Logout / account switch: drop every posted tray notification for this app. */
    fun cancelAll(context: Context) {
        synchronized(notificationMutationLock) {
            NotificationManagerCompat.from(context).cancelAll()
        }
    }

    fun effectiveSoundEnabled(context: Context, preferenceEnabled: Boolean): Boolean =
        NotificationSoundPolicy.messageSoundEnabled(
            runtimeFlagEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.NOTIFICATION_SOUND),
            userPreferenceEnabled = preferenceEnabled,
        )

    fun effectiveRingtoneEnabled(context: Context, preferenceEnabled: Boolean): Boolean =
        NotificationSoundPolicy.ringtoneEnabled(
            runtimeFlagEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.RINGTONE),
            userPreferenceEnabled = preferenceEnabled,
        )

    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun notificationOwnerMatches(context: Context, expectedUserId: String): Boolean {
        if (com.maodouchat.security.SecureSessionManager.isPurgeInProgress()) return false
        val tokenManager = com.maodouchat.network.TokenManager.getInstance(context.applicationContext)
        val liveUserId = tokenManager.getUserId()
        return com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = expectedUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = liveUserId,
        )
    }

    fun Intent.putNotificationOwner(expectedUserId: String) {
        putExtra(NotificationIntents.EXTRA_NOTIFICATION_OWNER_USER_ID, expectedUserId)
    }

    /**
     * Lint cannot prove [canPostNotifications] gates every notify site; callers already return early.
     * Catch SecurityException so a revoked runtime permission never crashes the process.
     */
    @SuppressLint("MissingPermission")
    fun safeNotify(
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
    fun safeNotify(
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

    fun shouldHideSensitiveDetails(context: Context, explicitPreviewEnabled: Boolean = true): Boolean {
        val userPreviewEnabled = NotificationPreferences.previewEnabled(context)
        return NotificationPrivacyPolicy.hideSensitiveDetails(
            appLockEnabled = AppLockManager.isEnabled(context),
            previewEnabled = explicitPreviewEnabled && userPreviewEnabled
        )
    }

    /** Local chat PIN: hide tray/notification-center body even when previews are enabled. */
    fun isChatPinLocked(context: Context, chatId: String): Boolean {
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
    fun isSecretChat(context: Context, chatId: String): Boolean {
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

    fun genericNotification(context: Context, channelId: String, bodyRes: Int) =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(bodyRes))
            .build()
}
