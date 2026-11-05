package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentTransferDispositionTest {

    @Test
    fun `transfer dispositions are the three expected entries in order`() {
        assertEquals(
            listOf(
                AttachmentTransferDisposition.SUCCESS,
                AttachmentTransferDisposition.RETRY,
                AttachmentTransferDisposition.FAILURE,
            ),
            AttachmentTransferDisposition.entries.toList(),
        )
    }

    @Test
    fun `transfer dispositions round-trip through valueOf`() {
        assertEquals(
            listOf("SUCCESS", "RETRY", "FAILURE"),
            AttachmentTransferDisposition.entries.map { it.name },
        )
        assertEquals(
            AttachmentTransferDisposition.RETRY,
            AttachmentTransferDisposition.valueOf("RETRY"),
        )
    }
}
