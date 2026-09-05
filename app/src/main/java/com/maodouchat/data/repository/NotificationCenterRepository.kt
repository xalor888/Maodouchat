package com.maodouchat.data.repository

import android.content.Context
import android.util.Log
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.NotificationCenterItemEntity
import com.maodouchat.data.local.entity.toEntity
import com.maodouchat.data.local.entity.toModel
import com.maodouchat.network.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

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
            val current = _items.value
            // 同主题条目按类型合并（如同一未接来电不重复叠加）
            val index = current.indexOfFirst {
                it.type == item.type && it.mergeKey == item.mergeKey && !it.read
            }
            val updated = if (index >= 0) {
                val existing = current[index]
                // FCM+WS same message, or ring-timeout+peer-hang-up same missed call:
                // do not double-count under a shared mergeKey.
                val skipCount = com.maodouchat.notification.NotificationCenterMergePolicy
                    .shouldSkipCountIncrement(
                        itemType = item.type,
                        existingId = existing.id,
                        existingExtra = existing.extra,
                        incomingId = item.id,
                        incomingExtra = item.extra,
                    )
                val merged = existing.copy(
                    count = if (skipCount) existing.count else existing.count + 1,
                    updatedAt = item.updatedAt,
                    title = item.title,
                    preview = item.preview,
                    subtitle = item.subtitle,
                    deeplink = item.deeplink,
                    // Keep latest messageId/callId so delete/revoke/cancel can still match the head.
                    extra = item.extra,
                    id = if (skipCount) existing.id else item.id,
                )
                current.toMutableList().apply { set(index, merged) }
            } else {
                (listOf(item) + current).take(MAX_KEEP)
            }
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
                val now = System.currentTimeMillis()
                val updated = _items.value.map { if (it.read) it else it.copy(read = true, updatedAt = now) }
                _items.value = updated
                saveToDisk(ownerUserId, updated)
            }
        }
    }

    fun markRead(itemId: String) {
        withCurrentAccount { ownerUserId ->
            val now = System.currentTimeMillis()
            val current = _items.value
            val updated = current.map { if (it.id == itemId && !it.read) it.copy(read = true, updatedAt = now) else it }
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
            val updated = current.map { if (it.id == itemId && it.read) it.copy(read = false, updatedAt = now) else it }
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
            val now = System.currentTimeMillis()
            val updated = current.map { item ->
                val matches = com.maodouchat.notification.NotificationCenterReadPolicy.isChatMessageItem(
                    chatId = chatId,
                    mergeKey = item.mergeKey,
                    deeplink = item.deeplink,
                    extraChatId = item.extra["chatId"]
                )
                if (matches && !item.read) item.copy(read = true, updatedAt = now) else item
            }
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
            val now = System.currentTimeMillis()
            val updated = current.map { item ->
                val matches = item.type == "AI_TASK" &&
                    (item.mergeKey == "ai_tasks_$chatId" ||
                        item.deeplink == "maodouchat:ai_tasks:$chatId" ||
                        item.extra["chatId"] == chatId)
                if (matches && !item.read) item.copy(read = true, updatedAt = now) else item
            }
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
            val now = System.currentTimeMillis()
            val updated = current.map { item ->
                val matches = item.type == "POST_INTERACTION" &&
                    (item.mergeKey == "post_$postId" || item.extra["postId"] == postId)
                if (matches && !item.read) item.copy(read = true, updatedAt = now) else item
            }
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
            val updated = current.filterNot { item ->
                com.maodouchat.notification.NotificationCenterReadPolicy.belongsToChat(
                    chatId = chatId,
                    mergeKey = item.mergeKey,
                    deeplink = item.deeplink,
                    extraChatId = item.extra["chatId"]
                )
            }
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
            val updated = current.filterNot { item ->
                com.maodouchat.notification.NotificationCenterReadPolicy.referencesMessage(
                    messageId = messageId,
                    itemId = item.id,
                    extraMessageId = item.extra["messageId"]
                )
            }
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
        val ids = messageIds.distinct().filter(String::isNotBlank)
        withCurrentAccount { ownerUserId ->
            val current = _items.value
            val updated = current.filterNot { item ->
                ids.any { id ->
                    com.maodouchat.notification.NotificationCenterReadPolicy.referencesMessage(
                        messageId = id,
                        itemId = item.id,
                        extraMessageId = item.extra["messageId"]
                    )
                }
            }
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
            val now = System.currentTimeMillis()
            val updated = current.map { item ->
                val matches = com.maodouchat.notification.NotificationCenterReadPolicy.referencesMessage(
                    messageId = messageId,
                    itemId = item.id,
                    extraMessageId = item.extra["messageId"]
                )
                if (matches && item.preview != preview) item.copy(preview = preview, updatedAt = now) else item
            }
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
        if (userId.isNotBlank()) migrateLegacy(userId)
        loadedUserId = userId
        _items.value = if (userId.isBlank()) emptyList() else loadFromDisk(userId)
    }

    private fun migrateLegacy(userId: String) {
        // 旧 SharedPreferences 快照一次性导入 Room：Room 为空且 prefs 有数据时搬运后删键。
        // 此后 prefs 不再被读写（`notification_center` 键彻底退役）。
        if (roomItems(userId).isNotEmpty()) return
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for (key in listOf(accountItemsKey(userId), KEY_ITEMS)) {
            val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: continue
            val items = runCatching {
                val arr = org.json.JSONArray(raw)
                (0 until arr.length()).mapNotNull { index ->
                    runCatching { NotificationCenterItem.fromJson(arr.getJSONObject(index)) }.getOrNull()
                }
            }.getOrNull().orEmpty()
            if (items.isNotEmpty()) {
                runCatching {
                    kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                        database.notificationCenterDao().replaceAllBlocking(userId, items.map { it.toEntity(userId) })
                    }
                }
            }
            runCatching { prefs.edit().remove(key).apply() }
            if (key == accountItemsKey(userId)) break
        }
    }

    private fun roomItems(userId: String): List<NotificationCenterItemEntity> = runCatching {
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            database.notificationCenterDao().itemsForOwnerBlocking(userId)
        }
    }.getOrDefault(emptyList())

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

    private fun accountItemsKey(userId: String): String = "$KEY_ITEMS:$userId"

    companion object {
        private const val PREFS = "notification_center"
        private const val KEY_ITEMS = "items"
        private const val MAX_KEEP = 520
        private const val MAX_AGE_MS = 180L * 24L * 3600L * 1000L
    }
}

data class NotificationCenterItem(
    val id: String,
    val type: String,            // MESSAGE | MISSED_CALL | AI_TASK | POST_INTERACTION | FRIEND_REQUEST | GROUP_INVITE | REPORT | SECURITY
    val mergeKey: String,        // 用于合并同类同主键的通知
    val title: String,
    val subtitle: String? = null,
    val preview: String? = null,
    val deeplink: String? = null,
    val extra: Map<String, String> = emptyMap(),
    val read: Boolean = false,
    val count: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        // 注：旧 JSON 读写（toJson/fromJson）已随 Room 迁移删除；
        // 一次性 prefs 导入仍复用 fromJson（见 migrateLegacy）。
        fun fromJson(json: JSONObject): NotificationCenterItem {
            val extraJson = json.optJSONObject("extra")
            val extraMap = if (extraJson == null) emptyMap()
                else extraJson.keys().asSequence().associateWith { extraJson.optString(it) }
            return NotificationCenterItem(
                id = json.getString("id"),
                type = json.getString("type"),
                mergeKey = json.optString("mergeKey", json.optString("id", "")),
                title = json.optString("title", ""),
                subtitle = json.optString("subtitle", "").takeIf { it.isNotBlank() },
                preview = json.optString("preview", "").takeIf { it.isNotBlank() },
                deeplink = json.optString("deeplink", "").takeIf { it.isNotBlank() },
                extra = extraMap,
                read = json.optBoolean("read", false),
                count = json.optInt("count", 1).coerceAtLeast(1),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }
}
