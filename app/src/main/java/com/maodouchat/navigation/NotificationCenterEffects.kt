package com.maodouchat.navigation

import android.content.Context
import com.maodouchat.MaodouchatApp
import com.maodouchat.network.TokenManager
import com.maodouchat.notification.CallNotificationService
import com.maodouchat.notification.MessageNotificationService
import com.maodouchat.notification.ReminderNotificationService
import com.maodouchat.notification.SocialNotificationService

object NotificationCenterEffects {

    fun create(context: Context): NotificationCenterOpenController {
        val appContext = context.applicationContext
        return NotificationCenterOpenController(
            hasSessionToken = {
                !TokenManager.getInstance(appContext).getToken().isNullOrBlank()
            },
            clearMessageTray = { chatId ->
                MessageNotificationService.cancelMessage(appContext, chatId)
            },
            clearMissedCallTray = { callId ->
                CallNotificationService.cancelMissedCall(appContext, callId)
            },
            clearAiTaskReminders = { chatId ->
                ReminderNotificationService.cancelAiTaskRemindersForChat(appContext, chatId)
            },
            clearPostInteractionTray = { postId ->
                SocialNotificationService.cancelPostInteraction(appContext, postId)
            },
            markPostInteractionsRead = { postId ->
                // 1.121：打开动态时将该动态的全部互动通知标记已读（角标/未读同步归零）
                runCatching {
                    (appContext as? MaodouchatApp)
                        ?.notificationCenter
                        ?.markPostInteractionsRead(postId)
                }
            },
            emitOpenMissedCalls = { MaodouchatApp.emitOpenMissedCalls() },
            emitOpenContacts = { MaodouchatApp.emitOpenContacts() },
        )
    }
}
