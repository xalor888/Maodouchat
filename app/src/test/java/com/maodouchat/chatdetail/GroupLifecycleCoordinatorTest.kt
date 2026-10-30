package com.maodouchat.chatdetail

import com.maodouchat.network.ChatDto
import com.maodouchat.group.GroupLifecycleCoordinator
import com.maodouchat.group.GroupLifecycleSessionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GroupLifecycleCoordinatorTest {
    @Test
    fun `successful mutation refreshes chat and invalidates exact revision`() = runTest {
        val calls = mutableListOf<String>()
        val coordinator = coordinator(
            fetchChat = { liveToken, chatId ->
                calls += "fetch:$liveToken:$chatId"
                Result.success(ChatDto(id = chatId, isGroup = true, memberRevision = 12L))
            },
            invalidateEpoch = { chatId, owner, revision ->
                calls += "invalidate:$chatId:$owner:$revision"
            },
        )

        val result = coordinator.mutate("group-1", rotateSenderKey = true) { liveToken ->
            calls += "mutate:$liveToken"
        }

        assertTrue(result.committed)
        assertEquals(12L, result.refreshedChat?.memberRevision)
        assertFalse(result.hasPostCommitWarning)
        assertEquals(
            listOf(
                "mutate:token-1",
                "fetch:token-1:group-1",
                "invalidate:group-1:owner-1:12",
            ),
            calls,
        )
    }

    @Test
    fun `refresh failure remains committed and invalidates unknown revision`() = runTest {
        val refreshFailure = IOException("offline")
        var invalidatedRevision: Long? = 99L
        val coordinator = coordinator(
            fetchChat = { _, _ -> Result.failure(refreshFailure) },
            invalidateEpoch = { _, _, revision -> invalidatedRevision = revision },
        )

        val result = coordinator.mutate("group-1", rotateSenderKey = true) {}

        assertTrue(result.committed)
        assertSame(refreshFailure, result.refreshError)
        assertNull(result.refreshedChat)
        assertNull(invalidatedRevision)
        assertTrue(result.hasPostCommitWarning)
    }

    @Test
    fun `session switch after commit skips remote refresh but still invalidates old owner`() = runTest {
        var active = true
        var fetchCalled = false
        var invalidatedOwner = ""
        val coordinator = coordinator(
            sessionActive = { active },
            fetchChat = { _, _ ->
                fetchCalled = true
                Result.success(null)
            },
            invalidateEpoch = { _, owner, _ -> invalidatedOwner = owner },
        )

        val result = coordinator.mutate("group-1", rotateSenderKey = true) {
            active = false
        }

        assertTrue(result.committed)
        assertTrue(result.refreshError is GroupLifecycleSessionException)
        assertFalse(fetchCalled)
        assertEquals("owner-1", invalidatedOwner)
    }

    @Test
    fun `mutation failure does not refresh or invalidate`() = runTest {
        val mutationFailure = IOException("mutation failed")
        var fetchCalled = false
        var invalidateCalled = false
        val coordinator = coordinator(
            fetchChat = { _, _ ->
                fetchCalled = true
                Result.success(null)
            },
            invalidateEpoch = { _, _, _ -> invalidateCalled = true },
        )

        val thrown = runCatching {
            coordinator.mutate("group-1", rotateSenderKey = true) {
                throw mutationFailure
            }
        }.exceptionOrNull()

        assertSame(mutationFailure, thrown)
        assertFalse(fetchCalled)
        assertFalse(invalidateCalled)
    }

    @Test
    fun `blank chatId rejected before any session read or mutation`() = runTest {
        var mutationCalled = false
        var sessionRead = false
        val coordinator = GroupLifecycleCoordinator(
            ownerUserId = {
                sessionRead = true
                "owner-1"
            },
            token = { "token-1" },
            sessionActive = { sessionRead = true; true },
            fetchChat = { _, _ -> Result.success(null) },
            invalidateEpoch = { _, _, _ -> },
        )

        val thrown = runCatching {
            coordinator.mutate("", rotateSenderKey = true) { mutationCalled = true }
        }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
        assertEquals("group_chat_id_missing", thrown?.message)
        assertFalse(mutationCalled)
        assertFalse(sessionRead)
    }

    @Test
    fun `blank owner rejects before mutation`() = runTest {
        var mutationCalled = false
        val coordinator = GroupLifecycleCoordinator(
            ownerUserId = { "  " },
            token = { "token-1" },
            sessionActive = { true },
            fetchChat = { _, _ -> Result.success(null) },
            invalidateEpoch = { _, _, _ -> },
        )

        val thrown = runCatching {
            coordinator.mutate("group-1", rotateSenderKey = true) { mutationCalled = true }
        }.exceptionOrNull()

        assertTrue(thrown is GroupLifecycleSessionException)
        assertFalse(mutationCalled)
    }

    @Test
    fun `session inactive pre-mutation rejects without touching remote`() = runTest {
        var mutationCalled = false
        var fetchCalled = false
        val coordinator = GroupLifecycleCoordinator(
            ownerUserId = { "owner-1" },
            token = { "token-1" },
            sessionActive = { false },
            fetchChat = { _, _ ->
                fetchCalled = true
                Result.success(null)
            },
            invalidateEpoch = { _, _, _ -> },
        )

        val thrown = runCatching {
            coordinator.mutate("group-1", rotateSenderKey = true) { mutationCalled = true }
        }.exceptionOrNull()

        assertTrue(thrown is GroupLifecycleSessionException)
        assertFalse(mutationCalled)
        assertFalse(fetchCalled)
    }

    @Test
    fun `mutation cancellation propagates without refresh`() = runTest {
        val cancelled = CancellationException("cancelled")
        var fetchCalled = false
        var invalidateCalled = false
        val coordinator = coordinator(
            fetchChat = { _, _ ->
                fetchCalled = true
                Result.success(null)
            },
            invalidateEpoch = { _, _, _ -> invalidateCalled = true },
        )

        val thrown = runCatching {
            coordinator.mutate("group-1", rotateSenderKey = true) { throw cancelled }
        }.exceptionOrNull()

        assertSame(cancelled, thrown)
        assertFalse(fetchCalled)
        assertFalse(invalidateCalled)
    }

    @Test
    fun `token is re-read live for post-commit refresh`() = runTest {
        val seen = mutableListOf<String>()
        val tokens = ArrayDeque(listOf("tok-1", "tok-2"))
        val coordinator = GroupLifecycleCoordinator(
            ownerUserId = { "owner-1" },
            token = { tokens.removeFirst() },
            sessionActive = { true },
            fetchChat = { token, _ ->
                seen += token
                Result.success(ChatDto(id = "group-1", isGroup = true))
            },
            invalidateEpoch = { _, _, _ -> },
        )

        coordinator.mutate("group-1") { token -> seen += token }

        assertEquals(listOf("tok-1", "tok-2"), seen)
    }

    @Test
    fun `blank token rejects before mutation`() = runTest {
        var mutationCalled = false
        val coordinator = GroupLifecycleCoordinator(
            ownerUserId = { "owner-1" },
            token = { "  " },
            sessionActive = { true },
            fetchChat = { _, _ -> Result.success(null) },
            invalidateEpoch = { _, _, _ -> },
        )

        val thrown = runCatching {
            coordinator.mutate("group-1", rotateSenderKey = true) { mutationCalled = true }
        }.exceptionOrNull()

        assertTrue(thrown is GroupLifecycleSessionException)
        assertFalse(mutationCalled)
    }

    @Test
    fun `no rotation skips invalidation on refresh success`() = runTest {
        var invalidateCalled = false
        val coordinator = coordinator(
            fetchChat = { _, _ ->
                Result.success(ChatDto(id = "group-1", isGroup = true, memberRevision = 12L))
            },
            invalidateEpoch = { _, _, _ -> invalidateCalled = true },
        )

        val result = coordinator.mutate("group-1", rotateSenderKey = false) {}

        assertTrue(result.committed)
        assertEquals(12L, result.refreshedChat?.memberRevision)
        assertFalse(result.hasPostCommitWarning)
        assertFalse(invalidateCalled)
    }

    @Test
    fun `zero revision invalidates with null revision`() = runTest {
        var invalidatedRevision: Long? = 99L
        val coordinator = coordinator(
            fetchChat = { _, _ ->
                Result.success(ChatDto(id = "group-1", isGroup = true, memberRevision = 0L))
            },
            invalidateEpoch = { _, _, revision -> invalidatedRevision = revision },
        )

        val result = coordinator.mutate("group-1", rotateSenderKey = true) {}

        assertTrue(result.committed)
        assertNull(invalidatedRevision)
        assertFalse(result.hasPostCommitWarning)
    }

    @Test
    fun `invalidate failure stays committed as a warning`() = runTest {
        val invalidateFailure = IOException("epoch offline")
        val coordinator = coordinator(
            fetchChat = { _, _ ->
                Result.success(ChatDto(id = "group-1", isGroup = true, memberRevision = 12L))
            },
            invalidateEpoch = { _, _, _ -> throw invalidateFailure },
        )

        val result = coordinator.mutate("group-1", rotateSenderKey = true) {}

        assertTrue(result.committed)
        assertEquals(12L, result.refreshedChat?.memberRevision)
        assertSame(invalidateFailure, result.reconciliationError)
        assertTrue(result.hasPostCommitWarning)
    }

    @Test
    fun `refresh cancellation propagates without invalidation`() = runTest {
        val cancelled = CancellationException("refresh cancelled")
        var invalidateCalled = false
        val coordinator = coordinator(
            fetchChat = { _, _ -> throw cancelled },
            invalidateEpoch = { _, _, _ -> invalidateCalled = true },
        )

        val thrown = runCatching {
            coordinator.mutate("group-1", rotateSenderKey = true) {}
        }.exceptionOrNull()

        assertSame(cancelled, thrown)
        assertFalse(invalidateCalled)
    }

    private fun coordinator(
        sessionActive: (String) -> Boolean = { true },
        fetchChat: suspend (String, String) -> Result<ChatDto?> = { _, _ -> Result.success(null) },
        invalidateEpoch: suspend (String, String, Long?) -> Unit = { _, _, _ -> },
    ) = GroupLifecycleCoordinator(
        ownerUserId = { "owner-1" },
        token = { "token-1" },
        sessionActive = sessionActive,
        fetchChat = fetchChat,
        invalidateEpoch = invalidateEpoch,
    )
}
