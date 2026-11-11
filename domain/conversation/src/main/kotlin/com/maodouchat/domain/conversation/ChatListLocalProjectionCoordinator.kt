package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatListReceiptPolicy
import com.maodouchat.core.model.LikeQueryPolicy
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.Receipt
import com.maodouchat.core.model.ScheduledMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 会话列表本地投影（草稿 / 回执 / 锁定密聊 / 消息搜索）。
 *
 * 端口化：列表状态经 getChats/chatsFlow 与各 update 端口读写，草稿走纯模型
 * ChatDraft（Room 实体在 app 侧调用点映射），会话门禁、文案与日志走端口。
 */
class ChatListLocalProjectionCoordinator(
    private val scope: CoroutineScope,
    private val getChats: () -> List<Chat>,
    private val chatsFlow: Flow<List<Chat>>,
    private val getIdentityChangedUserIds: () -> Set<String>,
    private val updateIdentityChangedUserIds: (Set<String>) -> Unit,
    private val updateLockedChatIds: (Set<String>) -> Unit,
    private val updateSecretChatIds: (Set<String>) -> Unit,
    private val updateScheduledByChat: (Map<String, Int>) -> Unit,
    private val updateSelectedTab: (Int) -> Unit,
    private val updateShowArchived: (Boolean) -> Unit,
    private val updateErrorMessage: (String?) -> Unit,
    private val updateOwnerTransferRequiredChatId: (String?) -> Unit,
    private val getSearchQuery: () -> String,
    private val updateSearchQuery: (String) -> Unit,
    private val updateMessageMatchedChatIds: (Set<String>) -> Unit,
    private val updateDrafts: (Map<String, ChatDraft>) -> Unit,
    private val updateReceiptsByChat: (Map<String, Receipt>) -> Unit,
    private val ownerUserId: () -> String,
    private val observeDraftsForOwner: (ownerUserId: String) -> Flow<List<ChatDraft>>,
    private val getRecentMessages: suspend (chatId: String, limit: Int) -> List<Message>,
    private val listLockedChatIds: suspend () -> Set<String>,
    private val searchChatIdsByMessageContent: suspend (escaped: String) -> List<String>,
    private val listSecretChatIds: suspend () -> Set<String>,
    private val trustChangedRemoteIds: suspend (ownerUserId: String, remoteIds: Set<String>) -> Set<String>,
    private val searchFailedText: () -> String,
    private val logWarning: (String, Throwable?) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val searchDebounceMs: Long = LIST_MESSAGE_SEARCH_DEBOUNCE_MS,
    private val searchMaxLength: Int = LIST_SEARCH_MAX_LENGTH,
    private val listScheduled: () -> List<ScheduledMessage> = { emptyList() },
) {
    private var messageSearchJob: Job? = null

    fun start() {
        observeDrafts()
        observeReceipts()
    }

    /** 1.165：扫描本地 identity_trust，标记身份密钥已变更（CHANGED）的对端用户（纯本地，无网络请求）。 */
    fun refreshIdentityWarnings() {
        val owner = ownerUserId()
        if (owner.isBlank() || owner == "me") return
        val remoteIds = getChats()
            .filter { !it.isGroup }
            .mapNotNull { chat -> chat.participants.firstOrNull { it.id != owner }?.id }
            .toSet()
        if (remoteIds.isEmpty()) return
        scope.launch(ioDispatcher) {
            val changed = try {
                trustChangedRemoteIds(owner, remoteIds)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptySet()
            }
            if (ownerUserId() != owner) return@launch
            if (changed != getIdentityChangedUserIds()) {
                updateIdentityChangedUserIds(changed)
            }
        }
    }

    fun refreshLockedChats() {
        val owner = ownerUserId()
        if (owner.isBlank()) return
        scope.launch(ioDispatcher) {
            val ids = try {
                listLockedChatIds()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptySet()
            }
            if (ownerUserId() != owner) return@launch
            updateLockedChatIds(ids)
        }
    }

    fun refreshSecretChats() {
        val owner = ownerUserId()
        if (owner.isBlank()) return
        scope.launch(ioDispatcher) {
            val ids = getChats().filter { it.isSecret }.map { it.id }.toSet()
            if (ownerUserId() != owner) return@launch
            updateSecretChatIds(ids)
        }
    }

    /** 1.146：刷新各会话待发送定时消息数（本地 prefs store，非流式 → 按需刷新）。 */
    fun refreshScheduledCounts() {
        val counts = try {
            listScheduled()
                .groupingBy { it.chatId }
                .eachCount()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyMap()
        }
        updateScheduledByChat(counts)
    }

    fun onTabSelected(tab: Int) { updateSelectedTab(tab) }

    fun setShowArchived(show: Boolean) { updateShowArchived(show) }

    fun clearError() { updateErrorMessage(null) }

    fun clearOwnerTransferRequired() { updateOwnerTransferRequiredChatId(null) }

    fun onSearchQueryChange(query: String) {
        // 8.52 UX：列表搜索框长度上限（对齐其它搜索框，防超长 LIKE 查询）
        val clipped = if (query.length > searchMaxLength) query.take(searchMaxLength) else query
        updateSearchQuery(clipped)
        messageSearchJob?.cancel()
        if (clipped.isBlank() || clipped.length < 2) {
            updateMessageMatchedChatIds(emptySet())
            return
        }
        // Debounce + single-flight: rapid typing must not apply a slower older LIKE result.
        messageSearchJob = scope.launch {
            delay(searchDebounceMs)
            val searchOwnerUserId = ownerUserId()
            if (searchOwnerUserId.isBlank()) {
                updateMessageMatchedChatIds(emptySet())
                return@launch
            }
            // 9.156：转义与陈旧比对统一使用 clipped
            val escaped = LikeQueryPolicy.escapeForContains(clipped)
            if (escaped.isBlank()) {
                updateMessageMatchedChatIds(emptySet())
                return@launch
            }
            try {
                val matchedIds = withContext(ioDispatcher) {
                    val locked = listLockedChatIds()
                    val secret = listSecretChatIds()
                    searchChatIdsByMessageContent(escaped)
                        .filterNot { it in locked || it in secret }
                }
                // Drop if user kept typing past this snapshot or account switched mid-search.
                if (getSearchQuery() != clipped) return@launch
                if (ownerUserId() != searchOwnerUserId) return@launch
                updateMessageMatchedChatIds(matchedIds.toSet())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (getSearchQuery() != clipped) return@launch
                if (ownerUserId() != searchOwnerUserId) return@launch
                logWarning("list message search failed", error)
                updateMessageMatchedChatIds(emptySet())
                updateErrorMessage(searchFailedText())
            }
        }
    }

    private fun observeDrafts() {
        val owner = ownerUserId()
        if (owner.isBlank()) return
        scope.launch {
            observeDraftsForOwner(owner).collect { drafts ->
                // Drop if process-local session switched while Flow was still open.
                if (ownerUserId() != owner) return@collect
                updateDrafts(drafts.associateBy(ChatDraft::chatId))
            }
        }
    }

    /** Telegram ticks: latest local message per chat, no schema change. */
    private fun observeReceipts() {
        val owner = ownerUserId()
        if (owner.isBlank()) return
        scope.launch(ioDispatcher) {
            chatsFlow
                .map { chats -> chats.map { it.id } to chats.map { it.lastMessageTime } }
                .distinctUntilChanged()
                .collect { (chatIds, _) ->
                    if (ownerUserId() != owner) return@collect
                    val chatsById = getChats().associateBy { it.id }
                    val receipts = chatIds.associateWith { chatId ->
                        val latest = runCatching {
                            getRecentMessages(chatId, 1).firstOrNull()
                        }.getOrNull()
                        ChatListReceiptPolicy.fromLatest(
                            senderId = latest?.senderId,
                            status = latest?.status,
                            isHidden = latest?.type?.isHidden == true,
                            currentUserId = owner,
                            isGroup = chatsById[chatId]?.isGroup == true,
                        )
                    }.filterValues { it != null }.mapValues { it.value!! }
                    if (ownerUserId() != owner) return@collect
                    updateReceiptsByChat(receipts)
                }
        }
    }

    companion object {
        const val LIST_MESSAGE_SEARCH_DEBOUNCE_MS: Long = 250L
        const val LIST_SEARCH_MAX_LENGTH: Int = 200
    }
}
