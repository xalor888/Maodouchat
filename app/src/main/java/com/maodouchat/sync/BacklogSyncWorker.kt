package com.maodouchat.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.maodouchat.MaodouchatApp
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.MessageRepository
import com.maodouchat.network.ApiService
import com.maodouchat.network.MessageDto
import com.maodouchat.network.TokenManager
import com.maodouchat.notification.NotificationPreferences
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.util.AppNotifier
import com.maodouchat.util.RuntimeFlags
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 断线窗口消息收敛（多设备同步兜底，8.29 调优新增）。
 *
 * 背景：WS 重连上限 20 次后停止、Doze 下 WS 必然断开、且无后台周期同步 →
 * 断线期间到达的消息正文只在打开聊天时落库，期间无 tray 通知（FCM TTL 已提到 24h，
 * 但 Doze 设备仍可能延迟数小时）。
 *
 * 本 worker 每 15 分钟（网络约束 + 背靠背）：
 * - 对每个本地会话按同步游标拉取增量消息，合并落库（与打开聊天同一 merge 逻辑）；
 * - 推进游标，防止重复拉取；
 * - 对新消息补发加密预览通知（非活跃会话 + 未静音 + DND 不抑制时）。
 *
 * mutation（DELETE/REVOKE/EDIT）回放由打开聊天时的 mutation 游标重放兜底，此处不重复实现。
 * 接线：MainActivity.onCreate 调用 [schedule]（幂等）。
 */
class BacklogSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? MaodouchatApp ?: return Result.failure()
        val tokenManager = TokenManager.getInstance(app)
        val ownerId = tokenManager.getUserId()?.takeIf(String::isNotBlank)
            ?: return Result.success()
        val token = tokenManager.getToken()?.takeIf(String::isNotBlank)
            ?: return Result.success()
        if (!BackgroundSessionGate.mayContinue(ownerId, token, ownerId)) return Result.success()

        val messageRepo = MessageRepository(app.database.messageDao(), app.database)
        val chats = app.database.chatDao().getAllChatsDirect()
        if (chats.isEmpty()) return Result.success()

        val now = System.currentTimeMillis()
        var synced = 0
        var notified = 0
        // 8.34 修复：网络类失败此前被 runCatching 吞掉并返回 success——游标未推进下轮会重拉
        //（语义安全），但无退避节流且日志缺失。改为标记失败 → Result.retry()（指数退避）。
        var syncFailed = false
        for (chat in chats) {
            // 节流依据是「上次同步尝试的墙钟时刻」（成功/失败都更新），而非游标时间戳——
            // 活跃会话的消息时间戳始终贴近 now，若用游标做门控会被无限跳过（8.35 CRITICAL 修复）
            if (now - tokenManager.getLastBacklogSyncAtMs(chat.id) < MIN_SYNC_INTERVAL_MS) continue
            // 8.48 修复：节流语义——空结果（最常见）也 markBacklogSyncAttempted，避免每轮
            // 15 分钟周期对安静会话重复拉取；失败判断改为 per-chat（此前 syncFailed 全局，
            // 任一会话失败后所有成功会话也不再标记，节流窗被错误占用）
            var perChatFailed = false
            try {
                // 8.46 修复：runCatching 吞掉 CancellationException——WorkManager 停止 worker
                // 时协程取消被吞，循环继续处理其余会话，且把"没同步成功"的会话记了尝试时刻
                // （节流窗被错误占用）。改 try/catch 并对 CancellationException 重抛。
                val cursor = tokenManager.getSyncCursor(chat.id)
                val result = ApiService.getMessagesSince(token, chat.id, cursor.timestampMs, limit = 100, sinceId = cursor.messageId)
                if (result.isFailure) {
                    perChatFailed = true
                    syncFailed = true
                    continue
                }
                val dtos = result.getOrThrow()
                // 与 ChatDetailViewModel 相同字段映射（E2EE 密文原样落库，预览由 AppNotifier 统一脱敏）
                val messages = dtos.sortedWith(compareBy<MessageDto> { it.timestamp }.thenBy { it.id })
                    .map { dto ->
                        Message(
                            id = dto.id,
                            chatId = dto.chatId,
                            senderId = dto.senderId,
                            content = dto.content,
                            type = MessageType.fromWire(dto.type),
                            timestamp = dto.timestamp,
                            status = MessageStatus.fromWire(dto.status),
                            editedAt = dto.editedAt,
                            starred = dto.starred,
                            reactions = dto.reactions,
                            expiresAt = dto.expiresAt,
                            sealedSender = dto.sealedSender
                        )
                    }
                val activeChatId = MaodouchatApp.activeChatId
                val newMessageIds = messages.mapNotNull { msg ->
                    if (messageRepo.getMessageById(msg.id) == null) msg.id else null
                }.toSet()
                messageRepo.insertMessages(messages)
                val incomingUnread = messages.count { msg ->
                    msg.id in newMessageIds &&
                        msg.senderId != ownerId &&
                        msg.type !in setOf(MessageType.SK_DIST, MessageType.REVOKED) &&
                        activeChatId != chat.id
                }
                if (incomingUnread > 0) {
                    app.database.chatDao().incrementUnread(chat.id, incomingUnread)
                }
                // 8.48：空结果（游标已最新）不推进游标但仍标记尝试时刻
                if (messages.isNotEmpty()) {
                    val last = messages.maxWith(compareBy<Message> { it.timestamp }.thenBy { it.id })
                    tokenManager.saveSyncCursor(chat.id, com.maodouchat.network.TokenManager.SyncCursor(timestampMs = last.timestamp, messageId = last.id))
                    synced += messages.size
                // 前台聊天列表若正在展示，立即从 Room 重算尾部预览/排序；
                // 本地未读增量只作断线窗口兜底，服务端会话快照会覆盖校准。
                    com.maodouchat.MaodouchatApp.emitChatListPreviewRefresh(chat.id)
                }

                // 补发通知：非活跃会话 + 未静音 + DND 不抑制（跳过 SK_DIST/REVOKED 控制消息）
                val muted = chat.notificationsMuted
                val suppress = LocalSuppressPolicy.applies(app)
                // 8.46：会话级免打扰时段——per-chat 静音窗内不弹通知
                val quietHoursSuppress = com.maodouchat.notification.ChatQuietHoursPolicy.shouldSuppress(
                    com.maodouchat.notification.ChatQuietHoursStore.get(app, chat.id),
                    com.maodouchat.notification.ChatQuietHoursPolicy.currentMinute()
                )
                // 1.02：会话临时静音至（静音期间不弹通知）
                val silentUntilSuppress = com.maodouchat.notification.ChatQuietHoursStore.silentUntil(app, chat.id) > System.currentTimeMillis()
                val visible = messages.any { it.type !in setOf(MessageType.SK_DIST, MessageType.REVOKED) }
                if (visible && activeChatId != chat.id && !muted && !suppress && !quietHoursSuppress && !silentUntilSuppress) {
                    val senderId = messages.lastOrNull()?.senderId.orEmpty()
                    val senderName = app.database.userDao().getUserById(senderId)?.name
                        ?: app.getString(com.maodouchat.R.string.app_name)
                    AppNotifier.showMessage(
                        context = app,
                        chatId = chat.id,
                        senderName = senderName,
                        preview = app.getString(com.maodouchat.R.string.notification_encrypted_message),
                        messageId = messages.lastOrNull()?.id.orEmpty(),
                        soundEnabled = NotificationPreferences.soundEnabled(app) && chat.notificationsMuted != true,
                        expectedUserId = ownerId,
                        // 0.72：群聊走独立通知渠道（独立铃声）
                        isGroup = chat.isGroup,
                    )
                    notified++
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                perChatFailed = true
                syncFailed = true
                Log.w(TAG, "Backlog sync failed for ${chat.id}: ${e.message}", e)
            }
            // 8.40：仅成功（未触发网络失败）时记录尝试时刻——失败会话不记录，
            // 使 Result.retry() 退避后能真正重试；8.48 改为 per-chat 判断（此前全局 syncFailed
            // 会让同轮成功的会话也不标记，节流窗被错误占用）
            if (!perChatFailed) tokenManager.markBacklogSyncAttempted(chat.id)
        }
        if (synced > 0) {
            Log.i(TAG, "Backlog sync: $synced messages from ${chats.size} chats, $notified notifications")
        }
        // 8.34：网络失败 → 退避重试（此前静默 success，仅靠 15 分钟周期兜底）
        if (syncFailed) {
            Log.w(TAG, "Backlog sync: network failure, will retry with backoff")
            return Result.retry()
        }
        return Result.success()
    }

    private object LocalSuppressPolicy {
        fun applies(context: Context): Boolean =
            com.maodouchat.notification.LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = NotificationPreferences.notificationsEnabled(context),
                dndStartHour = NotificationPreferences.dndStartHour(context),
                dndEndHour = NotificationPreferences.dndEndHour(context),
                hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                dndRuntimeEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.DND),
                dndEnabled = NotificationPreferences.dndEnabled(context),
                startMinute = NotificationPreferences.dndStartMinute(context),
                endMinute = NotificationPreferences.dndEndMinute(context),
                currentMinute = Calendar.getInstance().let { c ->
                    c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
                },
            )
    }

    companion object {
        private const val WORK_NAME = "maodouchat_backlog_sync"
        private const val TAG = "BacklogSync"
        private const val PERIOD_MINUTES = 15L
        /** 单会话最小同步间隔（10 分钟），避免周期任务与手动打开聊天重复拉取。 */
        private const val MIN_SYNC_INTERVAL_MS = 10L * 60L * 1_000L

        /** 幂等注册周期任务（进程重启自动恢复；与 SecretSurfaceWatchdogWorker 同模式）。 */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BacklogSyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
