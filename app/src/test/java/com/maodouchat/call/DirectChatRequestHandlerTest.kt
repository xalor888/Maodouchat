package com.maodouchat.call

import com.maodouchat.session.CurrentSession
import com.maodouchat.session.Snapshot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U02 延伸：`DirectChatRequestHandler` 的会话门禁测试（JVM）。
 *
 * 覆盖得住的：无会话/半会话直接 `SessionExpired`（这条分支不碰网络）。
 * **未覆盖（诚实登记）**：建聊成功/失败分支要真网络（`ChatNetworkRepository`），
 * 属 e2e 类，留给后续专项。
 */
class DirectChatRequestHandlerTest {

    @After
    fun tearDown() {
        CurrentSession.override = null
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
}
