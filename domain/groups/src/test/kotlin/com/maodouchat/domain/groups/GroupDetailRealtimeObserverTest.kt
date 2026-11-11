package com.maodouchat.domain.groups

import com.maodouchat.core.model.GroupDetailUiState
import com.maodouchat.core.model.GroupMemberUi
import com.maodouchat.core.model.GroupMessagingCoordinator
import com.maodouchat.core.model.GroupMessagingSession
import com.maodouchat.core.model.User
import com.maodouchat.core.realtime.RealtimeDomainEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GroupDetailRealtimeObserverTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private class Harness(
        private val dispatcher: TestDispatcher,
        private val scope: CoroutineScope,
        initial: GroupDetailUiState = GroupDetailUiState(
            currentUserId = "owner1",
            memberRevision = 5,
            members = listOf(GroupMemberUi(userId = "u1", name = "A", isOnline = false)),
            candidates = listOf(User(id = "u2", name = "B", isOnline = false, status = "hi", lastSeen = 7)),
        ),
        sessionActive: Boolean = true,
        eventsFlow: MutableSharedFlow<RealtimeDomainEvent>? = MutableSharedFlow(extraBufferCapacity = 8),
    ) {
        var live: GroupDetailUiState = initial
        var sessionActiveFlag = sessionActive
        val visibilityCalls = mutableListOf<String>()
        val invalidated = mutableListOf<Triple<String, String, Long?>>()
        var reloads = 0
        val events: MutableSharedFlow<RealtimeDomainEvent>? = eventsFlow
        private val coordinator = GroupMessagingCoordinator(
            currentSession = { GroupMessagingSession(ownerUserId = "owner1", authToken = "t", deviceId = 1) },
            invalidatePreparedEpoch = { owner, chat, rev, _ -> invalidated += Triple(owner, chat, rev) },
            invalidateLocalSenderKey = {},
            reconcileAttachments = { _, _ -> },
            ensureCoverageNow = { _, _ -> },
            redistributeCoverageNow = { _ -> true },
            fetchCoverageStatus = { _, _, _, _ -> error("unused") },
            hasLocalSenderKeyMaterial = { _, _ -> false },
            enqueueCoverageRetryCommand = { _, _, _ -> },
        )
        val observer = GroupDetailRealtimeObserver(
            scope = scope,
            currentState = { live },
            updateState = { transform -> live = transform(live) },
            chatId = { "chat1" },
            ownerUserId = { "owner1" },
            groupMessagingCoordinator = coordinator,
            onRevisionReload = { reloads++ },
            eventsFlow = events,
            sessionActive = { _ -> sessionActiveFlag },
            applyRealtimeVisibility = { userId, _, _, _ -> visibilityCalls += userId },
            ioDispatcher = dispatcher,
        )

        suspend fun emit(event: RealtimeDomainEvent) {
            events!!.emit(event)
            dispatcher.scheduler.advanceUntilIdle()
        }
    }

    @Test
    fun `presence updates member online and candidate visibility`() = runTest(dispatcher) {
        val h = Harness(dispatcher, backgroundScope)
        h.observer.start()
        dispatcher.scheduler.advanceUntilIdle()
        h.emit(RealtimeDomainEvent.Presence(userId = "u1", isOnline = true, lastSeen = 100))
        assertTrue(h.live.members.first { it.userId == "u1" }.isOnline)
        h.emit(RealtimeDomainEvent.Presence(userId = "u2", isOnline = true, lastSeen = 200))
        val candidate = h.live.candidates.first { it.id == "u2" }
        assertTrue(candidate.isOnline)
        assertEquals("hi", candidate.status)
        assertEquals(200, candidate.lastSeen)
        assertTrue(h.visibilityCalls.isEmpty())
    }

    @Test
    fun `presence revoked writes visibility through port and clears online`() = runTest(dispatcher) {
        val h = Harness(dispatcher, backgroundScope)
        h.observer.start()
        dispatcher.scheduler.advanceUntilIdle()
        h.emit(RealtimeDomainEvent.Presence(userId = "u1", isOnline = true, onlineRevoked = true))
        assertEquals(listOf("u1"), h.visibilityCalls)
        assertFalse(h.live.members.first { it.userId == "u1" }.isOnline)
    }

    @Test
    fun `presence for unknown user leaves state alone but still writes revoked visibility`() = runTest(dispatcher) {
        val h = Harness(dispatcher, backgroundScope)
        h.observer.start()
        dispatcher.scheduler.advanceUntilIdle()
        val before = h.live
        h.emit(RealtimeDomainEvent.Presence(userId = "ghost", isOnline = true, statusRevoked = true))
        assertEquals(before, h.live)
        assertEquals(listOf("ghost"), h.visibilityCalls)
    }

    @Test
    fun `newer group revision invalidates sender key and reloads`() = runTest(dispatcher) {
        val h = Harness(dispatcher, backgroundScope)
        h.observer.start()
        dispatcher.scheduler.advanceUntilIdle()
        h.emit(RealtimeDomainEvent.GroupRevision(chatId = "chat1", memberRevision = 7, reason = "join"))
        assertEquals(listOf(Triple<String, String, Long?>("owner1", "chat1", 7L)), h.invalidated)
        assertEquals(1, h.reloads)
    }

    @Test
    fun `stale group revision skips invalidate but still reloads`() = runTest(dispatcher) {
        val h = Harness(dispatcher, backgroundScope)
        h.observer.start()
        dispatcher.scheduler.advanceUntilIdle()
        h.emit(RealtimeDomainEvent.GroupRevision(chatId = "chat1", memberRevision = 3, reason = "join"))
        assertTrue(h.invalidated.isEmpty())
        assertEquals(1, h.reloads)
    }

    @Test
    fun `group revision for another chat is ignored`() = runTest(dispatcher) {
        val h = Harness(dispatcher, backgroundScope)
        h.observer.start()
        dispatcher.scheduler.advanceUntilIdle()
        h.emit(RealtimeDomainEvent.GroupRevision(chatId = "other", memberRevision = 9, reason = "join"))
        assertTrue(h.invalidated.isEmpty())
        assertEquals(0, h.reloads)
    }

    @Test
    fun `inactive session skips events entirely`() = runTest(dispatcher) {
        val h = Harness(dispatcher, backgroundScope, sessionActive = false)
        h.observer.start()
        dispatcher.scheduler.advanceUntilIdle()
        val before = h.live
        h.emit(RealtimeDomainEvent.Presence(userId = "u1", isOnline = true, lastSeen = 100))
        h.emit(RealtimeDomainEvent.GroupRevision(chatId = "chat1", memberRevision = 7, reason = "join"))
        assertEquals(before, h.live)
        assertTrue(h.visibilityCalls.isEmpty())
        assertTrue(h.invalidated.isEmpty())
        assertEquals(0, h.reloads)
    }

    @Test
    fun `null events flow starts without subscribing`() = runTest(dispatcher) {
        val h = Harness(dispatcher, backgroundScope, eventsFlow = null)
        h.observer.start()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, h.reloads)
    }
}
