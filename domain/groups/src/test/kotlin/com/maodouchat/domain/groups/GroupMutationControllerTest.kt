package com.maodouchat.domain.groups

import com.maodouchat.core.model.ChatDto
import com.maodouchat.core.model.GroupDetailUiState
import com.maodouchat.core.model.GroupLifecycleService
import com.maodouchat.core.model.GroupMemberDto
import com.maodouchat.core.model.GroupMutationCommit
import com.maodouchat.core.model.GroupMutationFeedbackKind
import com.maodouchat.core.model.User
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GroupMutationControllerTest {

    private val dispatcher = StandardTestDispatcher()

    private fun commit() = GroupMutationCommit(
        refreshedChat = ChatDto(
            id = "c1",
            groupName = "G2",
            groupAnnouncement = "A2",
            groupAvatar = "av2",
            memberRevision = 7L,
        ),
    )

    private class FakeGroupLifecycleService : GroupLifecycleService {
        val calls = mutableListOf<String>()
        var updateGroupInfoFn: suspend (String, String?, String?, String?) -> GroupMutationCommit =
            { _, _, _, _ -> GroupMutationCommit() }
        var updateMyNicknameFn: suspend (String, String) -> GroupMutationCommit =
            { _, _ -> GroupMutationCommit() }
        var addMembersFn: suspend (String, List<String>) -> GroupMutationCommit =
            { _, _ -> GroupMutationCommit() }
        var removeMemberFn: suspend (String, String) -> GroupMutationCommit =
            { _, _ -> GroupMutationCommit() }
        var setRoleFn: suspend (String, String, String) -> GroupMutationCommit =
            { _, _, _ -> GroupMutationCommit() }
        var transferOwnershipFn: suspend (String, String) -> GroupMutationCommit =
            { _, _ -> GroupMutationCommit() }
        var setTitleFn: suspend (String, String, String) -> GroupMutationCommit =
            { _, _, _ -> GroupMutationCommit() }
        var setMemberMuteFn: suspend (String, String, Long) -> GroupMutationCommit =
            { _, _, _ -> GroupMutationCommit() }
        var setMuteAllFn: suspend (String, Boolean) -> GroupMutationCommit =
            { _, _ -> GroupMutationCommit() }
        var uploadAvatarFn: suspend (String, String) -> Result<String> =
            { _, _ -> Result.success("https://avatar/new") }

        override suspend fun addMembers(chatId: String, userIds: List<String>): GroupMutationCommit {
            calls += "addMembers:$userIds"
            return addMembersFn(chatId, userIds)
        }

        override suspend fun removeMember(chatId: String, userId: String): GroupMutationCommit {
            calls += "removeMember:$userId"
            return removeMemberFn(chatId, userId)
        }

        override suspend fun setRole(chatId: String, userId: String, role: String): GroupMutationCommit {
            calls += "setRole:$userId:$role"
            return setRoleFn(chatId, userId, role)
        }

        override suspend fun transferOwnership(chatId: String, newOwnerUserId: String): GroupMutationCommit {
            calls += "transferOwnership:$newOwnerUserId"
            return transferOwnershipFn(chatId, newOwnerUserId)
        }

        override suspend fun setTitle(chatId: String, userId: String, title: String): GroupMutationCommit {
            calls += "setTitle:$userId:$title"
            return setTitleFn(chatId, userId, title)
        }

        override suspend fun setMemberMute(chatId: String, userId: String, mutedUntil: Long): GroupMutationCommit {
            calls += "setMemberMute:$userId:$mutedUntil"
            return setMemberMuteFn(chatId, userId, mutedUntil)
        }

        override suspend fun setMuteAll(chatId: String, muted: Boolean): GroupMutationCommit {
            calls += "setMuteAll:$muted"
            return setMuteAllFn(chatId, muted)
        }

        override suspend fun updateGroupInfo(
            chatId: String,
            name: String?,
            announcement: String?,
            avatar: String?,
        ): GroupMutationCommit {
            calls += "updateGroupInfo:$name"
            return updateGroupInfoFn(chatId, name, announcement, avatar)
        }

        override suspend fun updateMyNickname(chatId: String, nickname: String): GroupMutationCommit {
            calls += "updateMyNickname:$nickname"
            return updateMyNicknameFn(chatId, nickname)
        }

        override suspend fun leaveGroup(chatId: String) = GroupMutationCommit()
        override suspend fun dissolveGroup(chatId: String) = GroupMutationCommit()
        override suspend fun fetchGroupDetails(chatId: String) = Result.success(null)
        override suspend fun fetchGroupMembers(chatId: String) = Result.success(emptyList<GroupMemberDto>())
        override suspend fun fetchCandidates(
            excludeMemberIds: Set<String>,
            excludeUserId: String,
        ) = Result.success(emptyList<User>())

        override suspend fun uploadAvatar(chatId: String, base64: String): Result<String> {
            calls += "uploadAvatar"
            return uploadAvatarFn(chatId, base64)
        }
    }

    private class Harness(
        initial: GroupDetailUiState,
        val dispatcher: TestDispatcher,
        val service: FakeGroupLifecycleService = FakeGroupLifecycleService(),
        chatId: () -> String = { "c1" },
        token: () -> String = { "tok" },
        owner: String = "me",
        sessionActive: (String) -> Boolean = { true },
        encodeAvatar: suspend (String) -> String? = { "base64data" },
    ) {
        var live: GroupDetailUiState = initial
        var committed: String? = null
        var pendingRetry: (() -> Unit)? = null
        val encodeCalls = mutableListOf<String>()
        val controller = GroupMutationController(
            scope = CoroutineScope(dispatcher),
            currentState = { live },
            updateState = { transform -> live = transform(live) },
            text = { message, _ -> "[${message.name}]" },
            chatId = chatId,
            token = token,
            ownerUserId = { owner },
            sessionActive = sessionActive,
            encodeAvatar = { uri -> encodeCalls += uri; encodeAvatar(uri) },
            ioDispatcher = dispatcher,
            groupLifecycleService = service,
            onCommitted = { committed = it },
            pendingRetrySet = { pendingRetry = it },
        )
    }

    private fun harness(
        initial: GroupDetailUiState = GroupDetailUiState(memberRevision = 5L),
        service: FakeGroupLifecycleService = FakeGroupLifecycleService(),
        chatId: () -> String = { "c1" },
        token: () -> String = { "tok" },
        sessionActive: (String) -> Boolean = { true },
        encodeAvatar: suspend (String) -> String? = { "base64data" },
    ) = Harness(initial, dispatcher, service, chatId, token, sessionActive = sessionActive, encodeAvatar = encodeAvatar)

    private fun awaitQuiescence() {
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `renameGroup trims name and commits refreshed chat`() = runTest(dispatcher) {
        val h = harness()
        h.service.updateGroupInfoFn = { _, _, _, _ -> commit() }

        h.controller.renameGroup("  New  ")
        awaitQuiescence()

        assertEquals(listOf("updateGroupInfo:New"), h.service.calls)
        assertEquals("G2", h.live.groupName)
        assertEquals("A2", h.live.groupAnnouncement)
        assertEquals("av2", h.live.groupAvatar)
        assertEquals(7L, h.live.memberRevision)
        assertEquals("[GROUP_NAME_UPDATED]", h.live.message)
        assertEquals("[GROUP_NAME_UPDATED]", h.committed)
        assertFalse(h.live.isUpdating)
        assertNull(h.pendingRetry)
    }

    @Test
    fun `removeMember self is a no-op`() = runTest(dispatcher) {
        val h = harness()

        h.controller.removeMember("me")

        assertTrue(h.service.calls.isEmpty())
    }

    @Test
    fun `transferOwnership by non-owner is a no-op`() = runTest(dispatcher) {
        val h = harness()

        h.controller.transferOwnership("u2")

        assertTrue(h.service.calls.isEmpty())
    }

    @Test
    fun `blank chat writes session expired feedback without launching`() = runTest(dispatcher) {
        val h = harness(chatId = { "" })

        h.controller.renameGroup("New")

        assertEquals("[SESSION_EXPIRED]", h.live.message)
        assertNotNull(h.live.feedback)
        assertFalse(h.live.isUpdating)
        assertTrue(h.service.calls.isEmpty())
    }

    @Test
    fun `session inactive writes session expired feedback`() = runTest(dispatcher) {
        val h = harness(sessionActive = { false })

        h.controller.renameGroup("New")
        awaitQuiescence()

        assertEquals("[SESSION_EXPIRED]", h.live.message)
        assertNotNull(h.live.feedback)
        assertFalse(h.live.isUpdating)
        assertTrue(h.service.calls.isEmpty())
    }

    @Test
    fun `mutation failure shows retryable feedback and arms retry`() = runTest(dispatcher) {
        val h = harness()
        h.service.updateGroupInfoFn = { _, _, _, _ -> throw java.io.IOException("boom") }

        h.controller.renameGroup("New")
        awaitQuiescence()

        assertEquals("boom", h.live.message)
        assertEquals(GroupMutationFeedbackKind.ERROR_RETRYABLE, h.live.feedback?.kind)
        assertTrue(h.live.feedback?.canRetry == true)
        assertFalse(h.live.isUpdating)
        assertNotNull(h.pendingRetry)
    }

    @Test
    fun `isUpdating guard drops the second call while first is in flight`() = runTest(dispatcher) {
        val gate = CompletableDeferred<GroupMutationCommit>()
        val h = harness()
        h.service.updateGroupInfoFn = { _, _, _, _ -> gate.await() }

        h.controller.renameGroup("A")
        awaitQuiescence()
        assertTrue(h.live.isUpdating)

        h.controller.renameGroup("B")
        assertEquals(listOf("updateGroupInfo:A"), h.service.calls)

        gate.complete(commit())
        awaitQuiescence()

        assertEquals("G2", h.live.groupName)
        assertFalse(h.live.isUpdating)
    }

    @Test
    fun `updateMemberMute future sets mute message`() = runTest(dispatcher) {
        val h = harness()
        val until = System.currentTimeMillis() + 100_000L

        h.controller.updateMemberMute("u2", until)
        awaitQuiescence()

        assertEquals(listOf("setMemberMute:u2:$until"), h.service.calls)
        assertEquals("[MUTE_SET]", h.live.message)
        assertFalse(h.live.isUpdating)
    }

    @Test
    fun `updateMemberMute past clears mute message`() = runTest(dispatcher) {
        val h = harness()
        val until = System.currentTimeMillis() - 100_000L

        h.controller.updateMemberMute("u2", until)
        awaitQuiescence()

        assertEquals("[MUTE_CLEARED]", h.live.message)
    }

    @Test
    fun `muteAllMembers future sets mute-all message`() = runTest(dispatcher) {
        val h = harness()
        val until = System.currentTimeMillis() + 100_000L

        h.controller.muteAllMembers(until)
        awaitQuiescence()

        assertEquals(listOf("setMuteAll:true"), h.service.calls)
        assertEquals("[MUTE_ALL_SET]", h.live.message)
    }

    @Test
    fun `uploadGroupAvatar success updates avatar and commits`() = runTest(dispatcher) {
        val h = harness(initial = GroupDetailUiState(myRole = "OWNER"))

        h.controller.uploadGroupAvatar("content://avatar")
        awaitQuiescence()

        assertEquals(listOf("content://avatar"), h.encodeCalls)
        assertEquals(listOf("uploadAvatar"), h.service.calls)
        assertEquals("https://avatar/new", h.live.groupAvatar)
        assertEquals("[AVATAR_UPDATED]", h.committed)
        assertFalse(h.live.isUploadingAvatar)
        assertNull(h.pendingRetry)
    }

    @Test
    fun `uploadGroupAvatar encode failure arms retry`() = runTest(dispatcher) {
        val h = harness(
            initial = GroupDetailUiState(myRole = "OWNER"),
            encodeAvatar = { null },
        )

        h.controller.uploadGroupAvatar("content://avatar")
        awaitQuiescence()

        assertEquals(GroupMutationFeedbackKind.ERROR_RETRYABLE, h.live.feedback?.kind)
        assertEquals("[IMAGE_PROCESS_FAILED]", h.live.message)
        assertFalse(h.live.isUploadingAvatar)
        assertNotNull(h.pendingRetry)
        assertTrue(h.service.calls.isEmpty())
    }

    @Test
    fun `uploadGroupAvatar by non-manager is a no-op`() = runTest(dispatcher) {
        val h = harness()

        h.controller.uploadGroupAvatar("content://avatar")
        awaitQuiescence()

        assertTrue(h.encodeCalls.isEmpty())
        assertTrue(h.service.calls.isEmpty())
        assertFalse(h.live.isUploadingAvatar)
    }
}
