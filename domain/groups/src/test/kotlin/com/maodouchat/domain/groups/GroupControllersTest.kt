package com.maodouchat.domain.groups

import com.maodouchat.core.model.GroupSenderKeyMaintenanceCoordinator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupControllersTest {

    private val blankToken = { "" }

    @Test
    fun `invite controller fails fast on blank token`() = runTest {
        val result = GroupInviteController(tokenProvider = blankToken, gateway = FakeGroupNetworkGateway())
            .fetchInvite("c1")

        assertTrue(result.isFailure)
        assertEquals("session_missing", result.exceptionOrNull()?.message)
    }

    @Test
    fun `invite rotate passes rotate flag to gateway`() = runTest {
        var seenRotate = false
        val gateway = FakeGroupNetworkGateway().apply {
            getOrCreateGroupInvite = { _, _, rotate, _, _ ->
                seenRotate = rotate
                Result.success(com.maodouchat.core.model.GroupInviteResponse(token = "tok", payload = "p"))
            }
        }
        GroupInviteController(tokenProvider = { "t" }, gateway = gateway).rotateInvite("c1")

        assertTrue(seenRotate)
    }

    @Test
    fun `audit controller fails fast on blank token`() = runTest {
        val result = GroupAuditController(tokenProvider = blankToken, gateway = FakeGroupNetworkGateway())
            .fetchAuditLogs("c1")

        assertTrue(result.isFailure)
        assertEquals("session_missing", result.exceptionOrNull()?.message)
    }

    @Test
    fun `audit controller forwards limit and offset`() = runTest {
        var seen: Pair<Int, Int>? = null
        val gateway = FakeGroupNetworkGateway().apply {
            getGroupAudit = { _, _, limit, offset ->
                seen = limit to offset
                Result.success(emptyList())
            }
        }
        GroupAuditController(tokenProvider = { "t" }, gateway = gateway).fetchAuditLogs("c1", limit = 10, offset = 20)

        assertEquals(10 to 20, seen)
    }

    @Test
    fun `bot controller filters blank id and disabled bots and falls back to username`() = runTest {
        val gateway = FakeGroupNetworkGateway().apply {
            listBots = { _ ->
                Result.success(
                    """[{"id":"b1","name":"","username":"botone","enabled":true},
                       {"id":"","name":"NoId","username":"noid","enabled":true},
                       {"id":"b2","name":"Two","username":"bottwo","enabled":false},
                       {"id":"b3","name":"Three","username":"botthree"}]"""
                )
            }
        }
        val result = GroupBotController(tokenProvider = { "t" }, gateway = gateway).fetchCandidateBots()

        assertTrue(result.isSuccess)
        val bots = result.getOrThrow()
        assertEquals(listOf("b1", "b3"), bots.map { it.id })
        assertEquals("botone", bots[0].name)
        assertEquals("Three", bots[1].name)
    }

    @Test
    fun `bot controller fails fast on blank token`() = runTest {
        val result = GroupBotController(tokenProvider = blankToken, gateway = FakeGroupNetworkGateway())
            .inviteBot("c1", "b1")

        assertTrue(result.isFailure)
        assertEquals("session_missing", result.exceptionOrNull()?.message)
    }

    @Test
    fun `encryption health controller fails fast on blank token`() = runTest {
        val coordinator = GroupSenderKeyMaintenanceCoordinator(
            ensureCoverage = { _, _ -> Result.success(null) },
            redistribute = { _, _ -> Result.success(null) },
            hasLocalSenderKey = { _, _ -> false },
            enqueueRetry = { _, _, _ -> },
        )
        val result = GroupEncryptionHealthController(
            maintenanceCoordinator = coordinator,
            tokenProvider = blankToken,
            gateway = FakeGroupNetworkGateway(),
        ).fetchSenderKeyStatus("c1")

        assertTrue(result.isFailure)
        assertEquals("session_missing", result.exceptionOrNull()?.message)
    }
}
