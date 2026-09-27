package com.maodouchat.navigation

import android.content.Context
import com.maodouchat.MaodouchatApp
import com.maodouchat.network.TokenManager
import com.maodouchat.notification.CallNotificationService
import com.maodouchat.notification.MessageNotificationService
import com.maodouchat.notification.ReminderNotificationService
import com.maodouchat.notification.SocialNotificationService

/**
 * [NotificationCenterOpenController] 的 Android 侧接线（非 ui 层）。
 *
 * 这里集中放置原先写在 `ui/navigation/SearchCenterDestinations.kt` 里的
 * app 单例/通知服务访问，让 Composable 只剩「决策 → 导航」的映射。
 * 逐字对齐原实现的副作用：托盘清理 4 个服务、通知中心标记已读（原 runCatching
 * 包一层，防单条坏数据影响导航）。
 */
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
