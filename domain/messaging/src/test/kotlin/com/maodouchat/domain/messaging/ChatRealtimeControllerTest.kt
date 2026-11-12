package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.PinnedMessageDto
import com.maodouchat.core.model.User
import com.maodouchat.core.model.WebSocketErrorKind
import com.maodouchat.core.model.WebSocketEvent
import com.maodouchat.core.realtime.RealtimeConnectionManager
import com.maodouchat.core.realtime.RealtimeDisconnectPolicy
import com.maodouchat.core.session.SessionContext
import com.maodouchat.core.session.SessionContextProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRealtimeControllerTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val me = User(id = "owner-1", name = "Me")
    private val peer = User(id = "u2", name = "U2")
    private val chat = Chat(id = "c1", participants = listOf(me, peer))

    private class FakeRealtime : RealtimeConnectionManager {
        val eventsFlow = MutableSharedFlow<WebSocketEvent>()
        override val events: SharedFlow<WebSocketEvent> = eventsFlow
        val starts = mutableListOf<Triple<String, String, SessionContext>>()
        val typingSends = mutableListOf<Triple<SessionContext, String, Boolean>>()
        var connected = false

        override fun start(serverUrl: String, accessToken: String, owner: SessionContext, reconnect: Boolean): Boolean {
            starts += Triple(serverUrl, accessToken, owner)
            return true
        }

        override fun stop(owner: SessionContext): Boolean = true
        override fun stopCurrent() {}
        override fun isConnected(owner: SessionContext): Boolean = connected
        override fun sendRaw(owner: SessionContext, text: String): Boolean = true
        override fun sendTyping(owner: SessionContext, chatId: String, isTyping: Boolean): Boolean {
            typingSends += Triple(owner, chatId, isTyping)
            return true
        }

        override fun sendPresence(owner: SessionContext, foreground: Boolean): Boolean = true
    }

    private class FakeProvider(initial: SessionContext?) : SessionContextProvider {
        override val contexts = MutableStateFlow(initial)
        override fun isAccessTokenCurrent(context: SessionContext, accessToken: String): Boolean =
            isCurrent(context) && accessToken.isNotBlank()
    }

    private inner class Harness(
        initial: ChatDetailUiState = ChatDetailUiState(contact = peer, chat = chat),
    ) {
        val uiState = MutableStateFlow(initial)
        val realtime = FakeRealtime()
        val provider = FakeProvider(SessionContext("owner-1", 1L))
        var ownerId = "owner-1"
        var token = "tok"
        var activeChatId = "c1"
        var sessionActive = true
        var presenceEnabled = true
        var secretPresenceBlocked = false
        var typingIndicatorsEnabled = true
        var secretTypingBlocked = false
        var purge = false
        var presenceResult: Result<UserPresenceSnapshot> =
            Result.success(UserPresenceSnapshot(isOnline = true, lastSeen = 12_345L))
        val presenceFetches = mutableListOf<Pair<String, String>>()
        val persisted = mutableListOf<Pair<String, Int>>()
        val visibilityApplied = mutableListOf<WebSocketEvent.UserOnline>()
        val revisionEvents = mutableListOf<WebSocketEvent.GroupRevisionChanged>()
        val warnings = mutableListOf<Pair<String, Throwable?>>()

        // 控制器跑在 runTest 的 backgroundScope 上：测试结束时自动取消，
        // 否则 presence 轮询的无限循环会让 runTest 收尾的 advanceUntilIdle 空转。
        fun controller(scope: CoroutineScope) = ChatRealtimeController(
            scope = scope,
            sessionContextProvider = provider,
            realtime = realtime,
            ownerUserId = { ownerId },
            token = { token },
            activeChatId = { activeChatId },
            sessionActive = { sessionActive },
            currentState = uiState::value,
            updateState = { transform -> uiState.update(transform) },
            persistDisappearingMessages = { chatId, seconds -> persisted += chatId to seconds },
            applyRealtimeVisibility = { visibilityApplied += it },
            onGroupRevisionChanged = { revisionEvents += it },
            text = { key ->
                when (key) {
                    ChatRealtimeText.CONNECTION_FAILED -> "连接失败"
                    ChatRealtimeText.DATA_INVALID -> "数据无效"
                }
            },
            wsUrl = { "wss://example/ws" },
            presenceEnabled = { presenceEnabled },
            secretPresenceBlocked = { secretPresenceBlocked },
            typingIndicatorsEnabled = { typingIndicatorsEnabled },
            secretTypingBlocked = { secretTypingBlocked },
            purgeInProgress = { purge },
            fetchUserPresence = { liveToken, contactId ->
                presenceFetches += liveToken to contactId
                presenceResult
            },
            logWarning = { message, error -> warnings += message to error },
            ioDispatcher = dispatcher,
        )

        suspend fun emit(event: WebSocketEvent) {
            realtime.eventsFlow.emit(event)
            dispatcher.scheduler.runCurrent()
        }
    }

    @Test
    fun `start connects with ws url token and owner context`() = runTest(dispatcher) {
        val h = Harness()
        h.controller(backgroundScope).start()
        assertEquals(
            listOf(Triple("wss://example/ws", "tok", SessionContext("owner-1", 1L))),
            h.realtime.starts,
        )
    }

    @Test
    fun `start skips connect when owner is blank or placeholder or session inactive`() = runTest(dispatcher) {
        val blank = Harness().also { it.ownerId = "" }
        blank.controller(backgroundScope).start()
        assertTrue(blank.realtime.starts.isEmpty())

        val meOwner = Harness().also { it.ownerId = "me" }
        meOwner.controller(backgroundScope).start()
        assertTrue(meOwner.realtime.starts.isEmpty())

        val inactive = Harness().also { it.sessionActive = false }
        inactive.controller(backgroundScope).start()
        assertTrue(inactive.realtime.starts.isEmpty())
    }

    @Test
    fun `start skips connect without token or owner context`() = runTest(dispatcher) {
        val noToken = Harness().also { it.token = "" }
        noToken.controller(backgroundScope).start()
        assertTrue(noToken.realtime.starts.isEmpty())

        val noContext = Harness().also { it.provider.contexts.value = null }
        noContext.controller(backgroundScope).start()
        assertTrue(noContext.realtime.starts.isEmpty())
    }

    @Test
    fun `pinned messages update only for the active chat`() = runTest(dispatcher) {
        val h = Harness()
        val c = h.controller(backgroundScope)
        c.start()
        val pins = listOf(PinnedMessageDto(chatId = "c1", messageId = "m1", pinnedBy = "u2", pinnedAt = 1L))
        h.emit(WebSocketEvent.PinnedMessagesUpdated(chatId = "other", actorId = "u2", pins = pins))
        assertTrue(h.uiState.value.pinnedMessages.isEmpty())
        h.emit(WebSocketEvent.PinnedMessagesUpdated(chatId = "c1", actorId = "u2", pins = pins))
        assertEquals(pins, h.uiState.value.pinnedMessages)
    }

    @Test
    fun `disappearing update persists and refreshes state`() = runTest(dispatcher) {
        val h = Harness()
        val c = h.controller(backgroundScope)
        c.start()
        h.emit(WebSocketEvent.DisappearingMessagesUpdated(chatId = "c1", seconds = 60))
        assertEquals(60, h.uiState.value.disappearingMessageSeconds)
        assertEquals(60, h.uiState.value.chat?.disappearingMessageSeconds)
        assertEquals(listOf("c1" to 60), h.persisted)
    }

    @Test
    fun `typing event marks remote typing only for the active chat`() = runTest(dispatcher) {
        val h = Harness()
        val c = h.controller(backgroundScope)
        c.start()
        h.emit(WebSocketEvent.UserTyping(userId = "u2", chatId = "other", isTyping = true))
        assertNull(h.uiState.value.typingContact)
        h.emit(WebSocketEvent.UserTyping(userId = "u2", chatId = "c1", isTyping = true))
        assertEquals("u2", h.uiState.value.typingContact)
        c.clearRemoteTyping()
        runCurrent()
        assertNull(h.uiState.value.typingContact)
    }

    @Test
    fun `group revision change is forwarded`() = runTest(dispatcher) {
        val h = Harness()
        val c = h.controller(backgroundScope)
        c.start()
        val event = WebSocketEvent.GroupRevisionChanged(chatId = "c1", memberRevision = 8L, reason = "join")
        h.emit(event)
        assertEquals(listOf(event), h.revisionEvents)
    }

    @Test
    fun `user online updates contact presence`() = runTest(dispatcher) {
        val h = Harness()
        val c = h.controller(backgroundScope)
        c.start()
        h.emit(WebSocketEvent.UserOnline(userId = "u2", isOnline = true, lastSeen = 999L))
        assertTrue(h.uiState.value.contact.isOnline)
        assertEquals(999L, h.uiState.value.contact.lastSeen)
    }

    @Test
    fun `revoked user online applies visibility and resolves contact`() = runTest(dispatcher) {
        val h = Harness()
        val c = h.controller(backgroundScope)
        c.start()
        val event = WebSocketEvent.UserOnline(
            userId = "u2", isOnline = false, lastSeen = 0L, onlineRevoked = true,
        )
        h.emit(event)
        assertEquals(listOf(event), h.visibilityApplied)
        assertFalse(h.uiState.value.contact.isOnline)
    }

    @Test
    fun `connected success clears the connection failed warning`() = runTest(dispatcher) {
        val h = Harness(ChatDetailUiState(contact = peer, chat = chat, groupEncryptionWarning = "连接失败"))
        val c = h.controller(backgroundScope)
        c.start()
        h.emit(WebSocketEvent.Connected(success = true))
        assertNull(h.uiState.value.groupEncryptionWarning)
    }

    @Test
    fun `disconnected shows banner after delay unless already reconnected`() = runTest(dispatcher) {
        val h = Harness()
        val c = h.controller(backgroundScope)
        c.start()
        h.emit(WebSocketEvent.Disconnected)
        advanceTimeBy(RealtimeDisconnectPolicy.BANNER_DELAY_MS + 1)
        assertEquals("连接失败", h.uiState.value.groupEncryptionWarning)

        val reconnected = Harness()
        val c2 = reconnected.controller(backgroundScope)
        c2.start()
        reconnected.emit(WebSocketEvent.Disconnected)
        reconnected.realtime.connected = true
        advanceTimeBy(RealtimeDisconnectPolicy.BANNER_DELAY_MS + 1)
        assertNull(reconnected.uiState.value.groupEncryptionWarning)
    }

    @Test
    fun `connection error schedules banner while other errors warn inline`() = runTest(dispatcher) {
        val h = Harness()
        val c = h.controller(backgroundScope)
        c.start()
        h.emit(WebSocketEvent.Error(kind = WebSocketErrorKind.CONNECTION, debugDetail = "boom"))
        assertEquals(1, h.warnings.size)
        advanceTimeBy(RealtimeDisconnectPolicy.BANNER_DELAY_MS + 1)
        assertEquals("连接失败", h.uiState.value.groupEncryptionWarning)

        val data = Harness()
        val c2 = data.controller(backgroundScope)
        c2.start()
        data.emit(WebSocketEvent.Error(kind = WebSocketErrorKind.MESSAGE_PARSE, debugDetail = "bad"))
        assertEquals("数据无效", data.uiState.value.groupEncryptionWarning)
    }

    @Test
    fun `server error shows message truncated to 120 chars`() = runTest(dispatcher) {
        val h = Harness()
        val c = h.controller(backgroundScope)
        c.start()
        h.emit(WebSocketEvent.ServerError(code = "500", message = "x".repeat(200)))
        assertEquals("x".repeat(120), h.uiState.value.groupEncryptionWarning)
    }

    @Test
    fun `composer typing announces then stops after debounce`() = runTest(dispatcher) {
        val h = Harness()
        val c = h.controller(backgroundScope)
        c.start()
        c.onComposerTextChanged(true)
        assertEquals(
            listOf(Triple(SessionContext("owner-1", 1L), "c1", true)),
            h.realtime.typingSends,
        )
        advanceTimeBy(3_001L)
        assertEquals(
            listOf(
                Triple(SessionContext("owner-1", 1L), "c1", true),
                Triple(SessionContext("owner-1", 1L), "c1", false),
            ),
            h.realtime.typingSends,
        )
    }

    @Test
    fun `composer typing is blocked when indicators disabled`() = runTest(dispatcher) {
        val h = Harness().also { it.typingIndicatorsEnabled = false }
        val c = h.controller(backgroundScope)
        c.start()
        c.onComposerTextChanged(true)
        assertTrue(h.realtime.typingSends.isEmpty())
    }

    @Test
    fun `clear stops the announcement and remote typing`() = runTest(dispatcher) {
        val h = Harness()
        val c = h.controller(backgroundScope)
        c.start()
        c.onComposerTextChanged(true)
        assertEquals(1, h.realtime.typingSends.size)
        c.clear()
        assertEquals(
            listOf(
                Triple(SessionContext("owner-1", 1L), "c1", true),
                Triple(SessionContext("owner-1", 1L), "c1", false),
            ),
            h.realtime.typingSends,
        )
        assertNull(h.uiState.value.typingContact)
    }

    @Test
    fun `presence fallback poll fetches and applies contact presence`() = runTest(dispatcher) {
        val h = Harness()
        val c = h.controller(backgroundScope)
        c.start()
        advanceTimeBy(30_001L)
        assertEquals(listOf("tok" to "u2"), h.presenceFetches)
        assertTrue(h.uiState.value.contact.isOnline)
        assertEquals(12_345L, h.uiState.value.contact.lastSeen)
    }

    @Test
    fun `presence fallback poll skips group chats`() = runTest(dispatcher) {
        val groupChat = Chat(id = "c1", participants = listOf(me, peer), isGroup = true)
        val h = Harness(ChatDetailUiState(contact = peer, chat = groupChat, chatIsGroup = true))
        val c = h.controller(backgroundScope)
        c.start()
        advanceTimeBy(30_001L)
        assertTrue(h.presenceFetches.isEmpty())
    }
}
