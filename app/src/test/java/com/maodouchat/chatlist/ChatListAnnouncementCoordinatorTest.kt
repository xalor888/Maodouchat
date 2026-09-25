package com.maodouchat.chatlist

import com.maodouchat.network.TokenManager
import com.maodouchat.notification.AnnouncementPolicy
import com.maodouchat.security.SecureSessionManager
import com.maodouchat.ui.screen.chatlist.ChatListAnnouncementCoordinator
import com.maodouchat.ui.screen.chatlist.ChatListUiState
import com.maodouchat.ui.screen.chatlist.PushVerifyKeyAction
import com.maodouchat.ui.screen.chatlist.parsePushVerifyKeyPayload
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListAnnouncementCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        mockkObject(SecureSessionManager)
        every { SecureSessionManager.isPurgeInProgress() } returns false
    }

    @AfterTest
    fun tearDown() {
        unmockkObject(SecureSessionManager)
    }

    @Test
    fun refreshAnnouncementsParsesAndFiltersActivePayload() = runTest(dispatcher) {
        val now = 1_000_000L
        val uiState = MutableStateFlow(ChatListUiState())
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.getToken() } returns "tok"
        every { tokenManager.getUserId() } returns "me"
        com.maodouchat.session.CurrentSession.override = { com.maodouchat.session.CurrentSession.Snapshot(tokenManager.getToken(), tokenManager.getUserId()) }
        val raw = """
            {"announcements":[
              {"id":"a1","title":"T","content":"C","level":"INFO","startsAt":0,"expiresAt":2000000,"status":"ACTIVE","acked":false},
              {"id":"a2","title":"Old","content":"C","level":"INFO","startsAt":0,"expiresAt":10,"status":"ACTIVE","acked":false}
            ]}
        """.trimIndent()
        val coordinator = ChatListAnnouncementCoordinator(
            scope = this,
            uiState = uiState,
            tokenManager = tokenManager,
            fetchActiveAnnouncements = { Result.success(raw) },
            ackAnnouncementRemote = { _, _ -> error("no") },
            fetchPushVerifyKeyRaw = { error("no") },
            applyPushVerifyKey = {},
            nowMs = { now },
        )

        coordinator.refreshAnnouncements()
        advanceUntilIdle()

        assertEquals(listOf("a1"), uiState.value.activeAnnouncements.map { it.id })
    }

    @Test
    fun ackAnnouncementDedupesWhileInFlight() = runTest(dispatcher) {
        val item = AnnouncementPolicy.AnnouncementData(
            id = "a1",
            title = "T",
            content = "C",
            level = "INFO",
            startsAt = 0,
            expiresAt = Long.MAX_VALUE,
            status = "ACTIVE",
            acked = false,
        )
        val uiState = MutableStateFlow(ChatListUiState(activeAnnouncements = listOf(item)))
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.getToken() } returns "tok"
        every { tokenManager.getUserId() } returns "me"
        com.maodouchat.session.CurrentSession.override = { com.maodouchat.session.CurrentSession.Snapshot(tokenManager.getToken(), tokenManager.getUserId()) }
        var ackCount = 0
        val coordinator = ChatListAnnouncementCoordinator(
            scope = this,
            uiState = uiState,
            tokenManager = tokenManager,
            fetchActiveAnnouncements = { error("no") },
            ackAnnouncementRemote = { _, id ->
                assertEquals("a1", id)
                ackCount += 1
                Result.success("ok")
            },
            fetchPushVerifyKeyRaw = { error("no") },
            applyPushVerifyKey = {},
        )

        coordinator.ackAnnouncement("a1")
        coordinator.ackAnnouncement("a1")
        advanceUntilIdle()

        assertEquals(1, ackCount)
        assertTrue(uiState.value.activeAnnouncements.isEmpty())
    }

    @Test
    fun parsePushVerifyKeyPayloadClearWhenNullKey() {
        assertEquals(PushVerifyKeyAction.Clear, parsePushVerifyKeyPayload("""{"key":null}"""))
    }

    @Test
    fun parsePushVerifyKeyPayloadSetWhenPresent() {
        val action = parsePushVerifyKeyPayload("""{"key":"abc"}""")
        assertIs<PushVerifyKeyAction.Set>(action)
        assertEquals("abc", action.key)
    }

    @Test
    fun parseAndFilterActivePayloadRejectsAcked() {
        val raw = """
            {"announcements":[
              {"id":"a1","title":"T","content":"C","level":"INFO","startsAt":0,"expiresAt":999999999,"status":"ACTIVE","acked":true}
            ]}
        """.trimIndent()
        assertTrue(AnnouncementPolicy.parseAndFilterActivePayload(raw, nowMs = 100L).isEmpty())
    }
}
