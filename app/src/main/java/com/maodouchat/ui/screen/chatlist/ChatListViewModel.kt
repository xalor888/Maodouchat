package com.maodouchat.ui.screen.chatlist

import com.maodouchat.util.RuntimeFlags
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.User
import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.data.repository.AiSummaryRepository
import com.maodouchat.data.repository.AiTaskRepository
import com.maodouchat.data.repository.AiOperationRepository
import com.maodouchat.data.repository.MessageRepository
import com.maodouchat.data.repository.NotificationCenterRepository
import com.maodouchat.network.ApiConfig
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiService
import com.maodouchat.network.MessageMutationDto
import com.maodouchat.network.UpdateChatSettingsRequest
import com.maodouchat.network.TokenManager
import com.maodouchat.network.WebSocketClient
import com.maodouchat.network.WebSocketEvent
import com.maodouchat.ui.OwnerSessionPolicy
import com.maodouchat.ui.OwnerSessionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta

internal const val GROUP_OWNER_TRANSFER_REQUIRED = "GROUP_OWNER_TRANSFER_REQUIRED"

internal fun requiresGroupOwnershipTransfer(error: Throwable?): Boolean =
    (error as? ApiException)?.serverCode == GROUP_OWNER_TRANSFER_REQUIRED

data class ChatListUiState(
    val chats: List<Chat> = emptyList(),
    val searchQuery: String = "",
    val messageMatchedChatIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /** Non-blocking banner when realtime WS is down (cleared on Connected). */
    val realtimeBanner: String? = null,
    val ownerTransferRequiredChatId: String? = null,
    val selectedTab: Int = 0,
    val missedCalls: List<com.maodouchat.data.model.MissedCall> = emptyList(),
    val drafts: Map<String, ChatDraftEntity> = emptyMap(),
    /** 1.146：各会话待发送的本地定时消息数（chatId -> count） */
    val scheduledByChat: Map<String, Int> = emptyMap(),
    val showArchived: Boolean = false,
    /** 本地会话文件夹；null/all 表示全部 */
    val folders: List<com.maodouchat.util.ChatFolder> = emptyList(),
    val selectedFolderId: String? = null,
    /** 未读智能优先（本地排序，默认开） */
    val unreadPriorityEnabled: Boolean = true,
    /** 本地 PIN 锁定的会话 id */
    val lockedChatIds: Set<String> = emptySet(),
    /** 本机密聊会话 id（防截屏 + 盲水印） */
    val secretChatIds: Set<String> = emptySet(),
    /** 正在删除中的会话 id，防止双击删除并发重复清理本地数据 */
    val deletingChatIds: Set<String> = emptySet(),
    /** 服务端活跃公告（未读 + 生效窗口内，已按优先级排序） */
    val activeAnnouncements: List<com.maodouchat.notification.AnnouncementPolicy.AnnouncementData> = emptyList(),
    /** 8.47：智能归档建议（纯本地启发式；采纳走现有归档流程） */
    val archiveSuggestions: List<com.maodouchat.ai.AiArchiveSuggestion.Suggestion> = emptyList(),
    /** 1.103：对端正在输入（chatId -> userId，3s 过期） */
    val typingByChat: Map<String, String> = emptyMap(),
    /** 1.165：身份密钥已变更（CHANGED）的对端 userId（本地 identity_trust 聚合，会话列表警告）。 */
    val identityChangedUserIds: Set<String> = emptySet(),
    /** 1.368：多选模式（批量置顶/已读/删除，对标 TG/微信会话列表长按多选） */
    val selectionMode: Boolean = false,
    /** 1.368：多选模式下已勾选的会话 id */
    val selectedChatIds: Set<String> = emptySet(),
) {
    val filteredChats: List<Chat>
        get() {
            val visible = chats.filter { it.archived == showArchived }
            val folderFiltered = when {
                selectedFolderId.isNullOrBlank() -> visible
                selectedFolderId == com.maodouchat.util.ChatFolderPolicy.SYSTEM_GROUPS_ID ->
                    visible.filter { it.isGroup }
                selectedFolderId == com.maodouchat.util.ChatFolderPolicy.SYSTEM_DIRECT_ID ->
                    visible.filter { !it.isGroup }
                selectedFolderId == com.maodouchat.util.ChatFolderPolicy.SYSTEM_UNREAD_ID ->
                    visible.filter {
                        com.maodouchat.util.ChatFolderPolicy.isUnreadChat(it.unreadCount, it.markedUnread)
                    }
                selectedFolderId == com.maodouchat.util.ChatFolderPolicy.SYSTEM_SECRET_ID ->
                    visible.filter { it.id in secretChatIds }
                selectedFolderId == com.maodouchat.util.ChatFolderPolicy.SYSTEM_LOCKED_ID ->
                    visible.filter { it.id in lockedChatIds }
                else -> {
                    val folder = folders.firstOrNull { it.id == selectedFolderId }
                    if (folder == null) visible
                    else {
                        val ids = folder.chatIds.toSet()
                        visible.filter { it.id in ids }
                    }
                }
            }
            val matched = if (searchQuery.isBlank()) folderFiltered else folderFiltered.filter { chat ->
                val name = chat.groupName
                    ?: chat.participants.firstOrNull()?.displayName
                    ?: chat.participants.firstOrNull()?.name
                    ?: ""
                val nameHit = name.contains(searchQuery, ignoreCase = true) ||
                    chat.participants.any { user ->
                        user.displayName.contains(searchQuery, ignoreCase = true) ||
                            user.name.contains(searchQuery, ignoreCase = true) ||
                            (user.nickname?.contains(searchQuery, ignoreCase = true) == true) ||
                            user.email.contains(searchQuery, ignoreCase = true)
                    }
                // PIN-locked chats: match by title/participants only; hide body/draft hits.
                if (chat.id in lockedChatIds) return@filter nameHit
                nameHit ||
                    chat.lastMessage.contains(searchQuery, ignoreCase = true) ||
                    drafts[chat.id]?.text?.contains(searchQuery, ignoreCase = true) == true ||
                    chat.id in messageMatchedChatIds
            }
            return matched.sortedWith(
                compareByDescending<Chat> { it.pinnedAt > 0 }
                    .thenByDescending { it.pinnedAt }
                    .thenByDescending { chat ->
                        if (unreadPriorityEnabled) {
                            com.maodouchat.util.UnreadPriorityPolicy.activityScore(
                                lastMessageTime = chat.lastMessageTime,
                                draftUpdatedAt = drafts[chat.id]?.updatedAt ?: 0L,
                                unreadCount = chat.unreadCount,
                                markedUnread = chat.markedUnread,
                                muted = chat.notificationsMuted
                            )
                        } else {
                            maxOf(chat.lastMessageTime, drafts[chat.id]?.updatedAt ?: 0L)
                        }
                    }
                    // 1.244：时间相近时静音会话排后面（微信式；仅在未读优先关闭时生效）
                    .thenBy { if (!unreadPriorityEnabled && it.notificationsMuted) 1 else 0 }
            )
        }

    val unreadChatCount: Int
        get() = com.maodouchat.util.UnreadPriorityPolicy.countUnreadChats(
            unreadCounts = chats.filter { !it.archived }.map { it.unreadCount },
            markedUnreadFlags = chats.filter { !it.archived }.map { it.markedUnread }
        )

    val showUnreadPriorityHint: Boolean
        get() = com.maodouchat.util.UnreadPriorityPolicy.shouldShowHint(
            enabled = unreadPriorityEnabled,
            totalUnreadChats = unreadChatCount,
            isSearching = searchQuery.isNotBlank()
        )

    fun unreadInFolder(folderId: String): Int {
        if (folderId == com.maodouchat.util.ChatFolderPolicy.SYSTEM_GROUPS_ID) {
            return com.maodouchat.util.UnreadPriorityPolicy.countUnreadChats(
                unreadCounts = chats.filter { !it.archived && it.isGroup }.map { it.unreadCount },
                markedUnreadFlags = chats.filter { !it.archived && it.isGroup }.map { it.markedUnread }
            )
        }
        if (folderId == com.maodouchat.util.ChatFolderPolicy.SYSTEM_DIRECT_ID) {
            return com.maodouchat.util.UnreadPriorityPolicy.countUnreadChats(
                unreadCounts = chats.filter { !it.archived && !it.isGroup }.map { it.unreadCount },
                markedUnreadFlags = chats.filter { !it.archived && !it.isGroup }.map { it.markedUnread }
            )
        }
        if (folderId == com.maodouchat.util.ChatFolderPolicy.SYSTEM_UNREAD_ID) {
            return unreadChatCount
        }
        if (folderId == com.maodouchat.util.ChatFolderPolicy.SYSTEM_SECRET_ID) {
            val secretVisible = chats.filter { !it.archived && it.id in secretChatIds }
            return com.maodouchat.util.UnreadPriorityPolicy.countUnreadChats(
                unreadCounts = secretVisible.map { it.unreadCount },
                markedUnreadFlags = secretVisible.map { it.markedUnread }
            )
        }
        if (folderId == com.maodouchat.util.ChatFolderPolicy.SYSTEM_LOCKED_ID) {
            val lockedVisible = chats.filter { !it.archived && it.id in lockedChatIds }
            return com.maodouchat.util.UnreadPriorityPolicy.countUnreadChats(
                unreadCounts = lockedVisible.map { it.unreadCount },
                markedUnreadFlags = lockedVisible.map { it.markedUnread }
            )
        }
        val folder = folders.firstOrNull { it.id == folderId } ?: return 0
        val unreadMap = chats.associate { it.id to it.unreadCount }
        return com.maodouchat.util.ChatFolderPolicy.unreadInFolder(folder, unreadMap)
    }
}

class ChatListViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MaodouchatApp
    private val chatRepo = ChatRepository(app.database.chatDao(), app.database.userDao())
    private val messageRepo = MessageRepository(app.database.messageDao(), app.database)
    private val aiSummaryRepo = AiSummaryRepository(app.database.aiSummaryCacheDao())
    private val aiTaskRepo = AiTaskRepository(app.database.aiTaskDao(), application)
    private val aiOperationRepo = AiOperationRepository(app.database.aiOperationDao())
    private val missedRepo = com.maodouchat.data.repository.MissedCallRepository(app.database.missedCallDao())
    private val tokenManager = TokenManager.getInstance(application)
    private fun text(id: Int): String = getApplication<Application>().getString(id)

    private suspend fun <T> bestEffort(block: suspend () -> T): T? = try {
        block()
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    /** 1.171：清空指定会话的本地聊天记录（保留会话/PIN/草稿；服务端密文仍在，重开会再同步）。 */
    fun clearLocalChatHistory(chatId: String) {
        if (chatId.isBlank()) return
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (ownerUserId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val cachedMessageIds = bestEffort { messageRepo.getMessageIdsByChatId(chatId) }.orEmpty()
            bestEffort { com.maodouchat.attachment.AttachmentTransferCoordinator.cancelForChat(app, chatId) }
            bestEffort {
                val removed = com.maodouchat.util.ScheduledMessageStore.clearForChat(app, chatId)
                removed.forEach { com.maodouchat.util.ScheduledMessageScheduler.cancel(app, it) }
            }
            bestEffort { messageRepo.deleteMessagesByChatId(chatId) }
            bestEffort { app.database.messageSearchDao().deleteChatIndex(chatId) }
            if (ownerUserId.isNotBlank()) {
                bestEffort {
                    app.database.attachmentTransferDao().clearWireContentForChat(chatId, ownerUserId = ownerUserId)
                }
            }
            cachedMessageIds.forEach { messageId ->
                bestEffort { com.maodouchat.util.MediaCache.deleteCachedMediaForMessage(app, messageId) }
            }
            bestEffort {
                app.notificationCenter.removeChatItems(chatId)
                com.maodouchat.util.AppNotifier.cancelMessage(app, chatId)
            }
            bestEffort { tokenManager.clearChatCursors(chatId) }
            val local = chatRepo.getChatById(chatId)
            if (local != null) {
                bestEffort {
                    chatRepo.cacheChats(
                        listOf(
                            local.copy(
                                lastMessage = "",
                                lastMessageType = MessageType.TEXT,
                                unreadCount = 0,
                                markedUnread = false
                            )
                        )
                    )
                }
            }
            loadChats(showLoading = false)
        }
    }

    /** 1.165：扫描本地 identity_trust，标记身份密钥已变更（CHANGED）的对端用户（纯本地，无网络请求）。 */
    private fun refreshIdentityWarnings() {
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (ownerUserId.isBlank() || ownerUserId == "me") return
        val remoteIds = _uiState.value.chats
            .filter { !it.isGroup }
            .mapNotNull { chat -> chat.participants.firstOrNull { it.id != ownerUserId }?.id }
            .toSet()
        if (remoteIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val changed = remoteIds.filter { remoteId ->
                try {
                    app.database.identityTrustDao().getAllTrustForUser(ownerUserId, remoteId)
                        .any { it.trustState == com.maodouchat.crypto.PersistentSignalProtocolStore.TRUST_CHANGED }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    false
                }
            }.toSet()
            if (changed != _uiState.value.identityChangedUserIds) {
                _uiState.update { it.copy(identityChangedUserIds = changed) }
            }
        }
    }

    private val _uiState = MutableStateFlow(ChatListUiState())
    val uiState: StateFlow<ChatListUiState> = _uiState.asStateFlow()

    // 设置开关（置顶/静音/归档/标未读）入口级重入保护：防止同帧连点发出方向相反的两笔请求
    private val settingsInFlight = mutableSetOf<String>()

    /**
     * 本 VM 生命周期内已成功删除（退出服务端）的会话 id。
     * 用于防止删除竞态：删除先乐观从 UI 移除，但服务端 leave 尚未生效时若 WS 推来该会话的新消息，
     * MessageReceived 分支会触发 requestLoadChats→loadChats 从服务端重新拉回该会话（角标鬼影/复活）。
     * 命中本集合的未知会话消息不触发重载，loadChats 合入时也会丢弃，并从本地清理。
     */
    private val deletedChatIds = mutableSetOf<String>()

    private val notificationRepo: NotificationCenterRepository = app.notificationCenter

    private fun ownerSession(ownerUserId: String = currentUserIdStr): OwnerSessionSnapshot =
        OwnerSessionSnapshot(ownerUserId, MaodouchatApp.currentSessionGeneration())

    private fun isOwnerSessionCurrent(session: OwnerSessionSnapshot): Boolean =
        OwnerSessionPolicy.isCurrent(
            snapshot = session,
            liveUserId = tokenManager.getUserId(),
            liveToken = tokenManager.getToken(),
            liveSessionGeneration = MaodouchatApp.currentSessionGeneration(),
            purgeInProgress = com.maodouchat.security.SecureSessionManager.isPurgeInProgress(),
        )

    private suspend fun withOwnerRoomWrite(
        session: OwnerSessionSnapshot,
        block: suspend () -> Unit,
    ): Boolean = app.database.withTransaction {
        if (!isOwnerSessionCurrent(session)) {
            false
        } else {
            block()
            true
        }
    }

    /** Reaction WS can arrive before the message row exists; buffer briefly. */
    @Volatile
    private var pendingReactions: Map<String, PendingReactionPolicy.Entry> = emptyMap()

    val notificationCenterUnread: StateFlow<Int> = notificationRepo.items
        .map { items -> items.count { !it.read } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), notificationRepo.unreadCount())

    // 1.112：Explore 标签「动态互动」未读角标（仅 POST_INTERACTION 未读）
    val exploreBadgeCount: StateFlow<Int> = notificationRepo.items
        .map { items -> items.count { !it.read && it.type == com.maodouchat.ui.screen.chatlist.NotificationCenterType.POST_INTERACTION } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        loadFolders()
        loadUnreadPriority()
        refreshLockedChats()
        refreshSecretChats()
        requestLoadChats(ChatListReloadPolicy.Trigger.INITIAL)
        observeDrafts()
        observeRealtime()
        observeMissedCalls()
        refreshAnnouncements()
        fetchPushVerifyKey()
        // 1.146：会话列表显示待发送定时消息数（本地 store，prefs 非流式 → 按需刷新）
        refreshScheduledCounts()
        // 8.47：智能归档建议（纯本地，全库扫描较重）——延迟到主页稳定后一次性计算
        viewModelScope.launch {
            delay(3_000L)
            loadArchiveSuggestions()
        }
        // 1.54：底部导航未读角标——汇总未读数推送到 UnreadBadgeStore
        viewModelScope.launch {
            // 8.49 修复：与 unreadChatCount/文件夹角标口径统一，排除已归档会话——
            // 否则归档会话来消息时 Tab 角标上涨，默认列表却看不到对应未读
            _uiState.map { state -> state.chats.filter { !it.archived }.sumOf { it.unreadCount } }
                .distinctUntilChanged()
                .collect { com.maodouchat.ui.screen.chatlist.UnreadBadgeStore.totalUnread.value = it }
        }
        // 1.112：Explore 标签「动态互动」未读角标
        viewModelScope.launch {
            notificationRepo.items
                .map { items -> items.count { !it.read && it.type == com.maodouchat.ui.screen.chatlist.NotificationCenterType.POST_INTERACTION } }
                .distinctUntilChanged()
                .collect { com.maodouchat.ui.screen.chatlist.ExploreBadgeStore.count.value = it }
        }
        // 1.103：会话列表「正在输入」presence（3s 过期由 store 维护）
        viewModelScope.launch {
            com.maodouchat.util.TypingPresenceStore.typingByChat.collect { typing ->
                _uiState.update { it.copy(typingByChat = typing) }
            }
        }
    }

    /** 8.48 修复：忽略集合按账号持久化（此前仅内存，进程重启后已忽略建议重现）。 */
    private val dismissedArchiveSuggestions = loadDismissedArchiveSuggestions()

    private fun archiveDismissPrefs(): android.content.SharedPreferences =
        getApplication<Application>().getSharedPreferences("archive_suggestion_dismiss", android.content.Context.MODE_PRIVATE)

    private fun loadDismissedArchiveSuggestions(): MutableSet<String> {
        val userId = tokenManager.getUserId().orEmpty()
        if (userId.isBlank()) return mutableSetOf()
        return archiveDismissPrefs()
            .getStringSet("dismissed_$userId", emptySet())
            .orEmpty()
            .toMutableSet()
    }

    private fun persistDismissedArchiveSuggestions() {
        val userId = tokenManager.getUserId().orEmpty()
        if (userId.isBlank()) return
        archiveDismissPrefs()
            .edit()
            .putStringSet("dismissed_$userId", dismissedArchiveSuggestions)
            .apply()
    }

    /** 重算智能归档建议（纯本地 SQLCipher 打分，无服务端调用）。 */
    fun loadArchiveSuggestions() {
        val ownerUserId = currentUserIdStr
        if (ownerUserId.isBlank()) return
        val session = ownerSession(ownerUserId)
        viewModelScope.launch {
            if (!isOwnerSessionCurrent(session)) return@launch
            val suggestions = try {
                com.maodouchat.ai.AiArchiveSuggestion.refresh(getApplication(), app.database)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("ChatListViewModel", "loadArchiveSuggestions failed", error)
                emptyList()
            }
            if (!isOwnerSessionCurrent(session)) return@launch
            _uiState.update { it.copy(archiveSuggestions = suggestions.filter { s -> s.chatId !in dismissedArchiveSuggestions }) }
        }
    }

    /** 忽略单条归档建议（持久化，进程重启后不重现）。 */
    fun dismissArchiveSuggestion(chatId: String) {
        dismissedArchiveSuggestions += chatId
        persistDismissedArchiveSuggestions()
        _uiState.update { it.copy(archiveSuggestions = it.archiveSuggestions.filter { s -> s.chatId != chatId }) }
    }

    /** 忽略全部归档建议（持久化）。 */
    fun dismissAllArchiveSuggestions() {
        _uiState.value.archiveSuggestions.forEach { dismissedArchiveSuggestions += it.chatId }
        persistDismissedArchiveSuggestions()
        _uiState.update { it.copy(archiveSuggestions = emptyList()) }
    }

    /** 采纳智能归档建议：归档会话（复用 toggleArchived 服务端同步）并移除建议。 */
    fun archiveChatFromSuggestion(chat: Chat) {
        // 8.48 修复：建议卡片只在 init+3s 计算一次，本地 archived 可能已陈旧——
        // 若该会话已被（长按菜单/他端/服务端）归档，点「归档」不得反向取消归档，仅移除建议
        val fresh = _uiState.value.chats.firstOrNull { it.id == chat.id }
        if (fresh != null && !fresh.archived) toggleArchived(chat.id)
        dismissArchiveSuggestion(chat.id)
    }

    /** 经认证通道拉取推送 HMAC 校验密钥（P0 修复后续：密钥不再匿名暴露于 status 端点）。 */
    private fun fetchPushVerifyKey() {
        val token = tokenManager.getToken().orEmpty()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank()) return
        viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            val raw = ApiService.getPushVerifyKey(token).getOrNull().orEmpty()
            if (raw.isBlank()) return@launch
            runCatching {
                val o = org.json.JSONObject(raw)
                if (o.isNull("key")) {
                    // 服务端未配置密钥：清理旧 key → fail-open
                    com.maodouchat.util.PushVerifyPrefs.clearKey(getApplication())
                } else {
                    val key = o.optString("key")
                    if (key.isNotBlank() && key != "null") {
                        com.maodouchat.util.PushVerifyPrefs.setKey(getApplication(), key)
                    }
                }
            }
        }
    }

    fun refreshLockedChats() {
        val ownerUserId = currentUserIdStr
        if (ownerUserId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val ids = try {
                app.database.chatLockDao().listLockedChatIds().toSet()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                emptySet()
            }
            if (tokenManager.getUserId().orEmpty() != ownerUserId) return@launch
            _uiState.update { it.copy(lockedChatIds = ids) }
        }
    }

    fun refreshSecretChats() {
        val ownerUserId = currentUserIdStr
        if (ownerUserId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val ids = try {
                app.database.secretChatDao().listSecretChatIds().toSet()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                emptySet()
            }
            if (tokenManager.getUserId().orEmpty() != ownerUserId) return@launch
            _uiState.update { it.copy(secretChatIds = ids) }
        }
    }

    /** 拉取服务端活跃公告 → 本地过滤（未读 + 生效窗口 + 合法级别）→ 展示列表。 */
    fun refreshAnnouncements() {
        val token = tokenManager.getToken().orEmpty()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank()) return
        viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            val raw = ApiService.getActiveAnnouncements(token).getOrNull().orEmpty()
            if (raw.isBlank()) return@launch
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            val list = runCatching {
                val arr = org.json.JSONObject(raw).optJSONArray("announcements") ?: org.json.JSONArray()
                fun safeOpt(o: org.json.JSONObject, key: String): String =
                    if (o.has(key)) o.optString(key).takeIf { it != "null" }.orEmpty() else ""
                val items = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = safeOpt(o, "id")
                    if (id.isBlank()) return@mapNotNull null
                    com.maodouchat.notification.AnnouncementPolicy.AnnouncementData(
                        id = id,
                        title = safeOpt(o, "title"),
                        content = safeOpt(o, "content"),
                        level = safeOpt(o, "level"),
                        startsAt = o.optLong("startsAt", 0L),
                        expiresAt = o.optLong("expiresAt", 0L),
                        status = safeOpt(o, "status"),
                        acked = o.optBoolean("acked", false)
                    )
                }
                com.maodouchat.notification.AnnouncementPolicy.filterForDisplay(items, System.currentTimeMillis())
            }.getOrNull().orEmpty()
            if (tokenManager.getUserId().orEmpty() != ownerUserId) return@launch
            _uiState.update { it.copy(activeAnnouncements = list) }
        }
    }

    /** 公告已读确认：调服务端 ack 并从本地展示列表移除（inFlight 防重入）。 */
    private val announcementAckInFlight = mutableSetOf<String>()
    fun ackAnnouncement(announcementId: String) {
        val token = tokenManager.getToken().orEmpty()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank()) return
        if (!announcementAckInFlight.add(announcementId)) return
        viewModelScope.launch {
            try {
                ApiService.ackAnnouncement(token, announcementId)
            } finally {
                announcementAckInFlight.remove(announcementId)
            }
            if (tokenManager.getUserId().orEmpty() != ownerUserId) return@launch
            _uiState.update { it.copy(activeAnnouncements = it.activeAnnouncements.filterNot { a -> a.id == announcementId }) }
        }
    }

    private fun loadFolders() {
        val folders = com.maodouchat.util.ChatFolderPreferences.getFolders(getApplication())
        _uiState.update { it.copy(folders = folders) }
        syncFoldersFromCloud()
    }

    /** 拉取云端文件夹并与本地合并（云端更新时间更新时优先云端，否则推本地）。 */
    private fun syncFoldersFromCloud() {
        val token = tokenManager.getToken().orEmpty()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank()) return
        viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) return@launch
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            val remoteResult = ApiService.getChatFolders(liveToken)
            val remoteError = remoteResult.exceptionOrNull()
            if (remoteError is kotlinx.coroutines.CancellationException) throw remoteError
            val remote = remoteResult.getOrNull() ?: return@launch
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) return@launch
            val local = com.maodouchat.util.ChatFolderPreferences.getFolders(getApplication())
            if (remote.folders.isEmpty() && local.isNotEmpty()) {
                pushFoldersToCloud(local)
                return@launch
            }
            if (remote.folders.isEmpty()) return@launch
            val mapped = remote.folders.map { dto ->
                com.maodouchat.util.ChatFolder(
                    id = dto.id,
                    name = dto.name,
                    chatIds = dto.chatIds,
                    sortOrder = dto.sortOrder
                )
            }.sortedBy { it.sortOrder }
            com.maodouchat.util.ChatFolderPreferences.setFolders(getApplication(), mapped)
            _uiState.update {
                val selectedStillExists = it.selectedFolderId == null ||
                    com.maodouchat.util.ChatFolderPolicy.isSystemFilter(it.selectedFolderId) ||
                    mapped.any { folder -> folder.id == it.selectedFolderId }
                it.copy(
                    folders = mapped,
                    selectedFolderId = if (selectedStillExists) it.selectedFolderId else null
                )
            }
        }
    }

    private fun pushFoldersToCloud(folders: List<com.maodouchat.util.ChatFolder>) {
        val token = tokenManager.getToken().orEmpty()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank()) return
        viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) return@launch
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            val payload = folders.map { folder ->
                com.maodouchat.network.ChatFolderDto(
                    id = folder.id,
                    name = folder.name,
                    sortOrder = folder.sortOrder,
                    chatIds = folder.chatIds
                )
            }
            val result = ApiService.putChatFolders(liveToken, payload)
            val error = result.exceptionOrNull()
            if (error is kotlinx.coroutines.CancellationException) throw error
        }
    }

    private fun loadUnreadPriority() {
        refreshUnreadPriorityPreference()
    }

    /** 从设置页返回时同步开关（不外发）。 */
    fun refreshUnreadPriorityPreference() {
        val enabled = com.maodouchat.util.UnreadPriorityPreferences.isEnabled(getApplication())
        _uiState.update { it.copy(unreadPriorityEnabled = enabled) }
    }

    fun setUnreadPriorityEnabled(enabled: Boolean) {
        com.maodouchat.util.UnreadPriorityPreferences.setEnabled(getApplication(), enabled)
        _uiState.update { it.copy(unreadPriorityEnabled = enabled) }
    }

    private fun persistFolders(folders: List<com.maodouchat.util.ChatFolder>) {
        com.maodouchat.util.ChatFolderPreferences.setFolders(getApplication(), folders)
        _uiState.update {
            val selectedStillExists = it.selectedFolderId == null ||
                com.maodouchat.util.ChatFolderPolicy.isSystemFilter(it.selectedFolderId) ||
                folders.any { folder -> folder.id == it.selectedFolderId }
            it.copy(
                folders = folders,
                selectedFolderId = if (selectedStillExists) it.selectedFolderId else null
            )
        }
        pushFoldersToCloud(folders)
    }

    fun selectFolder(folderId: String?) {
        _uiState.update {
            val id = folderId?.takeIf { raw -> raw.isNotBlank() }
            // System filters and user folders both sticky for this session only
            it.copy(selectedFolderId = id)
        }
    }

    fun createFolder(name: String): Boolean {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CHAT_FOLDERS)) {
            return false
        }
        val next = com.maodouchat.util.ChatFolderPolicy.createFolder(_uiState.value.folders, name)
            ?: return false
        persistFolders(next)
        return true
    }

    fun renameFolder(folderId: String, name: String): Boolean {
        val next = com.maodouchat.util.ChatFolderPolicy.renameFolder(_uiState.value.folders, folderId, name)
            ?: return false
        persistFolders(next)
        return true
    }

    fun deleteFolder(folderId: String) {
        persistFolders(com.maodouchat.util.ChatFolderPolicy.deleteFolder(_uiState.value.folders, folderId))
    }

    /** 9.222：文件夹上下移（交换 sortOrder，本地+云端同步）。 */
    fun moveFolder(folderId: String, delta: Int): Boolean {
        val next = com.maodouchat.util.ChatFolderPolicy.moveFolder(_uiState.value.folders, folderId, delta)
            ?: return false
        persistFolders(next)
        return true
    }

    /** 9.233：拖拽排序——把文件夹移到目标位置（插入语义，云端同步）。 */
    fun reorderFolder(folderId: String, targetIndex: Int): Boolean {
        val next = com.maodouchat.util.ChatFolderPolicy.reorderFolder(_uiState.value.folders, folderId, targetIndex)
            ?: return false
        persistFolders(next)
        return true
    }

    fun moveChatToFolder(chatId: String, folderId: String?) {
        if (chatId.isBlank()) return
        persistFolders(
            com.maodouchat.util.ChatFolderPolicy.moveChatToFolder(
                existing = _uiState.value.folders,
                chatId = chatId,
                targetFolderId = folderId
            )
        )
    }

    fun markMissedCallsRead() {
        val markOwnerUserId = currentUserIdStr
        if (
            markOwnerUserId.isBlank() ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = markOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return
        }
        viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = markOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            val snapshot = _uiState.value.missedCalls
            try {
                missedRepo.markAllRead()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("ChatListViewModel", "markMissedCallsRead failed", error)
            }
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = markOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            // Drop tray + center rows so mark-read on the list card matches privacy UX.
            dismissMissedCallNotifications(snapshot)
        }
    }

    fun clearMissedCalls() {
        val clearOwnerUserId = currentUserIdStr
        if (
            clearOwnerUserId.isBlank() ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = clearOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return
        }
        viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = clearOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            val snapshot = _uiState.value.missedCalls
            try {
                missedRepo.clearAll()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("ChatListViewModel", "clearMissedCalls failed", error)
            }
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = clearOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            dismissMissedCallNotifications(snapshot)
        }
    }

    /**
     * Resolve local 1:1 chat id for a missed-call peer (if a conversation already exists).
     */
    fun findDirectChatIdForUser(userId: String): String? {
        if (userId.isBlank()) return null
        return _uiState.value.chats.firstOrNull { chat ->
            !chat.isGroup && chat.participants.any { it.id == userId }
        }?.id
    }

    /**
     * 1.289：从会话列表移除单条通话记录。
     * 同步删除 Room missed_calls（保持角标一致）+ 更新本地 state（弹窗即时消失）。
     * CallLogStore 已由调用方删除。
     */
    fun removeMissedCallLocally(callId: String) {
        if (callId.isBlank()) return
        val ownerUserId = currentUserIdStr
        _uiState.update { st ->
            st.copy(missedCalls = st.missedCalls.filterNot { it.id == callId })
        }
        if (ownerUserId.isBlank()) return
        viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            try {
                missedRepo.delete(callId)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
            }
            // 若该条已聚合在系统托盘，同步移除阴影
            runCatching { com.maodouchat.util.AppNotifier.cancelMissedCall(getApplication(), callId) }
        }
    }

    private suspend fun dismissMissedCallNotifications(snapshot: List<com.maodouchat.data.model.MissedCall>) {
        for (call in snapshot) {
            try {
                com.maodouchat.util.AppNotifier.cancelMissedCall(app, call.id)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
        try {
            for (call in snapshot) {
                notificationRepo.remove("missed_${call.id}")
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w("ChatListViewModel", "missed-call center cleanup failed", error)
        }
    }

    private val currentUserIdStr: String get() = tokenManager.getUserId() ?: ""

    private fun observeDrafts() {
        val ownerUserId = currentUserIdStr
        if (ownerUserId.isBlank()) return
        viewModelScope.launch {
            app.database.chatDraftDao().observeForOwner(ownerUserId).collect { drafts ->
                // Drop if process-local session switched while Flow was still open.
                if (tokenManager.getUserId().orEmpty() != ownerUserId) return@collect
                _uiState.update { state -> state.copy(drafts = drafts.associateBy(ChatDraftEntity::chatId)) }
            }
        }
    }

    private var messageSearchJob: Job? = null

    /** 1.146：刷新各会话待发送定时消息数（本地 prefs store）。 */
    fun refreshScheduledCounts() {
        val ctx = getApplication<Application>()
        val counts = try {
            com.maodouchat.util.ScheduledMessageStore.list(ctx)
                .groupingBy { it.chatId }
                .eachCount()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
        _uiState.update { it.copy(scheduledByChat = counts) }
    }

    fun onSearchQueryChange(query: String) {
        // 8.52 UX：列表搜索框长度上限（对齐其它搜索框，防超长 LIKE 查询）
        val clipped = if (query.length > LIST_SEARCH_MAX_LENGTH) query.take(LIST_SEARCH_MAX_LENGTH) else query
        _uiState.update { it.copy(searchQuery = clipped) }
        messageSearchJob?.cancel()
        if (clipped.isBlank() || clipped.length < 2) {
            _uiState.update { it.copy(messageMatchedChatIds = emptySet()) }
            return
        }
        // Debounce + single-flight: rapid typing must not apply a slower older LIKE result.
        messageSearchJob = viewModelScope.launch {
            delay(LIST_MESSAGE_SEARCH_DEBOUNCE_MS)
            val searchOwnerUserId = tokenManager.getUserId().orEmpty()
            if (searchOwnerUserId.isBlank()) {
                _uiState.update { it.copy(messageMatchedChatIds = emptySet()) }
                return@launch
            }
            // 9.156：转义与陈旧比对统一使用 clipped——此前用未截断的 query，
            // 超长粘贴（> LIST_SEARCH_MAX_LENGTH）时 searchQuery 存的是截断值，
            // 陈旧守卫恒判「已过期」→ 搜索结果永远被丢弃；LIKE 还以全文匹配浪费查询
            val escaped = com.maodouchat.data.local.LikeQueryPolicy.escapeForContains(clipped)
            if (escaped.isBlank()) {
                _uiState.update { it.copy(messageMatchedChatIds = emptySet()) }
                return@launch
            }
            val matchedIds = withContext(Dispatchers.IO) {
                val locked = app.database.chatLockDao().listLockedChatIds().toSet()
                val secret = app.database.secretChatDao().listSecretChatIds().toSet()
                app.database.messageDao().searchChatIdsByMessageContent(escaped)
                    .filterNot { it in locked || it in secret }
            }
            // Drop if user kept typing past this snapshot or account switched mid-search.
            if (_uiState.value.searchQuery != clipped) return@launch
            if (tokenManager.getUserId().orEmpty() != searchOwnerUserId) return@launch
            _uiState.update { it.copy(messageMatchedChatIds = matchedIds.toSet()) }
        }
    }

    fun onTabSelected(tab: Int) { _uiState.update { it.copy(selectedTab = tab) } }
    fun setShowArchived(show: Boolean) { _uiState.update { it.copy(showArchived = show) } }
    fun refresh() = requestLoadChats(ChatListReloadPolicy.Trigger.USER_REFRESH)

    /**
     * Coalesce bursty WS-driven full list reloads (delete/revoke/group revision).
     * Local preview/unread already update optimistically; getChats is for server truth.
     */
    private var debouncedLoadChatsJob: Job? = null
    private var loadChatsJob: Job? = null
    private var loadChatsRequestId: Long = 0L

    private fun requestLoadChats(trigger: ChatListReloadPolicy.Trigger) {
        val mode = ChatListReloadPolicy.modeFor(trigger)
        when (mode) {
            ChatListReloadPolicy.Mode.IMMEDIATE_VISIBLE,
            ChatListReloadPolicy.Mode.IMMEDIATE_SILENT -> {
                debouncedLoadChatsJob?.cancel()
                loadChats(showLoading = ChatListReloadPolicy.shouldShowLoading(mode))
            }
            ChatListReloadPolicy.Mode.DEBOUNCED_SILENT -> {
                debouncedLoadChatsJob?.cancel()
                debouncedLoadChatsJob = viewModelScope.launch {
                    delay(ChatListReloadPolicy.debounceMs(mode))
                    loadChats(showLoading = false)
                }
            }
        }
    }

    /** Flush SENDING text outbox without requiring ChatDetail to be open. */
    private fun flushTextOutbox() {
        val flushOwnerUserId = tokenManager.getUserId().orEmpty()
        if (
            flushOwnerUserId.isBlank() ||
            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = flushOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = flushOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                com.maodouchat.data.repository.TextOutboxFlusher.flush(app = app)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("ChatListViewModel", "text outbox flush failed: ${error.message}")
            }
        }
    }

    /**
     * Update list preview + sort key in memory and Room.
     * [unreadDelta] is applied only when the chat is already on the list (incoming path).
     * [forceTimestamp] uses the given time as-is (delete/revoke/empty tail); default keeps
     * monotonic max so late WS cannot rewind sort order for ordinary sends.
     */
    private fun applyChatListPreview(
        chatId: String,
        previewText: String,
        messageType: MessageType,
        timestamp: Long,
        unreadDelta: Int = 0,
        forceTimestamp: Boolean = false,
        ownerUserId: String = currentUserIdStr,
        sessionGeneration: Long = MaodouchatApp.currentSessionGeneration(),
    ) {
        val session = OwnerSessionSnapshot(ownerUserId, sessionGeneration)
        if (chatId.isBlank() || !isOwnerSessionCurrent(session)) return
        _uiState.update { state ->
            val target = state.chats.find { it.id == chatId } ?: return@update state
            val others = state.chats.filterNot { it.id == chatId }
            val nextTime = if (forceTimestamp) timestamp else maxOf(target.lastMessageTime, timestamp)
            val updatedTarget = target.copy(
                lastMessage = previewText,
                lastMessageType = messageType,
                lastMessageTime = nextTime,
                unreadCount = (target.unreadCount + unreadDelta).coerceAtLeast(0)
            )
            // 保持置顶优先排序：新消息把未置顶会话顶到“未置顶区”最前，而非越过所有置顶会话。
            val reordered = (others + updatedTarget).sortedWith(
                compareByDescending<Chat> { it.pinnedAt > 0 }
                    .thenByDescending { it.pinnedAt }
                    .thenByDescending { it.lastMessageTime }
            )
            state.copy(chats = reordered)
        }
        viewModelScope.launch {
            try {
                withOwnerRoomWrite(session) {
                    val cached = chatRepo.getChatById(chatId) ?: return@withOwnerRoomWrite
                    val nextTime = if (forceTimestamp) timestamp else maxOf(cached.lastMessageTime, timestamp)
                    chatRepo.cacheChats(
                        listOf(
                            cached.copy(
                                lastMessage = previewText,
                                lastMessageType = messageType,
                                lastMessageTime = nextTime,
                                unreadCount = if (unreadDelta != 0) {
                                    (cached.unreadCount + unreadDelta).coerceAtLeast(0)
                                } else {
                                    _uiState.value.chats.find { it.id == chatId }?.unreadCount
                                        ?: cached.unreadCount
                                }
                            )
                        )
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("ChatListViewModel", "Failed to persist last-message preview", error)
            }
        }
    }

    private fun mediaPreviewLabel(type: MessageType): String = when (type) {
        MessageType.IMAGE -> text(R.string.message_preview_image)
        MessageType.GIF -> text(R.string.message_preview_gif)
        MessageType.STICKER -> text(R.string.message_preview_sticker)
        MessageType.LOCATION -> text(R.string.message_preview_location)
        MessageType.VOICE -> text(R.string.message_preview_voice)
        MessageType.VIDEO -> text(R.string.message_preview_video)
        MessageType.FILE -> text(R.string.message_preview_file)
        MessageType.NUDGE -> text(R.string.message_preview_nudge)
        MessageType.SYSTEM -> text(R.string.message_preview_system)
        else -> text(R.string.message_preview_encrypted)
    }

    /**
     * Best-effort decrypt for inactive-chat list/notify previews.
     * Only TEXT/STICKER/LOCATION (inline plaintext body). On success the caller persists
     * plaintext so ChatDetail [localReadableMessage] skips re-decrypt (ratchet-safe).
     * Returns null on failure / non-inline types / already-plaintext.
     */
    private fun tryDecryptInlinePreview(message: Message): String? {
        if (message.type !in setOf(MessageType.TEXT, MessageType.MARKDOWN, MessageType.STICKER, MessageType.LOCATION)) {
            return null
        }
        val content = message.content
        if (content.isBlank()) return null
        val signal = app.signalProtocol
        // Already readable local body (own multi-device echo, etc.)
        if (!signal.isEncryptedEnvelope(content) &&
            !signal.isSenderKeyEnvelope(content) &&
            !ChatListPreviewPolicy.looksLikeWireEnvelope(content)
        ) {
            return content
        }
        return try {
            val isGroup = _uiState.value.chats.find { it.id == message.chatId }?.isGroup == true
            val result = if (isGroup || signal.isSenderKeyEnvelope(content)) {
                signal.decryptGroupContentEnvelope(
                    message.senderId,
                    content,
                    expectedGroupId = message.chatId
                )
            } else {
                signal.decryptContentEnvelope(message.senderId, content)
            }
            when (result) {
                is com.maodouchat.crypto.SignalProtocol.DecryptResult.Success ->
                    result.plaintext.takeIf { it.isNotBlank() }
                else -> null
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w(
                "ChatListViewModel",
                "tryDecryptInlinePreview failed for ${message.id}: ${error.message}"
            )
            null
        }
    }

    /** Recipient-facing NUDGE copy; stored body is always sender-centric from server. */
    private fun listNudgePreview(
        isOwnMessage: Boolean,
        storedContent: String,
        senderId: String,
        chatId: String,
        chatHint: Chat? = null
    ): String {
        val chat = chatHint ?: _uiState.value.chats.find { it.id == chatId }
        val senderName = chat?.participants?.firstOrNull { it.id == senderId }?.name
            ?.takeIf { it.isNotBlank() }
            ?: senderId
        val appCtx = getApplication<Application>()
        return com.maodouchat.ui.screen.chatdetail.NudgeDisplayPolicy.displayText(
            isOwnMessage = isOwnMessage,
            storedContent = storedContent,
            senderDisplayName = senderName,
            isDirectChat = chat?.isGroup != true,
            templates = com.maodouchat.ui.screen.chatdetail.NudgeDisplayPolicy.Templates(
                youNudged = { target -> appCtx.getString(R.string.chat_nudge_you_nudged, target) },
                theyNudgedYou = { sender -> appCtx.getString(R.string.chat_nudge_they_nudged_you, sender) },
                theyNudgedTarget = { sender, target ->
                    appCtx.getString(R.string.chat_nudge_they_nudged_target, sender, target)
                }
            )
        )
    }

    /**
     * Server list uses type placeholders (NUDGE → "[提醒]", TEXT → encrypted label,
     * media → Chinese e2ee labels). Prefer local Room tail + client-localized media labels.
     */
    private suspend fun enrichServerChatPreview(
        server: Chat,
        ownerUserId: String = currentUserIdStr,
    ): Chat {
        // Always localize media/revoked placeholders even when Room has no tail yet.
        val localizedMedia = when (server.lastMessageType) {
            MessageType.IMAGE,
            MessageType.GIF,
            MessageType.STICKER,
            MessageType.LOCATION,
            MessageType.VOICE,
            MessageType.VIDEO,
            MessageType.FILE -> server.copy(lastMessage = mediaPreviewLabel(server.lastMessageType))
            MessageType.NUDGE -> {
                // Server placeholder is Chinese "[提醒]"; prefer local POV or localized label.
                val localizedLabel = text(R.string.message_preview_nudge)
                if (server.lastMessage.isBlank() ||
                    server.lastMessage == "[提醒]" ||
                    server.lastMessage == localizedLabel
                ) {
                    server.copy(lastMessage = localizedLabel)
                } else {
                    server
                }
            }
            MessageType.SYSTEM -> {
                // Server placeholder is Chinese "[系统]"; keep real system body when present.
                val body = server.lastMessage.trim()
                if (body.isBlank() || body == "[系统]") {
                    server.copy(lastMessage = text(R.string.message_preview_system))
                } else {
                    server
                }
            }
            MessageType.REVOKED -> server.copy(
                lastMessage = text(R.string.chat_message_revoked_placeholder)
            )
            else -> server
        }
        if (localizedMedia.lastMessageType != MessageType.NUDGE &&
            localizedMedia.lastMessageType != MessageType.TEXT &&
            localizedMedia.lastMessageType != MessageType.MARKDOWN
        ) {
            return localizedMedia
        }
        return try {
            val recent = messageRepo.getRecentMessages(localizedMedia.id, limit = 8)
            val preview = ChatListPreviewPolicy.fromLatestMessages(
                candidatesNewestFirst = recent,
                mediaLabel = { mediaPreviewLabel(it) },
                encryptedPlaceholder = text(R.string.message_preview_encrypted),
                revokedPlaceholder = text(R.string.chat_message_revoked_placeholder),
                nudgeText = { msg ->
                    listNudgePreview(
                        isOwnMessage = msg.senderId == ownerUserId,
                        storedContent = msg.content,
                        senderId = msg.senderId,
                        chatId = localizedMedia.id,
                        chatHint = localizedMedia
                    )
                }
            )
            if (preview.text.isBlank()) return localizedMedia
            when (preview.type) {
                MessageType.NUDGE -> localizedMedia.copy(
                    lastMessage = preview.text,
                    lastMessageType = preview.type,
                    lastMessageTime = maxOf(localizedMedia.lastMessageTime, preview.timestamp)
                )
                MessageType.TEXT, MessageType.MARKDOWN -> {
                    // Only replace when local has readable plaintext (own send / decrypted).
                    val looksEncrypted = ChatListPreviewPolicy.looksLikeWireEnvelope(preview.text) ||
                        preview.text == text(R.string.message_preview_encrypted)
                    if (looksEncrypted) localizedMedia
                    else localizedMedia.copy(
                        lastMessage = preview.text.take(280),
                        lastMessageType = MessageType.TEXT,
                        lastMessageTime = maxOf(localizedMedia.lastMessageTime, preview.timestamp)
                    )
                }
                MessageType.IMAGE,
                MessageType.GIF,
                MessageType.STICKER,
                MessageType.LOCATION,
                MessageType.VOICE,
                MessageType.VIDEO,
                MessageType.FILE,
                MessageType.REVOKED -> localizedMedia.copy(
                    lastMessage = preview.text,
                    lastMessageType = preview.type,
                    lastMessageTime = maxOf(localizedMedia.lastMessageTime, preview.timestamp)
                )
                else -> localizedMedia
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            localizedMedia
        }
    }

    /**
     * Recompute list last-message from local Room after delete/revoke (and optional head edit).
     * Absolute write so empty/older tails replace a stale head without waiting for getChats.
     */
    private fun refreshChatListPreviewFromLocal(
        chatId: String,
        ownerUserId: String = currentUserIdStr,
        sessionGeneration: Long = MaodouchatApp.currentSessionGeneration(),
    ) {
        val session = OwnerSessionSnapshot(ownerUserId, sessionGeneration)
        if (chatId.isBlank() || !isOwnerSessionCurrent(session)) return
        viewModelScope.launch {
            try {
                // Fetch a few rows so a trailing SK_DIST does not wipe a real conversation head.
                val recent = messageRepo.getRecentMessages(chatId, limit = 8)
                if (!isOwnerSessionCurrent(session)) return@launch
                val preview = ChatListPreviewPolicy.fromLatestMessages(
                    candidatesNewestFirst = recent,
                    mediaLabel = { mediaPreviewLabel(it) },
                    encryptedPlaceholder = text(R.string.message_preview_encrypted),
                    revokedPlaceholder = text(R.string.chat_message_revoked_placeholder),
                    nudgeText = { msg ->
                        listNudgePreview(
                            isOwnMessage = msg.senderId == ownerUserId,
                            storedContent = msg.content,
                            senderId = msg.senderId,
                            chatId = chatId
                        )
                    }
                )
                applyChatListPreview(
                    chatId = chatId,
                    previewText = preview.text,
                    messageType = preview.type,
                    timestamp = preview.timestamp,
                    forceTimestamp = true,
                    ownerUserId = ownerUserId,
                    sessionGeneration = sessionGeneration,
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("ChatListViewModel", "Failed to refresh list preview from local", error)
            }
        }
    }
    fun clearError() { _uiState.update { it.copy(errorMessage = null) } }
    fun clearRealtimeBanner() { _uiState.update { it.copy(realtimeBanner = null) } }
    fun clearOwnerTransferRequired() { _uiState.update { it.copy(ownerTransferRequiredChatId = null) } }

    // 9.150：置顶/静音/归档/标未读改为按 chatId 现查 _uiState 最新快照取反，
    // 不再信任调用方传入的 Chat 快照（长按菜单 menuChat 可能在 WS 刷新后陈旧，反向操作会覆盖新值）
    fun togglePinned(chatId: String) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CHAT_PIN)) {
            _uiState.update { it.copy(errorMessage = text(R.string.feature_disabled_by_admin)) }
            return
        }
        val chat = _uiState.value.chats.firstOrNull { it.id == chatId } ?: return
        updateChatSettings(
            chat,
            withOptimisticSettingsClock(
                chat.copy(pinnedAt = if (chat.pinnedAt > 0) 0 else System.currentTimeMillis())
            ),
            UpdateChatSettingsRequest(pinned = chat.pinnedAt <= 0)
        )
    }

    fun toggleNotificationsMuted(chatId: String) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CHAT_MUTE)) {
            _uiState.update { it.copy(errorMessage = text(R.string.feature_disabled_by_admin)) }
            return
        }
        val chat = _uiState.value.chats.firstOrNull { it.id == chatId } ?: return
        updateChatSettings(
            chat,
            withOptimisticSettingsClock(chat.copy(notificationsMuted = !chat.notificationsMuted)),
            UpdateChatSettingsRequest(notificationsMuted = !chat.notificationsMuted)
        )
    }

    fun toggleArchived(chatId: String) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CHAT_ARCHIVE)) {
            _uiState.update { it.copy(errorMessage = text(R.string.feature_disabled_by_admin)) }
            return
        }
        val chat = _uiState.value.chats.firstOrNull { it.id == chatId } ?: return
        updateChatSettings(
            chat,
            withOptimisticSettingsClock(
                chat.copy(archived = !chat.archived, pinnedAt = if (!chat.archived) 0 else chat.pinnedAt)
            ),
            UpdateChatSettingsRequest(archived = !chat.archived, pinned = if (!chat.archived) false else null)
        )
    }

    fun toggleMarkedUnread(chatId: String) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.MARKED_UNREAD)) {
            _uiState.update { it.copy(errorMessage = text(R.string.feature_disabled_by_admin)) }
            return
        }
        val chat = _uiState.value.chats.firstOrNull { it.id == chatId } ?: return
        updateChatSettings(
            chat,
            withOptimisticSettingsClock(chat.copy(markedUnread = !chat.markedUnread)),
            UpdateChatSettingsRequest(markedUnread = !chat.markedUnread)
        )
    }

    /**
     * 未读文件夹「全部已读」：对所有未读会话逐个调用 mark-read。
     * 服务端会向本账号广播 CHAT_MARKED_READ，本机既有处理器会把角标归零；
     * 这里再做乐观清零 + 落库，保证列表即时收敛且进程死亡不复活角标。
     */
    fun markAllUnreadChatsRead() {
        val ownerUserId = currentUserIdStr
        if (ownerUserId.isBlank()) return
        val unreadChats = _uiState.value.chats.filter {
            !it.archived && com.maodouchat.util.ChatFolderPolicy.isUnreadChat(it.unreadCount, it.markedUnread)
        }
        if (unreadChats.isEmpty()) return
        val session = ownerSession(ownerUserId)
        _uiState.update { state ->
            state.copy(chats = state.chats.map { chat ->
                if (unreadChats.any { it.id == chat.id }) chat.copy(unreadCount = 0, markedUnread = false) else chat
            })
        }
        viewModelScope.launch {
            val chatIds = unreadChats.map { it.id }
            unreadChats.forEach { chat ->
                if (!isOwnerSessionCurrent(session)) return@launch
                val liveToken = tokenManager.getToken().orEmpty()
                if (liveToken.isBlank()) return@launch
                try {
                    withOwnerRoomWrite(session) {
                        val cached = chatRepo.getChatById(chat.id)
                        val zeroed = cached?.copy(unreadCount = 0, markedUnread = false)
                            ?: chat.copy(unreadCount = 0, markedUnread = false)
                        if (cached == null || cached.unreadCount != 0 || cached.markedUnread) {
                            chatRepo.cacheChats(listOf(zeroed))
                        }
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // 本地已读缓存失败不阻塞后续清理
                }
                // 已读后清理该会话的 tray 通知（与聊天页进入后的行为一致）
                runCatching { com.maodouchat.util.AppNotifier.cancelMessage(getApplication(), chat.id) }
            }
            // 0.89：批量已读一次请求（此前每未读会话一次 markAllAsRead，N 次请求 → 1 次）
            if (chatIds.isNotEmpty() && isOwnerSessionCurrent(session)) {
                val liveToken = tokenManager.getToken().orEmpty()
                if (liveToken.isNotBlank()) {
                    // 失败静默：下次 getChats 服务端数据会收敛角标
                    try {
                        ApiService.batchMarkRead(liveToken, chatIds)
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // 静默：下次同步会收敛角标
                    }
                }
            }
        }
    }

    /** 1.368：进入会话列表多选模式（长按任一会话） */
    fun enterSelectionMode() {
        _uiState.update { it.copy(selectionMode = true) }
    }

    /** 1.368：退出多选模式并清空勾选 */
    fun exitSelectionMode() {
        _uiState.update { it.copy(selectionMode = false, selectedChatIds = emptySet()) }
    }

    /** 1.368：勾选/取消勾选一个会话（最后一个取消时自动退出多选） */
    fun toggleSelectChat(chatId: String) {
        _uiState.update { state ->
            val selected = state.selectedChatIds
            val next = if (chatId in selected) selected - chatId else selected + chatId
            if (next.isEmpty()) {
                state.copy(selectionMode = false, selectedChatIds = emptySet())
            } else {
                state.copy(selectedChatIds = next)
            }
        }
    }

    /** 1.368：批量置顶/取消置顶选中会话（复用单会话置顶，含 RuntimeFlags 门控） */
    fun batchTogglePinSelected() {
        val selected = _uiState.value.selectedChatIds
        if (selected.isEmpty()) return
        selected.forEach(::togglePinned)
    }

    /** 1.368：批量标记已读选中会话（未读/标未读才调用，服务端一次 batchMarkRead） */
    fun batchMarkReadSelected() {
        val selected = _uiState.value.selectedChatIds
        if (selected.isEmpty()) return
        val ownerUserId = currentUserIdStr
        if (ownerUserId.isBlank()) return
        val chatsById = _uiState.value.chats.associateBy { it.id }
        val toRead = selected.mapNotNull { chatsById[it] }
            .filter { com.maodouchat.util.ChatFolderPolicy.isUnreadChat(it.unreadCount, it.markedUnread) }
        if (toRead.isEmpty()) return
        val session = ownerSession(ownerUserId)
        val targetIds = toRead.map { it.id }
        _uiState.update { state ->
            state.copy(chats = state.chats.map { chat ->
                if (chat.id in targetIds) chat.copy(unreadCount = 0, markedUnread = false) else chat
            })
        }
        viewModelScope.launch {
            if (!isOwnerSessionCurrent(session)) return@launch
            toRead.forEach { chat ->
                if (!isOwnerSessionCurrent(session)) return@launch
                val liveToken = tokenManager.getToken().orEmpty()
                if (liveToken.isBlank()) return@launch
                try {
                    withOwnerRoomWrite(session) {
                        val cached = chatRepo.getChatById(chat.id)
                        val zeroed = cached?.copy(unreadCount = 0, markedUnread = false)
                            ?: chat.copy(unreadCount = 0, markedUnread = false)
                        if (cached == null || cached.unreadCount != 0 || cached.markedUnread) {
                            chatRepo.cacheChats(listOf(zeroed))
                        }
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // 本地已读缓存失败不阻塞后续清理
                }
                runCatching { com.maodouchat.util.AppNotifier.cancelMessage(getApplication(), chat.id) }
            }
            if (targetIds.isNotEmpty() && isOwnerSessionCurrent(session)) {
                val liveToken = tokenManager.getToken().orEmpty()
                if (liveToken.isNotBlank()) {
                    try {
                        ApiService.batchMarkRead(liveToken, targetIds)
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // 静默：下次同步会收敛角标
                    }
                }
            }
        }
    }

    /** 1.368：批量删除选中会话（逐个走 deleteChat，结束后退出多选） */
    fun batchDeleteSelected() {
        val selected = _uiState.value.selectedChatIds
        if (selected.isEmpty()) return
        selected.forEach { deleteChat(it) }
        exitSelectionMode()
    }

    /** Bump settingsUpdatedAt so getChats merge keeps optimistic pin/mute/archive. */
    private fun withOptimisticSettingsClock(chat: Chat): Chat {
        val now = System.currentTimeMillis()
        return chat.copy(settingsUpdatedAt = maxOf(now, chat.settingsUpdatedAt + 1L))
    }

    /**
     * Open-chat path: force markedUnread=false on server.
     * Unlike [toggleMarkedUnread], REST failure must not restore markedUnread=true
     * (user already entered the conversation and UI is zeroed).
     */
    private fun clearMarkedUnreadAfterOpen(chat: Chat) {
        if (!chat.markedUnread) return
        val clearOwnerUserId = currentUserIdStr
        if (clearOwnerUserId.isBlank()) return
        val optimistic = withOptimisticSettingsClock(
            chat.copy(unreadCount = 0, markedUnread = false)
        )
        viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = clearOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) return@launch
            try {
                chatRepo.cacheChats(listOf(optimistic))
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w(
                    "ChatListViewModel",
                    "clearMarkedUnread cache failed for ${chat.id}",
                    error
                )
            }
            val token = tokenManager.getToken().orEmpty()
            if (token.isBlank() || clearOwnerUserId.isBlank()) return@launch
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = clearOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                ApiService.updateChatSettings(
                    liveToken,
                    chat.id,
                    UpdateChatSettingsRequest(markedUnread = false)
                ).fold(
                    onSuccess = { settings ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = clearOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        val confirmed = optimistic.copy(
                            pinnedAt = settings.pinnedAt,
                            notificationsMuted = settings.notificationsMuted,
                            archived = settings.archived,
                            markedUnread = false,
                            settingsUpdatedAt = settings.updatedAt
                        )
                        chatRepo.cacheChats(listOf(confirmed))
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = clearOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) return@fold
                        _uiState.update { state ->
                            state.copy(
                                chats = state.chats.map {
                                    if (it.id == chat.id) {
                                        it.copy(
                                            markedUnread = false,
                                            unreadCount = 0,
                                            pinnedAt = confirmed.pinnedAt,
                                            notificationsMuted = confirmed.notificationsMuted,
                                            archived = confirmed.archived,
                                            settingsUpdatedAt = confirmed.settingsUpdatedAt
                                        )
                                    } else it
                                }
                            )
                        }
                    },
                    onFailure = { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = clearOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        // Keep local cleared; next open/getChats can retry. Do not resurrect badge.
                        android.util.Log.w(
                            "ChatListViewModel",
                            "clearMarkedUnread REST failed for ${chat.id}: ${error.message}"
                        )
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            }
        }
    }

    private fun updateChatSettings(chat: Chat, optimistic: Chat, request: UpdateChatSettingsRequest) {
        // 入口级重入保护：同帧连点同一聊天设置开关时，第二次直接返回，避免发出方向相反的两笔请求
        if (!settingsInFlight.add(chat.id)) return
        val settingsOwnerUserId = currentUserIdStr
        val token = tokenManager.getToken().orEmpty()
        if (token.isBlank() || settingsOwnerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
            return
        }
        _uiState.update { state -> state.copy(chats = state.chats.map { if (it.id == chat.id) optimistic else it }) }
        // Mute on: drop existing tray + mark center message rows read immediately (don't wait for REST).
        if (optimistic.notificationsMuted && !chat.notificationsMuted) {
            try {
                com.maodouchat.util.AppNotifier.cancelMessage(app, chat.id)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
            }
            try {
                notificationRepo.markChatMessagesRead(chat.id)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
        viewModelScope.launch {
            try {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = settingsOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) return@launch
            // Persist optimistic settings immediately so FCM mute / process death honor the toggle
            // before updateChatSettings REST returns (rollback Room on confirmed failure).
            try {
                chatRepo.cacheChats(listOf(optimistic))
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("ChatListViewModel", "optimistic cacheChats failed for ${chat.id}", error)
            }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = settingsOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                ApiService.updateChatSettings(liveToken, chat.id, request).fold(
                    onSuccess = { settings ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = settingsOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        val confirmed = optimistic.copy(
                            pinnedAt = settings.pinnedAt,
                            notificationsMuted = settings.notificationsMuted,
                            archived = settings.archived,
                            markedUnread = settings.markedUnread,
                            settingsUpdatedAt = settings.updatedAt
                        )
                        chatRepo.cacheChats(listOf(confirmed))
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = settingsOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        _uiState.update { state -> state.copy(chats = state.chats.map { if (it.id == chat.id) confirmed else it }) }
                    },
                    onFailure = { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = settingsOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        try {
                            chatRepo.cacheChats(listOf(chat))
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (_: Exception) {
                        }
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = settingsOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        _uiState.update { state -> state.copy(chats = state.chats.map { if (it.id == chat.id) chat else it }, errorMessage = text(R.string.chat_settings_sync_failed)) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            }
            } finally {
                settingsInFlight.remove(chat.id)
            }
        }
    }

    /**
     * 删除聊天（同时退出服务端聊天 + 清理本地缓存）
     * - 调用服务端 DELETE /api/chats/{chatId} 退出聊天
     * - 删除本地缓存的消息和聊天记录
     * - 从 UI 列表中移除
     */
    fun deleteChat(chatId: String) {
        val token = tokenManager.getToken().orEmpty()
        val deleteOwnerUserId = currentUserIdStr
        if (token.isBlank() || deleteOwnerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
            return
        }
        if (_uiState.value.deletingChatIds.contains(chatId)) return
        _uiState.update { it.copy(deletingChatIds = it.deletingChatIds + chatId) }
        viewModelScope.launch {
            try {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = deleteOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) return@launch
            // 先从 UI 移除，给用户即时反馈
            val previous = _uiState.value.chats.find { it.id == chatId }
            _uiState.update { state ->
                state.copy(chats = state.chats.filterNot { it.id == chatId })
            }
            var leaveConfirmed = false
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = deleteOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                val liveToken = tokenManager.getToken() ?: token
                // 服务端退出聊天（失败时回滚 UI 并提示）
                val result = ApiService.deleteChat(liveToken, chatId)
                val resultError = result.exceptionOrNull()
                if (resultError is kotlinx.coroutines.CancellationException) throw resultError
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = deleteOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) return@launch
                if (result.isFailure) {
                    val error = resultError
                    android.util.Log.w("ChatListViewModel", "deleteChat failed: " + (error?.message ?: "unknown"))
                    deletedChatIds.remove(chatId)
                    if (previous != null) {
                        // 回滚去重：若 WS/刷新已把会话加回列表则不再重复插入。
                        // 8.49 修复：按置顶/活跃度重排插入——此前无条件插到第 0 位，
                        // 会把置顶会话压下去、破坏列表排序直到下次 loadChats
                        _uiState.update { st ->
                            if (st.chats.none { it.id == chatId }) st.copy(chats = restoreChatSorted(st.chats, previous)) else st
                        }
                    }
                    if (requiresGroupOwnershipTransfer(error)) {
                        _uiState.update { it.copy(ownerTransferRequiredChatId = chatId, errorMessage = null) }
                    } else {
                        _uiState.update { it.copy(errorMessage = text(R.string.chat_leave_failed)) }
                    }
                    return@launch
                }
                leaveConfirmed = true
                // leave 已成功：本地清理必须跑完，避免半清草稿/密钥/附件
                deletedChatIds.add(chatId)
                // 8.53：删除会话后清理该会话全部待触发「稍后提醒」——否则到点通知 deeplink 落空
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val uid = tokenManager.getUserId()?.takeIf { it.isNotBlank() } ?: return@withContext
                        val reminders = com.maodouchat.util.MessageReminderStore.list(getApplication(), uid)
                        reminders.filter { it.chatId == chatId }.forEach { r ->
                            com.maodouchat.util.MessageReminderScheduler.cancel(getApplication(), r.id)
                            com.maodouchat.util.MessageReminderStore.remove(getApplication(), r.id, uid)
                        }
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // 提醒清理失败不阻塞会话删除主流程
                    }
                }
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = deleteOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        cleanupLocalChat(chatId, deleteOwnerUserId)
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                // 取消且 leave 未确认：恢复列表项；本地缓存未 cleanup
                deletedChatIds.remove(chatId)
                if (!leaveConfirmed && previous != null &&
                    com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = deleteOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    ) &&
                    _uiState.value.chats.none { it.id == chatId }
                ) {
                    _uiState.update { st -> st.copy(chats = restoreChatSorted(st.chats, previous)) }
                }
                throw error
            }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                // 8.55：leave 已成功后 cleanupLocalChat 若抛非取消异常，记录并继续——
                // 不得逃逸到协程未捕获（半清理一致性问题已部分由 NonCancellable 保证）
                android.util.Log.w("ChatListViewModel", "deleteChat cleanup failed", error)
            }
            finally {
                _uiState.update { it.copy(deletingChatIds = it.deletingChatIds - chatId) }
            }
        }
    }

    /** 8.49：删除失败回滚时按置顶/最近活跃把会话插回列表（filteredChats 主排序的简化版，最终由下次 loadChats 收敛）。 */
    private fun restoreChatSorted(existing: List<Chat>, chat: Chat): List<Chat> =
        (existing + chat).sortedWith(
            compareByDescending<Chat> { it.pinnedAt > 0 }
                .thenByDescending { it.pinnedAt }
                .thenByDescending { it.lastMessageTime }
        )

    private suspend fun cleanupLocalChat(chatId: String, ownerUserId: String) {
        // 先记录消息 ID；删除 SQLCipher 行后将无法定位对应的解密媒体缓存。
        val cachedMessageIds = messageRepo.getMessageIdsByChatId(chatId)
        com.maodouchat.attachment.AttachmentTransferCoordinator.cancelForChat(app, chatId)
        // Drop pending timed sends so leave/delete cannot fire into a gone chat.
        try {
            val removed = com.maodouchat.util.ScheduledMessageStore.clearForChat(app, chatId)
            removed.forEach { com.maodouchat.util.ScheduledMessageScheduler.cancel(app, it) }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
        }
        aiTaskRepo.deleteByChatId(chatId)
        aiOperationRepo.deleteByChatId(ownerUserId, chatId)
        aiSummaryRepo.deleteByChatId(chatId)
        app.database.chatDraftDao().deleteForChat(ownerUserId, chatId)
        app.database.chatLockDao().remove(chatId)
        com.maodouchat.security.ChatLockSession.clear(chatId)
        app.database.secretChatDao().remove(chatId)
        com.maodouchat.security.SecretChatSession.markSurfaceInactive(chatId, getApplication())
        app.database.senderKeyRetryDao().delete(ownerUserId, chatId)
        app.signalProtocol.invalidateGroupSenderKey(chatId)
        try {
            app.database.attachmentTransferDao().clearWireContentForChat(
                chatId,
                ownerUserId = ownerUserId,
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w("ChatListViewModel", "clearWireContentForChat failed for $chatId", error)
        }
        cachedMessageIds.forEach { messageId ->
            com.maodouchat.util.MediaCache.deleteCachedMediaForMessage(app, messageId)
        }
        // Keyword index must not keep orphan docs after leave/delete.
        try {
            app.database.messageSearchDao().deleteChatIndex(chatId)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w("ChatListViewModel", "deleteChatIndex failed for $chatId", error)
        }
        tokenManager.clearChatCursors(chatId)
        // In-app notification center + system tray for this chat (messages + AI task reminders).
        try {
            notificationRepo.removeChatItems(chatId)
            com.maodouchat.util.AppNotifier.cancelMessage(app, chatId)
            com.maodouchat.util.AppNotifier.cancelAiTaskRemindersForChat(app, chatId)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w("ChatListViewModel", "notification cleanup failed for $chatId", error)
        }
        messageRepo.deleteMessagesByChatId(chatId)
        chatRepo.deleteChat(chatId)
    }

    /** 1.142：会话列表长按菜单「清除草稿」（本地，不打开会话）。 */
    fun clearChatDraft(chatId: String) {
        if (chatId.isBlank()) return
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (ownerUserId.isBlank()) return
        viewModelScope.launch {
            try {
                app.database.chatDraftDao().deleteForChat(ownerUserId, chatId)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    /**
     * When remote delete/revoke removes the message that last populated a
     * notification, drop center row + tray so previews cannot outlive content.
     */
    private fun dismissNotificationIfReferencesMessage(
        chatId: String,
        messageId: String,
        session: OwnerSessionSnapshot,
    ) {
        if (messageId.isBlank() || !isOwnerSessionCurrent(session)) return
        try {
            val removed = notificationRepo.removeMessageReferences(messageId)
            if (removed && chatId.isNotBlank() && isOwnerSessionCurrent(session)) {
                com.maodouchat.util.AppNotifier.cancelMessage(app, chatId)
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w(
                "ChatListViewModel",
                "dismissNotificationIfReferencesMessage failed for $messageId",
                error
            )
        }
    }

    /** EDIT of the message currently shown in center → refresh preview text. */
    private suspend fun refreshNotificationPreviewIfReferencesMessage(
        chatId: String,
        messageId: String,
        preview: String,
        session: OwnerSessionSnapshot,
    ) {
        if (messageId.isBlank() || preview.isBlank() || !isOwnerSessionCurrent(session)) return
        try {
            notificationRepo.updateMessagePreview(messageId, resolveNotificationPreview(chatId, preview))
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w(
                "ChatListViewModel",
                "refreshNotificationPreviewIfReferencesMessage failed for $messageId",
                error
            )
        }
    }

    /**
     * Mirror AppNotifier write-time masking: a PIN-locked or secret (when blocked) chat must
     * never leak message bodies into the in-app notification center, even when an edit refresh
     * rewrites the head preview (otherwise the lock/secret gate is bypassed via the center).
     */
    private suspend fun resolveNotificationPreview(chatId: String, preview: String): String {
        if (chatId.isBlank()) return preview
        return try {
            val locked = RuntimeFlags.isEnabled(app, RuntimeFlags.CHAT_LOCK) &&
                app.database.chatLockDao().get(chatId) != null
            val secret = app.database.secretChatDao().isSecret(chatId) &&
                RuntimeFlags.isEnabled(app, RuntimeFlags.SECRET_NOTIF_PREVIEW_BLOCK)
            when {
                locked -> app.getString(R.string.chat_lock_list_preview)
                secret -> app.getString(R.string.secret_chat_notification_preview)
                else -> preview
            }
        } catch (_: Exception) {
            preview
        }
    }

    /**
     * Apply buffered MESSAGE_REACTION_UPDATED once the message row exists.
     * Must run on a coroutine that can hit Room (caller already on IO when needed).
     */
    private suspend fun applyPendingReactionsIfAny(
        chatId: String,
        messageId: String,
        session: OwnerSessionSnapshot,
    ) {
        if (chatId.isBlank() || messageId.isBlank() || !isOwnerSessionCurrent(session)) return
        val result = PendingReactionPolicy.takeForMessage(
            pending = pendingReactions,
            chatId = chatId,
            messageId = messageId,
            nowMs = System.currentTimeMillis()
        )
        pendingReactions = result.pending
        withOwnerRoomWrite(session) {
            for (entry in result.ready) {
                try {
                    val existing = messageRepo.getMessageById(entry.messageId) ?: continue
                    if (existing.chatId != entry.chatId) continue
                    if (existing.type == MessageType.REVOKED) continue
                    messageRepo.updateMessageReactions(entry.messageId, entry.reactions)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.util.Log.w(
                        "ChatListViewModel",
                        "Failed to apply pending reaction for ${entry.messageId}",
                        error
                    )
                }
            }
        }
    }

    private fun observeRealtime() {
        val realtimeOwnerUserId = currentUserIdStr
        val realtimeSession = ownerSession(realtimeOwnerUserId)
        viewModelScope.launch {
            // 监听 ChatDetailViewModel 发出的已读事件，实时归零未读数（UI + Room）
            com.maodouchat.MaodouchatApp.chatReadEvents.collect { event ->
                if (event.sessionGeneration != realtimeSession.sessionGeneration ||
                    !isOwnerSessionCurrent(realtimeSession)
                ) {
                    return@collect
                }
                val readChatId = event.chatId
                if (readChatId.isBlank()) return@collect
                val manuallyUnread = _uiState.value.chats.firstOrNull { it.id == readChatId && it.markedUnread }
                _uiState.update { state ->
                    state.copy(chats = state.chats.map { chat ->
                        if (chat.id == readChatId) chat.copy(unreadCount = 0, markedUnread = false) else chat
                    })
                }
                // Persist zero unread so process death does not resurrect badge before next getChats.
                viewModelScope.launch {
                    try {
                        withOwnerRoomWrite(realtimeSession) {
                            val cached = chatRepo.getChatById(readChatId) ?: return@withOwnerRoomWrite
                            if (cached.unreadCount != 0 || cached.markedUnread) {
                                chatRepo.cacheChats(
                                    listOf(cached.copy(unreadCount = 0, markedUnread = false))
                                )
                            }
                        }
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        android.util.Log.w("ChatListViewModel", "Failed to persist chat read", error)
                    }
                }
                // Open-chat must clear server markedUnread without toggle rollback to true.
                manuallyUnread?.let { clearMarkedUnreadAfterOpen(it) }
            }
        }
        viewModelScope.launch {
            // 发送/附件 finalize 用单调时间；delete/revoke 本地重算用 forceTimestamp 绝对写
            com.maodouchat.MaodouchatApp.chatMessageSentEvents.collect { event ->
                if (event.sessionGeneration != realtimeSession.sessionGeneration ||
                    !isOwnerSessionCurrent(realtimeSession)
                ) {
                    return@collect
                }
                if (event.forceFromLocal) {
                    refreshChatListPreviewFromLocal(
                        event.chatId,
                        realtimeOwnerUserId,
                        realtimeSession.sessionGeneration,
                    )
                    return@collect
                }
                applyChatListPreview(
                    chatId = event.chatId,
                    previewText = event.previewText,
                    messageType = MessageType.fromWire(event.messageTypeWire),
                    timestamp = System.currentTimeMillis(),
                    ownerUserId = realtimeOwnerUserId,
                    sessionGeneration = realtimeSession.sessionGeneration,
                )
            }
        }
        viewModelScope.launch {
            WebSocketClient.events.collect { event ->
                // Logout disconnects WS but buffered events may still drain; drop if session gone.
                if (!isOwnerSessionCurrent(realtimeSession)) return@collect
                val liveUserId = realtimeOwnerUserId
                when (event) {
                    is WebSocketEvent.AdminBroadcast -> {
                        val title = event.title.ifBlank { text(R.string.notification_admin_broadcast_default_title) }
                        val body = event.text.trim()
                        if (body.isNotBlank()) {
                            try {
                                app.notificationCenter.add(
                                    com.maodouchat.data.repository.NotificationCenterItem(
                                        id = "admin_bc_${event.ts}_${body.hashCode()}",
                                        type = "SECURITY",
                                        mergeKey = "admin_broadcast_${event.ts}",
                                        title = title,
                                        subtitle = text(R.string.notification_admin_broadcast_sender),
                                        preview = body.take(200),
                                        deeplink = null,
                                        extra = mapOf("kind" to "admin_broadcast")
                                    ),
                                    expectedUserId = liveUserId,
                                )
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                android.util.Log.w("ChatListViewModel", "Failed to store admin broadcast", error)
                            }
                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                    expectedUserId = liveUserId,
                                    liveToken = tokenManager.getToken(),
                                    liveUserId = tokenManager.getUserId(),
                                )
                            ) return@collect
                            _uiState.update {
                                it.copy(realtimeBanner = "$title: $body".take(240))
                            }
                        }
                    }
                    is WebSocketEvent.MessageReceived -> {
                        // SK_DIST is crypto control traffic — never bump unread / overwrite list preview / notify.
                        val controlOnly = event.message.type in setOf(
                            MessageType.SK_DIST,
                            MessageType.REVOKED // revoke arrives via MessageRevoked / mutation, not as content head
                        )
                        if (!controlOnly) {
                            // 收到新消息时，更新预览、递增未读数、置顶聊天（UI + Room 同步）
                            try {
                                val targetId = event.message.chatId
                                val isOwnMessage = event.message.senderId == liveUserId
                                // Active chat: ChatDetail owns decrypt (avoid double-ratchet). List may
                                // briefly show ciphertext placeholder until detail emits plaintext.
                                // Inactive chat: best-effort decrypt TEXT/STICKER/LOCATION so list/notify
                                // show readable tail and Room holds plaintext for open-chat skip path.
                                val isActiveChat = com.maodouchat.MaodouchatApp.activeChatId == targetId
                                val encryptedPlaceholder = text(R.string.message_preview_encrypted)
                                // Same-message local echo only: Room already has plaintext for this id
                                // (emitMessageSent path). Do not key on chat-list lastMessage — that is
                                // the previous head and would freeze multi-device new sends on old tail.
                                val listReceiveOwnerUserId = liveUserId
                                val existingSameMessage = withContext(Dispatchers.IO) {
                                    messageRepo.getMessageById(event.message.id)
                                }
                                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                        expectedUserId = listReceiveOwnerUserId,
                                        liveToken = tokenManager.getToken(),
                                        liveUserId = tokenManager.getUserId(),
                                    )
                                ) return@collect
                                val sameMessageReadable = existingSameMessage?.content?.takeIf { body ->
                                    body.isNotBlank() &&
                                        !ChatListPreviewPolicy.looksLikeWireEnvelope(body) &&
                                        !app.signalProtocol.isEncryptedEnvelope(body) &&
                                        !app.signalProtocol.isSenderKeyEnvelope(body)
                                }
                                if (ChatListPreviewPolicy.shouldKeepExistingOwnPreview(
                                        isOwnMessage = isOwnMessage,
                                        messageType = event.message.type,
                                        existingSameMessageContent = sameMessageReadable,
                                        encryptedPlaceholder = encryptedPlaceholder
                                    ) && sameMessageReadable != null
                                ) {
                                    val listPreview = _uiState.value.chats.find { it.id == targetId }?.lastMessage
                                    val previewText = ChatListPreviewPolicy.ownEchoListPreview(
                                        messageType = event.message.type,
                                        sameMessagePlainOrLabel = sameMessageReadable,
                                        existingListPreview = listPreview,
                                        mediaLabel = ::mediaPreviewLabel
                                    )
                                    applyChatListPreview(
                                        chatId = targetId,
                                        previewText = previewText,
                                        messageType = event.message.type,
                                        timestamp = event.message.timestamp,
                                        unreadDelta = 0,
                                        ownerUserId = listReceiveOwnerUserId,
                                        sessionGeneration = realtimeSession.sessionGeneration,
                                    )
                                    // Own echo already in Room — still drain any reaction that raced ahead.
                                    withContext(Dispatchers.IO) {
                                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                                expectedUserId = listReceiveOwnerUserId,
                                                liveToken = tokenManager.getToken(),
                                                liveUserId = tokenManager.getUserId(),
                                            )
                                        ) return@withContext
                                        applyPendingReactionsIfAny(
                                            chatId = targetId,
                                            messageId = event.message.id,
                                            session = realtimeSession,
                                        )
                                    }
                                    // Skip decrypt / notify for this event only (not the whole collector).
                                    return@collect
                                }
                                val decryptedPlain: String? = if (!isActiveChat) {
                                    withContext(Dispatchers.IO) {
                                        tryDecryptInlinePreview(event.message)
                                    }
                                } else null
                                // Decrypt / Room read can outlive logout; drop before list/notify side effects.
                                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                        expectedUserId = listReceiveOwnerUserId,
                                        liveToken = tokenManager.getToken(),
                                        liveUserId = tokenManager.getUserId(),
                                    )
                                ) {
                                    return@collect
                                }
                                val previewText = when (event.message.type) {
                                    MessageType.IMAGE -> text(R.string.message_preview_image)
                                    MessageType.GIF -> text(R.string.message_preview_gif)
                                    MessageType.STICKER -> text(R.string.message_preview_sticker)
                                    MessageType.LOCATION -> text(R.string.message_preview_location)
                                    MessageType.VOICE -> text(R.string.message_preview_voice)
                                    MessageType.VIDEO -> text(R.string.message_preview_video)
                                    MessageType.FILE -> text(R.string.message_preview_file)
                                    MessageType.NUDGE -> listNudgePreview(
                                        isOwnMessage = isOwnMessage,
                                        storedContent = event.message.content,
                                        senderId = event.message.senderId,
                                        chatId = targetId
                                    )
                                    MessageType.SYSTEM -> event.message.content
                                    MessageType.TEXT, MessageType.MARKDOWN -> ChatListPreviewPolicy.textPreviewFromPlainOrEncrypted(
                                        decryptedPlain = decryptedPlain,
                                        encryptedPlaceholder = encryptedPlaceholder
                                    )
                                    else -> encryptedPlaceholder
                                }
                                // 1.66：群聊实时收消息预览带发送者名前缀（微信式「张三: 内容」；SYSTEM/NUDGE 例外）
                                val targetChatForPreview = _uiState.value.chats.find { it.id == targetId }
                                val senderForPreview = if (event.message.type in setOf(MessageType.SYSTEM, MessageType.NUDGE)) {
                                    null
                                } else {
                                    targetChatForPreview
                                        ?.takeIf { it.isGroup }
                                        ?.participants
                                        ?.firstOrNull { it.id == event.message.senderId }
                                        ?.displayName
                                }
                                val senderPrefixed = if (!senderForPreview.isNullOrBlank()) {
                                    senderForPreview + ": " + previewText
                                } else previewText
                                // 1.30：会话列表预览「[有人@我]」前缀（与通知强调一致，微信式；仅实时收消息路径）
                                val mentionListHighlight = runCatching {
                                    val contentForMeta = decryptedPlain ?: event.message.content
                                    val mentionIds = event.message.copy(content = contentForMeta).parsedMeta().mentions
                                    com.maodouchat.ui.screen.chatdetail.MentionPolicy.shouldHighlightMention(
                                        mentionIds = mentionIds,
                                        currentUserId = listReceiveOwnerUserId,
                                        notificationsMuted = false,
                                    )
                                }.getOrDefault(false)
                                val listPreviewText = if (mentionListHighlight) {
                                    text(R.string.chat_list_mention_prefix) + senderPrefixed
                                } else senderPrefixed
                                // 修复竞态：如果用户正在查看该聊天，不递增未读数（ChatDetailViewModel 会实时标记已读）
                                // 去重：WS NEW_MESSAGE 为 at-least-once，重连时服务端可能重发已落库的消息；
                                // 若该 id 本次事件前已存在于 Room（existingSameMessage != null），不再重复累未读，避免重连风暴导致未读角标翻倍。
                                val unreadDelta = if (isOwnMessage || isActiveChat || existingSameMessage != null) 0 else 1
                                applyChatListPreview(
                                    chatId = targetId,
                                    previewText = listPreviewText,
                                    messageType = event.message.type,
                                    timestamp = event.message.timestamp,
                                    unreadDelta = unreadDelta,
                                    ownerUserId = listReceiveOwnerUserId,
                                    sessionGeneration = realtimeSession.sessionGeneration,
                                )
                                // Persist before UI/notify so open-chat localReadableMessage
                                // sees plaintext and never double-decrypts the same ratchet step.
                                // NUDGE is plaintext control-ish UX body (not E2EE wire) — still Room+index
                                // so list LIKE / global search hit while chat was never opened.
                                val shouldPersistInactive = !isActiveChat && when (event.message.type) {
                                    MessageType.TEXT, MessageType.MARKDOWN, MessageType.STICKER, MessageType.LOCATION ->
                                        decryptedPlain != null
                                    MessageType.NUDGE ->
                                        event.message.content.isNotBlank()
                                    else -> false
                                }
                                if (shouldPersistInactive) {
                                    withContext(Dispatchers.IO) {
                                        withOwnerRoomWrite(realtimeSession) {
                                            val existing = messageRepo.getMessageById(event.message.id)
                                            val alreadyPlain = existing != null &&
                                                existing.content.isNotBlank() &&
                                                !ChatListPreviewPolicy.looksLikeWireEnvelope(existing.content) &&
                                                !app.signalProtocol.isEncryptedEnvelope(existing.content) &&
                                                !app.signalProtocol.isSenderKeyEnvelope(existing.content)
                                            val plainMessage = when (event.message.type) {
                                                MessageType.NUDGE -> event.message
                                                else -> {
                                                    // 8.49 防御：解密失败则不持久化明文（此前依赖 18 行前的
                                                    // shouldPersistInactive 守卫间接保证 decryptedPlain 非空）
                                                    val plain = decryptedPlain ?: return@withOwnerRoomWrite
                                                    event.message.copy(content = plain)
                                                }
                                            }
                                            if (!alreadyPlain) {
                                                messageRepo.insertMessage(plainMessage)
                                            }
                                            applyPendingReactionsIfAny(
                                                chatId = targetId,
                                                messageId = event.message.id,
                                                session = realtimeSession,
                                            )
                                            try {
                                                com.maodouchat.data.repository.MessageSearchRepository(app.database)
                                                    .indexMessage(plainMessage)
                                            } catch (error: kotlinx.coroutines.CancellationException) {
                                                throw error
                                            } catch (error: Exception) {
                                                android.util.Log.w(
                                                    "ChatListViewModel",
                                                    "indexMessage after list decrypt failed",
                                                    error
                                                )
                                            }
                                        }
                                    }
                                } else if (!isActiveChat) {
                                    // Media / undecrypted rows may already exist from history; drain buffer.
                                    withContext(Dispatchers.IO) {
                                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                                expectedUserId = listReceiveOwnerUserId,
                                                liveToken = tokenManager.getToken(),
                                                liveUserId = tokenManager.getUserId(),
                                            )
                                        ) {
                                            return@withContext
                                        }
                                        val existing = messageRepo.getMessageById(event.message.id)
                                        if (existing != null && existing.chatId == targetId) {
                                            applyPendingReactionsIfAny(
                                                chatId = targetId,
                                                messageId = event.message.id,
                                                session = realtimeSession,
                                            )
                                        }
                                    }
                                }
                                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                        expectedUserId = listReceiveOwnerUserId,
                                        liveToken = tokenManager.getToken(),
                                        liveUserId = tokenManager.getUserId(),
                                    )
                                ) {
                                    return@collect
                                }
                                // 修复 Bug #19：收到不在列表中的聊天的消息时，刷新聊天列表
                                // 但若该会话已被本会话删除（退出服务端），不触发重载，避免删除竞态下被 WS 推回（角标鬼影）
                                if (_uiState.value.chats.none { it.id == targetId } && !deletedChatIds.contains(targetId)) {
                                    requestLoadChats(ChatListReloadPolicy.Trigger.UNKNOWN_CHAT_MESSAGE)
                                }
                                // 系统通知：仅当不是当前活跃聊天且不是自己发时
                                // 注意：静音分支必须用局部 continue，不能 return@collect（否则整个 events collector 永久退出）
                                // 去重：同一条消息因重连重发时（existingSameMessage != null）不再重复弹通知
                                if (!isOwnMessage && !isActiveChat && existingSameMessage == null) {
                                    val target = _uiState.value.chats.find { it.id == targetId }
                                    val ctx = getApplication<Application>()
                                    val suppressLocal =
                                        com.maodouchat.notification.LocalNotificationSuppressPolicy.shouldSuppress(
                                            notificationsEnabled =
                                                com.maodouchat.notification.NotificationPreferences.notificationsEnabled(ctx),
                                            dndStartHour =
                                                com.maodouchat.notification.NotificationPreferences.dndStartHour(ctx),
                                            dndEndHour =
                                                com.maodouchat.notification.NotificationPreferences.dndEndHour(ctx),
                                            hourOfDay = java.util.Calendar.getInstance()
                                                .get(java.util.Calendar.HOUR_OF_DAY),
                                            dndRuntimeEnabled = RuntimeFlags.isEnabled(ctx, RuntimeFlags.DND),
                                            dndEnabled =
                                                com.maodouchat.notification.NotificationPreferences.dndEnabled(ctx),
                                            startMinute =
                                                com.maodouchat.notification.NotificationPreferences.dndStartMinute(ctx),
                                            endMinute =
                                                com.maodouchat.notification.NotificationPreferences.dndEndMinute(ctx),
                                            currentMinute = java.util.Calendar.getInstance().let { c ->
                                                c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
                                            },
                                        )
                                    // 8.46：会话级免打扰时段——per-chat 静音窗内不弹通知（本地，与全局 DND 互补）
                                    val quietHoursSuppress =
                                        com.maodouchat.notification.ChatQuietHoursPolicy.shouldSuppress(
                                            com.maodouchat.notification.ChatQuietHoursStore.get(ctx, targetId),
                                            java.util.Calendar.getInstance().let { c ->
                                                c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
                                            }
                                        )
                                    // 1.28：临时静音至（silentUntil）窗口内不弹通知（与 FCM/BacklogSync 路径一致）
                                    val silentUntilSuppress =
                                        com.maodouchat.notification.ChatQuietHoursStore.silentUntil(ctx, targetId) > System.currentTimeMillis()
                                    if (target?.notificationsMuted != true && !suppressLocal && !quietHoursSuppress && !silentUntilSuppress) {
                                        val senderName = target?.participants
                                            ?.firstOrNull { it.id == event.message.senderId }?.name
                                            ?: event.message.senderId
                                        // 解密后的 meta.mentions：@我 / @所有人 时标题强调（E2EE 服务端不可见）
                                        val mentionIds = runCatching {
                                            val contentForMeta = decryptedPlain ?: event.message.content
                                            event.message.copy(content = contentForMeta).parsedMeta().mentions
                                        }.getOrDefault(emptyList())
                                        val selfId = listReceiveOwnerUserId
                                        val mentionHighlight =
                                            com.maodouchat.ui.screen.chatdetail.MentionPolicy.shouldHighlightMention(
                                                mentionIds = mentionIds,
                                                currentUserId = selfId,
                                                notificationsMuted = false,
                                            )
                                        val notifyTitle = when {
                                            mentionHighlight && mentionIds.contains(
                                                com.maodouchat.ui.screen.chatdetail.MentionPolicy.EVERYONE_ID
                                            ) && !mentionIds.contains(selfId) ->
                                                ctx.getString(R.string.notification_mentioned_everyone, senderName)
                                            mentionHighlight ->
                                                ctx.getString(R.string.notification_mentioned_you, senderName)
                                            else -> senderName
                                        }
                                        // Reuse decryptedPlain (same WS event; never decrypt twice).
                                        // soundEnabled must match FCM path (prefs), not default true.
                                        // 9.164：showMessage 内部含 runBlocking(IO) 查锁/密聊表——
                                        // 该 collector 跑在主线程，直接调用会阻塞主线程（消息洪峰下掉帧/ANR）
                                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            com.maodouchat.util.AppNotifier.showMessage(
                                                ctx,
                                                targetId,
                                                notifyTitle,
                                                previewText,
                                                event.message.id,
                                                soundEnabled =
                                                    com.maodouchat.notification.NotificationPreferences.soundEnabled(ctx),
                                                expectedUserId = listReceiveOwnerUserId,
                                            )
                                        }
                                    }
                                }
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                android.util.Log.w("ChatListViewModel", "Failed to update chat on message", error)
                            }
                        }
                    }
                    is WebSocketEvent.MessageDeleted -> {
                        // 即使未打开该聊天，也必须清本地行，否则重开会显示幽灵消息
                        val deletedId = event.messageId
                        val chatId = event.chatId
                        val deleteOwnerUserId = liveUserId
                        if (deletedId.isNotBlank()) {
                            viewModelScope.launch {
                                try {
                                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                            expectedUserId = deleteOwnerUserId,
                                            liveToken = tokenManager.getToken(),
                                            liveUserId = tokenManager.getUserId(),
                                        )
                                    ) {
                                        return@launch
                                    }
                                    com.maodouchat.util.MediaCache.deleteCachedMediaForMessage(app, deletedId)
                                    if (!withOwnerRoomWrite(realtimeSession) {
                                            app.database.messageSearchDao().deleteDocument(deletedId)
                                            messageRepo.deleteMessage(deletedId)
                                        }
                                    ) return@launch
                                    // Absolute local preview: do not wait for getChats for last-message tail.
                                    if (chatId.isNotBlank()) {
                                        refreshChatListPreviewFromLocal(
                                            chatId,
                                            deleteOwnerUserId,
                                            realtimeSession.sessionGeneration,
                                        )
                                    }
                                    // Privacy: drop tray/center if still showing this message body.
                                    dismissNotificationIfReferencesMessage(chatId, deletedId, realtimeSession)
                                } catch (error: kotlinx.coroutines.CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    android.util.Log.w("ChatListViewModel", "Failed to apply remote delete", error)
                                }
                            }
                        }
                        requestLoadChats(ChatListReloadPolicy.Trigger.MESSAGE_DELETED)
                    }
                    is WebSocketEvent.MessageRevoked -> {
                        // 未打开聊天时也必须本地落撤回，否则 cursor 已越过该 timestamp 后永远收敛不到
                        val revokedId = event.messageId
                        val chatId = event.chatId
                        val revokeOwnerUserId = liveUserId
                        if (revokedId.isNotBlank()) {
                            viewModelScope.launch {
                                try {
                                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                            expectedUserId = revokeOwnerUserId,
                                            liveToken = tokenManager.getToken(),
                                            liveUserId = tokenManager.getUserId(),
                                        )
                                    ) {
                                        return@launch
                                    }
                                    val placeholder = text(R.string.chat_message_revoked_placeholder)
                                    com.maodouchat.util.MediaCache.deleteCachedMediaForMessage(app, revokedId)
                                    if (!withOwnerRoomWrite(realtimeSession) {
                                            val original = messageRepo.getMessageById(revokedId)
                                            val revoked = (original ?: com.maodouchat.data.model.Message(
                                                id = revokedId,
                                                chatId = chatId,
                                                senderId = "",
                                                content = placeholder,
                                                type = MessageType.REVOKED,
                                                timestamp = System.currentTimeMillis(),
                                                status = MessageStatus.SENT
                                            )).copy(
                                                content = placeholder,
                                                type = MessageType.REVOKED,
                                                meta = com.maodouchat.data.model.MessageMeta()
                                            )
                                            app.database.messageSearchDao().deleteDocument(revokedId)
                                            messageRepo.insertMessage(revoked)
                                        }
                                    ) return@launch
                                    if (chatId.isNotBlank()) {
                                        refreshChatListPreviewFromLocal(
                                            chatId,
                                            revokeOwnerUserId,
                                            realtimeSession.sessionGeneration,
                                        )
                                    }
                                    // Privacy: revoked body must not linger in tray/center preview.
                                    dismissNotificationIfReferencesMessage(chatId, revokedId, realtimeSession)
                                } catch (error: kotlinx.coroutines.CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    android.util.Log.w("ChatListViewModel", "Failed to apply remote revoke", error)
                                }
                            }
                        }
                        requestLoadChats(ChatListReloadPolicy.Trigger.MESSAGE_REVOKED)
                    }
                    is WebSocketEvent.ChatMarkedRead -> {
                        // 跨设备已读同步：同账号其他设备标记了该会话已读，本地未读角标立即清零
                        //（服务端 mark-read 广播 CHAT_MARKED_READ，含本设备连接，幂等处理）。
                        // 8.32 修复 F1：同步清理 tray 通知与通知中心未读，避免三处状态不一致。
                        val markedChatId = event.chatId
                        if (markedChatId.isNotBlank()) {
                            viewModelScope.launch {
                                try {
                                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                            expectedUserId = liveUserId,
                                            liveToken = tokenManager.getToken(),
                                            liveUserId = tokenManager.getUserId(),
                                        )
                                    ) {
                                        return@launch
                                    }
                                    withOwnerRoomWrite(realtimeSession) {
                                        app.database.chatDao().markAllRead(markedChatId)
                                    }
                                    runCatching {
                                        com.maodouchat.util.AppNotifier.cancelMessage(app, markedChatId)
                                    }
                                    runCatching {
                                        app.notificationCenter.markChatMessagesRead(markedChatId)
                                    }
                                    loadChats(showLoading = false)
                                } catch (error: kotlinx.coroutines.CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    android.util.Log.w("ChatListViewModel", "Failed to apply cross-device read", error)
                                }
                            }
                        }
                    }
                    is WebSocketEvent.StatusChanged -> {
                        // 全局落库投递状态，避免未打开详情时 MESSAGE_STATUS ACK 丢失导致 outbox 永久 SENDING
                        val messageId = event.messageId
                        val statusOwnerUserId = liveUserId
                        if (messageId.isNotBlank()) {
                            viewModelScope.launch {
                                try {
                                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                            expectedUserId = statusOwnerUserId,
                                            liveToken = tokenManager.getToken(),
                                            liveUserId = tokenManager.getUserId(),
                                        )
                                    ) {
                                        return@launch
                                    }
                                    withOwnerRoomWrite(realtimeSession) {
                                        messageRepo.updateMessageStatus(messageId, event.status)
                                    }
                                } catch (error: kotlinx.coroutines.CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    android.util.Log.w("ChatListViewModel", "Failed to apply remote status", error)
                                }
                            }
                        }
                    }
                    is WebSocketEvent.MessageReactionUpdated -> {
                        // Reactions are not in message-mutations cursor; list must persist while detail closed.
                        // If the message row is not yet in Room, buffer until MessageReceived inserts it.
                        val messageId = event.messageId
                        val chatId = event.chatId
                        val reactionOwnerUserId = liveUserId
                        if (messageId.isNotBlank() && chatId.isNotBlank()) {
                            viewModelScope.launch {
                                try {
                                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                            expectedUserId = reactionOwnerUserId,
                                            liveToken = tokenManager.getToken(),
                                            liveUserId = tokenManager.getUserId(),
                                        )
                                    ) {
                                        return@launch
                                    }
                                    val existing = app.database.withTransaction {
                                        if (!isOwnerSessionCurrent(realtimeSession)) null
                                        else messageRepo.getMessageById(messageId)
                                    }
                                    if (existing == null) {
                                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                                expectedUserId = reactionOwnerUserId,
                                                liveToken = tokenManager.getToken(),
                                                liveUserId = tokenManager.getUserId(),
                                            )
                                        ) {
                                            return@launch
                                        }
                                        pendingReactions = PendingReactionPolicy.put(
                                            pending = pendingReactions,
                                            chatId = chatId,
                                            messageId = messageId,
                                            reactions = event.reactions,
                                            nowMs = System.currentTimeMillis()
                                        )
                                        return@launch
                                    }
                                    if (existing.chatId != chatId) return@launch
                                    if (existing.type == MessageType.REVOKED) return@launch
                                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                            expectedUserId = reactionOwnerUserId,
                                            liveToken = tokenManager.getToken(),
                                            liveUserId = tokenManager.getUserId(),
                                        )
                                    ) {
                                        return@launch
                                    }
                                    withOwnerRoomWrite(realtimeSession) {
                                        messageRepo.updateMessageReactions(messageId, event.reactions)
                                    }
                                } catch (error: kotlinx.coroutines.CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    android.util.Log.w("ChatListViewModel", "Failed to apply remote reaction", error)
                                }
                            }
                        }
                    }
                    is WebSocketEvent.MessageEdited -> {
                        // 详情未打开时也落库 editedAt/content：仅当本地已有行（可解密）才更新，避免写入密文污染正文
                        val editedId = event.messageId
                        if (editedId.isNotBlank() && event.chatId.isNotBlank() &&
                            com.maodouchat.MaodouchatApp.activeChatId != event.chatId
                        ) {
                            val editOwnerUserId = liveUserId
                            viewModelScope.launch {
                                try {
                                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                            expectedUserId = editOwnerUserId,
                                            liveToken = tokenManager.getToken(),
                                            liveUserId = tokenManager.getUserId(),
                                        )
                                    ) {
                                        return@launch
                                    }
                                    val existing = messageRepo.getMessageById(editedId) ?: return@launch
                                    if (existing.chatId != event.chatId) return@launch
                                    if (existing.type == MessageType.REVOKED) return@launch
                                    val eventEditedAt = event.editedAt ?: System.currentTimeMillis()
                                    val existingEdit = existing.editedAt ?: Long.MIN_VALUE
                                    if (eventEditedAt < existingEdit) return@launch
                                    // 列表层无完整 decrypt 管道：只更新已是明文的 TEXT 行（编辑目标类型）
                                    if (existing.type != MessageType.TEXT && existing.type != MessageType.MARKDOWN) return@launch
                                    // 8.31 性能修复 F1：解密含同步阻塞 Room 读（SignalProtocol 内部），
                                    // 必须切 IO 线程，避免主线程磁盘 I/O + 掉帧。
                                    val plaintext = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        val signal = app.signalProtocol
                                        val isGroup = _uiState.value.chats.find { it.id == event.chatId }?.isGroup == true
                                        // Align wire heuristic with list preview policy (not bare startsWith("{")).
                                        when {
                                            !signal.isEncryptedEnvelope(event.content) &&
                                                !signal.isSenderKeyEnvelope(event.content) &&
                                                !ChatListPreviewPolicy.looksLikeWireEnvelope(event.content) -> event.content
                                            isGroup || signal.isSenderKeyEnvelope(event.content) -> when (
                                                val r = signal.decryptGroupContentEnvelope(
                                                    existing.senderId, event.content, expectedGroupId = event.chatId
                                                )
                                            ) {
                                                is com.maodouchat.crypto.SignalProtocol.DecryptResult.Success -> r.plaintext
                                                else -> null
                                            }
                                            else -> when (val r = signal.decryptTextEnvelope(existing.senderId, event.content)) {
                                                is com.maodouchat.crypto.SignalProtocol.DecryptResult.Success -> r.plaintext
                                                else -> null
                                            }
                                        }
                                    } ?: return@launch
                                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                            expectedUserId = editOwnerUserId,
                                            liveToken = tokenManager.getToken(),
                                            liveUserId = tokenManager.getUserId(),
                                        )
                                    ) {
                                        return@launch
                                    }
                                    val plainEdited = existing.copy(content = plaintext, editedAt = eventEditedAt)
                                    if (!withOwnerRoomWrite(realtimeSession) {
                                            messageRepo.insertMessage(plainEdited)
                                            try {
                                                com.maodouchat.data.repository.MessageSearchRepository(app.database)
                                                    .indexMessage(plainEdited)
                                            } catch (cancel: kotlinx.coroutines.CancellationException) {
                                                throw cancel
                                            } catch (indexError: Exception) {
                                                android.util.Log.w(
                                                    "ChatListViewModel",
                                                    "indexMessage after remote edit failed",
                                                    indexError
                                                )
                                            }
                                        }
                                    ) return@launch
                                    // Only head edits change list preview text.
                                    val headId = messageRepo.getRecentMessages(event.chatId, limit = 1)
                                        .firstOrNull()?.id
                                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                            expectedUserId = editOwnerUserId,
                                            liveToken = tokenManager.getToken(),
                                            liveUserId = tokenManager.getUserId(),
                                        )
                                    ) return@launch
                                    if (ChatListPreviewPolicy.affectsListHead(headId, editedId)) {
                                        refreshChatListPreviewFromLocal(
                                            event.chatId,
                                            editOwnerUserId,
                                            realtimeSession.sessionGeneration,
                                        )
                                    }
                                    // Keep notification-center body in sync with edited plaintext.
                                    refreshNotificationPreviewIfReferencesMessage(
                                        event.chatId,
                                        editedId,
                                        plaintext,
                                        realtimeSession,
                                    )
                                } catch (error: kotlinx.coroutines.CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    android.util.Log.w("ChatListViewModel", "Failed to apply remote edit", error)
                                }
                            }
                        }
                    }
                    is WebSocketEvent.GroupRevisionChanged -> {
                        // Membership bursts (join/leave/kick) often arrive in clusters.
                        requestLoadChats(ChatListReloadPolicy.Trigger.GROUP_REVISION)
                    }
                    is WebSocketEvent.Connected -> {
                        if (event.success) {
                            _uiState.update { it.copy(realtimeBanner = null) }
                            // Reconnect must refresh immediately; do not wait for debounce.
                            requestLoadChats(ChatListReloadPolicy.Trigger.RECONNECT)
                            // Leave-chat SENDING text must not wait for ChatDetail re-open.
                            flushTextOutbox()
                        } else {
                            _uiState.update {
                                it.copy(realtimeBanner = text(R.string.chat_ws_connection_failed))
                            }
                        }
                    }
                    is WebSocketEvent.Disconnected -> {
                        _uiState.update {
                            it.copy(realtimeBanner = text(R.string.chat_ws_connection_failed))
                        }
                    }
                    is WebSocketEvent.Error -> {
                        // Soft banner only — list remains usable offline from Room.
                        if (event.kind == com.maodouchat.network.WebSocketErrorKind.CONNECTION) {
                            _uiState.update {
                                it.copy(realtimeBanner = text(R.string.chat_ws_connection_failed))
                            }
                        }
                    }
                    is WebSocketEvent.UserOnline -> {
                        // Collector already blank-checks token/userId; re-check so buffered events
                        // after switch do not paint previous-owner online dots onto the new list.
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = liveUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@collect
                        }
                        if (event.onlineRevoked || event.statusRevoked) {
                            withOwnerRoomWrite(realtimeSession) {
                                app.database.userDao().applyRealtimeVisibility(
                                    userId = event.userId,
                                    isOnline = event.isOnline,
                                    onlineRevoked = event.onlineRevoked,
                                    statusRevoked = event.statusRevoked,
                                    updatedAt = System.currentTimeMillis()
                                )
                            }
                        }
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = liveUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) return@collect
                        _uiState.update { state ->
                            state.copy(chats = state.chats.map { chat ->
                                val updated = chat.participants.map { p ->
                                    if (p.id == event.userId) {
                                        val visibility = com.maodouchat.network.resolveUserVisibility(
                                            currentIsOnline = p.isOnline,
                                            currentStatus = p.status,
                                            currentLastSeen = p.lastSeen,
                                            eventIsOnline = event.isOnline,
                                            eventLastSeen = event.lastSeen,
                                            onlineRevoked = event.onlineRevoked,
                                            statusRevoked = event.statusRevoked
                                        )
                                        p.copy(
                                            isOnline = visibility.isOnline,
                                            status = visibility.status,
                                            lastSeen = visibility.lastSeen
                                        )
                                    } else {
                                        p
                                    }
                                }
                                chat.copy(participants = updated)
                            })
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun observeMissedCalls() {
        val missedOwnerUserId = currentUserIdStr
        viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = missedOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) return@launch
            try {
                missedRepo.trimToRetention()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("ChatListViewModel", "missed-call trim failed", error)
            }
            missedRepo.observeRecent().collect { list ->
                // Soft-purge may lag Room emission; old collectors must never clear the new owner.
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = missedOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) return@collect
                _uiState.update { it.copy(missedCalls = list) }
            }
        }
    }

    private fun loadChats(showLoading: Boolean = true) {
        // Supersede any in-flight getChats so rapid reconnect/refresh does not race UI.
        loadChatsJob?.cancel()
        val requestId = ++loadChatsRequestId
        val token = tokenManager.getToken().orEmpty()
        val loadOwnerUserId = currentUserIdStr
        if (showLoading) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        } else {
            _uiState.update { it.copy(errorMessage = null) }
        }
        loadChatsJob = viewModelScope.launch {
            try {
                if (token.isBlank() || loadOwnerUserId.isBlank()) {
                    // 无 Token，从本地加载（只取一次）；空缓存时提示会话过期，避免“空白列表无反馈”。
                    val chats = chatRepo.getAllChats().firstOrNull() ?: emptyList()
                    if (requestId != loadChatsRequestId ||
                        tokenManager.getUserId().orEmpty() != loadOwnerUserId ||
                        !tokenManager.getToken().isNullOrBlank()
                    ) return@launch
                    _uiState.update {
                        it.copy(
                            chats = chats,
                            isLoading = false,
                            errorMessage = if (chats.isEmpty()) text(R.string.error_session_expired) else null
                        )
                    }
                    return@launch
                }

                if (requestId != loadChatsRequestId || loadOwnerUserId.isBlank() ||
                    !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = loadOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@launch
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                // 首次进入：需要 WebSocket 未连接则连上，保证后续消息能实时接收
                if (!WebSocketClient.isConnected()) {
                    WebSocketClient.connect(ApiConfig.WS_URL, liveToken)
                }

                // 从 API 获取
                val result = ApiService.getChats(liveToken)
                result.fold(
                    onSuccess = { chatDtos ->
                        if (requestId != loadChatsRequestId ||
                            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = loadOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        val currentUserId = tokenManager.getUserId().orEmpty()
                        // Server NUDGE lastMessage is generic ("[提醒]"); rewrite from local Room POV when possible.
                        val localById = chatRepo.getAllChats().firstOrNull().orEmpty().associateBy { it.id }
                        val uiById = _uiState.value.chats.associateBy { it.id }
                        val activeId = com.maodouchat.MaodouchatApp.activeChatId
                        val chats = chatDtos.map { dto ->
                            val participants = dto.participants.map { User(it.id, it.name, it.avatar, it.email, it.isOnline, it.status) }
                            val local = localById[dto.id] ?: uiById[dto.id]
                            val isActive = !activeId.isNullOrBlank() && activeId == dto.id
                            val mergedUnread = ChatListUnreadPolicy.mergeUnreadCount(
                                serverUnread = dto.unreadCount,
                                localUnread = local?.unreadCount,
                                isActiveChat = isActive,
                                localMarkedUnread = local?.markedUnread == true,
                                serverLastMessageTime = dto.lastMessageTime,
                                localLastMessageTime = local?.lastMessageTime ?: 0L
                            )
                            val serverSettings = ChatListSettingsMergePolicy.SettingsSnapshot(
                                pinnedAt = dto.pinnedAt,
                                notificationsMuted = dto.notificationsMuted,
                                archived = dto.archived,
                                markedUnread = dto.markedUnread,
                                settingsUpdatedAt = dto.settingsUpdatedAt
                            )
                            val localSettings = local?.let {
                                ChatListSettingsMergePolicy.SettingsSnapshot(
                                    pinnedAt = it.pinnedAt,
                                    notificationsMuted = it.notificationsMuted,
                                    archived = it.archived,
                                    markedUnread = it.markedUnread,
                                    settingsUpdatedAt = it.settingsUpdatedAt
                                )
                            }
                            val mergedSettings = ChatListSettingsMergePolicy.merge(serverSettings, localSettings)
                            // Prefer settings-merge result for markedUnread when local is newer;
                            // still force false on active chat (open chat clears manual unread).
                            val mergedMarked = if (isActive) {
                                false
                            } else {
                                ChatListUnreadPolicy.mergeMarkedUnread(
                                    serverMarked = mergedSettings.markedUnread,
                                    localMarked = local?.markedUnread,
                                    isActiveChat = false
                                )
                            }
                            val base = Chat(
                                id = dto.id,
                                participants = if (dto.isGroup) participants else participants.filter { it.id != currentUserId }.ifEmpty { participants },
                                lastMessage = dto.lastMessage,
                                lastMessageType = MessageType.fromWire(dto.lastMessageType),
                                lastMessageTime = dto.lastMessageTime,
                                unreadCount = mergedUnread,
                                isGroup = dto.isGroup,
                                groupName = dto.groupName,
                                groupAnnouncement = dto.groupAnnouncement,
                                groupAvatar = dto.groupAvatar,
                                memberRevision = dto.memberRevision,
                                pinnedAt = mergedSettings.pinnedAt,
                                notificationsMuted = mergedSettings.notificationsMuted,
                                archived = mergedSettings.archived,
                                markedUnread = mergedMarked,
                                settingsUpdatedAt = mergedSettings.settingsUpdatedAt,
                                disappearingMessageSeconds = dto.disappearingMessageSeconds
                            )
                            enrichServerChatPreview(base, loadOwnerUserId)
                        }
                        // 丢弃本会话已删除（退出服务端）的会话：即便服务端 leave 尚未生效而仍返回该会话，
                        // 也不重新插回列表（防删除后角标鬼影/复活）；其本地缓存由下方 stale 清理删除。
                        val filteredChats = chats.filterNot { deletedChatIds.contains(it.id) }
                        val serverChatIds = filteredChats.mapTo(hashSetOf()) { it.id }
                        val staleChatIds = chatRepo.getAllChats().firstOrNull().orEmpty()
                            .map { it.id }
                            .filterNot(serverChatIds::contains)
                        if (requestId != loadChatsRequestId ||
                            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = loadOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) return@fold
                        // 服务端列表已到手：过期会话清理 + 缓存 + 列表 UI 收敛必须跑完，
                        // 避免 cancel 留下「半清本地 / UI 仍显示幽灵会话 / isLoading 卡死」
                        withContext(kotlinx.coroutines.NonCancellable) {
                            // BUG 2.1 fix: cleanup 可能抛异常，包裹 try-catch 确保 isLoading 总能被清除
                            try {
                                for (staleId in staleChatIds) {
                                    if (requestId != loadChatsRequestId ||
                                        !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                            expectedUserId = loadOwnerUserId,
                                            liveToken = tokenManager.getToken(),
                                            liveUserId = tokenManager.getUserId(),
                                        )
                                    ) return@withContext
                                    cleanupLocalChat(staleId, loadOwnerUserId)
                                }
                                if (requestId != loadChatsRequestId ||
                                    !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                        expectedUserId = loadOwnerUserId,
                                        liveToken = tokenManager.getToken(),
                                        liveUserId = tokenManager.getUserId(),
                                    )
                                ) return@withContext
                                // 8.49 修复：写库与 UI 一律使用 filteredChats——此前用未过滤的 chats，
                                // 刚被 stale 清理删掉的本地行又被插回 Room，幽灵会话+角标复活
                                chatRepo.cacheChats(filteredChats)
                            } catch (cleanupError: kotlinx.coroutines.CancellationException) {
                                throw cleanupError
                            } catch (cleanupError: Exception) {
                                android.util.Log.w("ChatListViewModel", "Chat cleanup failed", cleanupError)
                            }
                            if (requestId != loadChatsRequestId ||
                                !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                    expectedUserId = loadOwnerUserId,
                                    liveToken = tokenManager.getToken(),
                                    liveUserId = tokenManager.getUserId(),
                                )
                            ) return@withContext
                            // cacheChats 合并本地备注后，从 Room 回读标题用 displayName
                            val nickMerged = filteredChats.map { c ->
                                chatRepo.getChatById(c.id) ?: c
                            }
                            if (requestId != loadChatsRequestId ||
                                !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                    expectedUserId = loadOwnerUserId,
                                    liveToken = tokenManager.getToken(),
                                    liveUserId = tokenManager.getUserId(),
                                )
                            ) return@withContext
                            _uiState.update { it.copy(chats = nickMerged, isLoading = false) }
                            refreshIdentityWarnings()
                        }
                        // 离线/漏 WS 时：列表侧回放 mutation 日志（不依赖打开详情）；可取消
                        if (requestId == loadChatsRequestId &&
                            com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = loadOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            val liveTok = tokenManager.getToken() ?: token
                            syncClosedChatMutations(liveTok, chats.map { it.id })
                            // 冷启动/回列表：冲 SENDING 文本发件箱（附件走 WorkManager）
                            flushTextOutbox()
                        }
                    },
                    onFailure = { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        if (requestId != loadChatsRequestId ||
                            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = loadOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) return@fold
                        // API 失败，从本地加载
                        val chats = chatRepo.getAllChats().firstOrNull() ?: emptyList()
                        if (requestId != loadChatsRequestId ||
                            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = loadOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) return@fold
                        _uiState.update {
                            it.copy(
                                chats = chats,
                                isLoading = false,
                                errorMessage = error.message?.takeIf { message -> message.isNotBlank() } ?: text(R.string.chat_refresh_failed_cached)
                            )
                        }
                        refreshIdentityWarnings()
                        // 仍可尝试用本地 chat 缓存加密 flush（网络失败时 send 仍可能短暂可用）
                        flushTextOutbox()
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                // Superseded by a newer loadChats — do not clear isLoading here (race with winner).
                throw error
            }
        }
    }

    /**
     * 关闭中的聊天也要收敛 DELETE/REVOKE（及可解密的 EDIT）。
     * 跳过当前活跃详情页（由 ChatDetailViewModel 单飞同步）。
     */
    private suspend fun syncClosedChatMutations(token: String, chatIds: List<String>) {
        if (token.isBlank() || chatIds.isEmpty()) return
        val activeId = MaodouchatApp.activeChatId
        val targets = chatIds.asSequence()
            .filter { it.isNotBlank() && it != activeId }
            .distinct()
            .take(40)
            .toList()
        val mutationOwnerUserId = tokenManager.getUserId().orEmpty()
        withContext(Dispatchers.IO) {
            for (cid in targets) {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = mutationOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    break
                }
                try {
                    val liveTok = tokenManager.getToken() ?: token
                    syncMutationsForClosedChat(liveTok, cid)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.util.Log.w("ChatListViewModel", "mutation sync failed for $cid", error)
                }
            }
        }
    }

    private suspend fun syncMutationsForClosedChat(token: String, chatId: String) {
        val pageLimit = 100
        val maxPages = 8
        val pageOwnerUserId = tokenManager.getUserId().orEmpty()
        if (pageOwnerUserId.isBlank()) return
        var liveToken = token
        var cursor = tokenManager.getMutationCursor(chatId)
        var previewDirty = false
        for (page in 0 until maxPages) {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = pageOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                break
            }
            liveToken = tokenManager.getToken().orEmpty().ifBlank { liveToken }
            val pageResult = ApiService.getMessageMutationsSince(
                token = liveToken,
                chatId = chatId,
                sinceMs = cursor.timestampMs,
                limit = pageLimit,
                sinceId = cursor.messageId.takeIf { it.isNotBlank() }
            )
            val pageError = pageResult.exceptionOrNull()
            if (pageError is kotlinx.coroutines.CancellationException) throw pageError
            val mutations = pageResult.getOrNull() ?: break
            if (mutations.isEmpty()) break
            var advanced = cursor
            // 列表侧 EDIT 解密失败不推进越过该条（详情打开后会再处理）
            var pendingEditBlock: TokenManager.SyncCursor? = null
            mutations.sortedWith(
                compareBy<MessageMutationDto> { it.createdAt }.thenBy { it.id }
            ).forEach { mut ->
                // Page can outlive logout; stop applying without advancing past unapplied rows.
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = pageOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@forEach
                }
                val applied = when (mut.action) {
                    "DELETE" -> {
                        com.maodouchat.util.MediaCache.deleteCachedMediaForMessage(app, mut.messageId)
                        app.database.messageSearchDao().deleteDocument(mut.messageId)
                        messageRepo.deleteMessage(mut.messageId)
                        dismissNotificationIfReferencesMessage(mut.chatId, mut.messageId, ownerSession(pageOwnerUserId))
                        previewDirty = true
                        true
                    }
                    "REVOKE" -> {
                        val original = messageRepo.getMessageById(mut.messageId)
                        val placeholder = text(R.string.chat_message_revoked_placeholder)
                        val revoked = (original ?: Message(
                            id = mut.messageId,
                            chatId = mut.chatId,
                            senderId = mut.actorId,
                            content = placeholder,
                            type = MessageType.REVOKED,
                            timestamp = mut.createdAt,
                            status = MessageStatus.SENT
                        )).copy(
                            content = placeholder,
                            type = MessageType.REVOKED,
                            meta = MessageMeta()
                        )
                        com.maodouchat.util.MediaCache.deleteCachedMediaForMessage(app, mut.messageId)
                        app.database.messageSearchDao().deleteDocument(mut.messageId)
                        messageRepo.insertMessage(revoked)
                        dismissNotificationIfReferencesMessage(mut.chatId, mut.messageId, ownerSession(pageOwnerUserId))
                        previewDirty = true
                        true
                    }
                    "EDIT" -> {
                        val newContent = mut.content
                        if (newContent == null) {
                            true
                        } else {
                            val existing = messageRepo.getMessageById(mut.messageId)
                            when {
                                existing == null || existing.chatId != chatId -> true
                                existing.type == MessageType.REVOKED -> true
                                else -> {
                                    // 列表层无法可靠解密信封：仅当已是明文（非 wire envelope）时更新。
                                    // 密文 EDIT 留给详情 decrypt 路径，游标不越过。
                                    val looksEncrypted = ChatListPreviewPolicy.looksLikeWireEnvelope(newContent) ||
                                        app.signalProtocol.isEncryptedEnvelope(newContent) ||
                                        app.signalProtocol.isSenderKeyEnvelope(newContent)
                                    val existingPlain = existing.content.isNotBlank() &&
                                        !ChatListPreviewPolicy.looksLikeWireEnvelope(existing.content) &&
                                        !app.signalProtocol.isEncryptedEnvelope(existing.content) &&
                                        !app.signalProtocol.isSenderKeyEnvelope(existing.content)
                                    if (looksEncrypted && existingPlain) {
                                        // 已解密本地行，不要用密文覆盖
                                        if (pendingEditBlock == null) {
                                            pendingEditBlock = TokenManager.SyncCursor(mut.createdAt, mut.id)
                                        }
                                        false
                                    } else if (looksEncrypted) {
                                        // 本地也是密文：可写新密文（详情打开时再解）
                                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                                expectedUserId = pageOwnerUserId,
                                                liveToken = tokenManager.getToken(),
                                                liveUserId = tokenManager.getUserId(),
                                            )
                                        ) {
                                            false
                                        } else {
                                            messageRepo.insertMessage(
                                                existing.copy(
                                                    content = newContent,
                                                    editedAt = mut.editedAt ?: mut.createdAt
                                                )
                                            )
                                            // Ciphertext edit does not improve list plaintext preview.
                                            true
                                        }
                                    } else {
                                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                                expectedUserId = pageOwnerUserId,
                                                liveToken = tokenManager.getToken(),
                                                liveUserId = tokenManager.getUserId(),
                                            )
                                        ) {
                                            false
                                        } else {
                                            val plainEdited = existing.copy(
                                                content = newContent,
                                                editedAt = mut.editedAt ?: mut.createdAt
                                            )
                                            messageRepo.insertMessage(plainEdited)
                                            try {
                                                com.maodouchat.data.repository.MessageSearchRepository(app.database)
                                                    .indexMessage(plainEdited)
                                            } catch (error: kotlinx.coroutines.CancellationException) {
                                                throw error
                                            } catch (error: Exception) {
                                                android.util.Log.w(
                                                    "ChatListViewModel",
                                                    "indexMessage after closed-chat mutation EDIT failed",
                                                    error
                                                )
                                            }
                                            refreshNotificationPreviewIfReferencesMessage(mut.chatId, mut.messageId, newContent, ownerSession(pageOwnerUserId))
                                            previewDirty = true
                                            true
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> true
                }
                if (applied) {
                    val block = pendingEditBlock
                    val mutCursor = TokenManager.SyncCursor(mut.createdAt, mut.id)
                    if (block == null) {
                        advanced = mutCursor
                    } else {
                        val beforeBlock = mutCursor.timestampMs < block.timestampMs ||
                            (mutCursor.timestampMs == block.timestampMs && mutCursor.messageId < block.messageId)
                        if (beforeBlock) advanced = mutCursor
                    }
                }
            }
            val advancedPast = advanced.timestampMs > cursor.timestampMs ||
                (advanced.timestampMs == cursor.timestampMs && advanced.messageId > cursor.messageId)
            if (advancedPast) {
                tokenManager.saveMutationCursor(chatId, advanced)
                cursor = advanced
            } else {
                break
            }
            if (mutations.size < pageLimit || pendingEditBlock != null) break
        }
        if (previewDirty &&
            com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = pageOwnerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            refreshChatListPreviewFromLocal(chatId)
        }
    }

    private companion object {
        /** Coalesce list-bar message-content LIKE while the user is still typing. */
        const val LIST_MESSAGE_SEARCH_DEBOUNCE_MS: Long = 250L
        /** 8.52 UX：列表搜索框长度上限。 */
        const val LIST_SEARCH_MAX_LENGTH: Int = 200
    }
}
