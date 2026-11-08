package com.maodouchat.messaging.v2

import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.core.model.ChatQuietHoursPolicy
import com.maodouchat.core.model.Message
import com.maodouchat.domain.messaging.ArrivalPreview
import com.maodouchat.domain.messaging.ArrivalPreviewPolicy
import com.maodouchat.notification.ChatQuietHoursStore
import com.maodouchat.core.model.LocalNotificationSuppressPolicy
import com.maodouchat.notification.MessageNotificationService
import com.maodouchat.notification.NotificationPreferences

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
        MessageNotificationService.showMessage(
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

    private fun preview(message: Message): String = when (val preview = ArrivalPreviewPolicy.preview(message)) {
        is ArrivalPreview.Text -> preview.text
        ArrivalPreview.Image -> app.getString(R.string.message_preview_image)
        ArrivalPreview.Gif -> app.getString(R.string.message_preview_gif)
        ArrivalPreview.Sticker -> app.getString(R.string.message_preview_sticker)
        ArrivalPreview.Location -> app.getString(R.string.message_preview_location)
        ArrivalPreview.Voice -> app.getString(R.string.message_preview_voice)
        ArrivalPreview.Video -> app.getString(R.string.message_preview_video)
        ArrivalPreview.File -> app.getString(R.string.message_preview_file)
        ArrivalPreview.Nudge -> app.getString(R.string.message_preview_nudge)
        ArrivalPreview.Encrypted -> app.getString(R.string.notification_encrypted_message)
    }
}
