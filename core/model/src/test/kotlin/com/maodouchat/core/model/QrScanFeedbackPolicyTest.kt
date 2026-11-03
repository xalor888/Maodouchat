package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrScanFeedbackPolicyTest {

    @Test
    fun `fixed kinds are not retryable`() {
        assertEquals(Kind.INVALID_PAYLOAD, QrScanFeedbackPolicy.forUnparseablePayload().kind)
        assertFalse(QrScanFeedbackPolicy.forUnparseablePayload().retryable)
        assertEquals(Kind.USER_NOT_FOUND, QrScanFeedbackPolicy.forUserLookupMiss().kind)
        assertEquals(Kind.SESSION_EXPIRED, QrScanFeedbackPolicy.forSessionExpired().kind)
    }

    @Test
    fun `user lookup maps status and network outranks status`() {
        assertEquals(Kind.SESSION_EXPIRED, QrScanFeedbackPolicy.forUserLookup(401).kind)
        assertEquals(Kind.USER_NOT_FOUND, QrScanFeedbackPolicy.forUserLookup(404).kind)
        val net = QrScanFeedbackPolicy.forUserLookup(404, isNetwork = true)
        assertEquals(Kind.NETWORK, net.kind)
        assertTrue(net.retryable)
        val unknown = QrScanFeedbackPolicy.forUserLookup(500)
        assertEquals(Kind.UNKNOWN, unknown.kind)
        assertTrue(unknown.retryable)
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
        assertEquals(Kind.SESSION_EXPIRED, QrScanFeedbackPolicy.forJoinInvite(401, null, null).kind)
    }

    @Test
    fun `join server code outranks conflicting status and timeout outranks code`() {
        assertEquals(
            Kind.INVITE_BLOCKED,
            QrScanFeedbackPolicy.forJoinInvite(400, "GROUP_INVITE_BLOCKED", null).kind
        )
        val timeout = QrScanFeedbackPolicy.forJoinInvite(403, "GROUP_INVITE_BLOCKED", null, isTimeout = true)
        assertEquals(Kind.NETWORK, timeout.kind)
        assertTrue(timeout.retryable)
        assertEquals("GROUP_INVITE_BLOCKED", timeout.serverCode)
    }
}
