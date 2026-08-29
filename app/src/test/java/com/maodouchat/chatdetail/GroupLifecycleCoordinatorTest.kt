package com.maodouchat.chatdetail

import com.maodouchat.network.ChatDto
import com.maodouchat.ui.screen.chatdetail.GroupLifecycleCoordinator
import com.maodouchat.ui.screen.chatdetail.GroupLifecycleSessionException
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
