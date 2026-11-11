package com.maodouchat.domain.calls

import com.maodouchat.core.model.ChatDto
import com.maodouchat.core.model.DirectChatRequest
import com.maodouchat.core.session.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// `DirectChatRequestHandler` 的会话门禁与建聊分支测试：会话/网络/Log 全是 fake，不碰单例与网络。
// 注意：`runTest { … }` 在 coroutines-test 1.7.3 下返回值恒为 Unit，所以结果一律在
// runTest 体内断言，不要写 `val outcome = runTest { … }`（那样 outcome 是 Unit）。
class DirectChatRequestHandlerTest {

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

    private fun handler(
        session: FakeSession = FakeSession(tokenValue = "tok", ownerValue = "u1"),
        gateway: FakeGateway = FakeGateway(),
        warnings: MutableList<Pair<String, Throwable?>> = mutableListOf(),
    ) = DirectChatRequestHandler(session, gateway) { message, error ->
        warnings += message to error
    }

    @Test
    fun noSessionShortCircuitsToSessionExpired() = runTest {
        val session = FakeSession(tokenValue = "", ownerValue = "")
        assertEquals(Outcome.SessionExpired, handler(session).handle(request))
    }

    @Test
    fun tokenWithoutOwnerShortCircuitsToSessionExpired() = runTest {
        val session = FakeSession(tokenValue = "tok", ownerValue = "")
        assertEquals(Outcome.SessionExpired, handler(session).handle(request))
    }

    @Test
    fun tokenMissingWithOwnerShortCircuitsToSessionExpired() = runTest {
        val session = FakeSession(tokenValue = "", ownerValue = "u1")
        assertEquals(Outcome.SessionExpired, handler(session).handle(request))
    }

    @Test
    fun whitespaceTokenShortCircuitsToSessionExpired() = runTest {
        val session = FakeSession(tokenValue = "   ", ownerValue = "u1")
        assertEquals(Outcome.SessionExpired, handler(session).handle(request))
    }

    @Test
    fun blankOwnerWithTokenShortCircuitsToSessionExpired() = runTest {
        val session = FakeSession(tokenValue = "tok", ownerValue = "")
        assertEquals(Outcome.SessionExpired, handler(session).handle(request))
    }

    @Test
    fun whitespaceOwnerWithTokenShortCircuitsToSessionExpired() = runTest {
        val session = FakeSession(tokenValue = "tok", ownerValue = "  ")
        assertEquals(Outcome.SessionExpired, handler(session).handle(request))
    }

    @Test
    fun sessionGateIgnoresRequestFields() = runTest {
        // 门禁只看会话不看请求字段：同一个无会话快照下，换 userId 结果恒定。
        val session = FakeSession(tokenValue = "", ownerValue = "")
        val other = DirectChatRequest(userId = "u-other", userName = "Other")
        assertEquals(Outcome.SessionExpired, handler(session).handle(request))
        assertEquals(Outcome.SessionExpired, handler(session).handle(other))
    }

    @Test
    fun outcomeTypesAreExhaustiveByConstruction() {
        // 编译期穷尽性之外，钉一眼四类结果的身份（防止有人把 Dropped 合并回 SessionExpired——
        // 那会改变「在途失效静默丢弃」的语义）。
        val outcomes: List<Outcome> = listOf(
            Outcome.SessionExpired,
            Outcome.Dropped,
            Outcome.OpenChat("c-1"),
            Outcome.Failed(null),
        )
        assertTrue(outcomes[2] is Outcome.OpenChat)
        assertTrue(outcomes[3] is Outcome.Failed)
    }

    @Test
    fun createChatSuccessOpensChatWithReturnedId() = runTest {
        // 成功：token 与 peerIds 原样透传给网络层，返回的 chat.id 进 OpenChat。
        val gateway = FakeGateway(result = Result.success(ChatDto(id = "c-42")))
        assertEquals(Outcome.OpenChat("c-42"), handler(gateway = gateway).handle(request))
        assertEquals(listOf("tok" to listOf("u-peer")), gateway.calls)
    }

    @Test
    fun createChatFailureSurfacesMessage() = runTest {
        // 失败：网络层抛错走 Failed，error.message 原样带出，并记一条 warning。
        val error = RuntimeException("boom")
        val gateway = FakeGateway(result = Result.failure(error))
        val warnings = mutableListOf<Pair<String, Throwable?>>()
        assertEquals(Outcome.Failed("boom"), handler(gateway = gateway, warnings = warnings).handle(request))
        assertEquals(listOf("createChat failed" to error), warnings)
    }

    @Test
    fun sessionInvalidatedMidFlightDropsSilently() = runTest {
        // 取消：在途会话失效——建聊返回前登出，第二次门禁不过，静默 Dropped 而非 OpenChat。
        val session = FakeSession(tokenValue = "tok", ownerValue = "u1")
        val gateway = FakeGateway(
            result = Result.success(ChatDto(id = "c-1")),
            onCreateChat = { _, _ -> session.gateOpen = false },
        )
        assertEquals(Outcome.Dropped, handler(session, gateway).handle(request))
    }

    @Test
    fun repeatedRequestsAreNotDeduplicated() = runTest {
        // 重复：handler 自身无去重——同一请求 handle 两次，建聊逐次发生。
        val gateway = FakeGateway(result = Result.success(ChatDto(id = "c-1")))
        val h = handler(gateway = gateway)
        assertEquals(Outcome.OpenChat("c-1"), h.handle(request))
        assertEquals(Outcome.OpenChat("c-1"), h.handle(request))
        assertEquals(2, gateway.calls.size)
    }

    @Test
    fun accountSwitchUsesLatestSessionToken() = runTest {
        // 账号切换：第二次 handle 读到的是新账号的快照，token 按新会话透传。
        val session = FakeSession(tokenValue = "tok1", ownerValue = "u1")
        val gateway = FakeGateway(result = Result.success(ChatDto(id = "c-1")))
        val h = handler(session, gateway)
        h.handle(request)

        session.tokenValue = "tok2"
        session.ownerValue = "u2"
        assertEquals(Outcome.OpenChat("c-1"), h.handle(request))
        assertEquals("tok2" to listOf("u-peer"), gateway.calls.last())
    }
}
