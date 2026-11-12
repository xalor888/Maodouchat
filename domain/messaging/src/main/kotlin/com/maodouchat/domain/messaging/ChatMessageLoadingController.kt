package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ChatLoadEffect
import com.maodouchat.core.model.ChatLoadInput
import com.maodouchat.core.model.OwnSentMediaRestorePolicy
import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatHistoryLoadPolicy
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.User
import com.maodouchat.core.model.GroupLifecycleService
import com.maodouchat.core.model.GroupMemberDto
import com.maodouchat.core.model.GroupMessagingCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.ChatLoadPlan
import com.maodouchat.core.model.AUTO_DOWNLOAD_MEDIA_TYPES

// 消息加载/分页/刷新：从 ChatDetailViewModel 纯搬移；
// 仓库/网络/会话门禁/回执/R 文案与跨模块控制器走端口，其余逻辑逐字平移。
class ChatMessageLoadingController(
    private val uiState: MutableStateFlow<ChatDetailUiState>,
    private val scope: CoroutineScope,
    private val chatId: String,
    private val getActiveChatId: () -> String,
    private val getCurrentUserId: () -> String,
    private val getChatById: suspend (String) -> Chat?,
    private val cacheChats: suspend (List<Chat>) -> Unit,
    private val fetchChatById: suspend (String, String) -> Chat?,
    private val getRecentMessages: suspend (String, Int) -> List<Message>,
    private val getLatestIncomingMessage: suspend (String, String) -> Message?,
    private val chatUnreadCount: suspend (String) -> Int,
    private val groupLifecycleService: GroupLifecycleService,
    private val planChatLoad: (
        input: ChatLoadInput,
        currentState: ChatDetailUiState,
        formatGroupName: () -> String,
        formatMemberCount: (Int) -> String,
        formatRevisionWarning: () -> String,
    ) -> ChatLoadPlan,
    private val planLoadFailure: (
        currentState: ChatDetailUiState,
        formatError: () -> String,
    ) -> ChatDetailUiState,
    private val presentMembers: (ChatDetailUiState, List<GroupMemberDto>, String) -> ChatDetailUiState,
    private val initialHistoryLoaded: (ChatDetailUiState, List<Message>, String?) -> ChatDetailUiState,
    private val refreshPinnedMessages: suspend (String) -> Unit,
    private val cipherController: ChatSessionCipherController,
    private val loadGroupCandidates: () -> Unit,
    private val refreshBotCommands: (String) -> Unit,
    private val refreshBlockState: (String) -> Unit,
    private val scheduledMessageController: ScheduledMessageController,
    private val refreshIdentitySafetyState: (String) -> Unit,
    private val groupMessagingCoordinator: GroupMessagingCoordinator,
    private val prefetchRecentGroupCounts: () -> Unit,
    private val text: (ChatMessageLoadingText, Array<out Any>) -> String,
    private val quantityText: (ChatMessageLoadingText, Int, Array<out Any>) -> String,
    private val withLocalNickname: suspend (User) -> User,
    private val requestMediaAttachment: (String) -> Unit,
    private val maybeGenerateUnreadSummary: (List<Message>) -> Unit,
    private val armSecretDisappearing: suspend (String, String?) -> Unit,
    private val showNewDeviceHistoryBanner: (List<Message>) -> Unit,
    private val isLocalUriReadable: (String) -> Boolean,
    private val enqueueReadReceipt: suspend (String, String, Long?) -> Unit,
    private val emitChatRead: (String) -> Unit,
    private val currentToken: () -> String,
    private val sessionMayContinue: (String) -> Boolean,
) {
    /** 8.52 UX：初次加载失败后手动重试（UI 错误空态的重试按钮）。 */
    fun reloadChat() {
        uiState.update { it.copy(initialLoadError = null, isLoading = true, initialTimelineReady = false) }
        loadChat()
    }

    /** G65：纯决策产出的副作用清单真正执行掉（判定在 coordinator）。 */
    private suspend fun applyLoadEffects(
        plan: ChatLoadPlan,
        chat: Chat,
    ) {
        plan.effects.forEach { effect ->
            when (effect) {
                is ChatLoadEffect.OccupySessionCipher ->
                    cipherController.occupySessionCipher(effect.chatId, peerUserId = effect.peerUserId, updatePeer = true)
                ChatLoadEffect.LoadGroupCandidates -> loadGroupCandidates()
                is ChatLoadEffect.RefreshBotCommands -> refreshBotCommands(effect.chatId)
                is ChatLoadEffect.RefreshBlockState -> refreshBlockState(effect.peerUserId)
                ChatLoadEffect.RefreshScheduledMessages -> scheduledMessageController.refreshScheduledMessages()
                is ChatLoadEffect.RefreshIdentitySafety ->
                    refreshIdentitySafetyState(effect.peerUserId)
            }
        }
        if (plan.shouldInvalidateSenderKey) {
            invalidateGroupSenderKey(chat.id, chat.memberRevision)
        }
    }

    fun loadChat() {
        uiState.update { it.copy(isLoading = true, initialTimelineReady = false) }
        scope.launch {
            try {
                val loadOwnerUserId = getCurrentUserId()
                // Snapshot resolved chat id once at launch: activeChatId may be reassigned on
                // create-on-send, so the constructor chatId could mark the wrong chat read.
                val effectiveChatId = getActiveChatId().ifBlank { chatId }
                if (loadOwnerUserId.isBlank() ||
                    !sessionMayContinue(loadOwnerUserId)
                ) {
                    uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                val liveToken = currentToken()
                val cachedChat = getChatById(chatId)
                val fetchedChat = fetchChatById(liveToken, chatId)
                // getChats can outlive logout/switch — do not invalidate SK / cache / paint meta for next owner.
                if (!sessionMayContinue(loadOwnerUserId)
                ) {
                    uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                if (fetchedChat != null) {
                    val chat = fetchedChat.let { raw ->
                        raw.copy(participants = raw.participants.map { p -> withLocalNickname(p) })
                    }
                    val previousRevision = uiState.value.chat?.memberRevision ?: cachedChat?.memberRevision
                    val shouldInvalidateSenderKey = chat.isGroup &&
                        previousRevision != null &&
                        chat.memberRevision > previousRevision
                    if (shouldInvalidateSenderKey) {
                        invalidateGroupSenderKey(chat.id, chat.memberRevision)
                    }
                    cacheChats(listOf(chat))
                    uiState.update { it.copy(isLoading = false, initialLoadError = null) }
                    // G65：纯决策交给 ChatDetailLoadCoordinator（可单测），这里只编排副作用。
                    // 函数类型端口不支持具名实参，这里一律用位置实参（顺序与端口声明一致）。
                    val plan = planChatLoad(
                        ChatLoadInput(
                            chat = chat,
                            previousRevision = previousRevision,
                            currentUserId = getCurrentUserId(),
                            fromCache = false,
                        ),
                        uiState.value,
                        { text(ChatMessageLoadingText.GROUP_NAME, emptyArray()) },
                        { count -> quantityText(ChatMessageLoadingText.MEMBER_COUNT, count, arrayOf(count)) },
                        { text(ChatMessageLoadingText.GROUP_MEMBERS_CHANGED, emptyArray()) },
                    )
                    uiState.value = plan.nextState
                    applyLoadEffects(plan, chat)
                } else {
                    // API 失败时从本地缓存加载聊天信息
                    if (cachedChat != null) {
                        uiState.update { it.copy(isLoading = false) }
                        // G65：缓存回退同样走纯决策（fromCache = true），行为与 API 成功路径一致。
                        val cached = cachedChat.copy(
                            participants = cachedChat.participants.map { p -> withLocalNickname(p) }
                        )
                        val cachedPlan = planChatLoad(
                            ChatLoadInput(
                                chat = cached,
                                // 缓存回退路径没有 API 侧基线，用当前 UI 上的 revision
                                previousRevision = uiState.value.chat?.memberRevision,
                                currentUserId = getCurrentUserId(),
                                fromCache = true,
                            ),
                            uiState.value,
                            { text(ChatMessageLoadingText.GROUP_NAME, emptyArray()) },
                            { count -> quantityText(ChatMessageLoadingText.MEMBER_COUNT, count, arrayOf(count)) },
                            { text(ChatMessageLoadingText.GROUP_MEMBERS_CHANGED, emptyArray()) },
                        )
                        uiState.value = cachedPlan.nextState
                        applyLoadEffects(cachedPlan, cached)
                    } else {
                        // 8.52 UX：无本地缓存时记录加载失败，UI 显示错误态 + 重试（区别于真实空会话）
                        uiState.value = planLoadFailure(uiState.value) {
                            text(ChatMessageLoadingText.LOAD_FAILED_TITLE, emptyArray())
                        }
                    }
                }
                withContext(Dispatchers.IO) {
                    // G70：复制粘贴遗留的重复门禁已删，这里只留一次。
                    if (!sessionMayContinue(loadOwnerUserId)
                    ) {
                        return@withContext
                    }
                    val messages = getRecentMessages(effectiveChatId, HISTORY_PAGE_SIZE)
                    val unreadCount = chatUnreadCount(effectiveChatId)
                    val readBoundary = getLatestIncomingMessage(effectiveChatId, loadOwnerUserId)?.id
                    // G70：收尾判定（分隔线/阅后即焚/回执）下沉到纯策略，这里只执行
                    val historyPlan = ChatHistoryLoadPolicy.planHistoryLoad(
                        messages = messages,
                        unreadCount = unreadCount,
                        isSecretChat = uiState.value.isSecretChat == true,
                        readBoundaryMessageId = readBoundary,
                        groupRevision = uiState.value.chat?.memberRevision
                            ?.takeIf { uiState.value.chat?.isGroup == true },
                    )
                    uiState.update { state ->
                        initialHistoryLoaded(state, messages, historyPlan.unreadSeparatorId)
                    }
                    maybeAutoLoadLastGroupReadCount()
                    hydrateMissingLocalAttachments(messages)
                    maybeGenerateUnreadSummary(messages)
                    showNewDeviceHistoryBanner(messages)
                    if (historyPlan.armSecretDisappearing) {
                        armSecretDisappearing(effectiveChatId, readBoundary)
                    } else if (historyPlan.enqueueReadReceipt) {
                        // 不变量：两者同源于 shouldReceipt（ChatHistoryLoadPolicy）。用 `?:` 兜底而非 `!!`：破约时宁可少发一次回执，也别在加载历史时崩。
                        enqueueReadReceipt(
                            effectiveChatId,
                            historyPlan.readReceiptThroughMessageId ?: effectiveChatId,
                            historyPlan.readReceiptGroupRevision,
                        )
                        emitChatRead(effectiveChatId)
                    }
                }
                refreshPinnedMessages(loadOwnerUserId)
                if (uiState.value.chatIsGroup) {
                    refreshMyMemberRole(loadOwnerUserId)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                uiState.update { it.copy(isLoading = false) }
                throw error
            }
        }
    }

    fun loadOlderMessages() {
        uiState.update { state ->
            state.copy(hasMoreOlderMessages = false, isLoadingOlderMessages = false)
        }
    }

    suspend fun refreshMyMemberRole(expectedUserId: String) {
        if (expectedUserId.isBlank() || chatId.isBlank()) return
        if (!sessionMayContinue(expectedUserId)
        ) {
            return
        }
        val liveToken = currentToken()
        if (liveToken.isBlank()) return
        groupLifecycleService.fetchGroupMembers(chatId).onSuccess { members ->
            if (!sessionMayContinue(expectedUserId)
            ) {
                return@onSuccess
            }
            uiState.update { state -> presentMembers(state, members, expectedUserId) }
        }
    }

    private fun maybeAutoLoadLastGroupReadCount() = prefetchRecentGroupCounts()

    private fun hydrateMissingLocalAttachments(messages: List<Message>) {
        if (uiState.value.isSecretChat == true) return
        messages.asSequence()
            .filter { message ->
                OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                    isSecretChat = false,
                    type = message.type,
                    attachmentId = message.parsedMeta().attachmentId,
                    localUriReadable = isLocalUriReadable(message.parsedContent()),
                    senderIsCurrentUser = message.senderId == getCurrentUserId(),
                    autoDownload = message.type in AUTO_DOWNLOAD_MEDIA_TYPES
                )
            }
            .take(12)
            .forEach { requestMediaAttachment(it.id) }
    }

    private suspend fun invalidateGroupSenderKey(groupId: String, newRevision: Long? = null) {
        groupMessagingCoordinator.invalidateSenderKey(
            chatId = groupId,
            ownerUserId = getCurrentUserId(),
            newRevision = newRevision,
        )
    }

    companion object {
        private const val HISTORY_PAGE_SIZE = 100
    }
}
