package com.maodouchat.security

import com.maodouchat.notification.MessageNotificationService
import android.content.Context
import android.util.Log
import com.maodouchat.MaodouchatApp
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.domain.messaging.ConversationPrivacyCapabilities
import com.maodouchat.domain.messaging.ConversationPrivacyPolicy
import com.maodouchat.domain.messaging.PrivacyAction
import com.maodouchat.domain.messaging.SecretChatState
import com.maodouchat.domain.messaging.SecretChatStateMachine
import com.maodouchat.domain.messaging.SecretConversationController
import com.maodouchat.network.TokenManager

import com.maodouchat.util.DisappearingMessagePolicy
import com.maodouchat.util.MediaCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArraySet

/**
 * M10: 密聊与会话隐私统一控制器。
 *
 * 实现了 [SecretConversationController]，集中管理：
 * - 统一会话能力获取 [capabilities] 与权限判定 [isAllowed]，消除各子系统散写的密聊/锁定分支
 * - 消息已读触发定时自毁 [armOnRead] 与 WorkManager 持久任务挂载
 * - 密聊会话彻底销毁 [destroy]（解密媒体、消息数据、搜索索引、通知项、看门狗任务）
 * - 定时/看门狗驱动的过期消息安全擦除 [purgeExpiredMessages]
 */
class DefaultSecretConversationController(
    private val context: Context,
    private val database: AppDatabase,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SecretConversationController {

    private val secretChatIds = CopyOnWriteArraySet<String>()
    private val lockedChatIds = CopyOnWriteArraySet<String>()
    @Volatile private var isInitialized = false

    init {
        scope.launch(ioDispatcher) {
            try {
                secretChatIds.addAll(database.chatDao().listSecretChatIds())
                lockedChatIds.addAll(database.chatLockDao().listLockedChatIds())
                isInitialized = true
            } catch (e: Exception) {
                Log.w(TAG, "Failed initial load of secret/locked chats", e)
            }

            launch {
                try {
                    database.chatDao().observeSecretChatIds().collectLatest { list ->
                        secretChatIds.clear()
                        secretChatIds.addAll(list)
                        isInitialized = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Secret chat observer failed", e)
                }
            }

            launch {
                try {
                    database.chatLockDao().observeLockedChatIds().collectLatest { list ->
                        lockedChatIds.clear()
                        lockedChatIds.addAll(list)
                        isInitialized = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Chat lock observer failed", e)
                }
            }
        }
    }

    override fun capabilities(conversationId: String): ConversationPrivacyCapabilities {
        if (conversationId.isBlank()) {
            return ConversationPrivacyCapabilities(isSecretChat = false, isLocked = false)
        }
        val isSecret = if (isInitialized) {
            secretChatIds.contains(conversationId)
        } else {
            runCatching { database.chatDao().isSecretChatBlocking(conversationId) }.getOrDefault(false)
        }
        val isPinConfigured = if (isInitialized) {
            lockedChatIds.contains(conversationId)
        } else {
            runCatching { database.chatLockDao().isChatLockedBlocking(conversationId) }.getOrDefault(false)
        }
        val isLocked = isPinConfigured && !ChatLockSession.isUnlocked(conversationId)
        return ConversationPrivacyCapabilities(isSecretChat = isSecret, isLocked = isLocked)
    }

    suspend fun getCapabilities(conversationId: String): ConversationPrivacyCapabilities = withContext(ioDispatcher) {
        if (conversationId.isBlank()) {
            return@withContext ConversationPrivacyCapabilities(isSecretChat = false, isLocked = false)
        }
        val isSecret = if (isInitialized) {
            secretChatIds.contains(conversationId)
        } else {
            database.chatDao().isSecretChat(conversationId)
        }
        val isPinConfigured = if (isInitialized) {
            lockedChatIds.contains(conversationId)
        } else {
            database.chatLockDao().get(conversationId) != null
        }
        val isLocked = isPinConfigured && !ChatLockSession.isUnlocked(conversationId)
        ConversationPrivacyCapabilities(isSecretChat = isSecret, isLocked = isLocked)
    }

    fun isAllowed(conversationId: String, action: PrivacyAction): Boolean {
        return ConversationPrivacyPolicy.allows(capabilities(conversationId), action)
    }

    override suspend fun armOnRead(conversationId: String): Unit = withContext(ioDispatcher) {
        if (conversationId.isBlank()) return@withContext
        val chat = database.chatDao().getChatById(conversationId)
        val isSecret = chat?.chatType == "SECRET" || secretChatIds.contains(conversationId)
        val timer = if (isSecret) {
            DisappearingMessagePolicy.SECRET_DEFAULT_SECONDS
        } else {
            chat?.disappearingMessageSeconds ?: 0
        }
        if (timer <= 0) return@withContext

        // 密聊状态机流转：ACTIVE -> ARMED
        val nextState = SecretChatStateMachine.onRead(SecretChatState.ACTIVE, hasTimer = true)
        if (nextState != SecretChatState.ARMED) return@withContext

        val now = System.currentTimeMillis()
        val expiresAt = now + timer * 1000L
        val affected = database.messageDao().armMessagesOnRead(conversationId, expiresAt)
        if (affected > 0) {
            Log.d(TAG, "Armed $affected disappearing messages for $conversationId (expires in ${timer}s)")
            val ownerUserId = TokenManager.getInstance(context.applicationContext).getUserId()
            DisappearingMessageDestructionWorker.schedule(
                context = context,
                delayMs = timer * 1000L,
                chatId = conversationId,
                ownerUserId = ownerUserId
            )
        }
    }

    override suspend fun destroy(conversationId: String): Unit = withContext(ioDispatcher) {
        if (conversationId.isBlank()) return@withContext
        Log.i(TAG, "Destroying secret conversation data for: $conversationId")

        // 1. 删除消息记录
        database.messageDao().deleteMessagesByChatId(conversationId)

        // 2. 清除解密后的本地媒体缓存
        MediaCache.deleteSecretChatMedia(context, conversationId)

        // 3. 彻底清除搜索索引（明文 token 与 document）
        try {
            database.messageSearchDao().deleteChatIndex(conversationId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete search index for $conversationId", e)
        }

        // 4. 清理通知中心条目与系统通知
        try {
            (context.applicationContext as? MaodouchatApp)?.notificationCenter?.removeChatItems(conversationId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove notification items for $conversationId", e)
        }
        runCatching {
            MessageNotificationService.cancelMessage(context, conversationId)
        }

        // 5. 清理活跃 surface 标记
        SecretChatSession.markSurfaceInactive(conversationId, context)

        // 6. 移除本机 TTL 心跳记录
        try {
            database.secretChatDao().remove(conversationId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove secret chat heartbeat row for $conversationId", e)
        }

        // 7. 取消可能挂起的单次销毁 worker
        DisappearingMessageDestructionWorker.cancel(context, conversationId)
    }

    suspend fun purgeExpiredMessages(nowMs: Long = System.currentTimeMillis()): List<String> = withContext(ioDispatcher) {
        val expiredIds = database.messageDao().getExpiredMessageIds(nowMs)
        if (expiredIds.isEmpty()) {
            scheduleNextDestructionIfNeeded(nowMs)
            return@withContext emptyList()
        }

        // 分批删除防止超过 SQLite 变量数上限
        expiredIds.chunked(900).forEach { chunk ->
            database.messageDao().deleteMessagesByIds(chunk)
        }

        // 清理附件媒体文件
        expiredIds.forEach { id ->
            MediaCache.deleteCachedMediaForMessage(context, id)
        }

        // 清理搜索索引
        expiredIds.forEach { id ->
            try {
                database.messageSearchDao().deleteDocument(id)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete search index for expired message $id", e)
            }
        }

        // 清理通知中心
        try {
            (context.applicationContext as? MaodouchatApp)?.notificationCenter?.deleteItemsForMessages(expiredIds)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear notification items for expired messages", e)
        }

        scheduleNextDestructionIfNeeded(nowMs)
        return@withContext expiredIds
    }

    private suspend fun scheduleNextDestructionIfNeeded(nowMs: Long) {
        val nextExpiry = database.messageDao().getEarliestExpiryAfter(nowMs)
        if (nextExpiry != null && nextExpiry > nowMs) {
            val delay = nextExpiry - nowMs
            val ownerUserId = TokenManager.getInstance(context.applicationContext).getUserId()
            DisappearingMessageDestructionWorker.schedule(
                context = context,
                delayMs = delay,
                ownerUserId = ownerUserId
            )
        }
    }

    companion object {
        private const val TAG = "SecretConversationCtrl"
    }
}
