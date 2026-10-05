package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import com.maodouchat.chatdetail.ChatDetailAccess
import com.maodouchat.crypto.OwnSentMediaRestorePolicy
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.User
import com.maodouchat.data.repository.ChatNetworkRepository
import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.group.GroupLifecycleService
import com.maodouchat.messaging.v2.GroupMessagingCoordinator
import com.maodouchat.util.MediaCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 消息加载/分页/刷新一族：从 ChatDetailViewModel 纯搬移；VM 只留同签名委托。
internal class ChatMessageLoadingController(
    private val uiState: MutableStateFlow<ChatDetailUiState>,
    private val scope: CoroutineScope,
    private val application: Application,
    private val chatId: String,
    private val getActiveChatId: () -> String,
    private val getCurrentUserId: () -> String,
    private val chatRepo: ChatRepository,
    private val messageRepo: LocalMessageStore,
    private val groupLifecycleService: GroupLifecycleService,
    private val groupSecurityStateController: ChatGroupSecurityStateController,
    private val timelineStateController: ChatTimelineStateController,
    private val pinStarController: ChatPinStarController,
    private val cipherController: ChatSessionCipherController,
    private val botGroupActionController: ChatBotGroupActionController,
    private val moderationController: ChatModerationController,
    private val scheduledMessageController: ScheduledMessageController,
    private val identityVerificationController: IdentityVerificationController,
    private val groupMessagingCoordinator: GroupMessagingCoordinator,
    private val readReceiptCoordinator: ChatReadReceiptCoordinator,
    private val text: (Int, Array<out Any>) -> String,
    private val quantityText: (Int, Int, Array<out Any>) -> String,
    private val withLocalNickname: suspend (User) -> User,
    private val requestMediaAttachment: (String) -> Unit,
    private val maybeGenerateUnreadSummary: (List<Message>) -> Unit,
    private val armSecretDisappearing: suspend (String, String?) -> Unit,
    private val showNewDeviceHistoryBanner: (List<Message>) -> Unit,
) {
    /** 8.52 UX：初次加载失败后手动重试（UI 错误空态的重试按钮）。 */
    fun reloadChat() {
        uiState.update { it.copy(initialLoadError = null, isLoading = true, initialTimelineReady = false) }
        loadChat()
    }

    /** G65：纯决策产出的副作用清单真正执行掉（判定在 coordinator）。 */
    private suspend fun applyLoadEffects(
        plan: ChatDetailLoadCoordinator.ChatLoadPlan,
        chat: Chat,
    ) {
        plan.effects.forEach { effect ->
            when (effect) {
                is ChatDetailLoadCoordinator.ChatLoadEffect.OccupySessionCipher ->
                    cipherController.occupySessionCipher(effect.chatId, peerUserId = effect.peerUserId, updatePeer = true)
                ChatDetailLoadCoordinator.ChatLoadEffect.LoadGroupCandidates -> botGroupActionController.loadGroupCandidates()
                is ChatDetailLoadCoordinator.ChatLoadEffect.RefreshBotCommands -> botGroupActionController.refreshBotCommands(effect.chatId)
                is ChatDetailLoadCoordinator.ChatLoadEffect.RefreshBlockState -> moderationController.refreshBlockState(effect.peerUserId)
                ChatDetailLoadCoordinator.ChatLoadEffect.RefreshScheduledMessages -> scheduledMessageController.refreshScheduledMessages()
                is ChatDetailLoadCoordinator.ChatLoadEffect.RefreshIdentitySafety ->
                    identityVerificationController.refreshIdentitySafetyState(effect.peerUserId)
            }
        }
        if (plan.shouldInvalidateSenderKey) {
            invalidateGroupSenderKey(chat.id, chat.memberRevision)
        }
    }

    internal fun loadChat() {
        uiState.update { it.copy(isLoading = true, initialTimelineReady = false) }
        scope.launch {
            try {
                val loadOwnerUserId = getCurrentUserId()
                // Snapshot resolved chat id once at launch: activeChatId may be reassigned on
                // create-on-send, so the constructor chatId could mark the wrong chat read.
                val effectiveChatId = getActiveChatId().ifBlank { chatId }
                if (loadOwnerUserId.isBlank() ||
                    !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = loadOwnerUserId,
                    )
                ) {
                    uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                val liveToken = com.maodouchat.session.CurrentSession.snapshot().token.orEmpty()
                val cachedChat = chatRepo.getChatById(chatId)
                val chatsResult = ChatNetworkRepository().chats(liveToken)
                // getChats can outlive logout/switch — do not invalidate SK / cache / paint meta for next owner.
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = loadOwnerUserId,
                )
                ) {
                    uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                val chatDto = chatsResult.getOrNull()?.find { it.id == chatId }
                if (chatDto != null) {
                    val chat = chatDto.toDomainChat().let { raw ->
                        raw.copy(participants = raw.participants.map { p -> withLocalNickname(p) })
                    }
                    val previousRevision = uiState.value.chat?.memberRevision ?: cachedChat?.memberRevision
                    val shouldInvalidateSenderKey = chat.isGroup &&
                        previousRevision != null &&
                        chat.memberRevision > previousRevision
                    if (shouldInvalidateSenderKey) {
                        invalidateGroupSenderKey(chat.id, chat.memberRevision)
                    }
                    chatRepo.cacheChats(listOf(chat))
                    uiState.update { it.copy(isLoading = false, initialLoadError = null) }
                    // G65：纯决策交给 ChatDetailLoadCoordinator（可单测），这里只编排副作用。
                    val plan = ChatDetailLoadCoordinator.planChatLoad(
                        input = ChatDetailLoadCoordinator.ChatLoadInput(
                            chat = chat,
                            previousRevision = previousRevision,
                            currentUserId = getCurrentUserId(),
                            fromCache = false,
                        ),
                        currentState = uiState.value,
                        formatGroupName = { text(com.maodouchat.R.string.chat_group, emptyArray()) },
                        formatMemberCount = { count -> quantityText(com.maodouchat.R.plurals.chat_members_count, count, arrayOf(count)) },
                        formatRevisionWarning = { text(com.maodouchat.R.string.chat_group_members_changed_key, emptyArray()) },
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
                        val cachedPlan = ChatDetailLoadCoordinator.planChatLoad(
                            input = ChatDetailLoadCoordinator.ChatLoadInput(
                                chat = cached,
                                // 缓存回退路径没有 API 侧基线，用当前 UI 上的 revision
                                previousRevision = uiState.value.chat?.memberRevision,
                                currentUserId = getCurrentUserId(),
                                fromCache = true,
                            ),
                            currentState = uiState.value,
                            formatGroupName = { text(com.maodouchat.R.string.chat_group, emptyArray()) },
                            formatMemberCount = { count -> quantityText(com.maodouchat.R.plurals.chat_members_count, count, arrayOf(count)) },
                            formatRevisionWarning = { text(com.maodouchat.R.string.chat_group_members_changed_key, emptyArray()) },
                        )
                        uiState.value = cachedPlan.nextState
                        applyLoadEffects(cachedPlan, cached)
                    } else {
                        // 8.52 UX：无本地缓存时记录加载失败，UI 显示错误态 + 重试（区别于真实空会话）
                        uiState.value = ChatDetailLoadCoordinator.planLoadFailure(uiState.value) {
                            text(com.maodouchat.R.string.chat_load_failed_title, emptyArray())
                        }
                    }
                }
                withContext(Dispatchers.IO) {
                    // G70：复制粘贴遗留的重复门禁已删，这里只留一次。
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = loadOwnerUserId,
                    )
                    ) {
                        return@withContext
                    }
                    val messages = messageRepo.getRecentMessages(effectiveChatId, HISTORY_PAGE_SIZE)
                    val unreadCount = com.maodouchat.chatdetail.ChatDetailDataAccess.chatUnreadCount(effectiveChatId)
                    val readBoundary = messageRepo.getLatestIncomingMessage(effectiveChatId, loadOwnerUserId)?.id
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
                        timelineStateController.initialHistoryLoaded(state, messages, historyPlan.unreadSeparatorId)
                    }
                    maybeAutoLoadLastGroupReadCount()
                    hydrateMissingLocalAttachments(messages)
                    maybeGenerateUnreadSummary(messages)
                    showNewDeviceHistoryBanner(messages)
                    if (historyPlan.armSecretDisappearing) {
                        armSecretDisappearing(effectiveChatId, readBoundary)
                    } else if (historyPlan.enqueueReadReceipt) {
                        // 不变量：两者同源于 shouldReceipt（ChatHistoryLoadPolicy）。用 `?:` 兜底而非 `!!`：破约时宁可少发一次回执，也别在加载历史时崩。
                        ChatDetailAccess.messagingOutbox.enqueueReadReceipt(
                            conversationId = effectiveChatId,
                            throughMessageId = historyPlan.readReceiptThroughMessageId ?: effectiveChatId,
                            groupRevision = historyPlan.readReceiptGroupRevision,
                        )
                        ChatDetailAccess.emitChatRead(effectiveChatId)
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

    private suspend fun refreshPinnedMessages(expectedUserId: String) =
        pinStarController.refreshPinnedMessages(expectedUserId)

    internal suspend fun refreshMyMemberRole(expectedUserId: String) {
        if (expectedUserId.isBlank() || chatId.isBlank()) return
        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = expectedUserId,
        )
        ) {
            return
        }
        val liveToken = com.maodouchat.session.CurrentSession.snapshot().token.orEmpty()
        if (liveToken.isBlank()) return
        groupLifecycleService.fetchGroupMembers(chatId).onSuccess { members ->
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = expectedUserId,
            )
            ) {
                return@onSuccess
            }
            uiState.update { state -> groupSecurityStateController.presentMembers(state, members, expectedUserId) }
        }
    }

    private fun maybeAutoLoadLastGroupReadCount() = readReceiptCoordinator.prefetchRecentGroupCounts()

    private fun hydrateMissingLocalAttachments(messages: List<Message>) {
        if (uiState.value.isSecretChat == true) return
        messages.asSequence()
            .filter { message ->
                OwnSentMediaRestorePolicy.shouldHydrateMissingLocalAttachment(
                    isSecretChat = false,
                    type = message.type,
                    attachmentId = message.parsedMeta().attachmentId,
                    localUriReadable = MediaCache.isReadableLocalUri(application, message.parsedContent()),
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
