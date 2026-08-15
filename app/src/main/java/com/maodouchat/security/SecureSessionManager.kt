package com.maodouchat.security

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.work.WorkManager
import com.maodouchat.MaodouchatApp
import com.maodouchat.ai.AiTaskReminderScheduler
import com.maodouchat.attachment.AttachmentTransferCoordinator
import com.maodouchat.attachment.AttachmentTransferScheduler
import com.maodouchat.crypto.SealedSenderSupport
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.crypto.SenderKeyRetryWorkScheduler
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.network.WebSocketClient
import com.maodouchat.push.PushRegistrationManager
import kotlinx.coroutines.sync.withLock

/**
 * Centralized local privacy cleanup for logout and account switches.
 */
class SecureSessionManager(
    private val context: Context,
    private val database: AppDatabase,
    private val signalProtocol: SignalProtocol,
    private val tokenManager: TokenManager,
    private val onEncryptedDatabaseDestroyed: (() -> Unit)? = null
) {

    suspend fun purgeLocalSession(
        destroyEncryptedDatabase: Boolean = true,
        expectedOwnerUserId: String? = null
    ): Boolean {
        // 退出/换号清理必须跑完：中途 cancel 会留下半清状态与串号风险；
        // 清理含大量磁盘 IO（删除媒体缓存/密聊目录/清空 Coil 磁盘缓存/删库），必须在 IO 线程执行，
        // 否则主线程同步阻塞直接 ANR。
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            purgeMutex.withLock {
                if (expectedOwnerUserId != null && tokenManager.getUserId() != expectedOwnerUserId) {
                    return@withLock false
                }
                purgeInProgress.set(true)
                try {
                    purgeLocalSessionLocked(destroyEncryptedDatabase)
                    true
                } finally {
                    purgeInProgress.set(false)
                }
            }
        }
    }

    private suspend fun purgeLocalSessionLocked(destroyEncryptedDatabase: Boolean) {
            AppLockManager.clearAuthenticatedSession()
            // 假聊天解锁标记同样是进程内状态，注销/换号后必须失效，
            // 否则同账号重新登录可能直接绕过假聊天前置页。
            FakeChatManager.clearUnlockedSession()
            // 全局 typing presence 也应立即清空，避免账号切换后短暂沿用上一账号的输入状态。
            runCatching { com.maodouchat.util.TypingPresenceStore.clear() }
            // Process-global open-chat marker must not suppress notifications for the next account.
            MaodouchatApp.activeChatId = null
            // Stop any in-flight voice bubble so the next account never hears prior media.
            runCatching { com.maodouchat.util.VoicePlayer.stop() }
            // AI per-chat / global rate windows are process-local; clear so switch doesn't throttle.
            runCatching { com.maodouchat.ai.AiRetryPolicy.clearSession() }
            // Invalidate buffered group-call / direct-chat navigations from the prior session.
            runCatching { com.maodouchat.call.CallOrchestrator.invalidateSession() }
            // Drop ApiService in-memory access token before disk clear (concurrent refresh race).
            runCatching { com.maodouchat.network.ApiService.clearSessionTokens() }
            // Sealed-sender certificates are account/device credentials and must not survive logout.
            runCatching { SealedSenderSupport.clearCache() }
            // NOTE: MaodouchatApp.sessionGeneration is bumped AFTER call hang-up below so the
            // active CallViewModel still accepts the local hang-up, then leftover bus events
            // become stale for the next account.
            // Capture before token clear so notification-center disk key can be wiped.
            val accountUserId = tokenManager.getUserId()
            val refreshToken = tokenManager.getRefreshToken()
            val accessToken = tokenManager.getToken()
            val pushDeviceId = runCatching { PushRegistrationManager.currentDeviceId(context) }.getOrDefault("")
            // 8.33 隐私修复：登出/删号前撤销「附近的人」可见性（服务端 TTL 窗口内位置
            // 若无人撤销会继续对他人可见）。
            if (!accessToken.isNullOrBlank()) {
                runCatching { com.maodouchat.network.ApiService.stopNearbyLocationSharing(accessToken) }
                    .onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        Log.w(TAG, "Failed to revoke nearby visibility during purge", error)
                    }
            }
            if (!accessToken.isNullOrBlank()) {
                try {
                    PushRegistrationManager.unregisterCurrentDevice(context, accessToken)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "Failed to unregister push token during logout", error)
                }
            }
            // Registration state is process-local UI state; do not let the next account
            // inherit the previous account's "registered / healthy" push status.
            PushRegistrationManager.resetRegistrationStateForAccountChange(context)
            try {
                AttachmentTransferCoordinator.deleteAll(context)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to clear attachment transfers during local purge", error)
            }
            if (!refreshToken.isNullOrBlank()) {
                // deviceId 一并交给 logout：即使上面 unregister 失败，服务端仍可清本机 FCM
                try {
                    ApiService.logout(refreshToken, accessToken, deviceId = pushDeviceId)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "Failed to revoke refresh token during logout", error)
                }
            }
            try {
                WebSocketClient.disconnect()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to disconnect WebSocket during local purge", error)
            }
            // Drop in-memory ring state + end any foreground call so the next account
            // never inherits another user's pending offer or active media session.
            // Hang-up is stamped with the CURRENT session generation so the active CallViewModel
            // still accepts it; generation is then bumped so any leftover bus event is stale.
            try {
                val pendingCallId = com.maodouchat.call.IncomingCallCoordinator.peekPending()?.callId.orEmpty()
                com.maodouchat.call.IncomingCallCoordinator.clear()
                val activeCallId = com.maodouchat.service.CallForegroundService.getActiveCallId()
                if (activeCallId.isNotBlank()) {
                    com.maodouchat.call.CallActionBus.requestHangUp(activeCallId, notifyPeer = false)
                } else if (pendingCallId.isNotBlank()) {
                    com.maodouchat.call.CallActionBus.requestHangUp(pendingCallId, notifyPeer = false)
                }
                com.maodouchat.service.CallForegroundService.stop(context)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to clear call session during local purge", error)
            }
            // After hang-up: invalidate deep-link / wake / hang-up session epoch for next account.
            runCatching { MaodouchatApp.invalidateSessionGeneration() }
            try {
                SenderKeyRetryWorkScheduler.cancelAll(context)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to cancel SenderKey retry work during local purge", error)
            }
            try {
                AiTaskReminderScheduler.cancelAll(context)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to cancel AI task reminder work during local purge", error)
            }
            try {
                AttachmentTransferScheduler.cancelAll(context)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to cancel attachment transfer work during local purge", error)
            }
            try {
                // 8.48 修复：登出/换号时取消两个周期 Worker（此前残留，每 15 分钟空转唤醒 +
                // 读取已清空的本地库）；下次登录/启动由 MainActivity.onCreate 重新注册
                WorkManager.getInstance(context).cancelUniqueWork("maodouchat_backlog_sync")
                WorkManager.getInstance(context).cancelUniqueWork("secret_surface_watchdog")
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to cancel periodic workers during local purge", error)
            }
            try {
                val scheduled = com.maodouchat.util.ScheduledMessageStore.list(context)
                scheduled.forEach { com.maodouchat.util.ScheduledMessageScheduler.cancel(context, it.id) }
                accountUserId?.takeIf { it.isNotBlank() }?.let {
                    com.maodouchat.util.ScheduledMessageStore.clearForUser(context, it)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to clear scheduled messages during local purge", error)
            }
            // Account-scoped prefs keys must not outlive the account: GIF recents / chat folders / emoji recents.
            try {
                accountUserId?.takeIf { it.isNotBlank() }?.let { uid ->
                    com.maodouchat.util.GifSearchPreferences.clearForUser(context, uid)
                    com.maodouchat.util.ChatFolderPreferences.clearForUser(context, uid)
                    com.maodouchat.util.EmojiRecentPreferences.clearForUser(context, uid)
                    com.maodouchat.util.QuickPhrasePreferences.clearForUser(context, uid)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to clear account prefs during local purge", error)
            }
            // 8.41：消息「稍后提醒」属账号本地数据——登出取消全部提醒作业并清存储
            try {
                com.maodouchat.util.MessageReminderScheduler.cancelAll(context)
                accountUserId?.takeIf { it.isNotBlank() }?.let { uid ->
                    com.maodouchat.util.MessageReminderStore.clearForUser(context, uid)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to clear message reminders during local purge", error)
            }
            // 8.46：会话免打扰时段（per-chat 静音窗）属账号本地数据——登出清理
            try {
                accountUserId?.takeIf { it.isNotBlank() }?.let { uid ->
                    com.maodouchat.notification.ChatQuietHoursStore.clearForUser(context, uid)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to clear chat quiet hours during local purge", error)
            }
            // 1.55：底部导航未读角标（单例）在登出/切号时归零，避免残留上一账号计数
            com.maodouchat.ui.screen.chatlist.UnreadBadgeStore.totalUnread.value = 0
            // 8.52：全量通话记录属账号本地数据——登出清理
            try {
                accountUserId?.takeIf { it.isNotBlank() }?.let { uid ->
                    com.maodouchat.call.CallLogStore.clearForUser(context, uid)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to clear call log during local purge", error)
            }
            try {
                com.maodouchat.util.AppNotifier.cancelAll(context)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to cancel tray notifications during local purge", error)
            }
            try {
                (context.applicationContext as? MaodouchatApp)
                    ?.notificationCenter
                    ?.purgeAccount(accountUserId)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to purge notification center during local purge", error)
            }
            // Decrypted media / attachment staging must not survive logout or account switch.
            try {
                com.maodouchat.util.MediaCache.cleanupReturningBytes(context)
                // 8.40：密聊明文媒体目录整目录擦除——clearAllSurfaces 只删「当前活跃 surface」
                // 的会话，进程被杀后残留密聊明文会无限期留在磁盘
                com.maodouchat.util.MediaCache.deleteAllSecretChatMedia(context)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to wipe media cache during local purge", error)
            }
            // Link-preview process cache may retain titles/hosts from prior account.
            try {
                com.maodouchat.util.LinkPreviewRepository.clear()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to clear link preview cache during local purge", error)
            }
            // Process-scoped chat PIN unlock must not carry across logout/account switch.
            try {
                ChatLockSession.clearAll()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to clear chat lock session during local purge", error)
            }
            // Secret-chat FLAG_SECURE surface markers must not outlive the account.
            try {
                SecretChatSession.clearAllSurfaces(context.applicationContext)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to clear secret chat session during local purge", error)
            }
            // Explore composer drafts are account-scoped keys; wipe the whole prefs file on logout.
            try {
                context.applicationContext.deleteSharedPreferences("explore_draft")
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to clear explore draft prefs during local purge", error)
            }
            // Writing-style memory is optional and account-scoped; clear while userId still available.
            try {
                com.maodouchat.ai.AiWritingStylePreferences.clear(context)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to clear writing style prefs during local purge", error)
            }
            // Coil memory/disk may hold prior-account avatars and explore images (non-isolated keys).
            try {
                clearCoilImageCaches(context.applicationContext)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to clear Coil image cache during local purge", error)
            }

            try {
                if (destroyEncryptedDatabase) {
                    runCatching { tokenManager.clear() }
                    refreshNotificationAccount()
                    signalProtocol.invalidateInMemoryAccountState()
                    AppDatabase.destroyDatabase(context.applicationContext)
                    onEncryptedDatabaseDestroyed?.invoke()
                    return
                }

                // Soft purge keeps SQLCipher file but must wipe every privacy table
                // (search index / missed calls / locks / AI summary were previously orphaned).
                database.withTransaction {
                    database.messageDao().deleteAllMessages()
                    database.chatDao().deleteAllChats()
                    database.chatDraftDao().deleteAll()
                    database.userDao().deleteAllUsers()
                    database.senderKeyRetryDao().deleteAll()
                    database.aiTaskDao().deleteAll()
                    database.aiOperationDao().deleteAll()
                    database.attachmentTransferDao().deleteAll()
                    database.messageSearchDao().deleteAll()
                    database.missedCallDao().deleteAll()
                    database.chatLockDao().deleteAll()
                    database.secretChatDao().deleteAll()
                    database.aiSummaryCacheDao().deleteAll()
                    signalProtocol.clearLocalState()
                }
                com.maodouchat.security.SecretChatSession.clearAllSurfaces()
            } catch (error: Throwable) {
                Log.w(TAG, "Local database purge failed; destroying encrypted storage", error)
                AppDatabase.destroyDatabase(context.applicationContext)
                onEncryptedDatabaseDestroyed?.invoke()
            } finally {
                runCatching { tokenManager.clear() }
                    .onFailure { Log.w(TAG, "Failed to clear auth token during local purge", it) }
                // Ensure memory JWT cannot outlive disk clear on either soft or hard purge path.
                runCatching { com.maodouchat.network.ApiService.clearSessionTokens() }
                runCatching { SealedSenderSupport.clearCache() }
                refreshNotificationAccount()
            }
    }

    suspend fun purgeIfAccountChanged(nextUserId: String) {
        val currentUserId = tokenManager.getUserId()
        if (
            AccountIsolationPolicy.onLoginAccount(currentUserId, nextUserId) ==
            AccountSwitchAction.PURGE_LOCAL_DATA
        ) {
            purgeLocalSession(destroyEncryptedDatabase = true)
        }
    }

    private fun refreshNotificationAccount() {
        (context.applicationContext as? MaodouchatApp)?.notificationCenter?.refreshAccount()
    }


    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    private fun clearCoilImageCaches(appContext: Context) {
        val loader = coil.Coil.imageLoader(appContext)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }

    companion object {
        private const val TAG = "SecureSessionManager"
        private val purgeMutex = kotlinx.coroutines.sync.Mutex()
        private val purgeInProgress = java.util.concurrent.atomic.AtomicBoolean(false)

        fun isPurgeInProgress(): Boolean = purgeInProgress.get()
    }
}
