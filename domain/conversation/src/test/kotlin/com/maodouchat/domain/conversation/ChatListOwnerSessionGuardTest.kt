package com.maodouchat.domain.conversation

import com.maodouchat.core.session.OwnerSessionSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ChatListOwnerSessionGuardTest {

    private class Harness(
        var userId: String? = "u1",
        var token: String? = "t1",
        var generation: Long = 7L,
        var purge: Boolean = false,
        var transactionRan: Boolean = false,
    ) {
        fun build() = ChatListOwnerSessionGuard(
            currentUserId = { userId },
            currentToken = { token },
            sessionGeneration = { generation },
            isPurgeInProgress = { purge },
            withRoomTransaction = { block -> transactionRan = true; block() },
        )
    }

    @Test
    fun currentUserIdStrFallsBackToEmpty() {
        assertEquals("", Harness(userId = null).build().currentUserIdStr)
    }

    @Test
    fun ownerSessionUsesLiveGeneration() {
        val guard = Harness().build()
        assertEquals(OwnerSessionSnapshot("u1", 7L), guard.ownerSession())
        assertEquals(OwnerSessionSnapshot("other", 7L), guard.ownerSession("other"))
    }

    @Test
    fun isCurrentRequiresLiveUserTokenAndGeneration() {
        val harness = Harness()
        val guard = harness.build()
        val session = OwnerSessionSnapshot("u1", 7L)
        assertTrue(guard.isOwnerSessionCurrent(session))
        harness.token = null
        assertFalse(guard.isOwnerSessionCurrent(session))
        harness.token = "t1"
        harness.generation = 8L
        assertFalse(guard.isOwnerSessionCurrent(session))
    }

    @Test
    fun purgeInProgressInvalidatesSession() {
        val guard = Harness(purge = true).build()
        assertFalse(guard.isOwnerSessionCurrent(OwnerSessionSnapshot("u1", 7L)))
    }

    @Test
    fun meOwnerIsNeverCurrent() {
        val guard = Harness().build()
        assertFalse(guard.isOwnerSessionCurrent(OwnerSessionSnapshot("me", 7L)))
    }

    @Test
    fun withOwnerRoomWriteRunsBlockOnlyWhenCurrent() = runTest {
        val harness = Harness()
        val guard = harness.build()
        var ran = false
        assertTrue(guard.withOwnerRoomWrite(OwnerSessionSnapshot("u1", 7L)) { ran = true })
        assertTrue(ran)
        assertTrue(harness.transactionRan)
    }

    @Test
    fun withOwnerRoomWriteSkipsBlockWhenStale() = runTest {
        val harness = Harness()
        harness.generation = 8L
        val guard = harness.build()
        var ran = false
        assertFalse(guard.withOwnerRoomWrite(OwnerSessionSnapshot("u1", 7L)) { ran = true })
        assertFalse(ran)
        assertTrue(harness.transactionRan)
    }
}
