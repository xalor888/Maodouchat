package com.maodouchat.domain.calls

import com.maodouchat.core.session.CallSignalingOutboundCursor
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallHangUpSenderTest {

    private class FakeGateway(
        var hangUpError: Throwable? = null,
        var wsError: Throwable? = null,
    ) : CallHangUpGateway {
        data class HangUpCall(
            val token: String,
            val toUserId: String,
            val callId: String,
            val groupId: String,
            val groupMemberIds: List<String>,
            val epoch: Long,
            val sequence: Long,
            val idempotencyKey: String,
        )

        data class WsCall(
            val toUserId: String,
            val type: String,
            val payload: String,
            val callId: String,
            val groupId: String,
            val groupMemberIds: List<String>,
            val groupInvite: Boolean,
            val epoch: Long,
            val sequence: Long,
            val idempotencyKey: String,
        )

        val hangUps = mutableListOf<HangUpCall>()
        val wsSends = mutableListOf<WsCall>()

        override suspend fun hangUp(
            token: String,
            toUserId: String,
            callId: String,
            groupId: String,
            groupMemberIds: List<String>,
            epoch: Long,
            sequence: Long,
            idempotencyKey: String,
        ): Result<Unit> {
            hangUps += HangUpCall(token, toUserId, callId, groupId, groupMemberIds, epoch, sequence, idempotencyKey)
            hangUpError?.let { throw it }
            return Result.success(Unit)
        }

        override fun sendViaWebSocket(
            toUserId: String,
            type: String,
            payload: String,
            callId: String,
            groupId: String,
            groupMemberIds: List<String>,
            groupInvite: Boolean,
            epoch: Long,
            sequence: Long,
            idempotencyKey: String,
        ): Boolean {
            wsSends += WsCall(toUserId, type, payload, callId, groupId, groupMemberIds, groupInvite, epoch, sequence, idempotencyKey)
            wsError?.let { throw it }
            return true
        }
    }

    private class FakeSession(
        var owner: String = "u1",
        var token: String = "tok",
        var mayContinue: Boolean = true,
    ) : CallHangUpSession {
        override fun ownerUserId(): String = owner
        override fun token(): String = token
        override fun mayContinue(expectedUserId: String): Boolean = mayContinue
    }

    // scope 用 runTest 自身的 TestScope：launch 进 backgroundScope 时
    // advanceUntilIdle 泵不到（本轮 3 个异步测试因此失败）。
    private fun sender(
        gateway: FakeGateway,
        session: FakeSession,
        warnings: MutableList<String>,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = CallHangUpSender(
        outboundSignalingCursor = CallSignalingOutboundCursor(),
        gateway = gateway,
        session = session,
        scope = scope,
        logWarning = { message, _ -> warnings += message },
    )

    @Test
    fun `blank toUserId sends nothing`() = runTest {
        val gateway = FakeGateway()
        val warnings = mutableListOf<String>()
        sender(gateway, FakeSession(), warnings, this).sendHangUpDurable("", "c1", "", emptyList())
        advanceUntilIdle()
        assertTrue(gateway.hangUps.isEmpty())
        assertTrue(gateway.wsSends.isEmpty())
    }

    @Test
    fun `blank owner sends nothing`() = runTest {
        val gateway = FakeGateway()
        val warnings = mutableListOf<String>()
        sender(gateway, FakeSession(owner = ""), warnings, this)
            .sendHangUpDurable("u2", "c1", "", emptyList())
        advanceUntilIdle()
        assertTrue(gateway.hangUps.isEmpty())
        assertTrue(gateway.wsSends.isEmpty())
    }

    @Test
    fun `blank token sends nothing`() = runTest {
        val gateway = FakeGateway()
        val warnings = mutableListOf<String>()
        sender(gateway, FakeSession(token = ""), warnings, this)
            .sendHangUpDurable("u2", "c1", "", emptyList())
        advanceUntilIdle()
        assertTrue(gateway.hangUps.isEmpty())
        assertTrue(gateway.wsSends.isEmpty())
    }

    @Test
    fun `mayContinue false sends nothing`() = runTest {
        val gateway = FakeGateway()
        val warnings = mutableListOf<String>()
        sender(gateway, FakeSession(mayContinue = false), warnings, this)
            .sendHangUpDurable("u2", "c1", "", emptyList())
        advanceUntilIdle()
        assertTrue(gateway.hangUps.isEmpty())
        assertTrue(gateway.wsSends.isEmpty())
    }

    @Test
    fun `happy path sends REST then WS with the same ticket`() = runTest {
        val gateway = FakeGateway()
        val warnings = mutableListOf<String>()
        sender(gateway, FakeSession(), warnings, this)
            .sendHangUpDurable("u2", "c1", "g1", listOf("u2", "u3"))
        advanceUntilIdle()
        assertEquals(1, gateway.hangUps.size)
        assertEquals(1, gateway.wsSends.size)
        val rest = gateway.hangUps.single()
        assertEquals("tok", rest.token)
        assertEquals("u2", rest.toUserId)
        assertEquals("c1", rest.callId)
        assertEquals("g1", rest.groupId)
        assertEquals(listOf("u2", "u3"), rest.groupMemberIds)
        // 新游标首张票据：epoch 1、sequence 1、幂等键含归一化后的 hang-up。
        assertEquals(1L, rest.epoch)
        assertEquals(1L, rest.sequence)
        assertEquals("c1|1|1|hang-up", rest.idempotencyKey)
        val ws = gateway.wsSends.single()
        assertEquals("hang-up", ws.type)
        assertEquals("", ws.payload)
        assertEquals(false, ws.groupInvite)
        assertEquals(rest.epoch, ws.epoch)
        assertEquals(rest.sequence, ws.sequence)
        assertEquals(rest.idempotencyKey, ws.idempotencyKey)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `REST failure still attempts WS and logs warning`() = runTest {
        val gateway = FakeGateway(hangUpError = RuntimeException("rest down"))
        val warnings = mutableListOf<String>()
        sender(gateway, FakeSession(), warnings, this)
            .sendHangUpDurable("u2", "c1", "", emptyList())
        advanceUntilIdle()
        assertEquals(1, gateway.hangUps.size)
        assertEquals(1, gateway.wsSends.size)
        assertEquals(listOf("durable hang-up REST failed"), warnings)
    }

    @Test
    fun `WS failure logs warning and does not crash`() = runTest {
        val gateway = FakeGateway(wsError = RuntimeException("ws down"))
        val warnings = mutableListOf<String>()
        sender(gateway, FakeSession(), warnings, this)
            .sendHangUpDurable("u2", "c1", "", emptyList())
        advanceUntilIdle()
        assertEquals(1, gateway.hangUps.size)
        assertEquals(1, gateway.wsSends.size)
        assertEquals(listOf("durable hang-up WS failed"), warnings)
    }
}
