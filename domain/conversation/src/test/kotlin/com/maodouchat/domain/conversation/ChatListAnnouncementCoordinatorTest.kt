package com.maodouchat.domain.conversation

import com.maodouchat.core.model.AnnouncementData
import com.maodouchat.core.model.AnnouncementPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListAnnouncementCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()

    private class State(var announcements: List<AnnouncementData> = emptyList())

    private fun buildCoordinator(
        state: State,
        scope: kotlinx.coroutines.CoroutineScope,
        hasSession: Boolean = true,
        ownerId: String = "me",
        mayContinue: Boolean = true,
        fetchActiveAnnouncements: suspend () -> Result<String> = { error("no") },
        ackAnnouncementRemote: suspend (String) -> Result<*> = { error("no") },
        fetchPushVerifyKeyRaw: suspend () -> Result<String> = { error("no") },
        applyPushVerifyKey: (String) -> Unit = {},
        nowMs: () -> Long = { 1_000_000L },
    ) = ChatListAnnouncementCoordinator(
        scope = scope,
        hasSession = { hasSession },
        ownerUserId = { ownerId },
        sessionMayContinue = { mayContinue },
        getActiveAnnouncements = { state.announcements },
        updateActiveAnnouncements = { transform -> state.announcements = transform(state.announcements) },
        fetchActiveAnnouncements = fetchActiveAnnouncements,
        ackAnnouncementRemote = ackAnnouncementRemote,
        fetchPushVerifyKeyRaw = fetchPushVerifyKeyRaw,
        applyPushVerifyKey = applyPushVerifyKey,
        nowMs = nowMs,
    )

    @Test
    fun refreshAnnouncementsParsesAndFiltersActivePayload() = runTest(dispatcher) {
        val state = State()
        val raw = """
            {"announcements":[
              {"id":"a1","title":"T","content":"C","level":"INFO","startsAt":0,"expiresAt":2000000,"status":"ACTIVE","acked":false},
              {"id":"a2","title":"Old","content":"C","level":"INFO","startsAt":0,"expiresAt":10,"status":"ACTIVE","acked":false}
            ]}
        """.trimIndent()
        val coordinator = buildCoordinator(
            state, this,
            fetchActiveAnnouncements = { Result.success(raw) },
        )

        coordinator.refreshAnnouncements()
        advanceUntilIdle()

        assertEquals(listOf("a1"), state.announcements.map { it.id })
    }

    @Test
    fun ackAnnouncementDedupesWhileInFlight() = runTest(dispatcher) {
        val item = AnnouncementData(
            id = "a1",
            title = "T",
            content = "C",
            level = "INFO",
            startsAt = 0,
            expiresAt = Long.MAX_VALUE,
            status = "ACTIVE",
            acked = false,
        )
        val state = State(listOf(item))
        var ackCount = 0
        val coordinator = buildCoordinator(
            state, this,
            ackAnnouncementRemote = { id ->
                assertEquals("a1", id)
                ackCount += 1
                Result.success("ok")
            },
        )

        coordinator.ackAnnouncement("a1")
        coordinator.ackAnnouncement("a1")
        advanceUntilIdle()

        assertEquals(1, ackCount)
        assertTrue(state.announcements.isEmpty())
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

    @Test
    fun refreshAnnouncementsSkipsWhenNoSession() = runTest(dispatcher) {
        val state = State()
        val coordinator = buildCoordinator(
            state, this,
            hasSession = false,
            fetchActiveAnnouncements = { error("must not fetch") },
        )

        coordinator.refreshAnnouncements()
        advanceUntilIdle()

        assertTrue(state.announcements.isEmpty())
    }

    @Test
    fun fetchPushVerifyKeyAppliesNonBlankKey() = runTest(dispatcher) {
        val state = State()
        var applied: String? = null
        val coordinator = buildCoordinator(
            state, this,
            fetchPushVerifyKeyRaw = { Result.success("key-raw") },
            applyPushVerifyKey = { applied = it },
        )

        coordinator.fetchPushVerifyKey()
        advanceUntilIdle()

        assertEquals("key-raw", applied)
    }

    @Test
    fun fetchPushVerifyKeySkipsBlankRaw() = runTest(dispatcher) {
        val state = State()
        var applied: String? = null
        val coordinator = buildCoordinator(
            state, this,
            fetchPushVerifyKeyRaw = { Result.success("  ") },
            applyPushVerifyKey = { applied = it },
        )

        coordinator.fetchPushVerifyKey()
        advanceUntilIdle()

        assertNull(applied)
    }
}
