package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessagingV2Content
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DuplicateEnvelopeRecoveryTest {

    private val dataPayload = """{"version":1,"type":"TEXT","body":"hello"}"""

    @Test
    fun `no journal but a projected message is acknowledged`() = runTest {
        var checks = 0
        val recovery = decideDuplicateRecovery("DATA", null) { checks++; true }
        assertEquals(DuplicateEnvelopeRecovery.Acknowledge, recovery)
        assertEquals(1, checks, "没有 journal 时必须查一次投影")
    }

    @Test
    fun `no journal and no projection is uncommitted`() = runTest {
        assertEquals(
            DuplicateEnvelopeRecovery.Uncommitted,
            decideDuplicateRecovery("DATA", null) { false },
        )
    }

    @Test
    fun `a blank journal counts as no journal`() = runTest {
        var checks = 0
        val recovery = decideDuplicateRecovery("DATA", "   ") { checks++; false }
        assertEquals(DuplicateEnvelopeRecovery.Uncommitted, recovery)
        assertEquals(1, checks, "空白 journal 必须当成缺失，走投影查询")
    }

    @Test
    fun `a journaled data payload recovers without a projection check`() = runTest {
        var checks = 0
        val recovery = decideDuplicateRecovery("DATA", dataPayload) { checks++; true }
        assertEquals(
            DuplicateEnvelopeRecovery.Recover(
                MessagingV2Content(version = 1, type = "TEXT", body = "hello"),
            ),
            recovery,
        )
        assertEquals(0, checks, "journal 可用时不该再查投影")
    }

    @Test
    fun `an unparseable journal is discarded`() = runTest {
        assertEquals(
            DuplicateEnvelopeRecovery.DiscardJournal,
            decideDuplicateRecovery("DATA", "not-json{{{") { error("must not check the projection") },
        )
    }

    @Test
    fun `a journaled payload failing the content policy is discarded`() = runTest {
        var checks = 0
        val recovery = decideDuplicateRecovery("RECEIPT", dataPayload) { checks++; true }
        assertEquals(DuplicateEnvelopeRecovery.DiscardJournal, recovery)
        assertEquals(0, checks, "journal 可用时不该再查投影")
    }

    @Test
    fun `a journaled event payload recovers`() = runTest {
        val eventPayload = """{"version":1,"type":"EVENT","event":{"action":"DELETE","targetMessageId":"m1"}}"""
        val recovery = decideDuplicateRecovery("EVENT", eventPayload) { false }
        assertTrue(recovery is DuplicateEnvelopeRecovery.Recover)
        assertEquals("EVENT", recovery.content.type)
        assertEquals("DELETE", recovery.content.event?.action)
    }
}
