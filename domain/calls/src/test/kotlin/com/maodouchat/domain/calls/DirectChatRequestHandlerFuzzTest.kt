package com.maodouchat.domain.calls

import com.maodouchat.core.model.ChatDto
import com.maodouchat.core.model.DirectChatRequest
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectChatRequestHandlerFuzzTest {

    private val random = Random(0xCA11_2026L)

    private val tokens = listOf("", "   ", "tok", "tok-9")
    private val owners = listOf("", "  ", "u1", "u9")
    private val chatIds = listOf("c-1", "c-42", "", "chat-9")
    private val messages = listOf(null, "", "boom", "  padded  ", "网络异常")

    private class FakeSession(
        var tokenValue: String = "",
        var ownerValue: String = "",
        var gateOpen: Boolean = true,
    ) : DirectChatRequestSession {
        override fun ownerUserId(): String = ownerValue
        override fun token(): String = tokenValue
        override fun mayContinue(expectedUserId: String): Boolean =
            gateOpen && expectedUserId == ownerValue
    }

    private class FakeGateway(
        var result: Result<ChatDto> = Result.success(ChatDto(id = "c-1")),
        var onCreateChat: ((String, List<String>) -> Unit)? = null,
    ) : DirectChatRequestGateway {
        val calls = mutableListOf<Pair<String, List<String>>>()
        override suspend fun createChat(token: String, peerIds: List<String>): Result<ChatDto> {
            calls += token to peerIds
            onCreateChat?.invoke(token, peerIds)
            return result
        }
    }

    private val request = DirectChatRequest(userId = "u-peer", userName = "Peer")

    private fun randomThrowable(): Throwable {
        val message = messages.random(random)
        return if (message == null) Exception() else RuntimeException(message)
    }

    private fun randomGatewayResult(): Result<ChatDto> =
        if (random.nextBoolean()) {
            Result.success(ChatDto(id = chatIds.random(random)))
        } else {
            Result.failure(randomThrowable())
        }

    // handle 的逐字复述：token/owner 任一为空或门禁不过即 SessionExpired；
    // 建聊成功后在途门禁失效即 Dropped，否则 OpenChat(chat.id)；
    // 建聊失败即 Failed(error.message)，不复查门禁。
    private fun oracleOutcome(
        token: String,
        owner: String,
        gateOpen: Boolean,
        gatewayResult: Result<ChatDto>,
        flipGate: Boolean,
    ): Outcome {
        if (token.isBlank() || owner.isBlank() || !gateOpen) return Outcome.SessionExpired
        return gatewayResult.fold(
            onSuccess = { chat -> if (flipGate) Outcome.Dropped else Outcome.OpenChat(chat.id) },
            onFailure = { error -> Outcome.Failed(error.message) },
        )
    }

    @Test
    fun `handle matches session-gateway oracle over random inputs`() = runTest {
        repeat(200) {
            val token = tokens.random(random)
            val owner = owners.random(random)
            val gateOpen = random.nextBoolean()
            val gatewayResult = randomGatewayResult()
            val session = FakeSession(tokenValue = token, ownerValue = owner, gateOpen = gateOpen)
            val gateway = FakeGateway(result = gatewayResult)
            val handler = DirectChatRequestHandler(session, gateway)
            assertEquals(
                oracleOutcome(token, owner, gateOpen, gatewayResult, flipGate = false),
                handler.handle(request),
                "token=$token owner=$owner gateOpen=$gateOpen result=$gatewayResult",
            )
            if (token.isBlank() || owner.isBlank() || !gateOpen) {
                assertTrue(gateway.calls.isEmpty())
            } else {
                assertEquals(listOf(token to listOf("u-peer")), gateway.calls)
            }
        }
    }

    @Test
    fun `mid-flight gate flip drops instead of opening`() = runTest {
        repeat(100) {
            val flipGate = random.nextBoolean()
            val session = FakeSession(tokenValue = "tok", ownerValue = "u1")
            val gateway = FakeGateway(
                result = Result.success(ChatDto(id = chatIds.random(random))),
                onCreateChat = { _, _ -> if (flipGate) session.gateOpen = false },
            )
            val expected = if (flipGate) Outcome.Dropped else Outcome.OpenChat(gateway.result.getOrThrow().id)
            assertEquals(expected, DirectChatRequestHandler(session, gateway).handle(request))
        }
    }

    @Test
    fun `failure message passes through verbatim including null`() = runTest {
        repeat(100) {
            val error = randomThrowable()
            val gateway = FakeGateway(result = Result.failure(error))
            val warnings = mutableListOf<Pair<String, Throwable?>>()
            val handler = DirectChatRequestHandler(FakeSession(tokenValue = "tok", ownerValue = "u1"), gateway) { message, cause ->
                warnings += message to cause
            }
            assertEquals(Outcome.Failed(error.message), handler.handle(request))
            assertEquals(listOf("createChat failed" to error), warnings)
        }
    }

    @Test
    fun `session gate ignores request fields`() = runTest {
        repeat(100) {
            val token = tokens.random(random)
            val owner = owners.random(random)
            val session = FakeSession(tokenValue = token, ownerValue = owner)
            val handler = DirectChatRequestHandler(session, FakeGateway())
            val other = DirectChatRequest(
                userId = listOf("u-other", "u-x", "").random(random),
                userName = listOf("Other", "", "名字").random(random),
            )
            val expected = if (token.isBlank() || owner.isBlank()) Outcome.SessionExpired else Outcome.OpenChat("c-1")
            assertEquals(expected, handler.handle(request))
            assertEquals(expected, handler.handle(other))
        }
    }
}
