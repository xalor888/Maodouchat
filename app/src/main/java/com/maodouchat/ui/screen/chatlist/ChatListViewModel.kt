package com.maodouchat.ui.screen.chatlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.chatlist.AndroidChatListPorts
import com.maodouchat.chatlist.ChatListPorts
import com.maodouchat.conversation.conversationLocalCleanupSession
import com.maodouchat.data.model.Chat
import com.maodouchat.ui.OwnerSessionPolicy
import com.maodouchat.ui.OwnerSessionSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * G319c：主构造器恢复为 `private`。
 *
 * G317c 我曾把它放宽为 `internal`，理由是「UI 测试碰不到这个收
 * `ChatListPorts` 的构造器，所以 `ChatListScreen` 测不了」。
 * **该理由不成立**：`ChatListScreenUiTest`（G319c）用**公开**构造器
 * `ChatListViewModel(application)` 就能渲染并断言——它内部走
 * `AndroidChatListPorts.create(application)`，在仪器测试里
 * `application as MaodouchatApp` 成立，真实 Room 库可用。
 *
 * 而这个 `internal` **没有任何外部使用者**（唯一的 2 参调用者是本类
 * 第 588 行的内部工厂，`private` 本就可达）。按「无使用者就回退」，
 * 恢复 `private`，以免留下一个没有需求的加宽 API。
 *
 * 若将来要覆盖「指定会话数据下的 UI」，仍需要这个接缝（或把 43 参数的
 * `ChatListPorts` 按内聚分组）——那时再有理由加宽。
 */
class ChatListViewModel private constructor(
    application: Application,
    private val ports: ChatListPorts,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        AndroidChatListPorts.create(application),
    )

    private val chatRepo = ports.chatRepository
    private val messageRepo = ports.messageStore
    private val missedRepo = ports.missedCallRepository
    private val conversationScheduleCoordinator = ports.scheduleCoordinator
    private val conversationLocalStateCoordinator = ports.conversationLocalStateCoordinator
    private val notificationRepo = ports.notificationCenter

    private fun text(id: Int): String = getApplication<Application>().getString(id)

    // 显式类型：断开与 loadCoordinator 的初始化类型互推断（CI 曾报 recursive problem）。
    private val localCleanupCoordinator: ChatListLocalCleanupCoordinator = ChatListLocalCleanupCoordinator(
        scope = viewModelScope,
        ownerUserId = { currentUserIdStr },
        localStateCoordinator = conversationLocalStateCoordinator,
        deleteDraftForChat = ports.deleteDraftForChat,
        reloadChats = { loadCoordinator.loadChats(showLoading = false) },
    )

    /** 清空指定会话的本地明文（保留会话/PIN/草稿/同步游标）。不清游标，避免重拉密文 Duplicate。 */
    fun clearLocalChatHistory(chatId: String) =
        localCleanupCoordinator.clearLocalChatHistory(chatId)

    private val _uiState = MutableStateFlow(ChatListUiState())
    val uiState: StateFlow<ChatListUiState> = _uiState.asStateFlow()

    private val badgeCoordinator = ChatListBadgeCoordinator(
        scope = viewModelScope,
        uiState = _uiState,
        notificationCenter = notificationRepo,
    )

    val notificationCenterUnread: StateFlow<Int> = badgeCoordinator.notificationCenterUnread
    val exploreBadgeCount: StateFlow<Int> = badgeCoordinator.exploreBadgeCount

    private val folderController = ChatFolderController(
        context = application,
        scope = viewModelScope,
        uiState = _uiState,
    )

    /**
     * 本 VM 生命周期内已成功删除（退出服务端）的会话 id。
     * 用于防止删除竞态：删除先乐观从 UI 移除，但服务端 leave 尚未生效时同步快照仍可能
     * 把会话重新带回。命中本集合的会话在合并时丢弃，并从本地清理。
     */
    private val deletedChatIds = mutableSetOf<String>()

    private val previewCoordinator = ChatListPreviewCoordinator(
        scope = viewModelScope,
        uiState = _uiState,
        currentUserId = { currentUserIdStr },
        currentSessionGeneration = ports.sessionGeneration,
        isOwnerSessionCurrent = { isOwnerSessionCurrent(it) },
        withOwnerRoomWrite = { session, block -> withOwnerRoomWrite(session, block) },
        getCachedChat = { chatRepo.getChatById(it) },
        cacheChats = { chatRepo.cacheChats(it) },
        getRecentMessages = { chatId, limit -> messageRepo.getRecentMessages(chatId, limit) },
        text = { text(it) },
        nudgeYouNudged = { target -> getApplication<Application>().getString(R.string.chat_nudge_you_nudged, target) },
        nudgeTheyNudgedYou = { sender ->
            getApplication<Application>().getString(R.string.chat_nudge_they_nudged_you, sender)
        },
        nudgeTheyNudgedTarget = { sender, target ->
            getApplication<Application>().getString(R.string.chat_nudge_they_nudged_target, sender, target)
        },
    )

    private val loadCoordinator = ChatListLoadCoordinator(
        scope = viewModelScope,
        uiState = _uiState,
        deletedChatIds = deletedChatIds,
        ownerUserId = { currentUserIdStr },
        getAllChats = { chatRepo.getAllChats() },
        getChatById = { chatRepo.getChatById(it) },
        cacheChats = { chatRepo.cacheChats(it) },
        fetchRemoteChats = ports.fetchRemoteChats,
        enrichServerChatPreview = { chat, ownerUserId ->
            previewCoordinator.enrichServerChatPreview(chat, ownerUserId)
        },
        cleanupLocalChat = { chatId, session -> localCleanupCoordinator.cleanupLocalChat(chatId, session) },
        cleanupSessionFor = { conversationLocalCleanupSession(it) },
        activeChatId = ports.activeChatId,
        observeMissedCalls = { missedRepo.observeRecent() },
        trimMissedCalls = { missedRepo.trimToRetention() },
        text = { text(it) },
        onRefreshIdentityWarnings = { localProjectionCoordinator.refreshIdentityWarnings() },
        sessionExpiredMessageRes = R.string.error_session_expired,
        refreshFailedCachedMessageRes = R.string.chat_refresh_failed_cached,
    )

    // 设置开关（置顶/静音/归档/标未读）入口级重入保护：防止同帧连点发出方向相反的两笔请求
    private val settingsInFlight = mutableSetOf<String>()

    private val announcementCoordinator = ChatListAnnouncementCoordinator(
        scope = viewModelScope,
        uiState = _uiState,
        fetchActiveAnnouncements = ports.fetchActiveAnnouncements,
        ackAnnouncementRemote = ports.ackAnnouncementRemote,
        fetchPushVerifyKeyRaw = ports.fetchPushVerifyKeyRaw,
        applyPushVerifyKey = ports.applyPushVerifyKey,
    )

    private val mutationCoordinator = ChatListMutationCoordinator(
        scope = viewModelScope,
        uiState = _uiState,
        deletedChatIds = deletedChatIds,
        settingsInFlight = settingsInFlight,
        ownerUserId = { currentUserIdStr },
        cacheChats = { chatRepo.cacheChats(it) },
        updateChatSettingsRemote = ports.updateChatSettingsRemote,
        deleteChatRemote = ports.deleteChatRemote,
        createChatRemote = ports.createChatRemote,
        touchSecretChat = ports.touchSecretChat,
        cleanupLocalChat = { chatId, session -> localCleanupCoordinator.cleanupLocalChat(chatId, session) },
        cleanupSessionFor = { conversationLocalCleanupSession(it) },
        onMuteApplied = { chatId ->
            try {
                ports.cancelMessageNotification(chatId)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
            }
            try {
                ports.markChatMessagesRead(chatId)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        },
        text = { text(it) },
        secretChatFeatureEnabled = ports.secretChatFeatureEnabled,
        secretChatDisabledMessage = ports.secretChatDisabledMessage,
        secretChatStartFailedMessage = ports.secretChatStartFailedMessage,
    )

    private val settingsToggleCoordinator = ChatListSettingsToggleCoordinator(
        uiState = _uiState,
        isFlagEnabled = ports.isFlagEnabled,
        text = { text(it) },
        updateChatSettings = mutationCoordinator::updateChatSettings,
    )

    private val realtimeCoordinator = ChatListRealtimeCoordinator(
        scope = viewModelScope,
        uiState = _uiState,
        realtimeEventDispatcher = ports.realtimeEventDispatcher,
        notificationCenter = notificationRepo,
        chatReadEvents = ports.chatReadEvents,
        chatMessageSentEvents = ports.chatMessageSentEvents,
        ownerUserId = { currentUserIdStr },
        ownerSession = { ownerSession() },
        isOwnerSessionCurrent = { isOwnerSessionCurrent(it) },
        withOwnerRoomWrite = { session, block -> withOwnerRoomWrite(session, block) },
        getCachedChat = { chatRepo.getChatById(it) },
        cacheChats = { chatRepo.cacheChats(it) },
        applyRealtimeVisibility = ports.applyRealtimeVisibility,
        text = { text(it) },
        onRequestLoadChats = { loadCoordinator.requestLoadChats(it) },
        onApplyChatListPreview = { chatId, previewText, messageType, timestamp, ownerUserId, sessionGeneration ->
            previewCoordinator.applyChatListPreview(
                chatId = chatId,
                previewText = previewText,
                messageType = messageType,
                timestamp = timestamp,
                ownerUserId = ownerUserId,
                sessionGeneration = sessionGeneration,
            )
        },
        onRefreshPreviewFromLocal = { chatId, ownerUserId, sessionGeneration ->
            previewCoordinator.refreshChatListPreviewFromLocal(chatId, ownerUserId, sessionGeneration)
        },
        onClearMarkedUnreadAfterOpen = { mutationCoordinator.clearMarkedUnreadAfterOpen(it) },
        onRequestBacklogSync = ports.requestBacklogSync,
    )

    private val missedCallCoordinator = ChatListMissedCallCoordinator(
        scope = viewModelScope,
        uiState = _uiState,
        ownerUserId = { currentUserIdStr },
        markAllRead = { missedRepo.markAllRead() },
        clearAll = { missedRepo.clearAll() },
        deleteCall = { missedRepo.delete(it) },
        cancelMissedCallNotification = ports.cancelMissedCallNotification,
        removeCenterItem = { id -> notificationRepo.remove(id) },
    )

    private val archiveSuggestionCoordinator = ChatListArchiveSuggestionCoordinator(
        scope = viewModelScope,
        uiState = _uiState,
        ownerUserId = { currentUserIdStr },
        ownerSession = { ownerSession(it) },
        isOwnerSessionCurrent = { isOwnerSessionCurrent(it) },
        loadDismissedIds = ports.loadDismissedArchiveIds,
        addDismissal = ports.addArchiveDismissal,
        refreshSuggestions = ports.refreshArchiveSuggestions,
        onArchiveChat = { chat -> toggleArchived(chat.id) },
    )

    private val unreadBatchCoordinator = ChatListUnreadBatchCoordinator(
        scope = viewModelScope,
        uiState = _uiState,
        ownerUserId = { currentUserIdStr },
        ownerSession = { ownerSession(it) },
        isOwnerSessionCurrent = { isOwnerSessionCurrent(it) },
        withOwnerRoomWrite = { session, block -> withOwnerRoomWrite(session, block) },
        getCachedChat = { chatRepo.getChatById(it) },
        cacheChats = { chatRepo.cacheChats(it) },
        getLatestIncomingMessage = { chatId, owner ->
            messageRepo.getLatestIncomingMessage(chatId, owner)
        },
        enqueueReadReceipt = ports.enqueueReadReceipt,
        cancelMessageNotification = ports.cancelMessageNotification,
    )

    private val selectionCoordinator = ChatListSelectionCoordinator(
        uiState = _uiState,
        togglePinned = ::togglePinned,
        deleteChat = ::deleteChat,
    )

    private val localProjectionCoordinator = ChatListLocalProjectionCoordinator(
        scope = viewModelScope,
        uiState = _uiState,
        ownerUserId = { currentUserIdStr },
        observeDraftsForOwner = ports.observeDraftsForOwner,
        getRecentMessages = { chatId, limit -> messageRepo.getRecentMessages(chatId, limit) },
        listLockedChatIds = ports.listLockedChatIds,
        searchChatIdsByMessageContent = ports.searchChatIdsByMessageContent,
        listSecretChatIds = ports.listSecretChatIds,
        trustChangedRemoteIds = ports.trustChangedRemoteIds,
        text = { text(it) },
        listScheduled = { conversationScheduleCoordinator.listAllScheduled() },
    )

    private fun ownerSession(ownerUserId: String = currentUserIdStr): OwnerSessionSnapshot =
        OwnerSessionSnapshot(ownerUserId, ports.sessionGeneration())

    private fun isOwnerSessionCurrent(session: OwnerSessionSnapshot): Boolean =
        OwnerSessionPolicy.isCurrent(
            snapshot = session,
            liveUserId = com.maodouchat.session.CurrentSession.snapshot().userId,
            liveToken = com.maodouchat.session.CurrentSession.snapshot().token,
            liveSessionGeneration = ports.sessionGeneration(),
            purgeInProgress = ports.isPurgeInProgress(),
        )

    private suspend fun withOwnerRoomWrite(
        session: OwnerSessionSnapshot,
        block: suspend () -> Unit,
    ): Boolean = ports.withRoomTransaction {
        if (!isOwnerSessionCurrent(session)) {
            false
        } else {
            block()
            true
        }
    }

    init {
        folderController.loadFolders()
        folderController.loadUnreadPriority()
        localProjectionCoordinator.refreshLockedChats()
        localProjectionCoordinator.refreshSecretChats()
        loadCoordinator.requestLoadChats(ChatListReloadPolicy.Trigger.INITIAL)
        localProjectionCoordinator.start()
        realtimeCoordinator.start()
        loadCoordinator.startMissedCallObservation()
        refreshAnnouncements()
        fetchPushVerifyKey()
        archiveSuggestionCoordinator.start()
        // 1.146：会话列表显示待发送定时消息数（本地 store，prefs 非流式 → 按需刷新）
        refreshScheduledCounts()
        // 8.47：智能归档建议（纯本地，全库扫描较重）——延迟到主页稳定后一次性计算
        viewModelScope.launch {
            delay(3_000L)
            archiveSuggestionCoordinator.loadArchiveSuggestions()
        }
        // 角标订阅（未读汇总/Explore/typing presence）与会话重建后归零
        badgeCoordinator.start()
    }

    /** 重算智能归档建议（纯本地 SQLCipher 打分，无服务端调用）。 */
    fun loadArchiveSuggestions() = archiveSuggestionCoordinator.loadArchiveSuggestions()

    /** 忽略单条归档建议（持久化，进程重启后不重现）。 */
    fun dismissArchiveSuggestion(chatId: String) =
        archiveSuggestionCoordinator.dismissArchiveSuggestion(chatId)

    /** 忽略全部归档建议（持久化）。 */
    fun dismissAllArchiveSuggestions() =
        archiveSuggestionCoordinator.dismissAllArchiveSuggestions()

    /** 采纳智能归档建议：归档会话（复用 toggleArchived 服务端同步）并移除建议。 */
    fun archiveChatFromSuggestion(chat: Chat) =
        archiveSuggestionCoordinator.archiveChatFromSuggestion(chat)

    /** 经认证通道拉取推送 HMAC 校验密钥（P0 修复后续：密钥不再匿名暴露于 status 端点）。 */
    private fun fetchPushVerifyKey() = announcementCoordinator.fetchPushVerifyKey()

    fun refreshLockedChats() = localProjectionCoordinator.refreshLockedChats()

    fun refreshSecretChats() = localProjectionCoordinator.refreshSecretChats()

    fun clearCreatedSecretChat() {
        _uiState.update { it.copy(createdSecretChatId = null) }
    }

    fun startSecretChatWithPeer(peerId: String) =
        mutationCoordinator.startSecretChatWithPeer(peerId)

    /** 拉取服务端活跃公告 → 本地过滤（未读 + 生效窗口 + 合法级别）→ 展示列表。 */
    fun refreshAnnouncements() = announcementCoordinator.refreshAnnouncements()

    /** 公告已读确认：调服务端 ack 并从本地展示列表移除（inFlight 防重入）。 */
    fun ackAnnouncement(announcementId: String) =
        announcementCoordinator.ackAnnouncement(announcementId)

    fun refreshUnreadPriorityPreference() = folderController.refreshUnreadPriorityPreference()

    fun setUnreadPriorityEnabled(enabled: Boolean) = folderController.setUnreadPriorityEnabled(enabled)

    fun selectFolder(folderId: String?) = folderController.selectFolder(folderId)

    fun createFolder(name: String): Boolean = folderController.createFolder(name)

    fun renameFolder(folderId: String, name: String): Boolean = folderController.renameFolder(folderId, name)

    fun deleteFolder(folderId: String) = folderController.deleteFolder(folderId)

    fun moveFolder(folderId: String, delta: Int): Boolean = folderController.moveFolder(folderId, delta)

    fun reorderFolder(folderId: String, targetIndex: Int): Boolean = folderController.reorderFolder(folderId, targetIndex)

    fun moveChatToFolder(chatId: String, folderId: String?) = folderController.moveChatToFolder(chatId, folderId)
    fun markMissedCallsRead() = missedCallCoordinator.markMissedCallsRead()

    fun clearMissedCalls() = missedCallCoordinator.clearMissedCalls()

    /**
     * Resolve local 1:1 chat id for a missed-call peer (if a conversation already exists).
     */
    fun findDirectChatIdForUser(userId: String): String? =
        missedCallCoordinator.findDirectChatIdForUser(userId)

    /**
     * 1.289：从会话列表移除单条通话记录。
     * 同步删除 Room missed_calls（保持角标一致）+ 更新本地 state（弹窗即时消失）。
     * CallLogStore 已由调用方删除。
     */
    fun removeMissedCallLocally(callId: String) =
        missedCallCoordinator.removeMissedCallLocally(callId)

    private val currentUserIdStr: String get() = com.maodouchat.session.CurrentSession.snapshot().userId ?: ""

    fun refreshScheduledCounts() = localProjectionCoordinator.refreshScheduledCounts()

    fun onSearchQueryChange(query: String) = localProjectionCoordinator.onSearchQueryChange(query)

    fun onTabSelected(tab: Int) { _uiState.update { it.copy(selectedTab = tab) } }
    fun setShowArchived(show: Boolean) { _uiState.update { it.copy(showArchived = show) } }
    fun refresh() = loadCoordinator.requestLoadChats(ChatListReloadPolicy.Trigger.USER_REFRESH)

    fun refreshOnForeground() = loadCoordinator.requestLoadChats(ChatListReloadPolicy.Trigger.FOREGROUND)

    fun clearError() { _uiState.update { it.copy(errorMessage = null) } }
    fun clearRealtimeBanner() = realtimeCoordinator.clearRealtimeBanner()
    fun clearOwnerTransferRequired() { _uiState.update { it.copy(ownerTransferRequiredChatId = null) } }

    fun togglePinned(chatId: String) = settingsToggleCoordinator.togglePinned(chatId)

    fun toggleNotificationsMuted(chatId: String) =
        settingsToggleCoordinator.toggleNotificationsMuted(chatId)

    fun toggleArchived(chatId: String) = settingsToggleCoordinator.toggleArchived(chatId)

    fun toggleMarkedUnread(chatId: String) =
        settingsToggleCoordinator.toggleMarkedUnread(chatId)

    /**
     * 未读文件夹「全部已读」：本地原子清零，再为每个普通会话写入持久 v2 已读水位。
     * 乐观投影会落 Room，保证列表即时收敛且进程死亡不复活角标。
     */
    fun markAllUnreadChatsRead() = unreadBatchCoordinator.markAllUnreadChatsRead()

    fun enterSelectionMode() = selectionCoordinator.enterSelectionMode()

    fun exitSelectionMode() = selectionCoordinator.exitSelectionMode()

    fun toggleSelectChat(chatId: String) = selectionCoordinator.toggleSelectChat(chatId)

    fun batchTogglePinSelected() = selectionCoordinator.batchTogglePinSelected()

    /** Batch local read projection plus one durable v2 read watermark per conversation. */
    fun batchMarkReadSelected() = unreadBatchCoordinator.batchMarkReadSelected()

    fun batchDeleteSelected() = selectionCoordinator.batchDeleteSelected()

    /**
     * 删除聊天（同时退出服务端聊天 + 清理本地缓存）
     * - 调用服务端 DELETE /api/chats/{chatId} 退出聊天
     * - 删除本地缓存的消息和聊天记录
     * - 从 UI 列表中移除
     */
    fun deleteChat(chatId: String) = mutationCoordinator.deleteChat(chatId)

    /** 1.142：会话列表长按菜单「清除草稿」（本地，不打开会话）。 */
    fun clearChatDraft(chatId: String) = localCleanupCoordinator.clearChatDraft(chatId)

    companion object {
        /** Same-module test / DI seam — production uses the Application constructor. */
        internal fun createForTest(
            application: Application,
            ports: ChatListPorts,
        ): ChatListViewModel = ChatListViewModel(application, ports)
    }
}
