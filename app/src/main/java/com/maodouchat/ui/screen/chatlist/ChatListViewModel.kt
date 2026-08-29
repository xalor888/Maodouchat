package com.maodouchat.ui.screen.chatlist

import com.maodouchat.util.RuntimeFlags
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.data.repository.ChatListPreviewPolicy
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.User
import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.data.repository.NotificationCenterRepository
import com.maodouchat.conversation.ConversationLocalCleanupMode
import com.maodouchat.conversation.ConversationLocalCleanupSession
import com.maodouchat.conversation.conversationLocalCleanupSession
import com.maodouchat.conversation.createAndroidConversationLocalStateCoordinator
import com.maodouchat.network.ApiConfig
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiService
import com.maodouchat.network.UpdateChatSettingsRequest
import com.maodouchat.network.TokenManager
import com.maodouchat.network.WebSocketClient
import com.maodouchat.network.WebSocketEvent
import com.maodouchat.scheduling.AndroidConversationScheduleBackend
import com.maodouchat.scheduling.ConversationScheduleCoordinator
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

/** Notification / nudge sender label: nickname → name → truncated id, never a blank title. */
internal fun listSenderLabel(chat: Chat?, senderId: String, unknownLabel: String = ""): String {
    val fromParticipant = chat?.participants
        ?.firstOrNull { it.id == senderId }
        ?.displayName
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val truncated = com.maodouchat.ui.screen.chatdetail.truncatedSenderId(senderId)
    return fromParticipant ?: truncated ?: unknownLabel.ifBlank { senderId }
}

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
    /** 密聊会话 id（chatType=SECRET，双方同步的独立 1:1） */
    val secretChatIds: Set<String> = emptySet(),
    /** 从列表发起密聊成功后打开新会话 */
    val createdSecretChatId: String? = null,
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
    /** 本机最近一条可见消息的回执（chatId → 自己发出时才有）。不进 Room schema。 */
    val receiptsByChat: Map<String, ChatListReceiptPolicy.Receipt> = emptyMap(),
) {
    val filteredChats: List<Chat>
        get() {
            val visible = chats.filter { it.archived == showArchived }
            val folderFiltered = when {
                selectedFolderId.isNullOrBlank() ->
                    visible.filter { !com.maodouchat.security.SecretChatPolicy.excludeFromAllChats(it.isSecret) }
                selectedFolderId == com.maodouchat.util.ChatFolderPolicy.SYSTEM_GROUPS_ID ->
                    visible.filter { it.isGroup }
                selectedFolderId == com.maodouchat.util.ChatFolderPolicy.SYSTEM_DIRECT_ID ->
                    visible.filter { !it.isGroup && !it.isSecret }
                selectedFolderId == com.maodouchat.util.ChatFolderPolicy.SYSTEM_UNREAD_ID ->
                    visible.filter {
                        !it.isSecret &&
                            com.maodouchat.util.ChatFolderPolicy.isUnreadChat(it.unreadCount, it.markedUnread)
                    }
                selectedFolderId == com.maodouchat.util.ChatFolderPolicy.SYSTEM_SECRET_ID ->
                    visible.filter { it.isSecret || it.id in secretChatIds }
                selectedFolderId == com.maodouchat.util.ChatFolderPolicy.SYSTEM_LOCKED_ID ->
                    visible.filter { it.id in lockedChatIds && !it.isSecret }
                else -> {
                    val folder = folders.firstOrNull { it.id == selectedFolderId }
                    if (folder == null) visible.filter { !it.isSecret }
                    else {
                        val ids = folder.chatIds.toSet()
                        visible.filter { it.id in ids && !it.isSecret }
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
            unreadCounts = chats.filter { !it.archived && !it.isSecret }.map { it.unreadCount },
            markedUnreadFlags = chats.filter { !it.archived && !it.isSecret }.map { it.markedUnread }
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
                unreadCounts = chats.filter { !it.archived && !it.isGroup && !it.isSecret }.map { it.unreadCount },
                markedUnreadFlags = chats.filter { !it.archived && !it.isGroup && !it.isSecret }.map { it.markedUnread }
            )
        }
        if (folderId == com.maodouchat.util.ChatFolderPolicy.SYSTEM_UNREAD_ID) {
            return unreadChatCount
        }
        if (folderId == com.maodouchat.util.ChatFolderPolicy.SYSTEM_SECRET_ID) {
            val secretVisible = chats.filter { !it.archived && (it.isSecret || it.id in secretChatIds) }
            return com.maodouchat.util.UnreadPriorityPolicy.countUnreadChats(
                unreadCounts = secretVisible.map { it.unreadCount },
                markedUnreadFlags = secretVisible.map { it.markedUnread }
            )
        }
        if (folderId == com.maodouchat.util.ChatFolderPolicy.SYSTEM_LOCKED_ID) {
            val lockedVisible = chats.filter { !it.archived && it.id in lockedChatIds && !it.isSecret }
            return com.maodouchat.util.UnreadPriorityPolicy.countUnreadChats(
                unreadCounts = lockedVisible.map { it.unreadCount },
                markedUnreadFlags = lockedVisible.map { it.markedUnread }
            )
        }
        val folder = folders.firstOrNull { it.id == folderId } ?: return 0
        val unreadMap = chats.filter { !it.isSecret }.associate { it.id to it.unreadCount }
        return com.maodouchat.util.ChatFolderPolicy.unreadInFolder(folder, unreadMap)
    }
}

class ChatListViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MaodouchatApp
    private val chatRepo = ChatRepository(app.database.chatDao(), app.database.userDao())
    private val messageRepo = LocalMessageStore(app.database.messageDao(), app.database)
    private val missedRepo = com.maodouchat.data.repository.MissedCallRepository(app.database.missedCallDao())
    private val tokenManager = TokenManager.getInstance(application)
    private val conversationScheduleCoordinator = ConversationScheduleCoordinator(
        ownerUserId = { tokenManager.getUserId().orEmpty() },
        backend = AndroidConversationScheduleBackend(application),
    )
    private val conversationLocalStateCoordinator = createAndroidConversationLocalStateCoordinator(
        app = app,
        tokenManager = tokenManager,
        scheduleCoordinator = conversationScheduleCoordinator,
    )
    private fun text(id: Int): String = getApplication<Application>().getString(id)

    /** 清空指定会话的本地明文（保留会话/PIN/草稿/同步游标）。不清游标，避免重拉密文 Duplicate。 */
    fun clearLocalChatHistory(chatId: String) {
        if (chatId.isBlank()) return
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (ownerUserId.isBlank()) return
        val cleanupSession = conversationLocalCleanupSession(ownerUserId)
        viewModelScope.launch(Dispatchers.IO) {
            val report = withContext(kotlinx.coroutines.NonCancellable) {
                conversationLocalStateCoordinator.cleanup(
                    chatId = chatId,
                    expectedSession = cleanupSession,
                    mode = ConversationLocalCleanupMode.CLEAR_HISTORY,
                )
            }
            report.failures.forEach { failure ->
                android.util.Log.w(
                    "ChatListViewModel",
                    "local conversation cleanup failed at ${failure.step} for $chatId",
                    failure.error,
                )
            }
            if (!report.completed) return@launch
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
     * 用于防止删除竞态：删除先乐观从 UI 移除，但服务端 leave 尚未生效时同步快照仍可能
     * 把会话重新带回。命中本集合的会话在合并时丢弃，并从本地清理。
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
        observeReceipts()
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
            val ids = _uiState.value.chats.filter { it.isSecret }.map { it.id }.toSet()
            if (tokenManager.getUserId().orEmpty() != ownerUserId) return@launch
            _uiState.update { it.copy(secretChatIds = ids) }
        }
    }

    fun clearCreatedSecretChat() {
        _uiState.update { it.copy(createdSecretChatId = null) }
    }

    fun startSecretChatWithPeer(peerId: String) {
        if (peerId.isBlank()) return
        if (!RuntimeFlags.isEnabled(app, RuntimeFlags.SECRET_CHAT)) {
            _uiState.update { it.copy(errorMessage = app.getString(R.string.secret_chat_feature_disabled)) }
            return
        }
        val token = tokenManager.getToken().orEmpty()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = app.getString(R.string.error_session_expired)) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                _uiState.update { it.copy(errorMessage = app.getString(R.string.error_session_expired)) }
                return@launch
            }
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            val result = ApiService.createChat(
                liveToken,
                listOf(peerId),
                isGroup = false,
                groupName = null,
                chatType = com.maodouchat.security.SecretChatPolicy.CHAT_TYPE
            )
            result.fold(
                onSuccess = { chat ->
                    try {
                        com.maodouchat.data.repository.SecretChatRepository(app.database.secretChatDao())
                            .touch(chat.id)
                    } catch (_: Exception) {}
                    try {
                        chatRepo.cacheChats(
                            listOf(
                                Chat(
                                    id = chat.id,
                                    participants = chat.participants.map { p ->
                                        User(p.id, p.name, p.avatar, p.email, p.isOnline, p.status, lastSeen = p.lastSeen)
                                    },
                                    lastMessage = chat.lastMessage,
                                    lastMessageType = MessageType.fromWire(chat.lastMessageType),
                                    lastMessageTime = chat.lastMessageTime,
                                    unreadCount = chat.unreadCount,
                                    isGroup = chat.isGroup,
                                    chatType = chat.chatType,
                                    groupName = chat.groupName,
                                    groupAnnouncement = chat.groupAnnouncement,
                                    groupAvatar = chat.groupAvatar,
                                    memberRevision = chat.memberRevision,
                                    disappearingMessageSeconds = chat.disappearingMessageSeconds
                                )
                            )
                        )
                    } catch (_: Exception) {}
                    _uiState.update { it.copy(createdSecretChatId = chat.id) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(errorMessage = error.message ?: app.getString(R.string.secret_chat_start_failed))
                    }
                }
            )
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
        val secretIds = _uiState.value.secretChatIds +
            _uiState.value.chats.filter { it.isSecret }.map { it.id }.toSet()
        val sanitized = folders.map { folder ->
            folder.copy(chatIds = folder.chatIds.filterNot { it in secretIds })
        }
        com.maodouchat.util.ChatFolderPreferences.setFolders(getApplication(), sanitized)
        _uiState.update {
            val selectedStillExists = it.selectedFolderId == null ||
                com.maodouchat.util.ChatFolderPolicy.isSystemFilter(it.selectedFolderId) ||
                sanitized.any { folder -> folder.id == it.selectedFolderId }
            it.copy(
                folders = sanitized,
                selectedFolderId = if (selectedStillExists) it.selectedFolderId else null
            )
        }
        pushFoldersToCloud(sanitized)
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
        val moving = _uiState.value.chats.firstOrNull { it.id == chatId }
        if (moving?.isSecret == true || chatId in _uiState.value.secretChatIds) return
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

    /** Telegram ticks: latest local message per chat, no schema change. */
    private fun observeReceipts() {
        val ownerUserId = currentUserIdStr
        if (ownerUserId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState
                .map { state -> state.chats.map { it.id } to state.chats.map { it.lastMessageTime } }
                .distinctUntilChanged()
                .collect { (chatIds, _) ->
                    if (tokenManager.getUserId().orEmpty() != ownerUserId) return@collect
                    val chatsById = _uiState.value.chats.associateBy { it.id }
                    val receipts = chatIds.associateWith { chatId ->
                        val latest = runCatching {
                            messageRepo.getRecentMessages(chatId, limit = 1).firstOrNull()
                        }.getOrNull()
                        ChatListReceiptPolicy.fromLatest(
                            latest = latest,
                            currentUserId = ownerUserId,
                            isGroup = chatsById[chatId]?.isGroup == true,
                        )
                    }.filterValues { it != null }.mapValues { it.value!! }
                    if (tokenManager.getUserId().orEmpty() != ownerUserId) return@collect
                    _uiState.update { it.copy(receiptsByChat = receipts) }
                }
        }
    }

    private var messageSearchJob: Job? = null

    /** 1.146：刷新各会话待发送定时消息数（本地 prefs store）。 */
    fun refreshScheduledCounts() {
        val counts = try {
            conversationScheduleCoordinator.listAllScheduled()
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
            try {
                val matchedIds = withContext(Dispatchers.IO) {
                    val locked = app.database.chatLockDao().listLockedChatIds().toSet()
                    val secret = app.database.chatDao().listSecretChatIds().toSet()
                    app.database.messageDao().searchChatIdsByMessageContent(escaped)
                        .filterNot { it in locked || it in secret }
                }
                // Drop if user kept typing past this snapshot or account switched mid-search.
                if (_uiState.value.searchQuery != clipped) return@launch
                if (tokenManager.getUserId().orEmpty() != searchOwnerUserId) return@launch
                _uiState.update { it.copy(messageMatchedChatIds = matchedIds.toSet()) }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (_uiState.value.searchQuery != clipped) return@launch
                if (tokenManager.getUserId().orEmpty() != searchOwnerUserId) return@launch
                android.util.Log.w("ChatListViewModel", "list message search failed", error)
                _uiState.update {
                    it.copy(
                        messageMatchedChatIds = emptySet(),
                        errorMessage = text(R.string.contacts_search_failed)
                    )
                }
            }
        }
    }

    fun onTabSelected(tab: Int) { _uiState.update { it.copy(selectedTab = tab) } }
    fun setShowArchived(show: Boolean) { _uiState.update { it.copy(showArchived = show) } }
    fun refresh() = requestLoadChats(ChatListReloadPolicy.Trigger.USER_REFRESH)

    fun refreshOnForeground() = requestLoadChats(ChatListReloadPolicy.Trigger.FOREGROUND)

    /**
     * Coalesce bursty WS-driven full list reloads (delete/revoke/group revision).
     * Local preview/unread already update optimistically; getChats is for server truth.
     */
    private var debouncedLoadChatsJob: Job? = null
    private var loadChatsJob: Job? = null
    private var loadChatsRequestId: Long = 0L
    private var disconnectBannerJob: Job? = null

    private fun requestLoadChats(trigger: ChatListReloadPolicy.Trigger) {
        val mode = ChatListReloadPolicy.modeFor(trigger)
        val wait = ChatListReloadPolicy.debounceMs(mode, trigger)
        if (wait > 0L) {
            debouncedLoadChatsJob?.cancel()
            debouncedLoadChatsJob = viewModelScope.launch {
                delay(wait)
                loadChats(showLoading = false)
            }
            return
        }
        debouncedLoadChatsJob?.cancel()
        loadChats(showLoading = ChatListReloadPolicy.shouldShowLoading(mode))
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

    /** Recipient-facing NUDGE copy; stored body is always sender-centric from server. */
    private fun listNudgePreview(
        isOwnMessage: Boolean,
        storedContent: String,
        senderId: String,
        chatId: String,
        chatHint: Chat? = null
    ): String {
        val chat = chatHint ?: _uiState.value.chats.find { it.id == chatId }
        val senderName = listSenderLabel(chat, senderId)
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
            val recent = messageRepo.getRecentMessages(localizedMedia.id, limit = 24)
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
            if (preview.text.isBlank()) {
                return if (localizedMedia.lastMessageType == MessageType.TEXT ||
                    localizedMedia.lastMessageType == MessageType.MARKDOWN
                ) {
                    localizedMedia.copy(
                        lastMessage = ChatListPreviewPolicy.listVisibleText(
                            localizedMedia.lastMessage,
                            text(R.string.message_preview_encrypted)
                        )
                    )
                } else {
                    localizedMedia
                }
            }
            when (preview.type) {
                MessageType.NUDGE -> localizedMedia.copy(
                    lastMessage = preview.text,
                    lastMessageType = preview.type,
                    lastMessageTime = maxOf(localizedMedia.lastMessageTime, preview.timestamp)
                )
                MessageType.TEXT, MessageType.MARKDOWN -> {
                    // Only replace when local has readable plaintext (own send / decrypted).
                    val looksEncrypted = ChatListPreviewPolicy.looksLikeLeftoverPreviewGarbage(preview.text) ||
                        preview.text == text(R.string.message_preview_encrypted)
                    if (looksEncrypted) {
                        localizedMedia.copy(
                            lastMessage = ChatListPreviewPolicy.listVisibleText(
                                localizedMedia.lastMessage,
                                text(R.string.message_preview_encrypted)
                            )
                        )
                    }
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
                val recent = messageRepo.getRecentMessages(chatId, limit = 24)
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
    fun clearRealtimeBanner() {
        disconnectBannerJob?.cancel()
        disconnectBannerJob = null
        _uiState.update { it.copy(realtimeBanner = null) }
    }

    /** Brief flaps reconnect inside BANNER_DELAY_MS; only then surface the down banner. */
    private fun scheduleDisconnectBanner() {
        if (disconnectBannerJob?.isActive == true) return
        disconnectBannerJob = viewModelScope.launch {
            delay(com.maodouchat.network.RealtimeDisconnectPolicy.BANNER_DELAY_MS)
            if (com.maodouchat.network.WebSocketClient.isConnected()) return@launch
            _uiState.update { it.copy(realtimeBanner = text(R.string.chat_ws_connection_failed)) }
        }
    }
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
     * 未读文件夹「全部已读」：本地原子清零，再为每个普通会话写入持久 v2 已读水位。
     * 乐观投影会落 Room，保证列表即时收敛且进程死亡不复活角标。
     */
    fun markAllUnreadChatsRead() {
        val ownerUserId = currentUserIdStr
        if (ownerUserId.isBlank()) return
        val unreadChats = _uiState.value.chats.filter {
            !it.archived &&
                com.maodouchat.util.ChatFolderPolicy.isUnreadChat(it.unreadCount, it.markedUnread)
        }
        if (unreadChats.isEmpty()) return
        val ordinaryUnread = unreadChats.filter { !it.isSecret }
        val secretUnread = unreadChats.filter { it.isSecret }
        val session = ownerSession(ownerUserId)
        _uiState.update { state ->
            state.copy(chats = state.chats.map { chat ->
                if (unreadChats.any { it.id == chat.id }) chat.copy(unreadCount = 0, markedUnread = false) else chat
            })
        }
        viewModelScope.launch {
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
            if (ordinaryUnread.isNotEmpty() && isOwnerSessionCurrent(session)) {
                ordinaryUnread.forEach { chat ->
                    val boundary = messageRepo.getLatestIncomingMessage(chat.id, ownerUserId)
                        ?: return@forEach
                    app.messagingV2Outbox.enqueueReadReceipt(
                        conversationId = chat.id,
                        throughMessageId = boundary.id,
                        groupRevision = chat.memberRevision.takeIf { chat.isGroup },
                    )
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

    /** Batch local read projection plus one durable v2 read watermark per conversation. */
    fun batchMarkReadSelected() {
        val selected = _uiState.value.selectedChatIds
        if (selected.isEmpty()) return
        val ownerUserId = currentUserIdStr
        if (ownerUserId.isBlank()) return
        val chatsById = _uiState.value.chats.associateBy { it.id }
        val toRead = selected.mapNotNull { chatsById[it] }
            .filter { com.maodouchat.util.ChatFolderPolicy.isUnreadChat(it.unreadCount, it.markedUnread) }
        if (toRead.isEmpty()) return
        val ordinary = toRead.filter { !it.isSecret }
        val secret = toRead.filter { it.isSecret }
        val session = ownerSession(ownerUserId)
        val allReadIds = toRead.map { it.id }.toSet()
        _uiState.update { state ->
            state.copy(chats = state.chats.map { chat ->
                if (chat.id in allReadIds) chat.copy(unreadCount = 0, markedUnread = false) else chat
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
            if (ordinary.isNotEmpty() && isOwnerSessionCurrent(session)) {
                ordinary.forEach { chat ->
                    val boundary = messageRepo.getLatestIncomingMessage(chat.id, ownerUserId)
                        ?: return@forEach
                    app.messagingV2Outbox.enqueueReadReceipt(
                        conversationId = chat.id,
                        throughMessageId = boundary.id,
                        groupRevision = chat.memberRevision.takeIf { chat.isGroup },
                    )
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
        val cleanupSession = conversationLocalCleanupSession(deleteOwnerUserId)
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
                withContext(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    cleanupLocalChat(chatId, cleanupSession)
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

    private suspend fun cleanupLocalChat(
        chatId: String,
        cleanupSession: ConversationLocalCleanupSession,
    ) {
        val report = conversationLocalStateCoordinator.cleanup(
            chatId = chatId,
            expectedSession = cleanupSession,
            mode = ConversationLocalCleanupMode.DELETE_CONVERSATION,
        )
        report.failures.forEach { failure ->
            android.util.Log.w(
                "ChatListViewModel",
                "conversation deletion cleanup failed at ${failure.step} for $chatId",
                failure.error,
            )
        }
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
            val secret = app.database.chatDao().isSecretChat(chatId) &&
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
                    is WebSocketEvent.GroupRevisionChanged -> {
                        // Membership bursts (join/leave/kick) often arrive in clusters.
                        requestLoadChats(ChatListReloadPolicy.Trigger.GROUP_REVISION)
                    }
                    is WebSocketEvent.Connected -> {
                        if (event.success) {
                            disconnectBannerJob?.cancel()
                            disconnectBannerJob = null
                            _uiState.update { it.copy(realtimeBanner = null) }
                            // Immediate silent: keep previous rows, don't flash isLoading.
                            requestLoadChats(ChatListReloadPolicy.Trigger.RECONNECT)
                            // 9.3xx：断线窗口补拉（Ideaura 式）——重连后立即同步各会话增量，
                            // 否则断线期间的消息要等 15 分钟周期任务或手动打开聊天才出现。
                            runCatching {
                                com.maodouchat.sync.BacklogSyncWorker.requestNow(getApplication())
                            }
                        } else {
                            scheduleDisconnectBanner()
                        }
                    }
                    is WebSocketEvent.Disconnected -> {
                        scheduleDisconnectBanner()
                    }
                    is WebSocketEvent.Error -> {
                        // Soft banner only — list remains usable offline from Room.
                        if (event.kind == com.maodouchat.network.WebSocketErrorKind.CONNECTION) {
                            scheduleDisconnectBanner()
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
        val loadCleanupSession = conversationLocalCleanupSession(loadOwnerUserId)
        if (showLoading) {
            // Keep the previous list; only the first empty load shows shimmer.
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        } else {
            // 静默刷新会取消进行中的可见加载；必须顺带关掉 shimmer，否则空白列表卡死。
            _uiState.update { it.copy(isLoading = false) }
        }
        loadChatsJob = viewModelScope.launch {
            fun stillCurrent(): Boolean = requestId == loadChatsRequestId &&
                com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = loadOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            fun finishIfCurrent(errorMessage: String? = null, chats: List<Chat>? = null) {
                if (requestId != loadChatsRequestId) return
                _uiState.update { state ->
                    state.copy(
                        chats = chats ?: state.chats,
                        isLoading = false,
                        errorMessage = errorMessage
                    )
                }
            }
            try {
                if (token.isBlank() || loadOwnerUserId.isBlank()) {
                    // 无 Token，从本地加载（只取一次）；空缓存时提示会话过期，避免“空白列表无反馈”。
                    val chats = chatRepo.getAllChats().firstOrNull() ?: emptyList()
                    if (requestId != loadChatsRequestId ||
                        tokenManager.getUserId().orEmpty() != loadOwnerUserId ||
                        !tokenManager.getToken().isNullOrBlank()
                    ) {
                        finishIfCurrent()
                        return@launch
                    }
                    _uiState.update {
                        it.copy(
                            chats = chats,
                            isLoading = false,
                            errorMessage = if (chats.isEmpty()) text(R.string.error_session_expired) else null
                        )
                    }
                    return@launch
                }

                if (requestId != loadChatsRequestId) {
                    return@launch
                }
                if (!stillCurrent()) {
                    finishIfCurrent(errorMessage = text(R.string.error_session_expired))
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
                        if (requestId != loadChatsRequestId) return@fold
                        if (!stillCurrent()) {
                            finishIfCurrent(errorMessage = text(R.string.error_session_expired))
                            return@fold
                        }
                        val currentUserId = tokenManager.getUserId().orEmpty()
                        // Server NUDGE lastMessage is generic ("[提醒]"); rewrite from local Room POV when possible.
                        val localById = chatRepo.getAllChats().firstOrNull().orEmpty().associateBy { it.id }
                        val uiById = _uiState.value.chats.associateBy { it.id }
                        val activeId = com.maodouchat.MaodouchatApp.activeChatId
                            ?: com.maodouchat.MaodouchatApp.openChatDetailId
                        val chats = chatDtos.map { dto ->
                            val participants = dto.participants.map { User(it.id, it.name, it.avatar, it.email, it.isOnline, it.status) }
                            // Prefer in-memory UI (optimistic read/unread) over Room: a lagged
                            // cache row must not resurrect a badge the list already cleared, nor
                            // wipe a badge the list just incremented before Room caught up.
                            val local = uiById[dto.id] ?: localById[dto.id]
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
                                chatType = dto.chatType,
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
                        if (requestId != loadChatsRequestId) return@fold
                        if (!stillCurrent()) {
                            finishIfCurrent(errorMessage = text(R.string.error_session_expired))
                            return@fold
                        }
                        // 服务端列表已到手：过期会话清理 + 缓存 + 列表 UI 收敛必须跑完，
                        // 避免 cancel 留下「半清本地 / UI 仍显示幽灵会话 / isLoading 卡死」
                        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                            // BUG 2.1 fix: cleanup 可能抛异常，包裹 try-catch 确保 isLoading 总能被清除
                            try {
                                for (staleId in staleChatIds) {
                                    if (requestId != loadChatsRequestId) return@withContext
                                    if (!stillCurrent()) {
                                        finishIfCurrent(errorMessage = text(R.string.error_session_expired))
                                        return@withContext
                                    }
                                    cleanupLocalChat(staleId, loadCleanupSession)
                                }
                                if (requestId != loadChatsRequestId) return@withContext
                                if (!stillCurrent()) {
                                    finishIfCurrent(errorMessage = text(R.string.error_session_expired))
                                    return@withContext
                                }
                                // 8.49 修复：写库与 UI 一律使用 filteredChats——此前用未过滤的 chats，
                                // 刚被 stale 清理删掉的本地行又被插回 Room，幽灵会话+角标复活
                                chatRepo.cacheChats(filteredChats)
                            } catch (cleanupError: kotlinx.coroutines.CancellationException) {
                                if (requestId == loadChatsRequestId) finishIfCurrent()
                                throw cleanupError
                            } catch (cleanupError: Exception) {
                                android.util.Log.w("ChatListViewModel", "Chat cleanup failed", cleanupError)
                            }
                            if (requestId != loadChatsRequestId) return@withContext
                            if (!stillCurrent()) {
                                finishIfCurrent(errorMessage = text(R.string.error_session_expired))
                                return@withContext
                            }
                            // cacheChats 合并本地备注后，从 Room 回读标题用 displayName
                            val nickMerged = filteredChats.map { c ->
                                chatRepo.getChatById(c.id) ?: c
                            }
                            if (requestId != loadChatsRequestId) return@withContext
                            if (!stillCurrent()) {
                                finishIfCurrent(errorMessage = text(R.string.error_session_expired))
                                return@withContext
                            }
                            _uiState.update {
                                it.copy(
                                    chats = nickMerged,
                                    isLoading = false,
                                    errorMessage = null,
                                    secretChatIds = nickMerged.filter { chat -> chat.isSecret }.map { chat -> chat.id }.toSet()
                                )
                            }
                            refreshIdentityWarnings()
                        }
                    },
                    onFailure = { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        if (requestId != loadChatsRequestId) return@fold
                        if (!stillCurrent()) {
                            finishIfCurrent(errorMessage = text(R.string.error_session_expired))
                            return@fold
                        }
                        // API 失败，从本地加载
                        val chats = chatRepo.getAllChats().firstOrNull() ?: emptyList()
                        if (requestId != loadChatsRequestId) return@fold
                        val rateLimited = (error as? com.maodouchat.network.ApiException)?.statusCode == 429 ||
                            error.message.orEmpty().contains("频繁")
                        // Silent reconnect/foreground must not toast 429 / 频繁 over a populated list.
                        val nextError = when {
                            !showLoading || rateLimited -> _uiState.value.errorMessage
                            else -> error.message?.takeIf { message -> message.isNotBlank() }
                                ?: text(R.string.chat_refresh_failed_cached)
                        }
                        finishIfCurrent(errorMessage = nextError, chats = chats)
                        refreshIdentityWarnings()
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                // 被更新的 loadChats 取代时不要清 isLoading，以免和胜者抢状态；
                // 若当前 request 仍是自己（VM 清空 / 无继任者），必须关掉 shimmer，否则空白卡死。
                if (requestId == loadChatsRequestId) finishIfCurrent()
                throw error
            } catch (error: Exception) {
                android.util.Log.w("ChatListViewModel", "loadChats failed", error)
                if (requestId != loadChatsRequestId) throw error
                val chats = runCatching { chatRepo.getAllChats().firstOrNull() ?: emptyList() }
                    .getOrDefault(emptyList())
                finishIfCurrent(
                    errorMessage = error.message?.takeIf { message -> message.isNotBlank() }
                        ?: text(R.string.chat_refresh_failed_cached),
                    chats = chats
                )
            }
        }
    }

    private companion object {
        /** Coalesce list-bar message-content LIKE while the user is still typing. */
        const val LIST_MESSAGE_SEARCH_DEBOUNCE_MS: Long = 250L
        /** 8.52 UX：列表搜索框长度上限。 */
        const val LIST_SEARCH_MAX_LENGTH: Int = 200
    }
}
