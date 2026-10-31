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

    @Test
    fun `blank refresh token after commit warns and invalidates unknown revision`() = runTest {
        val tokens = ArrayDeque(listOf("token-1", "  "))
        var fetchCalled = false
        var invalidatedRevision: Long? = 99L
        val coordinator = GroupLifecycleCoordinator(
            ownerUserId = { "owner-1" },
            token = { tokens.removeFirst() },
            sessionActive = { true },
            fetchChat = { _, _ ->
                fetchCalled = true
                Result.success(null)
            },
            invalidateEpoch = { _, _, revision -> invalidatedRevision = revision },
        )

        val result = coordinator.mutate("group-1", rotateSenderKey = true) {}

        assertTrue(result.committed)
        assertTrue(result.refreshError is GroupLifecycleSessionException)
        assertEquals("group_refresh_session_missing", result.refreshError?.message)
        assertFalse(fetchCalled)
        assertNull(invalidatedRevision)
        assertTrue(result.hasPostCommitWarning)
    }

    @Test
    fun `throwing refresh stays committed as a warning`() = runTest {
        val refreshFailure = IOException("refresh boom")
        var fetchCalled = false
        val coordinator = coordinator(
            fetchChat = { _, _ ->
                fetchCalled = true
                throw refreshFailure
            },
            invalidateEpoch = { _, _, _ -> },
        )

        val result = coordinator.mutate("group-1", rotateSenderKey = true) {}

        assertTrue(result.committed)
        assertTrue(fetchCalled)
        assertSame(refreshFailure, result.refreshError)
        assertNull(result.refreshedChat)
        assertTrue(result.hasPostCommitWarning)
    }

    @Test
    fun `padded owner is trimmed for session check and invalidation`() = runTest {
        var sessionSeen = ""
        var invalidatedOwner = ""
        val coordinator = GroupLifecycleCoordinator(
            ownerUserId = { "  owner-1  " },
            token = { "token-1" },
            sessionActive = { expected ->
                sessionSeen = expected
                expected == "owner-1"
            },
            fetchChat = { _, _ ->
                Result.success(ChatDto(id = "group-1", isGroup = true, memberRevision = 12L))
            },
            invalidateEpoch = { _, owner, _ -> invalidatedOwner = owner },
        )

        val result = coordinator.mutate("group-1", rotateSenderKey = true) {}

        assertTrue(result.committed)
        assertEquals("owner-1", sessionSeen)
        assertEquals("owner-1", invalidatedOwner)
    }

    @Test
    fun `invalidate cancellation propagates instead of warning`() = runTest {
        val cancelled = CancellationException("invalidate cancelled")
        val coordinator = coordinator(
            fetchChat = { _, _ ->
                Result.success(ChatDto(id = "group-1", isGroup = true, memberRevision = 12L))
            },
            invalidateEpoch = { _, _, _ -> throw cancelled },
        )

        val thrown = runCatching {
            coordinator.mutate("group-1", rotateSenderKey = true) {}
        }.exceptionOrNull()

        assertSame(cancelled, thrown)
    }

    @Test
    fun `padded token is trimmed for mutation and refresh`() = runTest {
        val seen = mutableListOf<String>()
        val coordinator = GroupLifecycleCoordinator(
            ownerUserId = { "owner-1" },
            token = { "  token-1  " },
            sessionActive = { true },
            fetchChat = { token, _ ->
                seen += token
                Result.success(ChatDto(id = "group-1", isGroup = true))
            },
            invalidateEpoch = { _, _, _ -> },
        )

        coordinator.mutate("group-1") { token -> seen += token }

        assertEquals(listOf("token-1", "token-1"), seen)
    }

    @Test
    fun `refresh success with null chat still invalidates unknown revision`() = runTest {
        var invalidatedRevision: Long? = 99L
        var invalidateCalled = false
        val coordinator = coordinator(
            fetchChat = { _, _ -> Result.success(null) },
            invalidateEpoch = { _, _, revision ->
                invalidateCalled = true
                invalidatedRevision = revision
            },
        )

        val result = coordinator.mutate("group-1", rotateSenderKey = true) {}

        assertTrue(result.committed)
        assertNull(result.refreshedChat)
        assertFalse(result.hasPostCommitWarning)
        assertTrue(invalidateCalled)
        assertNull(invalidatedRevision)
    }

    @Test
    fun `session switch after commit without rotation skips invalidation`() = runTest {
        var active = true
        var invalidateCalled = false
        val coordinator = coordinator(
            sessionActive = { active },
            fetchChat = { _, _ -> Result.success(null) },
            invalidateEpoch = { _, _, _ -> invalidateCalled = true },
        )

        val result = coordinator.mutate("group-1", rotateSenderKey = false) {
            active = false
        }

        assertTrue(result.committed)
        assertTrue(result.refreshError is GroupLifecycleSessionException)
        assertEquals("group_mutation_session_changed_after_commit", result.refreshError?.message)
        assertFalse(invalidateCalled)
    }

    @Test
    fun `refresh failure plus invalidate failure keeps both errors as warnings`() = runTest {
        val refreshFailure = IOException("refresh down")
        val invalidateFailure = IOException("epoch down")
        val coordinator = coordinator(
            fetchChat = { _, _ -> Result.failure(refreshFailure) },
            invalidateEpoch = { _, _, _ -> throw invalidateFailure },
        )

        val result = coordinator.mutate("group-1", rotateSenderKey = true) {}

        assertTrue(result.committed)
        assertSame(refreshFailure, result.refreshError)
        assertSame(invalidateFailure, result.reconciliationError)
        assertTrue(result.hasPostCommitWarning)
    }

    @Test
    fun `non-group refreshed chat still invalidates its revision`() = runTest {
        var invalidatedRevision: Long? = null
        val coordinator = coordinator(
            fetchChat = { _, _ ->
                Result.success(ChatDto(id = "group-1", isGroup = false, memberRevision = 9L))
            },
            invalidateEpoch = { _, _, revision -> invalidatedRevision = revision },
        )

        val result = coordinator.mutate("group-1", rotateSenderKey = true) {}

        assertTrue(result.committed)
        assertEquals(9L, invalidatedRevision)
        assertFalse(result.hasPostCommitWarning)
    }

    @Test
    fun `refresh failure without rotation skips invalidation`() = runTest {
        val refreshFailure = IOException("offline")
        var invalidateCalled = false
        val coordinator = coordinator(
            fetchChat = { _, _ -> Result.failure(refreshFailure) },
            invalidateEpoch = { _, _, _ -> invalidateCalled = true },
        )

        val result = coordinator.mutate("group-1", rotateSenderKey = false) {}

        assertTrue(result.committed)
        assertSame(refreshFailure, result.refreshError)
        assertTrue(result.hasPostCommitWarning)
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

    @Test
    fun `whitespace chatId rejected before any session read or mutation`() = runTest {
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
            coordinator.mutate("   ", rotateSenderKey = true) { mutationCalled = true }
        }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
        assertEquals("group_chat_id_missing", thrown?.message)
        assertFalse(mutationCalled)
        assertFalse(sessionRead)
    }

    @Test
    fun `negative revision invalidates with null revision`() = runTest {
        var invalidatedRevision: Long? = 99L
        val coordinator = coordinator(
            fetchChat = { _, _ ->
                Result.success(ChatDto(id = "group-1", isGroup = true, memberRevision = -5L))
            },
            invalidateEpoch = { _, _, revision -> invalidatedRevision = revision },
        )

        val result = coordinator.mutate("group-1", rotateSenderKey = true) {}

        assertTrue(result.committed)
        assertNull(invalidatedRevision)
        assertFalse(result.hasPostCommitWarning)
    }

    @Test
    fun `default revision invalidates with null revision`() = runTest {
        var invalidatedRevision: Long? = 99L
        val coordinator = coordinator(
            fetchChat = { _, _ ->
                Result.success(ChatDto(id = "group-1", isGroup = true))
            },
            invalidateEpoch = { _, _, revision -> invalidatedRevision = revision },
        )

        val result = coordinator.mutate("group-1", rotateSenderKey = true) {}

        assertTrue(result.committed)
        assertEquals("group-1", result.refreshedChat?.id)
        assertNull(invalidatedRevision)
        assertFalse(result.hasPostCommitWarning)
    }

    @Test
    fun `default rotation skips invalidation on refresh success`() = runTest {
        var invalidateCalled = false
        val coordinator = coordinator(
            fetchChat = { _, _ ->
                Result.success(ChatDto(id = "group-1", isGroup = true, memberRevision = 12L))
            },
            invalidateEpoch = { _, _, _ -> invalidateCalled = true },
        )

        val result = coordinator.mutate("group-1") {}

        assertTrue(result.committed)
        assertEquals(12L, result.refreshedChat?.memberRevision)
        assertFalse(result.hasPostCommitWarning)
        assertFalse(invalidateCalled)
    }

    @Test
    fun `session inactive pre-mutation reports session missing message`() = runTest {
        var mutationCalled = false
        var fetchCalled = false
        val coordinator = coordinator(
            sessionActive = { false },
            fetchChat = { _, _ ->
                fetchCalled = true
                Result.success(null)
            },
        )

        val thrown = runCatching {
            coordinator.mutate("group-1", rotateSenderKey = true) { mutationCalled = true }
        }.exceptionOrNull()

        assertTrue(thrown is GroupLifecycleSessionException)
        assertEquals("group_mutation_session_missing", thrown?.message)
        assertFalse(mutationCalled)
        assertFalse(fetchCalled)
    }

    @Test
    fun `session switch after commit with rotation invalidates null revision`() = runTest {
        var active = true
        var invalidatedOwner = ""
        var invalidatedRevision: Long? = 99L
        val coordinator = coordinator(
            sessionActive = { active },
            fetchChat = { _, _ -> Result.success(null) },
            invalidateEpoch = { _, owner, revision ->
                invalidatedOwner = owner
                invalidatedRevision = revision
            },
        )

        val result = coordinator.mutate("group-1", rotateSenderKey = true) {
            active = false
        }

        assertTrue(result.committed)
        assertTrue(result.refreshError is GroupLifecycleSessionException)
        assertEquals("owner-1", invalidatedOwner)
        assertNull(invalidatedRevision)
        assertTrue(result.hasPostCommitWarning)
    }

    @Test
    fun `session gate throwing pre-mutation propagates without mutation or refresh`() = runTest {
        var mutationCalled = false
        var fetchCalled = false
        var invalidateCalled = false
        val coordinator = GroupLifecycleCoordinator(
            ownerUserId = { "owner-1" },
            token = { "token-1" },
            sessionActive = { throw IllegalStateException("gate boom") },
            fetchChat = { _, _ ->
                fetchCalled = true
                Result.success(null)
            },
            invalidateEpoch = { _, _, _ -> invalidateCalled = true },
        )

        val thrown = runCatching {
            coordinator.mutate("group-1", rotateSenderKey = true) { mutationCalled = true }
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals("gate boom", thrown?.message)
        assertFalse(mutationCalled)
        assertFalse(fetchCalled)
        assertFalse(invalidateCalled)
    }

    @Test
    fun `revision one invalidates with one`() = runTest {
        var invalidatedRevision: Long? = null
        val coordinator = coordinator(
            fetchChat = { _, _ ->
                Result.success(ChatDto(id = "group-1", isGroup = true, memberRevision = 1L))
            },
            invalidateEpoch = { _, _, revision -> invalidatedRevision = revision },
        )

        val result = coordinator.mutate("group-1", rotateSenderKey = true) {}

        assertTrue(result.committed)
        assertEquals(1L, invalidatedRevision)
        assertFalse(result.hasPostCommitWarning)
    }

    @Test
    fun `refreshed chat with a different id is kept as-is with its revision`() = runTest {
        var invalidatedRevision: Long? = null
        val coordinator = coordinator(
            fetchChat = { _, _ ->
                Result.success(ChatDto(id = "group-9", isGroup = true, memberRevision = 7L))
            },
            invalidateEpoch = { _, _, revision -> invalidatedRevision = revision },
        )

        val result = coordinator.mutate("group-1", rotateSenderKey = true) {}

        assertTrue(result.committed)
        assertEquals("group-9", result.refreshedChat?.id)
        assertEquals(7L, invalidatedRevision)
        assertFalse(result.hasPostCommitWarning)
    }

    @Test
    fun `same coordinator mutated twice runs two full commit rounds`() = runTest {
        val calls = mutableListOf<String>()
        val coordinator = coordinator(
            fetchChat = { token, chatId ->
                calls += "fetch:$token:$chatId"
                Result.success(ChatDto(id = chatId, isGroup = true, memberRevision = 12L))
            },
            invalidateEpoch = { chatId, owner, revision ->
                calls += "invalidate:$chatId:$owner:$revision"
            },
        )

        val first = coordinator.mutate("group-1", rotateSenderKey = true) { token ->
            calls += "mutate:$token"
        }
        val second = coordinator.mutate("group-1", rotateSenderKey = true) { token ->
            calls += "mutate:$token"
        }

        assertTrue(first.committed)
        assertTrue(second.committed)
        assertEquals(
            listOf(
                "mutate:token-1",
                "fetch:token-1:group-1",
                "invalidate:group-1:owner-1:12",
                "mutate:token-1",
                "fetch:token-1:group-1",
                "invalidate:group-1:owner-1:12",
            ),
            calls,
        )
    }
}
