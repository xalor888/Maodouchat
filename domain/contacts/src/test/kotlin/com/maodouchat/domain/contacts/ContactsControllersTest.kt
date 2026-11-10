package com.maodouchat.domain.contacts

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ContactMutationUseCase
import com.maodouchat.core.model.ContactsLoadResult
import com.maodouchat.core.model.ContactsSession
import com.maodouchat.core.model.ContactsUiState
import com.maodouchat.core.model.ConversationCreationPort
import com.maodouchat.core.model.FriendRequestItem
import com.maodouchat.core.model.FriendRequestsSnapshot
import com.maodouchat.core.model.FriendRequestUseCase
import com.maodouchat.core.model.GroupInvitationDto
import com.maodouchat.core.model.GroupInviteAcceptResponse
import com.maodouchat.core.model.GroupInviteItem
import com.maodouchat.core.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactsControllersTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun user(id: String) = User(id = id, name = "User $id")

    private fun request(id: String) =
        FriendRequestItem(id = id, user = user("u-$id"))

    private class FakeContactsRepository(
        var session: ContactsSession? = ContactsSession("me"),
        var current: Boolean = true,
        var friends: List<User> = emptyList(),
        var loadResult: ContactsLoadResult = ContactsLoadResult(emptyList()),
        var searchResult: ContactsLoadResult = ContactsLoadResult(emptyList()),
    ) : ContactsRepository {
        var loadCalls = 0
        val searchQueries = mutableListOf<String>()

        override fun currentSession() = session

        override fun isCurrent(session: ContactsSession) = current

        override fun observeFriends(session: ContactsSession): Flow<List<User>> =
            flowOf(friends)

        override suspend fun loadFriends(session: ContactsSession): ContactsLoadResult {
            loadCalls += 1
            return loadResult
        }

        override suspend fun search(session: ContactsSession, query: String): ContactsLoadResult {
            searchQueries += query
            return searchResult
        }
    }

    private class FakeFriendRequestUseCase : FriendRequestUseCase {
        var snapshot = FriendRequestsSnapshot()
        var acceptResult: Result<FriendRequestItem?> = Result.success(null)
        var rejectResult: Result<Unit> = Result.success(Unit)
        var cancelResult: Result<Unit> = Result.success(Unit)
        var sendResult: Result<FriendRequestItem> =
            Result.success(FriendRequestItem("r1", User(id = "u1", name = "A")))
        var batchAcceptResult: Map<String, Result<FriendRequestItem?>> = emptyMap()
        var batchRejectResult: Map<String, Result<Unit>> = emptyMap()
        val acceptCalls = mutableListOf<String>()
        var sendCalls = 0

        override suspend fun loadRequests() = Result.success(snapshot)

        override suspend fun sendFriendRequest(targetUserId: String, note: String) =
            sendResult.also { sendCalls += 1 }

        override suspend fun acceptFriendRequest(requestId: String) =
            acceptResult.also { acceptCalls += requestId }

        override suspend fun rejectFriendRequest(requestId: String) = rejectResult

        override suspend fun cancelFriendRequest(requestId: String) = cancelResult

        override suspend fun batchAcceptFriendRequests(requestIds: List<String>) =
            batchAcceptResult

        override suspend fun batchRejectFriendRequests(requestIds: List<String>) =
            batchRejectResult
    }

    private class FakeContactMutationUseCase : ContactMutationUseCase {
        var nicknameResult: Result<Unit> = Result.success(Unit)
        var removeResult: Result<Unit> = Result.success(Unit)
        var blockResult: Result<Unit> = Result.success(Unit)
        var unblockResult: Result<Unit> = Result.success(Unit)

        override suspend fun setNickname(userId: String, nickname: String) = nicknameResult

        override suspend fun removeFriend(userId: String) = removeResult

        override suspend fun blockUser(userId: String) = blockResult

        override suspend fun unblockUser(userId: String) = unblockResult
    }

    private class FakeConversationCreationPort : ConversationCreationPort {
        var result: Result<Chat> = Result.success(Chat(id = "c9"))
        val directCalls = mutableListOf<String>()
        var groupCalls = 0

        override suspend fun createDirectChat(peerUserId: String) =
            result.also { directCalls += peerUserId }

        override suspend fun createSecretChat(peerUserId: String) = result

        override suspend fun createGroupChat(name: String, memberUserIds: List<String>) =
            result.also { groupCalls += 1 }

        override suspend fun createChannelChat(name: String, memberUserIds: List<String>) = result
    }

    private class Harness(
        initial: ContactsUiState,
        val dispatcher: TestDispatcher,
    ) {
        var live: ContactsUiState = initial
        val scope = CoroutineScope(dispatcher)
        val repository = FakeContactsRepository()
        val controller = ContactsController(repository)
        val friendRequests = FakeFriendRequestUseCase()
        val mutations = FakeContactMutationUseCase()
        val creation = FakeConversationCreationPort()
        var reloadContactsCalls = 0
        var friendRequestsEnabled = true
        var secretChatEnabled = true
        var hasSession = true
        var sessionOwner = "me"
        var sessionMayContinue = true
        var inviteAcceptedCalls = 0
        var loaderCalls = 0
        var loaderResult: Result<List<GroupInvitationDto>> = Result.success(emptyList())
        var acceptorResult: Result<GroupInviteAcceptResponse> =
            Result.success(GroupInviteAcceptResponse())
        var declinerResult: Result<GroupInviteAcceptResponse> =
            Result.success(GroupInviteAcceptResponse())

        private val text: (ContactsMessage, Array<out Any>) -> String =
            { message, args -> "[${message.name}:${args.joinToString(",")}]" }

        val load = ContactsLoadController(
            scope = scope,
            updateState = { transform -> live = transform(live) },
            text = text,
            contactsController = controller,
        )

        val search = ContactsSearchController(
            scope = scope,
            updateState = { transform -> live = transform(live) },
            text = text,
            contactsController = controller,
        )

        val friendActions = ContactsFriendActionController(
            scope = scope,
            updateState = { transform -> live = transform(live) },
            isFriendActionBusy = { live.isFriendActionBusy },
            incomingRequestIds = { live.incomingRequests.map { it.id } },
            text = text,
            friendRequestUseCase = friendRequests,
            contactMutationUseCase = mutations,
            friendRequestsEnabled = { friendRequestsEnabled },
            reloadContacts = { reloadContactsCalls += 1 },
        )

        val chatCreation = ContactsChatCreationController(
            scope = scope,
            updateState = { transform -> live = transform(live) },
            isCreatingChat = { live.isCreatingChat },
            text = text,
            conversationCreationPort = creation,
            secretChatEnabled = { secretChatEnabled },
        )

        val invites = ContactsGroupInviteController(
            scope = scope,
            updateState = { transform -> live = transform(live) },
            isGroupInviteBusy = { live.isGroupInviteBusy },
            hasSession = { hasSession },
            sessionOwnerUserId = { sessionOwner },
            sessionMayContinue = { sessionMayContinue },
            text = text,
            groupInviteLoader = {
                loaderCalls += 1
                loaderResult
            },
            groupInviteAcceptor = { acceptorResult },
            groupInviteDecliner = { declinerResult },
            onInviteAccepted = { inviteAcceptedCalls += 1 },
        )
    }

    private fun harness(initial: ContactsUiState = ContactsUiState()) =
        Harness(initial, dispatcher)

    @Test
    fun `load without session reports session expired`() = runTest(dispatcher) {
        val h = harness()
        h.repository.session = null
        h.load.reloadContacts()
        advanceUntilIdle()

        assertEquals("[SESSION_EXPIRED:]", h.live.errorMessage)
        assertFalse(h.live.isLoading)
        assertTrue(h.live.contacts.isEmpty())
    }

    @Test
    fun `load success fills contacts and clears loading`() = runTest(dispatcher) {
        val h = harness()
        val friends = listOf(user("u1"), user("u2"))
        h.repository.friends = friends
        h.repository.loadResult = ContactsLoadResult(friends)
        h.load.reloadContacts()
        advanceUntilIdle()

        assertEquals(friends, h.live.contacts)
        assertFalse(h.live.isLoading)
        assertEquals(null, h.live.errorMessage)
    }

    @Test
    fun `short search query clears results without network`() = runTest(dispatcher) {
        val h = harness(ContactsUiState(searchResults = listOf(user("u1"))))
        h.search.onSearchQueryChange("a")
        advanceUntilIdle()

        assertTrue(h.live.searchResults.isEmpty())
        assertFalse(h.live.isSearching)
        assertFalse(h.live.searchFailed)
        assertTrue(h.repository.searchQueries.isEmpty())
    }

    @Test
    fun `search without session fails`() = runTest(dispatcher) {
        val h = harness()
        h.repository.session = null
        h.search.onSearchQueryChange("alice")
        advanceUntilIdle()

        assertTrue(h.live.searchFailed)
        assertEquals("[SESSION_EXPIRED:]", h.live.errorMessage)
    }

    @Test
    fun `search success fills results`() = runTest(dispatcher) {
        val h = harness()
        h.repository.searchResult = ContactsLoadResult(listOf(user("u1")))
        h.search.onSearchQueryChange("alice")
        advanceUntilIdle()

        assertEquals(listOf(user("u1")), h.live.searchResults)
        assertFalse(h.live.isSearching)
        assertFalse(h.live.searchFailed)
        assertEquals(listOf("alice"), h.repository.searchQueries)
    }

    @Test
    fun `accept friend request shows accepted and refreshes`() = runTest(dispatcher) {
        val h = harness(ContactsUiState(incomingRequests = listOf(request("r1"))))
        h.friendRequests.snapshot = FriendRequestsSnapshot(incoming = listOf(request("r1")))
        h.friendActions.acceptFriendRequest("r1")
        advanceUntilIdle()

        assertEquals("[CONTACTS_FRIEND_ACCEPTED:]", h.live.infoMessage)
        assertFalse(h.live.isFriendActionBusy)
        assertEquals(listOf("r1"), h.friendRequests.acceptCalls)
        assertEquals(1, h.reloadContactsCalls)
        assertEquals(listOf(request("r1")), h.live.incomingRequests)
    }

    @Test
    fun `accept friend request failure shows error message`() = runTest(dispatcher) {
        val h = harness()
        h.friendRequests.acceptResult = Result.failure(RuntimeException("boom"))
        h.friendActions.acceptFriendRequest("r1")
        advanceUntilIdle()

        assertEquals("boom", h.live.errorMessage)
        assertFalse(h.live.isFriendActionBusy)
    }

    @Test
    fun `send friend request disabled shows disabled error`() = runTest(dispatcher) {
        val h = harness()
        h.friendRequestsEnabled = false
        h.friendActions.sendFriendRequest(user("u2"), "")
        advanceUntilIdle()

        assertEquals("[FRIEND_REQUESTS_DISABLED:]", h.live.errorMessage)
        assertEquals(0, h.friendRequests.sendCalls)
    }

    @Test
    fun `batch accept partial reports counts`() = runTest(dispatcher) {
        val h = harness(
            ContactsUiState(incomingRequests = listOf(request("r1"), request("r2")))
        )
        h.friendRequests.batchAcceptResult = mapOf(
            "r1" to Result.success(request("r1")),
            "r2" to Result.failure(RuntimeException("gone")),
        )
        h.friendActions.acceptAllFriendRequests()
        advanceUntilIdle()

        assertEquals("[CONTACTS_FRIEND_BATCH_PARTIAL:1,1]", h.live.infoMessage)
        assertFalse(h.live.isFriendActionBusy)
    }

    @Test
    fun `nickname save failure shows error`() = runTest(dispatcher) {
        val h = harness()
        h.mutations.nicknameResult = Result.failure(RuntimeException("boom"))
        h.friendActions.setContactNickname(user("u1"), "Nick")
        advanceUntilIdle()

        assertEquals("boom", h.live.errorMessage)
    }

    @Test
    fun `blank group name is rejected without network`() = runTest(dispatcher) {
        val h = harness()
        h.chatCreation.createGroupChat("   ", listOf(user("u1")))
        advanceUntilIdle()

        assertEquals("[CONTACTS_ENTER_GROUP_NAME:]", h.live.errorMessage)
        assertEquals(0, h.creation.groupCalls)
    }

    @Test
    fun `secret chat disabled shows disabled error`() = runTest(dispatcher) {
        val h = harness()
        h.secretChatEnabled = false
        h.chatCreation.startSecretChat(user("u1"))
        advanceUntilIdle()

        assertEquals("[SECRET_CHAT_FEATURE_DISABLED:]", h.live.errorMessage)
    }

    @Test
    fun `direct chat success stores created chat id`() = runTest(dispatcher) {
        val h = harness()
        h.chatCreation.createDirectChat(user("u2"))
        advanceUntilIdle()

        assertEquals("c9", h.live.createdChatId)
        assertFalse(h.live.isCreatingChat)
        assertEquals(listOf("u2"), h.creation.directCalls)
    }

    @Test
    fun `group invite load maps blank name to unnamed`() = runTest(dispatcher) {
        val h = harness()
        h.loaderResult = Result.success(
            listOf(
                GroupInvitationDto(
                    id = "i1",
                    chatId = "c1",
                    userId = "u9",
                    chatName = "",
                    inviterName = "Bob",
                )
            )
        )
        h.invites.loadGroupInvites()
        advanceUntilIdle()

        assertEquals(1, h.live.groupInvites.size)
        assertEquals("[CONTACTS_GROUP_UNNAMED:]", h.live.groupInvites.first().chatName)
        assertEquals("Bob", h.live.groupInvites.first().inviterName)
    }

    @Test
    fun `group invite accept removes invite and notifies`() = runTest(dispatcher) {
        val h = harness(
            ContactsUiState(
                groupInvites = listOf(GroupInviteItem("i1", "c1", "G", "Bob"))
            )
        )
        h.loaderResult = Result.success(emptyList())
        h.invites.acceptGroupInvite("i1")
        advanceUntilIdle()

        assertTrue(h.live.groupInvites.isEmpty())
        assertEquals("[CONTACTS_GROUP_INVITE_ACCEPTED:]", h.live.infoMessage)
        assertEquals(1, h.inviteAcceptedCalls)
    }

    @Test
    fun `group invite load without session does nothing`() = runTest(dispatcher) {
        val h = harness()
        h.hasSession = false
        h.invites.loadGroupInvites()
        advanceUntilIdle()

        assertEquals(0, h.loaderCalls)
        assertTrue(h.live.groupInvites.isEmpty())
    }
}
