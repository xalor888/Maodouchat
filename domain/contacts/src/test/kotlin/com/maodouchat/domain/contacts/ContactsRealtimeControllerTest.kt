package com.maodouchat.domain.contacts

import com.maodouchat.core.model.ContactsLoadResult
import com.maodouchat.core.model.ContactsSession
import com.maodouchat.core.model.User
import com.maodouchat.core.realtime.RealtimeDomainEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactsRealtimeControllerTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private class FakeSyncCoordinator(
        var events: MutableSharedFlow<ContactSyncEvent> = MutableSharedFlow(extraBufferCapacity = 8),
    ) : ContactsRealtimeSyncCoordinator {
        override val syncEvents: Flow<ContactSyncEvent> = events
        var observedFlow: Flow<RealtimeDomainEvent>? = null
        var observedSession: Pair<String?, String?>? = null

        override suspend fun handleEvent(
            event: RealtimeDomainEvent,
            expectedOwnerUserId: String,
            currentToken: String,
        ) = Unit

        override fun startObserving(
            scope: CoroutineScope,
            eventsFlow: Flow<RealtimeDomainEvent>,
            sessionProvider: () -> Pair<String?, String?>,
        ): Job {
            observedFlow = eventsFlow
            observedSession = sessionProvider()
            return Job()
        }
    }

    private class FakeContactsRepository(
        var session: ContactsSession? = ContactsSession("me"),
    ) : ContactsRepository {
        val loadFriendsCalls = mutableListOf<ContactsSession>()

        override fun currentSession(): ContactsSession? = session

        override fun isCurrent(session: ContactsSession): Boolean = true

        override fun observeFriends(session: ContactsSession): Flow<List<User>> =
            flowOf(emptyList())

        override suspend fun loadFriends(session: ContactsSession): ContactsLoadResult {
            loadFriendsCalls += session
            return ContactsLoadResult(emptyList())
        }

        override suspend fun search(session: ContactsSession, query: String): ContactsLoadResult =
            ContactsLoadResult(emptyList())
    }

    private fun controller(
        scope: CoroutineScope,
        coordinator: FakeSyncCoordinator,
        repository: FakeContactsRepository,
        eventsFlow: Flow<RealtimeDomainEvent>? = MutableSharedFlow(),
        currentSession: () -> Pair<String?, String?> = { Pair("me", "tok") },
        onFriendRequestsNeedsRefresh: () -> Unit = {},
        onGroupInvitesNeedsRefresh: () -> Unit = {},
    ) = ContactsRealtimeController(
        scope = scope,
        eventsFlow = eventsFlow,
        currentSession = currentSession,
        realtimeSyncCoordinator = coordinator,
        contactsController = ContactsController(repository),
        onFriendRequestsNeedsRefresh = onFriendRequestsNeedsRefresh,
        onGroupInvitesNeedsRefresh = onGroupInvitesNeedsRefresh,
    )

    @Test
    fun `start subscribes coordinator with events flow and current session`() = runTest(dispatcher) {
        val coordinator = FakeSyncCoordinator()
        val eventsFlow = MutableSharedFlow<RealtimeDomainEvent>()
        val c = controller(backgroundScope, coordinator, FakeContactsRepository(), eventsFlow = eventsFlow)
        c.start()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(coordinator.observedFlow === eventsFlow)
        assertEquals(Pair("me", "tok"), coordinator.observedSession)
    }

    @Test
    fun `null events flow does not subscribe`() = runTest(dispatcher) {
        val coordinator = FakeSyncCoordinator()
        val c = controller(backgroundScope, coordinator, FakeContactsRepository(), eventsFlow = null)
        c.start()
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(coordinator.observedFlow)
    }

    @Test
    fun `friend requests refresh event routes to callback`() = runTest(dispatcher) {
        val coordinator = FakeSyncCoordinator()
        var calls = 0
        val c = controller(
            backgroundScope,
            coordinator,
            FakeContactsRepository(),
            onFriendRequestsNeedsRefresh = { calls++ },
        )
        c.start()
        dispatcher.scheduler.advanceUntilIdle()
        coordinator.events.emit(ContactSyncEvent.FriendRequestsNeedsRefresh)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, calls)
    }

    @Test
    fun `group invites refresh event routes to callback`() = runTest(dispatcher) {
        val coordinator = FakeSyncCoordinator()
        var calls = 0
        val c = controller(
            backgroundScope,
            coordinator,
            FakeContactsRepository(),
            onGroupInvitesNeedsRefresh = { calls++ },
        )
        c.start()
        dispatcher.scheduler.advanceUntilIdle()
        coordinator.events.emit(ContactSyncEvent.GroupInvitesNeedsRefresh)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, calls)
    }

    @Test
    fun `friend accepted reloads friends for current session`() = runTest(dispatcher) {
        val coordinator = FakeSyncCoordinator()
        val repository = FakeContactsRepository()
        val c = controller(backgroundScope, coordinator, repository)
        c.start()
        dispatcher.scheduler.advanceUntilIdle()
        coordinator.events.emit(ContactSyncEvent.FriendAccepted("bob", "Bob"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(ContactsSession("me")), repository.loadFriendsCalls)
    }

    @Test
    fun `friend accepted with no session does nothing`() = runTest(dispatcher) {
        val coordinator = FakeSyncCoordinator()
        val repository = FakeContactsRepository(session = null)
        val c = controller(backgroundScope, coordinator, repository)
        c.start()
        dispatcher.scheduler.advanceUntilIdle()
        coordinator.events.emit(ContactSyncEvent.FriendAccepted("bob", "Bob"))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(repository.loadFriendsCalls.isEmpty())
    }

    @Test
    fun `friend removed is ignored`() = runTest(dispatcher) {
        val coordinator = FakeSyncCoordinator()
        val repository = FakeContactsRepository()
        var refreshCalls = 0
        val c = controller(
            backgroundScope,
            coordinator,
            repository,
            onFriendRequestsNeedsRefresh = { refreshCalls++ },
        )
        c.start()
        dispatcher.scheduler.advanceUntilIdle()
        coordinator.events.emit(ContactSyncEvent.FriendRemoved("bob"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, refreshCalls)
        assertTrue(repository.loadFriendsCalls.isEmpty())
    }
}
