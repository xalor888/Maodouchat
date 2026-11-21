package com.maodouchat.data.repository

import android.content.Context
import android.util.Log
import com.maodouchat.core.model.NotificationCenterItem
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.toEntity
import com.maodouchat.data.local.entity.toModel
import com.maodouchat.domain.conversation.NotificationCenterFoldPolicy
import com.maodouchat.network.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 应用通知中心聚合仓库。
 *
 * 通知中心聚合以下事件：
 * - 新消息提醒（被压缩成摘要，便于回看）
 * - 未接来电
 * - 动态（朋友圈）互动
 * - AI 任务到期
 * - 群系统事件（被邀请入群 / 角色变化）
 * - 审核 / 风控提醒
 *
 * 持久化策略：Room `notification_center_items` 表（内存 StateFlow 仍是读写真相源；
 * 磁盘读写经 `runBlocking(Dispatchers.IO)`/后台协程桥接，保持同步 API 不变，
 * 调用方横跨主线程与后台，直接换 suspend 会波及约 25 处）。
 */
class NotificationCenterRepository(context: Context) {

    private val appContext = context.applicationContext
    private val tokenManager = TokenManager.getInstance(appContext)
    private val database: AppDatabase by lazy { AppDatabase.getInstance(appContext) }
    private val legacyMigration by lazy { NotificationCenterLegacyMigration(appContext, database) }
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accountLock = Any()
    @Volatile private var loadedUserId: String? = null

    private val _items = MutableStateFlow<List<NotificationCenterItem>>(emptyList())
    val items: StateFlow<List<NotificationCenterItem>>
        get() {
            ensureCurrentAccount()
            return _items.asStateFlow()
        }

    init {
        ensureCurrentAccount()
    }

    /** 添加或合并一条通知。 */
    fun add(item: NotificationCenterItem, expectedUserId: String? = null) {
        withCurrentAccount(expectedUserId) { ownerUserId ->
            val updated = NotificationCenterFoldPolicy.foldInto(_items.value, item)
            _items.value = updated
            saveToDisk(ownerUserId, updated)
        }
    }

    fun remove(itemId: String) {
        withCurrentAccount { ownerUserId ->
            val updated = _items.value.filterNot { entry -> entry.id == itemId }
            if (updated != _items.value) {
                _items.value = updated
                saveToDisk(ownerUserId, updated)
            }
        }
    }

    fun clearAll() {
        withCurrentAccount { ownerUserId ->
            if (_items.value.isNotEmpty()) {
                _items.value = emptyList()
                saveToDisk(ownerUserId, emptyList())
            }
        }
    }

    /**
     * Logout path: wipe in-memory center and the disk snapshot for [userId]
     * (or the currently loaded account when blank). Safe to call after token clear
     * when [userId] was captured before [TokenManager.clear].
     */
    fun purgeAccount(userId: String? = null) {
        synchronized(accountLock) {
            val target = userId?.takeIf(String::isNotBlank)
                ?: loadedUserId?.takeIf(String::isNotBlank)
                ?: tokenManager.getUserId()?.takeIf(String::isNotBlank)
            _items.value = emptyList()
            if (target != null) {
                runCatching {
                    kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                        database.notificationCenterDao().deleteForUserBlocking(target)
                    }
                }
            }
            // Force next ensureCurrentAccount to re-bind after login.
            if (userId.isNullOrBlank() || userId == loadedUserId || target == loadedUserId) {
                loadedUserId = null
            }
        }
    }

    fun markAllRead() {
        withCurrentAccount { ownerUserId ->
            if (_items.value.any { !it.read }) {
                val updated = NotificationCenterFoldPolicy.markAllRead(
                    _items.value, System.currentTimeMillis()
                )
                _items.value = updated
                saveToDisk(ownerUserId, updated)
            }
        }
    }

    fun markRead(itemId: String) {
        withCurrentAccount { ownerUserId ->
            val now = System.currentTimeMillis()
            val current = _items.value
            val updated = NotificationCenterFoldPolicy.markRead(current, itemId, now)
            if (updated != current) {
                _items.value = updated
                saveToDisk(ownerUserId, updated)
            }
        }
    }

    /**
     * 1.280：标为未读 —— 反悔误点已读，未读角标/过滤恢复对应条目。
     */
    fun markUnread(itemId: String) {
        withCurrentAccount { ownerUserId ->
            val now = System.currentTimeMillis()
            val current = _items.value
            val updated = NotificationCenterFoldPolicy.markUnread(current, itemId, now)
            if (updated != current) {
                _items.value = updated
                saveToDisk(ownerUserId, updated)
            }
        }
    }

    /**
     * Mark all in-app notification-center rows for a chat as read when the user opens it
     * (mergeKey `msg_{chatId}` and extra.chatId / deeplink match).
     */
    fun markChatMessagesRead(chatId: String) {
        if (chatId.isBlank()) return
        withCurrentAccount { ownerUserId ->
            val current = _items.value
            val updated = NotificationCenterFoldPolicy.markChatMessagesRead(
                current, chatId, System.currentTimeMillis()
            )
            if (updated != current) {
                _items.value = updated
                saveToDisk(ownerUserId, updated)
            }
        }
    }

    /**
     * 打开 AI 任务中心时，把该会话的 AI_TASK 通知中心行标记已读。
     */
    fun markAiTasksRead(chatId: String) {
        if (chatId.isBlank()) return
        withCurrentAccount { ownerUserId ->
            val current = _items.value
            val updated = NotificationCenterFoldPolicy.markAiTasksRead(
                current, chatId, System.currentTimeMillis()
            )
            if (updated != current) {
                _items.value = updated
                saveToDisk(ownerUserId, updated)
            }
        }
    }

    /**
     * 1.121：打开某条动态时，将该动态的全部互动通知（点赞/评论/回复/评论赞）标记已读。
     * mergeKey `post_{postId}` 或 extra.postId 匹配。
     */
    fun markPostInteractionsRead(postId: String) {
        if (postId.isBlank()) return
        withCurrentAccount { ownerUserId ->
            val current = _items.value
            val updated = NotificationCenterFoldPolicy.markPostInteractionsRead(
                current, postId, System.currentTimeMillis()
            )
            if (updated != current) {
                _items.value = updated
                saveToDisk(ownerUserId, updated)
            }
        }
    }

    /**
     * Drop notification-center rows for a chat the user left/deleted so deeplinks
     * do not open a missing conversation (messages + AI tasks scoped to that chat).
     */
    fun removeChatItems(chatId: String) {
        if (chatId.isBlank()) return
        withCurrentAccount { ownerUserId ->
            val current = _items.value
            val updated = NotificationCenterFoldPolicy.removeChatItems(current, chatId)
            if (updated != current) {
                _items.value = updated
                saveToDisk(ownerUserId, updated)
            }
        }
    }

    /**
     * Drop center rows whose latest payload still references [messageId]
     * (remote delete / revoke). Returns true when anything was removed so
     * callers can cancel the matching system-tray shadow for that chat.
     */
    fun removeMessageReferences(messageId: String): Boolean {
        if (messageId.isBlank()) return false
        return withCurrentAccount { ownerUserId ->
            val current = _items.value
            val updated = NotificationCenterFoldPolicy.removeMessageReferences(current, messageId)
            val changed = updated != current
            if (changed) {
                _items.value = updated
                saveToDisk(ownerUserId, updated)
            }
            changed
        } ?: false
    }

    /** 批量移除引用这批消息的通知中心条目（8.32 修复 F9：阅后即焚到期清理）。 */
    fun deleteItemsForMessages(messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        withCurrentAccount { ownerUserId ->
            val current = _items.value
            val updated = NotificationCenterFoldPolicy.deleteItemsForMessages(current, messageIds)
            if (updated != current) {
                _items.value = updated
                saveToDisk(ownerUserId, updated)
            }
        }
    }

    /**
     * When a message is edited and still heads a center row, refresh the preview
     * so the center does not keep the pre-edit body. Returns true if any row updated
     * (caller may re-post tray).
     */
    fun updateMessagePreview(messageId: String, preview: String): Boolean {
        if (messageId.isBlank() || preview.isBlank()) return false
        return withCurrentAccount { ownerUserId ->
            val current = _items.value
            val updated = NotificationCenterFoldPolicy.updateMessagePreview(
                current, messageId, preview, System.currentTimeMillis()
            )
            val changed = updated != current
            if (changed) {
                _items.value = updated
                saveToDisk(ownerUserId, updated)
            }
            changed
        } ?: false
    }

    fun unreadCount(): Int {
        return withCurrentAccount { _items.value.count { !it.read } } ?: 0
    }

    fun snapshot(): List<NotificationCenterItem> {
        return withCurrentAccount { _items.value } ?: emptyList()
    }

    /** Reloads the StateFlow when login/logout or account switching changes the owner. */
    fun refreshAccount() {
        ensureCurrentAccount()
    }

    private fun ensureCurrentAccount() {
        synchronized(accountLock) {
            bindCurrentAccountLocked(tokenManager.getUserId().orEmpty())
        }
    }

    private inline fun <T> withCurrentAccount(
        expectedUserId: String? = null,
        block: (String) -> T,
    ): T? = synchronized(accountLock) {
        if (com.maodouchat.security.SecureSessionManager.isPurgeInProgress()) {
            return@synchronized null
        }
        val userId = tokenManager.getUserId().orEmpty()
        bindCurrentAccountLocked(userId)
        if (userId.isBlank() || (!expectedUserId.isNullOrBlank() && expectedUserId != userId)) {
            null
        } else {
            block(userId)
        }
    }

    private fun bindCurrentAccountLocked(userId: String) {
        if (loadedUserId == userId) return
        if (userId.isNotBlank()) legacyMigration.migrateIfNeeded(userId)
        loadedUserId = userId
        _items.value = if (userId.isBlank()) emptyList() else loadFromDisk(userId)
    }

    private fun loadFromDisk(userId: String): List<NotificationCenterItem> {
        // 注意：调用方含主线程，Room 禁止主线程查询，此处经 runBlocking(Dispatchers.IO)
        // 桥接（库内 MediaCache/SecretChatSession 同模式）；仅账号切换时触发一次。
        // 180 天过期行在加载时 GC（与旧 JSON 版语义一致）。
        return runCatching {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                val dao = database.notificationCenterDao()
                val cutoff = System.currentTimeMillis() - MAX_AGE_MS
                dao.deleteOlderThanBlocking(userId, cutoff)
                dao.itemsForOwnerBlocking(userId).map { it.toModel() }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveToDisk(userId: String, items: List<NotificationCenterItem>) {
        if (userId.isBlank()) return
        // Account switch / purge race: never write prior owner's list under a new key,
        // and never overwrite the previous account after loadedUserId already moved.
        val liveUserId = tokenManager.getUserId().orEmpty()
        if (liveUserId.isNotBlank() && liveUserId != userId) return
        if (loadedUserId != null && loadedUserId != userId) return
        // prefs apply() 语义的 Room 等价：后台协程写 behind，主线程不阻塞。
        val snapshot = items.map { it.toEntity(userId) }
        persistScope.launch {
            runCatching {
                database.notificationCenterDao().replaceAllBlocking(userId, snapshot)
            }.onFailure { error ->
                Log.w("NotificationCenter", "persist snapshot failed for $userId", error)
            }
        }
    }

    companion object {
        private const val MAX_AGE_MS = 180L * 24L * 3600L * 1000L
    }
}
