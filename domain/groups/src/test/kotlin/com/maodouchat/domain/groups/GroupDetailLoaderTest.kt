package com.maodouchat.domain.groups

import com.maodouchat.core.model.ChatDto
import com.maodouchat.core.model.GroupAuditLogDto
import com.maodouchat.core.model.GroupDetailUiState
import com.maodouchat.core.model.GroupLifecycleService
import com.maodouchat.core.model.GroupMemberDto
import com.maodouchat.core.model.GroupMessagingCoordinator
import com.maodouchat.core.model.GroupMutationAction
import com.maodouchat.core.model.GroupMutationCommit
import com.maodouchat.core.model.GroupMutationFeedbackKind
import com.maodouchat.core.model.GroupSenderKeyMaintenanceCoordinator
import com.maodouchat.core.model.SenderKeyDistributionStatusDto
import com.maodouchat.core.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GroupDetailLoaderTest {

    private val dispatcher = StandardTestDispatcher()

    private class FakeLifecycleService(
        var details: Result<ChatDto?> = Result.success(null),
        var members: Result<List<GroupMemberDto>> = Result.success(emptyList()),
        var candidates: Result<List<User>> = Result.success(emptyList()),
    ) : GroupLifecycleService {
        var detailsCalls = 0
        override suspend fun fetchGroupDetails(chatId: String): Result<ChatDto?> {
            detailsCalls++
            return details
        }

        override suspend fun fetchGroupMembers(chatId: String): Result<List<GroupMemberDto>> = members
        override suspend fun fetchCandidates(excludeMemberIds: Set<String>, excludeUserId: String): Result<List<User>> = candidates
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
        override suspend fun uploadAvatar(chatId: String, base64: String): Result<String> = error("unused")
    }

    private class Harness(
        initial: GroupDetailUiState = GroupDetailUiState(),
        val service: FakeLifecycleService = FakeLifecycleService(),
        val gateway: FakeGroupNetworkGateway = FakeGroupNetworkGateway(),
        val dispatcher: TestDispatcher,
        sessionActive: (String) -> Boolean = { true },
        isSecretChat: (String) -> Boolean = { false },
        chatId: String = "c1",
        token: String = "t",
        ownerUserId: String = "me",
    ) {
        var live: GroupDetailUiState = initial
        var offset = -1
        var pendingRetry: (() -> Unit)? = null
        var redistributed: GroupDetailUiState? = null
        var markedSecret: String? = null
        val controller = GroupDetailLoader(
            scope = CoroutineScope(dispatcher),
            currentState = { live },
            updateState = { transform -> live = transform(live) },
            text = { it.name },
            sessionActive = sessionActive,
            isSecretChat = isSecretChat,
            markSecretSurfaceActive = { markedSecret = it },
            ioDispatcher = dispatcher,
            chatId = { chatId },
            token = { token },
            ownerUserId = { ownerUserId },
            groupLifecycleService = service,
            groupEncryptionHealthController = GroupEncryptionHealthController(
                maintenanceCoordinator = GroupSenderKeyMaintenanceCoordinator(
                    ensureCoverage = { _, _ -> Result.success(null) },
                    redistribute = { _, _ -> Result.success(null) },
                    hasLocalSenderKey = { _, _ -> false },
                    enqueueRetry = { _, _, _ -> },
                ),
                tokenProvider = { "t" },
                gateway = gateway,
                ioDispatcher = dispatcher,
            ),
            groupAuditController = GroupAuditController(
                tokenProvider = { "t" },
                gateway = gateway,
                ioDispatcher = dispatcher,
            ),
            groupBotController = GroupBotController(tokenProvider = { "t" }, gateway = gateway),
            groupMessagingCoordinator = GroupMessagingCoordinator(
                currentSession = { null },
                invalidatePreparedEpoch = { _, _, _, _ -> },
                invalidateLocalSenderKey = {},
                reconcileAttachments = { _, _ -> },
                ensureCoverageNow = { _, _ -> },
                redistributeCoverageNow = { _ -> false },
                fetchCoverageStatus = { _, _, _, _ -> error("unused") },
                hasLocalSenderKeyMaterial = { _, _ -> true },
                enqueueCoverageRetryCommand = { _, _, _ -> },
            ),
            auditOffsetSet = { offset = it },
            pendingRetrySet = { pendingRetry = it },
            onAutoRedistribute = { redistributed = it },
        )

        // GroupBotController parses on Dispatchers.IO (real thread), so drain the
        // test dispatcher, let the IO hop land, and drain again.
        suspend fun awaitSettled() {
            withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    while (true) {
                        dispatcher.scheduler.advanceUntilIdle()
                        if (!live.isLoading) break
                        delay(10)
                    }
                }
            }
        }
    }

    private fun harness(
        initial: GroupDetailUiState = GroupDetailUiState(),
        service: FakeLifecycleService = FakeLifecycleService(),
        sessionActive: (String) -> Boolean = { true },
        isSecretChat: (String) -> Boolean = { false },
        chatId: String = "c1",
    ) = Harness(initial, service, FakeGroupNetworkGateway(), dispatcher, sessionActive, isSecretChat, chatId)

    private fun successGateway() = FakeGroupNetworkGateway().apply {
        getGroupAudit = { _, _, _, _ ->
            Result.success(listOf(GroupAuditLogDto(id = "a1", actorId = "u1", actorName = "Ann", action = "ADD_MEMBER", createdAt = 1L)))
        }
        listBots = { _ -> Result.success("[]") }
        getSenderKeyDistributionStatus = { _, _, _, _ ->
            Result.success(SenderKeyDistributionStatusDto(chatId = "c1", epoch = 5))
        }
    }

    private fun successService() = FakeLifecycleService(
        details = Result.success(
            ChatDto(id = "c1", groupName = "G", groupAnnouncement = "Ann", memberRevision = 5),
        ),
        members = Result.success(
            listOf(
                GroupMemberDto(userId = "me", name = "Me", role = "OWNER", groupNickname = "Nick"),
                GroupMemberDto(userId = "u2", name = "Two"),
            ),
        ),
        candidates = Result.success(listOf(User(id = "u3", name = "Three"))),
    )

    @Test
    fun `blank chat shows session expired without touching network`() = runTest {
        val h = harness(chatId = "")
        h.controller.load()
        h.awaitSettled()

        assertFalse(h.live.isLoading)
        assertEquals("SESSION_EXPIRED", h.live.message)
        assertEquals(GroupMutationAction.LOAD, h.live.feedback?.action)
        assertNull(h.pendingRetry)
        assertEquals(0, h.service.detailsCalls)
    }

    @Test
    fun `inactive session shows session expired without touching network`() = runTest {
        val h = harness(sessionActive = { false })
        h.controller.load()
        h.awaitSettled()

        assertFalse(h.live.isLoading)
        assertEquals("SESSION_EXPIRED", h.live.message)
        assertEquals(GroupMutationAction.LOAD, h.live.feedback?.action)
        assertNull(h.pendingRetry)
        assertEquals(0, h.service.detailsCalls)
    }

    @Test
    fun `success writes group state and preserves invite fields`() = runTest {
        val initial = GroupDetailUiState(
            groupInvitePayload = "pay",
            inviteExpiresAt = 7,
            inviteMaxUses = 3,
            inviteUsedCount = 1,
            inviteRemainingUses = 2,
            isLoadingInvite = true,
        )
        val h = Harness(initial, successService(), successGateway(), dispatcher)
        h.controller.load()
        h.awaitSettled()

        assertEquals("G", h.live.groupName)
        assertEquals("Ann", h.live.groupAnnouncement)
        assertEquals(5L, h.live.memberRevision)
        assertEquals(listOf("me", "u2"), h.live.members.map { it.userId })
        assertEquals("OWNER", h.live.myRole)
        assertEquals("Nick", h.live.myNickname)
        assertEquals("me", h.live.currentUserId)
        assertEquals(listOf("u3"), h.live.candidates.map { it.id })
        assertEquals(listOf("a1"), h.live.auditLogs.map { it.id })
        assertFalse(h.live.hasMoreAudit)
        assertEquals(1, h.offset)
        assertEquals(5L, h.live.senderKeyStatus?.epoch)
        assertTrue(h.live.localHasSenderKey == true)
        assertTrue(h.live.ownedBots.isEmpty())
        assertFalse(h.live.isLoading)
        assertNull(h.live.message)
        assertEquals("pay", h.live.groupInvitePayload)
        assertEquals(7L, h.live.inviteExpiresAt)
        assertEquals(3, h.live.inviteMaxUses)
        assertEquals(1, h.live.inviteUsedCount)
        assertEquals(2, h.live.inviteRemainingUses)
        assertTrue(h.live.isLoadingInvite)
        assertNull(h.pendingRetry)
        assertNotNull(h.redistributed)
    }

    @Test
    fun `fetch failure shows load failed and retry reloads`() = runTest {
        val service = FakeLifecycleService(details = Result.failure(IllegalStateException()))
        val h = Harness(GroupDetailUiState(), service, successGateway(), dispatcher)
        h.controller.load()
        h.awaitSettled()

        assertFalse(h.live.isLoading)
        assertEquals("LOAD_FAILED", h.live.message)
        assertTrue(h.live.feedback?.canRetry == true)
        assertNotNull(h.pendingRetry)
        assertEquals(1, service.detailsCalls)

        service.details = Result.success(ChatDto(id = "c1", groupName = "G2"))
        h.pendingRetry?.invoke()
        h.awaitSettled()

        assertEquals("G2", h.live.groupName)
        assertEquals(2, service.detailsCalls)
    }

    @Test
    fun `secret chat port marks surface active`() = runTest {
        val h = Harness(GroupDetailUiState(), successService(), successGateway(), dispatcher, isSecretChat = { true })
        h.controller.load()
        h.awaitSettled()

        assertTrue(h.live.isSecretChat)
        assertEquals("c1", h.markedSecret)
    }

    @Test
    fun `feedback message writes success feedback`() = runTest {
        val h = Harness(GroupDetailUiState(), successService(), successGateway(), dispatcher)
        h.controller.load("done!")
        h.awaitSettled()

        assertFalse(h.live.isLoading)
        assertEquals("done!", h.live.message)
        assertEquals(GroupMutationFeedbackKind.SUCCESS, h.live.feedback?.kind)
        assertEquals(GroupMutationAction.LOAD, h.live.feedback?.action)
    }
}
