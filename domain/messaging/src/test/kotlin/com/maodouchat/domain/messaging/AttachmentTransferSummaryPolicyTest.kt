package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Summary
import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentTransferSummaryPolicyTest {

    @Test
    fun `empty list summarizes to zeros`() {
        assertEquals(Summary(), AttachmentTransferSummaryPolicy.summarize(emptyList()))
    }

    @Test
    fun `states fall into the four buckets`() {
        val rows = listOf(
            TransferSummaryRow("PREPARING", 10L, 0L),
            TransferSummaryRow("UPLOADING", 20L, 5L),
            TransferSummaryRow("SENDING", 30L, 30L),
            TransferSummaryRow("QUEUED", 40L, 0L),
            TransferSummaryRow("PAUSED", 50L, 0L),
            TransferSummaryRow("FAILED", 60L, 10L),
            TransferSummaryRow("READY", 70L, 70L),
        )
        assertEquals(
            Summary(active = 3, queued = 2, failed = 1, completed = 1, totalBytes = 280L, uploadedBytes = 115L),
            AttachmentTransferSummaryPolicy.summarize(rows),
        )
    }

    @Test
    fun `unknown states count in no bucket but still sum bytes`() {
        val rows = listOf(TransferSummaryRow("WHATEVER", 10L, 4L))
        assertEquals(
            Summary(totalBytes = 10L, uploadedBytes = 4L),
            AttachmentTransferSummaryPolicy.summarize(rows),
        )
    }

    @Test
    fun `byte sums add up across rows`() {
        val rows = listOf(
            TransferSummaryRow("READY", 100L, 100L),
            TransferSummaryRow("READY", 50L, 25L),
        )
        val summary = AttachmentTransferSummaryPolicy.summarize(rows)
        assertEquals(150L, summary.totalBytes)
        assertEquals(125L, summary.uploadedBytes)
    }

    @Test
    fun `sending counts as handled when cancelling`() {
        assertEquals(
            AttachmentTransferCancelAction.COUNTED_AS_HANDLED,
            AttachmentTransferSummaryPolicy.cancelAction("SENDING"),
        )
    }

    @Test
    fun `failed paused uploading queued preparing try cancel`() {
        for (state in listOf("FAILED", "PAUSED", "UPLOADING", "QUEUED", "PREPARING")) {
            assertEquals(
                AttachmentTransferCancelAction.CANCEL,
                AttachmentTransferSummaryPolicy.cancelAction(state),
                "state=$state",
            )
        }
    }

    @Test
    fun `ready skips cancel`() {
        assertEquals(
            AttachmentTransferCancelAction.SKIP,
            AttachmentTransferSummaryPolicy.cancelAction("READY"),
        )
    }

    @Test
    fun `unknown state skips cancel`() {
        assertEquals(
            AttachmentTransferCancelAction.SKIP,
            AttachmentTransferSummaryPolicy.cancelAction("WHATEVER"),
        )
    }
}
