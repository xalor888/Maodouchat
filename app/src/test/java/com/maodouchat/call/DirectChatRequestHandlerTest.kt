package com.maodouchat.call

import com.maodouchat.data.repository.ChatNetworkRepository
import com.maodouchat.network.ChatDto
import com.maodouchat.session.CurrentSession
import com.maodouchat.session.Snapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `DirectChatRequestHandler` 的会话门禁与建聊分支测试（JVM）。
 * 网络层用 mockkConstructor 桩掉，失败分支的 Log.w 用 mockkStatic 桩掉。
 */
class DirectChatRequestHandlerTest {

    @After
    fun tearDown() {
        CurrentSession.override = null
        unmockkAll()
    }

    private val request = DirectChatRequest(userId = "u-peer", userName = "Peer")

    @Test
    fun noSessionShortCircuitsToSessionExpired() {
        CurrentSession.override = { Snapshot(null, null) }
        val outcome = runBlocking { DirectChatRequestHandler.handle(request) }
        assertEquals(Outcome.SessionExpired, outcome)
    }

    @Test
    fun tokenWithoutOwnerShortCircuitsToSessionExpired() {
        CurrentSession.override = { Snapshot("tok", null) }
        val outcome = runBlocking { DirectChatRequestHandler.handle(request) }
        assertEquals(Outcome.SessionExpired, outcome)
    }

    @Test
    fun tokenMissingWithOwnerShortCircuitsToSessionExpired() {
        CurrentSession.override = { Snapshot(null, "u1") }
        val outcome = runBlocking { DirectChatRequestHandler.handle(request) }
        assertEquals(Outcome.SessionExpired, outcome)
    }

    @Test
    fun whitespaceTokenShortCircuitsToSessionExpired() {
        CurrentSession.override = { Snapshot("   ", "u1") }
        val outcome = runBlocking { DirectChatRequestHandler.handle(request) }
        assertEquals(Outcome.SessionExpired, outcome)
    }

    @Test
    fun blankOwnerWithTokenShortCircuitsToSessionExpired() {
        CurrentSession.override = { Snapshot("tok", "") }
        val outcome = runBlocking { DirectChatRequestHandler.handle(request) }
        assertEquals(Outcome.SessionExpired, outcome)
    }

    @Test
    fun whitespaceOwnerWithTokenShortCircuitsToSessionExpired() {
        CurrentSession.override = { Snapshot("tok", "  ") }
        val outcome = runBlocking { DirectChatRequestHandler.handle(request) }
        assertEquals(Outcome.SessionExpired, outcome)
    }

    @Test
    fun sessionGateIgnoresRequestFields() {
        // 门禁只看会话不看请求字段：同一个无会话快照下，换 userId 结果恒定。
        CurrentSession.override = { Snapshot(null, null) }
        val other = DirectChatRequest(userId = "u-other", userName = "Other")
        val first = runBlocking { DirectChatRequestHandler.handle(request) }
        val second = runBlocking { DirectChatRequestHandler.handle(other) }
        assertEquals(Outcome.SessionExpired, first)
        assertEquals(Outcome.SessionExpired, second)
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
    fun createChatSuccessOpensChatWithReturnedId() {
        // 成功：token 与 peerIds 原样透传给网络层，返回的 chat.id 进 OpenChat。
        mockkConstructor(ChatNetworkRepository::class)
        coEvery { anyConstructed<ChatNetworkRepository>().createChat(any(), any()) } returns
            Result.success(ChatDto(id = "c-42"))
        CurrentSession.override = { Snapshot("tok", "u1") }

        val outcome = runBlocking { DirectChatRequestHandler.handle(request) }

        assertEquals(Outcome.OpenChat("c-42"), outcome)
        coVerify(exactly = 1) {
            anyConstructed<ChatNetworkRepository>().createChat("tok", listOf("u-peer"))
        }
    }

    @Test
    fun createChatFailureSurfacesMessage() {
        // 失败：网络层抛错走 Failed，error.message 原样带出（Log.w 在 JVM 上是桩）。
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0
        mockkConstructor(ChatNetworkRepository::class)
        coEvery { anyConstructed<ChatNetworkRepository>().createChat(any(), any()) } returns
            Result.failure(RuntimeException("boom"))
        CurrentSession.override = { Snapshot("tok", "u1") }

        val outcome = runBlocking { DirectChatRequestHandler.handle(request) }

        assertEquals(Outcome.Failed("boom"), outcome)
    }

    @Test
    fun sessionInvalidatedMidFlightDropsSilently() {
        // 取消：在途会话失效——建聊返回前登出，第二次门禁不过，静默 Dropped 而非 OpenChat。
        mockkConstructor(ChatNetworkRepository::class)
        coEvery { anyConstructed<ChatNetworkRepository>().createChat(any(), any()) } coAnswers {
            CurrentSession.override = { Snapshot(null, null) }
            Result.success(ChatDto(id = "c-1"))
        }
        CurrentSession.override = { Snapshot("tok", "u1") }

        val outcome = runBlocking { DirectChatRequestHandler.handle(request) }

        assertEquals(Outcome.Dropped, outcome)
    }

    @Test
    fun repeatedRequestsAreNotDeduplicated() {
        // 重复：handler 自身无去重——同一请求 handle 两次，建聊逐次发生。
        mockkConstructor(ChatNetworkRepository::class)
        coEvery { anyConstructed<ChatNetworkRepository>().createChat(any(), any()) } returns
            Result.success(ChatDto(id = "c-1"))
        CurrentSession.override = { Snapshot("tok", "u1") }

        val first = runBlocking { DirectChatRequestHandler.handle(request) }
        val second = runBlocking { DirectChatRequestHandler.handle(request) }

        assertEquals(Outcome.OpenChat("c-1"), first)
        assertEquals(Outcome.OpenChat("c-1"), second)
        coVerify(exactly = 2) {
            anyConstructed<ChatNetworkRepository>().createChat(any(), any())
        }
    }

    @Test
    fun accountSwitchUsesLatestSessionToken() {
        // 账号切换：第二次 handle 读到的是新账号的快照，token 按新会话透传。
        mockkConstructor(ChatNetworkRepository::class)
        coEvery { anyConstructed<ChatNetworkRepository>().createChat(any(), any()) } returns
            Result.success(ChatDto(id = "c-1"))
        CurrentSession.override = { Snapshot("tok1", "u1") }
        runBlocking { DirectChatRequestHandler.handle(request) }

        CurrentSession.override = { Snapshot("tok2", "u2") }
        val outcome = runBlocking { DirectChatRequestHandler.handle(request) }

        assertEquals(Outcome.OpenChat("c-1"), outcome)
        coVerify { anyConstructed<ChatNetworkRepository>().createChat("tok2", listOf("u-peer")) }
    }
}
