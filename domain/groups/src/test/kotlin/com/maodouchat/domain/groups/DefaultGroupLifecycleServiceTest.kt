package com.maodouchat.domain.groups

import com.maodouchat.core.model.ChatDto
import com.maodouchat.core.model.GroupLifecycleCoordinator
import com.maodouchat.core.model.UserDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultGroupLifecycleServiceTest {

    private fun service(
        gateway: GroupNetworkGateway = FakeGroupNetworkGateway(),
        token: String = "t",
        onInvalidateEpoch: ((String, String, Long?) -> Unit)? = null,
    ): DefaultGroupLifecycleService {
        val coordinator = GroupLifecycleCoordinator(
            ownerUserId = { "owner-1" },
            token = { token },
            sessionActive = { true },
            fetchChat = { _, _ -> Result.success(ChatDto(id = "c1")) },
            invalidateEpoch = { chatId, ownerId, revision -> onInvalidateEpoch?.invoke(chatId, ownerId, revision) },
        )
        return DefaultGroupLifecycleService(
            coordinator = coordinator,
            tokenProvider = { token },
            gateway = gateway,
        )
    }

    @Test
    fun `blank token fails fetchGroupDetails without touching gateway`() = runTest {
        val gateway = FakeGroupNetworkGateway()
        val result = service(gateway = gateway, token = "").fetchGroupDetails("c1")

        assertTrue(result.isFailure)
        assertEquals("session_missing", result.exceptionOrNull()?.message)
    }

    @Test
    fun `blank token fails fetchCandidates without touching gateway`() = runTest {
        val result = service(token = "").fetchCandidates(setOf("u1"), "owner-1")

        assertTrue(result.isFailure)
        assertEquals("session_missing", result.exceptionOrNull()?.message)
    }

    @Test
    fun `fetchCandidates filters excluded ids and maps dto to user`() = runTest {
        val gateway = FakeGroupNetworkGateway().apply {
            getAllSearchableUsers = { _ ->
                Result.success(
                    listOf(
                        UserDto(id = "u1", name = "A"),
                        UserDto(id = "u2", name = "B", avatar = "a", email = "b@x", isOnline = true, status = "hi"),
                        UserDto(id = "owner-1", name = "Me"),
                    )
                )
            }
        }
        val result = service(gateway = gateway).fetchCandidates(setOf("u1"), "owner-1")

        assertTrue(result.isSuccess)
        val users = result.getOrThrow()
        assertEquals(listOf("u2"), users.map { it.id })
        assertEquals("B", users.single().name)
        assertTrue(users.single().isOnline)
    }

    @Test
    fun `setMuteAll passes one year mute and does not rotate sender key`() = runTest {
        var muteUntil = -1L
        var rotated = false
        val gateway = FakeGroupNetworkGateway().apply {
            muteAllMembers = { _, _, until ->
                muteUntil = until
                Result.success(Unit)
            }
        }
        val before = System.currentTimeMillis()
        service(gateway = gateway, onInvalidateEpoch = { _, _, _ -> rotated = true }).setMuteAll("c1", true)

        assertFalse(rotated)
        assertTrue(muteUntil >= before + 86400000L * 365 - 60_000L)
        assertTrue(muteUntil <= System.currentTimeMillis() + 86400000L * 365 + 60_000L)
    }

    @Test
    fun `addMembers rotates sender key after commit`() = runTest {
        var rotated = false
        val gateway = FakeGroupNetworkGateway().apply {
            addGroupMembers = { _, _, _ -> Result.success(Unit) }
        }
        val commit = service(gateway = gateway, onInvalidateEpoch = { _, _, _ -> rotated = true })
            .addMembers("c1", listOf("u2"))

        assertTrue(rotated)
        assertEquals("c1", commit.refreshedChat?.id)
    }

    @Test
    fun `updateGroupInfo with only name calls rename and nothing else`() = runTest {
        val calls = mutableListOf<String>()
        val gateway = FakeGroupNetworkGateway().apply {
            renameGroup = { _, _, _ -> calls += "rename"; Result.success(Unit) }
            updateGroupAnnouncement = { _, _, _ -> calls += "announcement"; Result.success(Unit) }
            uploadGroupAvatar = { _, _, _ -> calls += "avatar"; Result.success("url") }
        }
        service(gateway = gateway).updateGroupInfo("c1", "  New  ", null, "")

        assertEquals(listOf("rename"), calls)
    }
}
