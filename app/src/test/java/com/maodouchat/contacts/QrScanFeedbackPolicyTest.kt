package com.maodouchat.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrScanFeedbackPolicyTest {
    @Test
    fun `unparseable and user miss`() {
        assertEquals(QrScanFeedbackPolicy.Kind.INVALID_PAYLOAD, QrScanFeedbackPolicy.forUnparseablePayload().kind)
        assertEquals(QrScanFeedbackPolicy.Kind.USER_NOT_FOUND, QrScanFeedbackPolicy.forUserLookupMiss().kind)
        assertEquals(QrScanFeedbackPolicy.Kind.SESSION_EXPIRED, QrScanFeedbackPolicy.forSessionExpired().kind)
    }

    @Test
    fun `join invite maps server codes and status`() {
        assertEquals(
            QrScanFeedbackPolicy.Kind.INVITE_BLOCKED,
            QrScanFeedbackPolicy.forJoinInvite(403, "GROUP_INVITE_BLOCKED", null).kind
        )
        assertEquals(
            QrScanFeedbackPolicy.Kind.GROUP_FULL,
            QrScanFeedbackPolicy.forJoinInvite(409, "GROUP_MEMBER_LIMIT_EXCEEDED", null).kind
        )
        assertEquals(
            QrScanFeedbackPolicy.Kind.INVITE_INVALID_OR_EXPIRED,
            QrScanFeedbackPolicy.forJoinInvite(404, null, "群邀请不存在或已失效").kind
        )
        assertEquals(
            QrScanFeedbackPolicy.Kind.SESSION_EXPIRED,
            QrScanFeedbackPolicy.forJoinInvite(401, null, null).kind
        )
        val net = QrScanFeedbackPolicy.forJoinInvite(null, null, null, isNetwork = true)
        assertEquals(QrScanFeedbackPolicy.Kind.NETWORK, net.kind)
        assertTrue(net.retryable)
    }

    @Test
    fun `unknown join is retryable without leaking success`() {
        val fb = QrScanFeedbackPolicy.forJoinInvite(500, null, "boom")
        assertEquals(QrScanFeedbackPolicy.Kind.UNKNOWN, fb.kind)
        assertTrue(fb.retryable)
        assertFalse(QrScanFeedbackPolicy.forJoinInvite(404, null, "失效").retryable)
    }
}
