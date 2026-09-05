package com.maodouchat.ui.screen.contacts

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.maodouchat.contacts.sync.ContactSyncEvent
import com.maodouchat.contacts.sync.ContactsRealtimeSyncCoordinator
import com.maodouchat.contacts.usecase.ContactMutationUseCase
import com.maodouchat.contacts.usecase.FriendRequestItem
import com.maodouchat.contacts.usecase.FriendRequestsSnapshot
import com.maodouchat.contacts.usecase.FriendRequestUseCase
import com.maodouchat.conversation.ConversationCreationPort
import com.maodouchat.core.realtime.RealtimeDomainEvent
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.User
import com.maodouchat.network.GroupInviteAcceptResponse
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModelTest {

    private lateinit var testDispatcher: TestDispatcher
    private val mockApplication = mockk<Application>(relaxed = true)
    private val createdViewModels = mutableListOf<ContactsViewModel>()

    @Before
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        io.mockk.mockkStatic(android.util.Log::class)
        io.mockk.every { android.util.Log.d(any(), any<String>()) } returns 0
        io.mockk.every { android.util.Log.i(any(), any<String>()) } returns 0
        io.mockk.every { android.util.Log.w(any(), any<String>()) } returns 0
        io.mockk.every { android.util.Log.w(any(), any<String>(), any()) } returns 0
        io.mockk.every { android.util.Log.e(any(), any<String>()) } returns 0
        io.mockk.every { android.util.Log.e(any(), any<String>(), any()) } returns 0

        io.mockk.mockkObject(com.maodouchat.network.TokenManager)
        val mockTm = io.mockk.mockk<com.maodouchat.network.TokenManager>(relaxed = true)
        io.mockk.every { com.maodouchat.network.TokenManager.getInstance(any()) } returns mockTm
        io.mockk.every { mockTm.getUserId() } returns "owner-1"
        io.mockk.every { mockTm.getToken() } returns "test-token"
    }

    @After
    fun tearDown() {
        createdViewModels.forEach { vm ->
            vm.viewModelScope.coroutineContext.job.cancelChildren()
        }
        createdViewModels.clear()
        Dispatchers.resetMain()
        io.mockk.unmockkAll()
    }

    private fun testUser(id: String, name: String) = User(
        id = id,
        name = name,
        email = "$id@example.com",
        isOnline = true
    )

    private fun createFakeContactsRepository(
        session: ContactsSession = ContactsSession("owner-1"),
        friends: List<User> = listOf(testUser("friend-1", "Alice"))
    ) = object : ContactsRepository {
        override fun currentSession(): ContactsSession? = session
        override fun isCurrent(session: ContactsSession): Boolean = true
        override fun observeFriends(session: ContactsSession): Flow<List<User>> = flowOf(friends)
        override suspend fun loadFriends(session: ContactsSession): ContactsLoadResult = ContactsLoadResult(friends)
        override suspend fun search(session: ContactsSession, query: String): ContactsLoadResult =
            ContactsLoadResult(friends.filter { it.name.contains(query, ignoreCase = true) })
    }

    private fun createFakeFriendRequestUseCase(
        onSend: (targetId: String, msg: String) -> Result<FriendRequestItem> = { id, msg ->
            Result.success(FriendRequestItem("req-1", testUser(id, "User $id"), msg, 1000L))
        },
        onAccept: (requestId: String) -> Result<FriendRequestItem?> = { id ->
            Result.success(FriendRequestItem(id, testUser("user-$id", "Accepted $id"), "", 1000L))
        },
        onReject: (requestId: String) -> Result<Unit> = { Result.success(Unit) },
        onCancel: (requestId: String) -> Result<Unit> = { Result.success(Unit) },
        onBatchAccept: (requestIds: List<String>) -> Map<String, Result<FriendRequestItem?>> = { ids ->
            ids.associateWith { Result.success(FriendRequestItem(it, testUser(it, "Accepted $it"), "", 1000L)) }
        },
        onBatchReject: (requestIds: List<String>) -> Map<String, Result<Unit>> = { ids ->
            ids.associateWith { Result.success(Unit) }
        },
        onLoad: () -> Result<FriendRequestsSnapshot> = {
            Result.success(FriendRequestsSnapshot(emptyList(), emptyList()))
        }
    ) = object : FriendRequestUseCase {
        override suspend fun loadRequests(): Result<FriendRequestsSnapshot> = onLoad()
        override suspend fun sendFriendRequest(targetUserId: String, note: String) = onSend(targetUserId, note)
        override suspend fun acceptFriendRequest(requestId: String) = onAccept(requestId)
        override suspend fun rejectFriendRequest(requestId: String) = onReject(requestId)
        override suspend fun cancelFriendRequest(requestId: String) = onCancel(requestId)
        override suspend fun batchAcceptFriendRequests(requestIds: List<String>) = onBatchAccept(requestIds)
        override suspend fun batchRejectFriendRequests(requestIds: List<String>) = onBatchReject(requestIds)
    }

    private fun createFakeContactMutationUseCase(
        onSetNickname: (userId: String, nickname: String) -> Result<Unit> = { _, _ -> Result.success(Unit) },
        onRemoveFriend: (userId: String) -> Result<Unit> = { Result.success(Unit) },
        onBlockUser: (userId: String) -> Result<Unit> = { Result.success(Unit) },
        onUnblockUser: (userId: String) -> Result<Unit> = { Result.success(Unit) }
    ) = object : ContactMutationUseCase {
        override suspend fun setNickname(userId: String, nickname: String) = onSetNickname(userId, nickname)
        override suspend fun removeFriend(userId: String) = onRemoveFriend(userId)
        override suspend fun blockUser(userId: String) = onBlockUser(userId)
        override suspend fun unblockUser(userId: String) = onUnblockUser(userId)
    }

    private fun createFakeConversationCreationPort(
        onCreateDirect: (peerUserId: String) -> Result<Chat> = { id ->
            Result.success(Chat(id = "chat-$id", isGroup = false, chatType = "DIRECT"))
        },
        onCreateSecret: (peerUserId: String) -> Result<Chat> = { id ->
            Result.success(Chat(id = "secret-$id", isGroup = false, chatType = "SECRET"))
        },
        onCreateGroup: (name: String, memberIds: List<String>) -> Result<Chat> = { name, _ ->
            Result.success(Chat(id = "grp-1", isGroup = true, chatType = "GROUP", groupName = name))
        },
        onCreateChannel: (name: String, memberIds: List<String>) -> Result<Chat> = { name, _ ->
            Result.success(Chat(id = "chan-1", isGroup = true, chatType = "CHANNEL", groupName = name))
        }
    ) = object : ConversationCreationPort {
        override suspend fun createDirectChat(peerUserId: String) = onCreateDirect(peerUserId)
        override suspend fun createSecretChat(peerUserId: String) = onCreateSecret(peerUserId)
        override suspend fun createGroupChat(name: String, memberUserIds: List<String>) = onCreateGroup(name, memberUserIds)
        override suspend fun createChannelChat(name: String, memberUserIds: List<String>) = onCreateChannel(name, memberUserIds)
    }

    private fun createFakeRealtimeCoordinator() = object : ContactsRealtimeSyncCoordinator {
        override val syncEvents: Flow<ContactSyncEvent> = MutableSharedFlow()
        override suspend fun handleEvent(
            event: RealtimeDomainEvent,
            expectedOwnerUserId: String,
            currentToken: String
        ) = Unit

        override fun startObserving(
            scope: CoroutineScope,
            eventsFlow: Flow<RealtimeDomainEvent>,
            sessionProvider: () -> Pair<String?, String?>
        ): Job = Job()
    }

    private fun buildTestViewModel(
        contactsController: ContactsController = ContactsController(createFakeContactsRepository()),
        friendRequestUseCase: FriendRequestUseCase = createFakeFriendRequestUseCase(),
        contactMutationUseCase: ContactMutationUseCase = createFakeContactMutationUseCase(),
        conversationCreationPort: ConversationCreationPort = createFakeConversationCreationPort(),
        realtimeSyncCoordinator: ContactsRealtimeSyncCoordinator = createFakeRealtimeCoordinator()
    ): ContactsViewModel {
        val vm = ContactsViewModel(
            application = mockApplication,
            contactsController = contactsController,
            friendRequestUseCase = friendRequestUseCase,
            contactMutationUseCase = contactMutationUseCase,
            conversationCreationPort = conversationCreationPort,
            realtimeSyncCoordinator = realtimeSyncCoordinator,
            groupInviteLoader = { Result.success(emptyList()) },
            groupInviteAcceptor = { _, _ -> Result.success(GroupInviteAcceptResponse("ok")) },
            groupInviteDecliner = { _, _ -> Result.success(GroupInviteAcceptResponse("ok")) }
        )
        createdViewModels.add(vm)
        return vm
    }

    @Test
    fun initialState_loadsFriendsAndRequests() = runTest {
        val friends = listOf(testUser("u1", "Alice"), testUser("u2", "Bob"))
        val requests = FriendRequestsSnapshot(
            incoming = listOf(FriendRequestItem("r1", testUser("u3", "Charlie"), "Hi", 100L)),
            outgoing = emptyList()
        )

        val vm = buildTestViewModel(
            contactsController = ContactsController(createFakeContactsRepository(friends = friends)),
            friendRequestUseCase = createFakeFriendRequestUseCase(onLoad = { Result.success(requests) })
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(2, state.contacts.size)
        assertEquals(1, state.incomingRequests.size)
        assertEquals("Charlie", state.incomingRequests[0].userName)
        assertFalse(state.isLoading)
        assertFalse(state.isFriendActionBusy)
    }

    @Test
    fun onlineOnlyToggle_updatesUiState() = runTest {
        val vm = buildTestViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.onlineOnly)
        vm.setOnlineOnly(true)
        assertTrue(vm.uiState.value.onlineOnly)
        vm.setOnlineOnly(false)
        assertFalse(vm.uiState.value.onlineOnly)
    }

    @Test
    fun contactMutations_delegateToUseCase() = runTest {
        val nicknameCalled = AtomicBoolean(false)
        val removeCalled = AtomicBoolean(false)
        val blockCalled = AtomicBoolean(false)

        val mutationUseCase = createFakeContactMutationUseCase(
            onSetNickname = { userId, nick ->
                assertEquals("friend-1", userId)
                assertEquals("BFF", nick)
                nicknameCalled.set(true)
                Result.success(Unit)
            },
            onRemoveFriend = { userId ->
                assertEquals("friend-1", userId)
                removeCalled.set(true)
                Result.success(Unit)
            },
            onBlockUser = { userId ->
                assertEquals("friend-1", userId)
                blockCalled.set(true)
                Result.success(Unit)
            }
        )

        val vm = buildTestViewModel(contactMutationUseCase = mutationUseCase)
        testDispatcher.scheduler.advanceUntilIdle()

        val user = testUser("friend-1", "Alice")
        vm.setContactNickname(user, "BFF")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(nicknameCalled.get())

        vm.removeFriend(user)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(removeCalled.get())

        vm.blockUser(user)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(blockCalled.get())
    }

    @Test
    fun conversationCreation_directAndGroupChats() = runTest {
        val directCreated = AtomicBoolean(false)
        val groupCreated = AtomicBoolean(false)

        val creationPort = createFakeConversationCreationPort(
            onCreateDirect = { id ->
                assertEquals("u2", id)
                directCreated.set(true)
                Result.success(Chat(id = "chat-u2", isGroup = false))
            },
            onCreateGroup = { name, ids ->
                assertEquals("Project Team", name)
                assertEquals(listOf("u2", "u3"), ids)
                groupCreated.set(true)
                Result.success(Chat(id = "group-1", isGroup = true, groupName = name))
            }
        )

        val vm = buildTestViewModel(conversationCreationPort = creationPort)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.createDirectChat(testUser("u2", "Bob"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(directCreated.get())
        assertEquals("chat-u2", vm.uiState.value.createdChatId)

        vm.clearCreatedChat()
        assertNull(vm.uiState.value.createdChatId)

        vm.createGroupChat("Project Team", listOf(testUser("u2", "Bob"), testUser("u3", "Charlie")))
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(groupCreated.get())
        assertEquals("group-1", vm.uiState.value.createdChatId)
    }

    @Test
    fun friendRequestActions_acceptRejectCancel() = runTest {
        val acceptCalled = AtomicBoolean(false)
        val rejectCalled = AtomicBoolean(false)
        val cancelCalled = AtomicBoolean(false)

        val friendRequestUseCase = createFakeFriendRequestUseCase(
            onAccept = { reqId ->
                assertEquals("req-100", reqId)
                acceptCalled.set(true)
                Result.success(FriendRequestItem(reqId, testUser("u100", "New Friend"), "", 1000L))
            },
            onReject = { reqId ->
                assertEquals("req-101", reqId)
                rejectCalled.set(true)
                Result.success(Unit)
            },
            onCancel = { reqId ->
                assertEquals("req-102", reqId)
                cancelCalled.set(true)
                Result.success(Unit)
            }
        )

        val vm = buildTestViewModel(friendRequestUseCase = friendRequestUseCase)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.acceptFriendRequest("req-100")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(acceptCalled.get())

        vm.rejectFriendRequest("req-101")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(rejectCalled.get())

        vm.cancelFriendRequest("req-102")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(cancelCalled.get())
    }

    @Test
    fun batchFriendRequestActions_delegatesProperly() = runTest {
        val batchAcceptCalled = AtomicBoolean(false)
        val batchRejectCalled = AtomicBoolean(false)

        val requests = listOf(
            FriendRequestItem("r1", testUser("u1", "User 1"), "Hi", 100L),
            FriendRequestItem("r2", testUser("u2", "User 2"), "Hi", 200L)
        )

        val friendRequestUseCase = createFakeFriendRequestUseCase(
            onLoad = { Result.success(FriendRequestsSnapshot(incoming = requests, outgoing = emptyList())) },
            onBatchAccept = { ids ->
                assertEquals(listOf("r1", "r2"), ids)
                batchAcceptCalled.set(true)
                ids.associateWith { Result.success(FriendRequestItem(it, testUser(it, "User"), "", 1000L)) }
            },
            onBatchReject = { ids ->
                assertEquals(listOf("r1", "r2"), ids)
                batchRejectCalled.set(true)
                ids.associateWith { Result.success(Unit) }
            }
        )

        val vm = buildTestViewModel(friendRequestUseCase = friendRequestUseCase)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.acceptAllFriendRequests()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(batchAcceptCalled.get())

        vm.rejectAllFriendRequests()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(batchRejectCalled.get())
    }
}
