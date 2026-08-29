package com.maodouchat.messaging.v2

import com.maodouchat.network.SenderKeyDistributionStatusDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GroupMessagingCoordinatorTest {
    @Test
    fun `epoch invalidation orders durable rows before key and attachment reconciliation`() = runTest {
        val harness = Harness()

        harness.coordinator.invalidateSenderKey("group", "alice", newRevision = 7)

        assertEquals(
            listOf(
                "outbox:alice:group:7:99",
                "key:group",
                "attachments:alice:group",
            ),
            harness.events,
        )
    }

    @Test
    fun `attachment reconciliation failure cannot roll back authoritative invalidation`() = runTest {
        val harness = Harness(attachmentFailure = IllegalStateException("disk"))

        harness.coordinator.invalidateSenderKey("group", "alice", newRevision = 4)

        assertEquals(listOf("outbox:alice:group:4:99", "key:group", "attachments:alice:group"), harness.events)
        assertEquals(listOf("group:disk"), harness.reconciliationFailures)
    }

    @Test
    fun `account switch after room write never invalidates new account signal state`() = runTest {
        val harness = Harness(switchOwnerAfterEpochWrite = true)

        harness.coordinator.invalidateSenderKey("group", "alice", newRevision = 8)

        assertEquals(listOf("outbox:alice:group:8:99"), harness.events)
    }

    @Test
    fun `coverage uses refreshed token for mailbox backed status`() = runTest {
        val harness = Harness(refreshTokenAfterCoverage = true)

        val result = harness.coordinator.ensureSenderKeyCoverage("group", 9)

        assertEquals(9, result.getOrThrow()?.epoch)
        assertEquals(listOf("ensure:group:9", "fetch:token-2:group:9:3"), harness.events)
    }

    @Test
    fun `account switch during status fetch cancels instead of returning foreign session result`() = runTest {
        val harness = Harness(switchOwnerDuringFetch = true)

        assertFailsWith<CancellationException> {
            harness.coordinator.ensureSenderKeyCoverage("group", 9)
        }
    }

    @Test
    fun `manual redistribution falls back to coverage when no retry task exists`() = runTest {
        val harness = Harness(redistributionFlushed = false)

        val result = harness.coordinator.redistributeNow("group", 11)

        assertTrue(result.isSuccess)
        assertEquals(
            listOf("redistribute:group", "ensure:group:11", "fetch:token-1:group:11:3"),
            harness.events,
        )
    }

    @Test
    fun `invalid epoch fails before touching protocol dependencies`() = runTest {
        val harness = Harness()

        val failure = harness.coordinator.ensureSenderKeyCoverage("group", 0).exceptionOrNull()

        assertIs<IllegalArgumentException>(failure)
        assertTrue(harness.events.isEmpty())
    }

    @Test
    fun `coverage retry accepts only current valid group commands`() = runTest {
        val harness = Harness()

        harness.coordinator.enqueueCoverageRetry("", 3, "missing-chat")
        harness.coordinator.enqueueCoverageRetry("group", 0, "missing-epoch")
        harness.coordinator.enqueueCoverageRetry("group", 3, "")
        harness.coordinator.enqueueCoverageRetry("group", 3, "coverage_failed")

        assertEquals(listOf("retry:group:3:coverage_failed"), harness.events)
    }

    private class Harness(
        private val attachmentFailure: Throwable? = null,
        private val switchOwnerAfterEpochWrite: Boolean = false,
        private val refreshTokenAfterCoverage: Boolean = false,
        private val switchOwnerDuringFetch: Boolean = false,
        private val redistributionFlushed: Boolean = true,
    ) {
        var session: GroupMessagingSession? = GroupMessagingSession("alice", "token-1", 3)
        val events = mutableListOf<String>()
        val reconciliationFailures = mutableListOf<String>()

        val coordinator = GroupMessagingCoordinator(
            currentSession = { session },
            invalidatePreparedEpoch = { owner, chat, revision, now ->
                events += "outbox:$owner:$chat:$revision:$now"
                if (switchOwnerAfterEpochWrite) {
                    session = GroupMessagingSession("bob", "bob-token", 4)
                }
            },
            invalidateLocalSenderKey = { events += "key:$it" },
            reconcileAttachments = { chat, owner ->
                events += "attachments:$owner:$chat"
                attachmentFailure?.let { throw it }
            },
            ensureCoverageNow = { chat, epoch ->
                events += "ensure:$chat:$epoch"
                if (refreshTokenAfterCoverage) {
                    session = GroupMessagingSession("alice", "token-2", 3)
                }
            },
            redistributeCoverageNow = {
                events += "redistribute:$it"
                redistributionFlushed
            },
            fetchCoverageStatus = { token, chat, epoch, deviceId ->
                events += "fetch:$token:$chat:$epoch:$deviceId"
                if (switchOwnerDuringFetch) {
                    session = GroupMessagingSession("bob", "bob-token", 4)
                }
                SenderKeyDistributionStatusDto(
                    chatId = chat,
                    epoch = epoch ?: 9,
                    total = 2,
                    sent = 2,
                )
            },
            hasLocalSenderKeyMaterial = { _, _ -> true },
            enqueueCoverageRetryCommand = { chat, epoch, reason ->
                events += "retry:$chat:$epoch:$reason"
            },
            onAttachmentReconciliationFailure = { chat, error ->
                reconciliationFailures += "$chat:${error.message}"
            },
            clock = { 99L },
        )
    }
}
