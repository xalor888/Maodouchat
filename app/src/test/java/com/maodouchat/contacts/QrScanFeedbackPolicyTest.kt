package com.maodouchat.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrScanFeedbackPolicyTest {
    @Test
    fun `unparseable and user miss`() {
        assertEquals(Kind.INVALID_PAYLOAD, QrScanFeedbackPolicy.forUnparseablePayload().kind)
        assertEquals(Kind.USER_NOT_FOUND, QrScanFeedbackPolicy.forUserLookupMiss().kind)
        assertEquals(Kind.SESSION_EXPIRED, QrScanFeedbackPolicy.forSessionExpired().kind)
    }

    @Test
    fun `join invite maps server codes and status`() {
        assertEquals(
            Kind.INVITE_BLOCKED,
            QrScanFeedbackPolicy.forJoinInvite(403, "GROUP_INVITE_BLOCKED", null).kind
        )
        assertEquals(
            Kind.GROUP_FULL,
            QrScanFeedbackPolicy.forJoinInvite(409, "GROUP_MEMBER_LIMIT_EXCEEDED", null).kind
        )
        assertEquals(
            Kind.INVITE_INVALID_OR_EXPIRED,
            QrScanFeedbackPolicy.forJoinInvite(404, null, "群邀请不存在或已失效").kind
        )
        assertEquals(
            Kind.SESSION_EXPIRED,
            QrScanFeedbackPolicy.forJoinInvite(401, null, null).kind
        )
        val net = QrScanFeedbackPolicy.forJoinInvite(null, null, null, isNetwork = true)
        assertEquals(Kind.NETWORK, net.kind)
        assertTrue(net.retryable)
    }

    @Test
    fun `user lookup maps session miss network`() {
        assertEquals(
            Kind.SESSION_EXPIRED,
            QrScanFeedbackPolicy.forUserLookup(401).kind
        )
        assertEquals(
            Kind.USER_NOT_FOUND,
            QrScanFeedbackPolicy.forUserLookup(404).kind
        )
        val net = QrScanFeedbackPolicy.forUserLookup(null, isNetwork = true)
        assertEquals(Kind.NETWORK, net.kind)
        assertTrue(net.retryable)
        val timeout = QrScanFeedbackPolicy.forUserLookup(500, isTimeout = true)
        assertEquals(Kind.NETWORK, timeout.kind)
        val unknown = QrScanFeedbackPolicy.forUserLookup(500)
        assertEquals(Kind.UNKNOWN, unknown.kind)
        assertTrue(unknown.retryable)
    }

    @Test
    fun `unknown join is retryable without leaking success`() {
        val fb = QrScanFeedbackPolicy.forJoinInvite(500, null, "boom")
        assertEquals(Kind.UNKNOWN, fb.kind)
        assertTrue(fb.retryable)
        assertFalse(QrScanFeedbackPolicy.forJoinInvite(404, null, "失效").retryable)
    }
}
