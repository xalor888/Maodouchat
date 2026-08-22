package com.maodouchat.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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
import com.maodouchat.notification.NotificationSoundPolicy
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

        // 9.3xx：周期自愈——保活服务被系统误杀后随 15 分钟周期任务恢复
        runCatching { com.maodouchat.push.PushKeepAlive.ensureForUser(applicationContext) }

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
            // 9.136：每轮迭代重新过门禁——循环内含网络/落库 suspend 点，
            // 中途登出/换号/清库时旧会话不得继续写共享 Room
            if (!BackgroundSessionGate.mayContinue(ownerId, tokenManager.getToken(), tokenManager.getUserId())) {
                return Result.success()
            }
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
                // 8.49 修复：单页 100 条把收敛速率钳制在 100 条/15 分钟——消息速率高的群
                // 持续落后。改为循环拉页直到取空或达到页数上限；游标逐页推进（中断安全，
                // 下轮续拉），多页合并为一次托盘通知
                var pageCursor = tokenManager.getSyncCursor(chat.id)
                val muted = chat.notificationsMuted
                val suppress = LocalSuppressPolicy.applies(app)
                // 8.46：会话级免打扰时段——per-chat 静音窗内不弹通知
                val quietHoursSuppress = com.maodouchat.notification.ChatQuietHoursPolicy.shouldSuppress(
                    com.maodouchat.notification.ChatQuietHoursStore.get(app, chat.id),
                    com.maodouchat.notification.ChatQuietHoursPolicy.currentMinute()
                )
                // 1.02：会话临时静音至（静音期间不弹通知）
                val silentUntilSuppress = com.maodouchat.notification.ChatQuietHoursStore.silentUntil(app, chat.id) > System.currentTimeMillis()
                var shouldNotify = false
                var notifySenderId = ""
                var notifyMessageId = ""
                var pages = 0
                while (pages < MAX_SYNC_PAGES_PER_CHAT) {
                    pages++
                    val result = ApiService.getMessagesSince(token, chat.id, pageCursor.timestampMs, limit = SYNC_PAGE_SIZE, sinceId = pageCursor.messageId)
                    if (result.isFailure) {
                        perChatFailed = true
                        syncFailed = true
                        break
                    }
                    // 9.136：网络往返后再过门禁——旧会话的响应不得写进新账号/清库中的 Room
                    if (!BackgroundSessionGate.mayContinue(ownerId, tokenManager.getToken(), tokenManager.getUserId())) {
                        return Result.success()
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
                    if (messages.isEmpty()) break
                    // 9.136：activeChatId 是进程级全局值（无账号归属），按迭代快照一次，
                    // 供未读抑制与通知抑制两处共用，避免跨账号会话状态污染本批决策
                    val activeChatId = MaodouchatApp.activeChatId
                    // 9.213：批量查重替代逐条 SELECT（每页可达 100 条）
                    val existingIds = messageRepo.getExistingMessageIds(messages.map { it.id })
                    val newMessageIds = messages.mapNotNull { msg ->
                        if (msg.id !in existingIds) msg.id else null
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
                    val last = messages.maxWith(compareBy<Message> { it.timestamp }.thenBy { it.id })
                    tokenManager.saveSyncCursor(chat.id, com.maodouchat.network.TokenManager.SyncCursor(timestampMs = last.timestamp, messageId = last.id))
                    synced += messages.size
                    // 前台聊天列表若正在展示，立即从 Room 重算尾部预览/排序；
                    // 本地未读增量只作断线窗口兜底，服务端会话快照会覆盖校准。
                    com.maodouchat.MaodouchatApp.emitChatListPreviewRefresh(chat.id)
                    // 8.49 修复：通知与未读计数同门禁——必须是本批新到（newMessageIds）且非本人
                    // 发送的消息才弹通知。旧条件只看类型，导致：① 前台 WS 已通知过的消息在
                    // 游标推进前的下一轮被重复通知；② 多设备场景自己的消息也弹"新加密消息"
                    messages.lastOrNull {
                        it.id in newMessageIds &&
                            it.senderId != ownerId &&
                            it.type !in setOf(MessageType.SK_DIST, MessageType.REVOKED)
                    }?.let { hit ->
                        shouldNotify = true
                        notifySenderId = hit.senderId
                        // 9.203 修复：通知应指向实际触发通知的消息；此前用批次内最后一条
                        //（可能是自己发的/控制消息），点击通知会定位错消息
                        notifyMessageId = hit.id
                    }
                    if (messages.size < SYNC_PAGE_SIZE) break
                    pageCursor = com.maodouchat.network.TokenManager.SyncCursor(timestampMs = last.timestamp, messageId = last.id)
                }

                // 补发通知：非活跃会话 + 未静音 + DND 不抑制（跳过 SK_DIST/REVOKED 控制消息）
                if (
                    shouldNotify &&
                        MaodouchatApp.activeChatId != chat.id &&
                        !muted && !suppress && !quietHoursSuppress && !silentUntilSuppress
                ) {
                    val senderName = app.database.userDao().getUserById(notifySenderId)?.name
                        ?: app.getString(com.maodouchat.R.string.app_name)
                    AppNotifier.showMessage(
                        context = app,
                        chatId = chat.id,
                        senderName = senderName,
                        preview = app.getString(com.maodouchat.R.string.notification_encrypted_message),
                        messageId = notifyMessageId,
                        soundEnabled = NotificationSoundPolicy.trayMessageSoundEnabled(
                            runtimeFlagEnabled = RuntimeFlags.isEnabled(app, RuntimeFlags.NOTIFICATION_SOUND),
                            userPreferenceEnabled = NotificationPreferences.soundEnabled(app),
                            chatMuted = chat.notificationsMuted == true,
                        ),
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

        /** 8.49：分页拉取——单页大小与页数上限（防极端积压一轮拉爆 worker 时限）。 */
        private const val SYNC_PAGE_SIZE = 100
        private const val MAX_SYNC_PAGES_PER_CHAT = 10

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

        /**
         * 9.3xx：WS 重连成功后立即补拉断线窗口增量（Ideaura 式断线补拉）——
         * 一次性工作与周期工作同名（REPLACE 只影响一次性链），周期任务不受影响。
         */
        fun requestNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<BacklogSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
