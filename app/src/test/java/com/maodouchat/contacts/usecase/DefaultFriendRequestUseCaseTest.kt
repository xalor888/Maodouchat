package com.maodouchat.contacts.usecase

import com.maodouchat.network.FriendRequestDto
import com.maodouchat.network.UserDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class DefaultFriendRequestUseCaseTest {

    private fun mockDto(
        id: String,
        fromId: String,
        toId: String,
        status: String = "PENDING",
        createdAt: Long = 1000L
    ): FriendRequestDto = FriendRequestDto(
        id = id,
        fromUser = UserDto(id = fromId, name = "User $fromId"),
        toUser = UserDto(id = toId, name = "User $toId"),
        message = "Hi",
        status = status,
        createdAt = createdAt,
        updatedAt = createdAt
    )

    @Test
    fun sendFriendRequest_failsOnSelfOrBlank() = runBlocking {
        val useCase = DefaultFriendRequestUseCase(
            sessionProvider = { "alice" to "token-alice" }
        )
        val selfResult = useCase.sendFriendRequest("alice")
        assertTrue(selfResult.isFailure)

        val blankResult = useCase.sendFriendRequest("   ")
        assertTrue(blankResult.isFailure)
    }

    @Test
    fun sendFriendRequest_duplicateSubmissionBlocked() = runBlocking {
        val barrier = CompletableDeferred<Unit>()
        val useCase = DefaultFriendRequestUseCase(
            sessionProvider = { "alice" to "token-alice" },
            sendFriendRequestApi = { _, target, _ ->
                barrier.await()
                Result.success(mockDto("req-1", "alice", target))
            }
        )

        // 第一个请求正在进行中
        val firstJob = async { useCase.sendFriendRequest("bob") }
        yield() // 让出事件循环，firstJob 得以开跑并记录 inFlightSends（单线程下确定性，不靠墙钟）

        // 第二个并发请求对同一个目标
        val secondResult = useCase.sendFriendRequest("bob")
        assertTrue(secondResult.isFailure)
        assertTrue(secondResult.exceptionOrNull()?.message?.contains("already in progress") == true)

        // 释放 barrier
        barrier.complete(Unit)
        val firstResult = firstJob.await()
        assertTrue(firstResult.isSuccess)
    }

    @Test
    fun loadRequests_deduplicatesAndOrdersOutOfOrderArrival() = runBlocking {
        val useCase = DefaultFriendRequestUseCase(
            sessionProvider = { "alice" to "token-alice" },
            getIncomingRequestsApi = {
                // 模拟乱序和重复到达的数据
                Result.success(
                    listOf(
                        mockDto("req-1", "bob", "alice", createdAt = 100L),
                        mockDto("req-2", "charlie", "alice", createdAt = 500L),
                        mockDto("req-1", "bob", "alice", createdAt = 100L), // 重复
                        mockDto("req-3", "david", "alice", createdAt = 300L)
                    )
                )
            },
            getOutgoingRequestsApi = { Result.success(emptyList()) }
        )

        val result = useCase.loadRequests()
        assertTrue(result.isSuccess)
        val snapshot = result.getOrNull()!!
        assertEquals(3, snapshot.incoming.size)
        // 验证因果时序已按 createdAt 倒序校正 (req-2(500), req-3(300), req-1(100))
        assertEquals("req-2", snapshot.incoming[0].id)
        assertEquals("req-3", snapshot.incoming[1].id)
        assertEquals("req-1", snapshot.incoming[2].id)
    }

    @Test
    fun acceptVsCancel_raceConditionBlockedDuringInFlight() = runBlocking {
        val barrier = CompletableDeferred<Unit>()
        val useCase = DefaultFriendRequestUseCase(
            sessionProvider = { "alice" to "token-alice" },
            acceptFriendRequestApi = { _, _ ->
                barrier.await()
                Result.success(mockDto("req-123", "bob", "alice", status = "ACCEPTED"))
            }
        )

        val acceptJob = async { useCase.acceptFriendRequest("req-123") }
        yield() // 让出事件循环，accept 进入 in-flight（同上）

        // 在 accept 未完成时尝试 cancel 撤回
        val cancelResult = useCase.cancelFriendRequest("req-123")
        assertTrue("Competing cancel should be rejected", cancelResult.isFailure)
        assertTrue(cancelResult.exceptionOrNull()?.message?.contains("already in flight") == true)

        barrier.complete(Unit)
        val acceptResult = acceptJob.await()
        assertTrue(acceptResult.isSuccess)
    }

    @Test
    fun acceptVsCancel_settledRequestBlocksCounterAction() = runBlocking {
        val useCase = DefaultFriendRequestUseCase(
            sessionProvider = { "alice" to "token-alice" },
            acceptFriendRequestApi = { _, _ -> Result.success(mockDto("req-123", "bob", "alice", status = "ACCEPTED")) },
            cancelFriendRequestApi = { _, _ -> Result.success(mockDto("req-123", "bob", "alice", status = "CANCELLED")) },
            rejectFriendRequestApi = { _, _ -> Result.success(mockDto("req-123", "bob", "alice", status = "REJECTED")) }
        )

        val acceptResult = useCase.acceptFriendRequest("req-123")
        assertTrue(acceptResult.isSuccess)

        // 已经接受完成后，尝试撤回
        val cancelResult = useCase.cancelFriendRequest("req-123")
        assertTrue(cancelResult.isFailure)
        assertTrue(cancelResult.exceptionOrNull()?.message?.contains("already been settled") == true)

        // 已经接受完成后，再次尝试拒绝
        val rejectResult = useCase.rejectFriendRequest("req-123")
        assertTrue(rejectResult.isFailure)
        assertTrue(rejectResult.exceptionOrNull()?.message?.contains("already been settled") == true)
    }

    @Test
    fun accountSwitched_abortsFriendRequestOperations() = runBlocking {
        var currentSession = "alice" to "token-alice"
        val friendAcceptedCalled = AtomicBoolean(false)

        val useCase = DefaultFriendRequestUseCase(
            sessionProvider = { currentSession },
            sendFriendRequestApi = { _, _, _ ->
                // 在网络请求返回期间切号为 eve
                currentSession = "eve" to "token-eve"
                Result.success(mockDto("req-x", "alice", "bob"))
            },
            onFriendAccepted = {
                friendAcceptedCalled.set(true)
            }
        )

        val sendResult = useCase.sendFriendRequest("bob")
        assertTrue("Switched account must abort result", sendResult.isFailure)
        assertFalse(friendAcceptedCalled.get())
    }
}
