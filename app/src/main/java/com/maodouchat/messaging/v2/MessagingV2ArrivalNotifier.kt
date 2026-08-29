package com.maodouchat.messaging.v2

import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.notification.ChatQuietHoursPolicy
import com.maodouchat.notification.ChatQuietHoursStore
import com.maodouchat.notification.LocalNotificationSuppressPolicy
import com.maodouchat.notification.NotificationPreferences
import com.maodouchat.util.AppNotifier
import com.maodouchat.util.RuntimeFlags
import java.util.Calendar

/** Notification policy and rendering for a newly committed local timeline row. */
class MessagingV2ArrivalNotifier(
    private val app: MaodouchatApp,
    private val ownerUserId: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun notify(message: Message) {
        val owner = ownerUserId()
        if (owner.isBlank() || !NotificationPreferences.notificationsEnabled(app)) return
        val chat = app.database.chatDao().getChatById(message.chatId) ?: return
        if (chat.notificationsMuted || message.parsedMeta().silent) return

        val now = Calendar.getInstance().apply { timeInMillis = clock() }
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        if (
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = NotificationPreferences.dndStartHour(app),
                dndEndHour = NotificationPreferences.dndEndHour(app),
                hourOfDay = now.get(Calendar.HOUR_OF_DAY),
                dndRuntimeEnabled = RuntimeFlags.isEnabled(app, RuntimeFlags.DND),
                dndEnabled = NotificationPreferences.dndEnabled(app),
                startMinute = NotificationPreferences.dndStartMinute(app),
                endMinute = NotificationPreferences.dndEndMinute(app),
                currentMinute = minute,
            ) ||
            ChatQuietHoursPolicy.shouldSuppress(ChatQuietHoursStore.get(app, message.chatId), minute) ||
            ChatQuietHoursStore.silentUntil(app, message.chatId) > clock()
        ) return

        val senderName = app.database.userDao().getUserById(message.senderId)?.name
            ?.takeIf(String::isNotBlank)
            ?: app.getString(R.string.app_name)
        AppNotifier.showMessage(
            context = app,
            chatId = message.chatId,
            senderName = senderName,
            preview = preview(message),
            messageId = message.id,
            soundEnabled = NotificationPreferences.soundEnabled(app),
            expectedUserId = owner,
            isGroup = chat.isGroup,
        )
    }

    private fun preview(message: Message): String = when (message.type) {
        MessageType.TEXT,
        MessageType.MARKDOWN,
        MessageType.SYSTEM -> message.parsedContent().take(500)
        MessageType.IMAGE -> app.getString(R.string.message_preview_image)
        MessageType.GIF -> app.getString(R.string.message_preview_gif)
        MessageType.STICKER -> app.getString(R.string.message_preview_sticker)
        MessageType.LOCATION -> app.getString(R.string.message_preview_location)
        MessageType.VOICE -> app.getString(R.string.message_preview_voice)
        MessageType.VIDEO -> app.getString(R.string.message_preview_video)
        MessageType.FILE -> app.getString(R.string.message_preview_file)
        MessageType.NUDGE -> app.getString(R.string.message_preview_nudge)
        MessageType.REVOKED,
        MessageType.SK_DIST -> app.getString(R.string.notification_encrypted_message)
    }
}
