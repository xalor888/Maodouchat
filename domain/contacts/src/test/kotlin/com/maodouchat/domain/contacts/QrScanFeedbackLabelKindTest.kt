package com.maodouchat.domain.contacts

import com.maodouchat.core.model.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class QrScanFeedbackLabelKindTest {

    @Test
    fun invalidPayloadMapsToInvalidPayload() {
        assertEquals(QrScanFeedbackLabelKind.INVALID_PAYLOAD, qrScanFeedbackLabelKind(Kind.INVALID_PAYLOAD))
    }

    @Test
    fun sessionExpiredMapsToSessionExpired() {
        assertEquals(QrScanFeedbackLabelKind.SESSION_EXPIRED, qrScanFeedbackLabelKind(Kind.SESSION_EXPIRED))
    }

    @Test
    fun userNotFoundMapsToUserNotFound() {
        assertEquals(QrScanFeedbackLabelKind.USER_NOT_FOUND, qrScanFeedbackLabelKind(Kind.USER_NOT_FOUND))
    }

    @Test
    fun inviteInvalidOrExpiredMapsToInviteInvalidOrExpired() {
        assertEquals(
            QrScanFeedbackLabelKind.INVITE_INVALID_OR_EXPIRED,
            qrScanFeedbackLabelKind(Kind.INVITE_INVALID_OR_EXPIRED),
        )
    }

    @Test
    fun inviteBlockedMapsToInviteBlocked() {
        assertEquals(QrScanFeedbackLabelKind.INVITE_BLOCKED, qrScanFeedbackLabelKind(Kind.INVITE_BLOCKED))
    }

    @Test
    fun groupFullMapsToGroupFull() {
        assertEquals(QrScanFeedbackLabelKind.GROUP_FULL, qrScanFeedbackLabelKind(Kind.GROUP_FULL))
    }

    @Test
    fun networkMapsToNetwork() {
        assertEquals(QrScanFeedbackLabelKind.NETWORK, qrScanFeedbackLabelKind(Kind.NETWORK))
    }

    @Test
    fun unknownMapsToUnknown() {
        assertEquals(QrScanFeedbackLabelKind.UNKNOWN, qrScanFeedbackLabelKind(Kind.UNKNOWN))
    }
}
