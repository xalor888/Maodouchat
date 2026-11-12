package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.ChatDto
import com.maodouchat.core.model.ChatLoadEffect
import com.maodouchat.core.model.ChatLoadPlan
import com.maodouchat.core.model.ChatScheduleController
import com.maodouchat.core.model.ConversationScheduleBackend
import com.maodouchat.core.model.ConversationScheduleCoordinator
import com.maodouchat.core.model.GroupLifecycleService
import com.maodouchat.core.model.GroupMemberDto
import com.maodouchat.core.model.GroupMessagingCoordinator
import com.maodouchat.core.model.GroupMessagingSession
import com.maodouchat.core.model.GroupMutationCommit
import com.maodouchat.core.model.Lease
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageReminder
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.ScheduledMessage
import com.maodouchat.core.model.ScheduledMessageRequest
import com.maodouchat.core.model.SenderKeyDistributionStatusDto
import com.maodouchat.core.model.SessionSnapshot
import com.maodouchat.core.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatMessageLoadingControllerTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val me = User(id = "owner-1", name = "Me")
    private val peer = User(id = "u2", name = "U2")
    private val chat = Chat(id = "c1", participants = listOf(me, peer), memberRevision = 1)

    private fun message(
        id: String = "m1",
        senderId: String = "u2",
        timestamp: Long = 1000L,
    ) = Message(
        id = id,
        chatId = "c1",
        senderId = senderId,
        content = "body",
        type = MessageType.TEXT,
        timestamp = timestamp,
    )

    private class FakeScheduleBackend : ConversationScheduleBackend {
        var listScheduledCalls = 0
        override fun listAllScheduled(ownerUserId: String): List<ScheduledMessage> = emptyList()
        override fun listScheduled(ownerUserId: String, chatId: String): List<ScheduledMessage> {
            listScheduledCalls++
            return emptyList()
        }
        override fun getScheduled(ownerUserId: String, id: String): ScheduledMessage? = null
        override fun addScheduled(ownerUserId: String, request: ScheduledMessageRequest): ScheduledMessage? = null
        override fun updateScheduled(ownerUserId: String, id: String, text: String?, sendAtMillis: Long?): ScheduledMessage? = null
        override fun removeScheduled(ownerUserId: String, id: String): Boolean = false
        override fun clearScheduled(ownerUserId: String, chatId: String): List<String> = emptyList()
        override fun scheduleJob(item: ScheduledMessage) {}
        override fun cancelJob(id: String) {}
        override fun rescheduleJob(item: ScheduledMessage) {}
        override fun listReminders(ownerUserId: String): List<MessageReminder> = emptyList()
        override fun upsertReminder(reminder: MessageReminder) {}
        override fun removeReminder(ownerUserId: String, id: String) {}
        override fun clearReminders(ownerUserId: String, chatId: String) {}
        override fun scheduleReminderJob(reminder: MessageReminder) {}
        override fun cancelReminderJob(id: String) {}
    }

    private class FakeGroupLifecycleService(
        var members: List<GroupMemberDto> = emptyList(),
    ) : GroupLifecycleService {
        val fetchMembersCalls = mutableListOf<String>()
        override suspend fun addMembers(chatId: String, userIds: List<String>): GroupMutationCommit = error("unused")
        override suspend fun removeMember(chatId: String, userId: String): GroupMutationCommit = error("unused")
        override suspend fun setRole(chatId: String, userId: String, role: String): GroupMutationCommit = error("unused")
        override suspend fun transferOwnership(chatId: String, newOwnerUserId: String): GroupMutationCommit = error("unused")
        override suspend fun setTitle(chatId: String, userId: String, title: String): GroupMutationCommit = error("unused")
        override suspend fun setMemberMute(chatId: String, userId: String, mutedUntil: Long): GroupMutationCommit = error("unused")
        override suspend fun setMuteAll(chatId: String, muted: Boolean): GroupMutationCommit = error("unused")
        override suspend fun updateGroupInfo(chatId: String, name: String?, announcement: String?, avatar: String?): GroupMutationCommit = error("unused")
        override suspend fun updateMyNickname(chatId: String, nickname: String): GroupMutationCommit = error("unused")
        override suspend fun leaveGroup(chatId: String): GroupMutationCommit = error("unused")
        override suspend fun dissolveGroup(chatId: String): GroupMutationCommit = error("unused")
        override suspend fun fetchGroupDetails(chatId: String): Result<ChatDto?> = error("unused")
        override suspend fun fetchGroupMembers(chatId: String): Result<List<GroupMemberDto>> {
            fetchMembersCalls += chatId
            return Result.success(members)
        }
        override suspend fun fetchCandidates(excludeMemberIds: Set<String>, excludeUserId: String): Result<List<User>> = error("unused")
        override suspend fun uploadAvatar(chatId: String, base64: String): Result<String> = error("unused")
    }

    private class Harness(
        private val dispatcher: UnconfinedTestDispatcher,
    ) {
        val uiState = MutableStateFlow(ChatDetailUiState(contact = peer))
        val scopeJob = Job()
        val scope = CoroutineScope(dispatcher + scopeJob)

        var ownerId: String = "owner-1"
        var token: String = "tok"
        var sessionMayContinue: Boolean = true
        var cachedChat: Chat? = null
        var fetchedChat: Chat? = null
        var recentMessages: List<Message> = emptyList()
        var latestIncoming: Message? = null
        var unreadCount: Int = 0
        var planEffects: List<ChatLoadEffect> = emptyList()
        var planShouldInvalidate: Boolean = false
        var isLocalUriReadable: Boolean = false

        val cachedChats = mutableListOf<List<Chat>>()
        val fetchCalls = mutableListOf<Pair<String, String>>()
        val planFromCache = mutableListOf<Boolean>()
        val pinnedRefreshes = mutableListOf<String>()
        val presentMembersCalls = mutableListOf<Triple<ChatDetailUiState, List<GroupMemberDto>, String>>()
        val historyLoadedCalls = mutableListOf<Triple<ChatDetailUiState, List<Message>, String?>>()
        val occupiedCiphers = mutableListOf<Triple<String, String?, Boolean>>()
        val groupCandidatesLoads = mutableListOf<Unit>()
        val botCommandsRefreshes = mutableListOf<String>()
        val blockRefreshes = mutableListOf<String>()
        val identityRefreshes = mutableListOf<String>()
        val prefetchCounts = mutableListOf<Unit>()
        val receipts = mutableListOf<Triple<String, String, Long?>>()
        val emittedReads = mutableListOf<String>()
        val invalidatedEpochs = mutableListOf<Triple<String, String, Long?>>()
        val requestedAttachments = mutableListOf<String>()
        val summaries = mutableListOf<List<Message>>()
        val banners = mutableListOf<List<Message>>()
        val armedDisappearing = mutableListOf<Pair<String, String?>>()

        val lifecycleService = FakeGroupLifecycleService()
        val scheduleBackend = FakeScheduleBackend()

        private val cipherController = ChatSessionCipherController(
            initialChatId = "",
            getChatById = { cachedChat },
            acquireOccupancy = { Lease(1) },
            occupyOccupancy = { _, target, peerId, update -> occupiedCiphers += Triple(target, peerId, update) },
            releaseOccupancy = {},
            currentUserId = { ownerId },
        )
        private val scheduledController = ScheduledMessageController(
            chatScheduleController = ChatScheduleController(
                ConversationScheduleCoordinator(ownerUserId = { ownerId }, backend = scheduleBackend, now = { 1000L }),
                scheduledMessagesEnabled = { true },
            ),
            uiState = uiState,
            textProvider = { message, _ -> "[${message.name}]" },
            session = { SessionSnapshot(token = token, userId = ownerId) },
            activeChatId = { "c1" },
            chatId = "c1",
            clearDraft = {},
            sendMessage = { _, _, _ -> false },
        )
        private val messagingCoordinator = GroupMessagingCoordinator(
            currentSession = { GroupMessagingSession(ownerUserId = ownerId, authToken = token, deviceId = 1) },
            invalidatePreparedEpoch = { owner, conversationId, revision, _ -> invalidatedEpochs += Triple(owner, conversationId, revision) },
            invalidateLocalSenderKey = {},
            reconcileAttachments = { _, _ -> },
            ensureCoverageNow = { _, _ -> },
            redistributeCoverageNow = { _ -> true },
            fetchCoverageStatus = { _, _, _, _ -> SenderKeyDistributionStatusDto(chatId = "c1", epoch = 0) },
            hasLocalSenderKeyMaterial = { _, _ -> false },
            enqueueCoverageRetryCommand = { _, _, _ -> },
        )

        fun controller(): ChatMessageLoadingController = ChatMessageLoadingController(
            uiState = uiState,
            scope = scope,
            chatId = "c1",
            getActiveChatId = { "c1" },
            getCurrentUserId = { ownerId },
            getChatById = { cachedChat },
            cacheChats = { cachedChats += it },
            fetchChatById = { liveToken, id ->
                fetchCalls += liveToken to id
                fetchedChat
            },
            getRecentMessages = { _, _ -> recentMessages },
            getLatestIncomingMessage = { _, _ -> latestIncoming },
            chatUnreadCount = { unreadCount },
            groupLifecycleService = lifecycleService,
            planChatLoad = { input, currentState, _, _, _ ->
                planFromCache += input.fromCache
                ChatLoadPlan(
                    nextState = currentState.copy(chat = input.chat, chatIsGroup = input.chat.isGroup, isLoading = false),
                    effects = planEffects,
                    shouldInvalidateSenderKey = planShouldInvalidate,
                    revisionWarning = null,
                    fromCache = input.fromCache,
                )
            },
            planLoadFailure = { state, format -> state.copy(isLoading = false, initialLoadError = format()) },
            presentMembers = { state, members, userId ->
                presentMembersCalls += Triple(state, members, userId)
                state.copy(myMemberRole = members.firstOrNull { it.userId == userId }?.role)
            },
            initialHistoryLoaded = { state, messages, separator ->
                historyLoadedCalls += Triple(state, messages, separator)
                state.copy(messages = messages, initialTimelineReady = true)
            },
            refreshPinnedMessages = { pinnedRefreshes += it },
            cipherController = cipherController,
            loadGroupCandidates = { groupCandidatesLoads += Unit },
            refreshBotCommands = { botCommandsRefreshes += it },
            refreshBlockState = { blockRefreshes += it },
            scheduledMessageController = scheduledController,
            refreshIdentitySafetyState = { identityRefreshes += it },
            groupMessagingCoordinator = messagingCoordinator,
            prefetchRecentGroupCounts = { prefetchCounts += Unit },
            text = { message, _ -> "text:${message.name}" },
            quantityText = { message, count, _ -> "qty:${message.name}:$count" },
            withLocalNickname = { it },
            requestMediaAttachment = { requestedAttachments += it },
            maybeGenerateUnreadSummary = { summaries += it },
            armSecretDisappearing = { chatId, watermark -> armedDisappearing += chatId to watermark },
            showNewDeviceHistoryBanner = { banners += it },
            isLocalUriReadable = { isLocalUriReadable },
            enqueueReadReceipt = { conversationId, throughMessageId, groupRevision ->
                receipts += Triple(conversationId, throughMessageId, groupRevision)
            },
            emitChatRead = { emittedReads += it },
            currentToken = { token },
            sessionMayContinue = { sessionMayContinue },
        )

        suspend fun settle() {
            scopeJob.children.forEach { it.join() }
        }
    }

    @Test
    fun blankOwnerSkipsLoad() = runTest(dispatcher) {
        val harness = Harness(dispatcher).apply { ownerId = "" }
        harness.controller().loadChat()
        harness.settle()

        assertFalse(harness.uiState.value.isLoading)
        assertTrue(harness.fetchCalls.isEmpty())
    }

    @Test
    fun sessionGateDeniedSkipsLoad() = runTest(dispatcher) {
        val harness = Harness(dispatcher).apply { sessionMayContinue = false }
        harness.controller().loadChat()
        harness.settle()

        assertFalse(harness.uiState.value.isLoading)
        assertTrue(harness.fetchCalls.isEmpty())
    }

    @Test
    fun apiSuccessLoadsChatAndHistory() = runTest(dispatcher) {
        val harness = Harness(dispatcher).apply {
            fetchedChat = chat
            recentMessages = listOf(message())
            latestIncoming = message()
        }
        harness.controller().loadChat()
        harness.settle()

        assertEquals(listOf("tok" to "c1"), harness.fetchCalls)
        assertEquals(listOf(listOf(chat)), harness.cachedChats)
        assertEquals("c1", harness.uiState.value.chat?.id)
        assertFalse(harness.uiState.value.isLoading)
        assertEquals(listOf(false), harness.planFromCache)
        assertEquals(1, harness.historyLoadedCalls.size)
        assertEquals(listOf("m1"), harness.uiState.value.messages.map { it.id })
        assertTrue(harness.uiState.value.initialTimelineReady)
        assertEquals(listOf("owner-1"), harness.pinnedRefreshes)
        assertEquals(1, harness.prefetchCounts.size)
        assertTrue(harness.invalidatedEpochs.isEmpty())
    }

    @Test
    fun apiFailureFallsBackToCache() = runTest(dispatcher) {
        val harness = Harness(dispatcher).apply {
            fetchedChat = null
            cachedChat = chat
        }
        harness.controller().loadChat()
        harness.settle()

        assertEquals("c1", harness.uiState.value.chat?.id)
        assertEquals(listOf(true), harness.planFromCache)
        assertNull(harness.uiState.value.initialLoadError)
    }

    @Test
    fun apiFailureWithoutCacheWritesLoadError() = runTest(dispatcher) {
        val harness = Harness(dispatcher).apply {
            fetchedChat = null
            cachedChat = null
        }
        harness.controller().loadChat()
        harness.settle()

        assertEquals("text:LOAD_FAILED_TITLE", harness.uiState.value.initialLoadError)
        assertFalse(harness.uiState.value.isLoading)
    }

    @Test
    fun groupRevisionRiseInvalidatesSenderKey() = runTest(dispatcher) {
        val groupChat = chat.copy(isGroup = true, memberRevision = 5)
        val harness = Harness(dispatcher).apply {
            fetchedChat = groupChat
            cachedChat = chat.copy(isGroup = true, memberRevision = 1)
        }
        harness.controller().loadChat()
        harness.settle()

        assertEquals(listOf(Triple("owner-1", "c1", 5L)), harness.invalidatedEpochs)
    }

    @Test
    fun loadEffectsExecuteInOrder() = runTest(dispatcher) {
        val harness = Harness(dispatcher).apply {
            fetchedChat = chat
            planEffects = listOf(
                ChatLoadEffect.LoadGroupCandidates,
                ChatLoadEffect.RefreshBotCommands("c1"),
                ChatLoadEffect.RefreshBlockState("u2"),
                ChatLoadEffect.RefreshScheduledMessages,
                ChatLoadEffect.RefreshIdentitySafety("u2"),
                ChatLoadEffect.OccupySessionCipher("c1", peerUserId = "u2"),
            )
        }
        harness.controller().loadChat()
        harness.settle()

        assertEquals(1, harness.groupCandidatesLoads.size)
        assertEquals(listOf("c1"), harness.botCommandsRefreshes)
        assertEquals(listOf("u2"), harness.blockRefreshes)
        assertEquals(1, harness.scheduleBackend.listScheduledCalls)
        assertEquals(listOf("u2"), harness.identityRefreshes)
        assertEquals(listOf(Triple("c1", "u2", true)), harness.occupiedCiphers)
    }

    @Test
    fun loadOlderMessagesClearsFlags() = runTest(dispatcher) {
        val harness = Harness(dispatcher)
        harness.uiState.update { it.copy(hasMoreOlderMessages = true, isLoadingOlderMessages = true) }
        harness.controller().loadOlderMessages()

        assertFalse(harness.uiState.value.hasMoreOlderMessages)
        assertFalse(harness.uiState.value.isLoadingOlderMessages)
    }

    @Test
    fun refreshMyMemberRoleGateDeniedSkipsFetch() = runTest(dispatcher) {
        val harness = Harness(dispatcher).apply { sessionMayContinue = false }
        harness.controller().refreshMyMemberRole("owner-1")

        assertTrue(harness.lifecycleService.fetchMembersCalls.isEmpty())
    }

    @Test
    fun refreshMyMemberRolePresentsMembers() = runTest(dispatcher) {
        val harness = Harness(dispatcher).apply {
            lifecycleService.members = listOf(GroupMemberDto(userId = "owner-1", name = "Me", role = "ADMIN"))
        }
        harness.controller().refreshMyMemberRole("owner-1")

        assertEquals(listOf("c1"), harness.lifecycleService.fetchMembersCalls)
        assertEquals(1, harness.presentMembersCalls.size)
        assertEquals("ADMIN", harness.uiState.value.myMemberRole)
    }
}
