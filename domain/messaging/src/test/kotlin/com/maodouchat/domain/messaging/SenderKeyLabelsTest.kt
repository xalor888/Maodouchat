package com.maodouchat.domain.messaging

import com.maodouchat.core.crypto.Reason
import kotlin.test.Test
import kotlin.test.assertEquals

class SenderKeyLabelsTest {

    @Test
    fun reasonMapsToSameNamedKind() {
        for (reason in Reason.entries) {
            assertEquals(reason.name, senderKeyReasonLabelKind(reason).name, reason.name)
        }
    }

    @Test
    fun sevenReasonKindsInOrder() {
        assertEquals(
            listOf(
                SenderKeyReasonLabel.LOCAL_MISSING,
                SenderKeyReasonLabel.EPOCH_MISMATCH,
                SenderKeyReasonLabel.FAILED_TARGETS,
                SenderKeyReasonLabel.PENDING_TARGETS,
                SenderKeyReasonLabel.UNKNOWN_TARGETS,
                SenderKeyReasonLabel.NO_SERVER_RECORD,
                SenderKeyReasonLabel.COMPLETE,
            ),
            SenderKeyReasonLabel.entries,
        )
    }

    @Test
    fun statusStringsMapToKinds() {
        assertEquals(SenderKeyStatusLabel.SENT, senderKeyStatusLabelKind("SENT"))
        assertEquals(SenderKeyStatusLabel.FAILED, senderKeyStatusLabelKind("FAILED"))
        assertEquals(SenderKeyStatusLabel.PENDING, senderKeyStatusLabelKind("PENDING"))
    }

    @Test
    fun statusMatchingIgnoresCase() {
        assertEquals(SenderKeyStatusLabel.SENT, senderKeyStatusLabelKind("sent"))
        assertEquals(SenderKeyStatusLabel.FAILED, senderKeyStatusLabelKind("Failed"))
        assertEquals(SenderKeyStatusLabel.PENDING, senderKeyStatusLabelKind("pEnDiNg"))
    }

    @Test
    fun unknownStatusIsOther() {
        assertEquals(SenderKeyStatusLabel.OTHER, senderKeyStatusLabelKind("WEIRD"))
        assertEquals(SenderKeyStatusLabel.OTHER, senderKeyStatusLabelKind(""))
    }

    @Test
    fun fourStatusKindsInOrder() {
        assertEquals(
            listOf(
                SenderKeyStatusLabel.SENT,
                SenderKeyStatusLabel.FAILED,
                SenderKeyStatusLabel.PENDING,
                SenderKeyStatusLabel.OTHER,
            ),
            SenderKeyStatusLabel.entries,
        )
    }
}
