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

    @Test
    fun `join server code is trimmed and case insensitive`() {
        assertEquals(
            Kind.GROUP_FULL,
            QrScanFeedbackPolicy.forJoinInvite(409, "  group_member_limit_exceeded  ", null).kind
        )
        assertEquals(
            Kind.INVITE_BLOCKED,
            QrScanFeedbackPolicy.forJoinInvite(403, "Group_Invite_Blocked", null).kind
        )
    }

    @Test
    fun `join network keeps raw server code and is retryable`() {
        val fb = QrScanFeedbackPolicy.forJoinInvite(null, "GROUP_INVITE_BLOCKED", null, isNetwork = true)
        assertEquals(Kind.NETWORK, fb.kind)
        assertTrue(fb.retryable)
        // 网络分支直接透传 serverCode，不做 trim/upper。
        assertEquals("GROUP_INVITE_BLOCKED", fb.serverCode)
    }

    @Test
    fun `join message keywords map in both languages`() {
        assertEquals(Kind.INVITE_BLOCKED, QrScanFeedbackPolicy.forJoinInvite(500, null, "你已被对方屏蔽").kind)
        assertEquals(Kind.GROUP_FULL, QrScanFeedbackPolicy.forJoinInvite(500, null, "人数已达上限").kind)
        assertEquals(
            Kind.INVITE_INVALID_OR_EXPIRED,
            QrScanFeedbackPolicy.forJoinInvite(500, null, "invite EXPIRED yesterday").kind
        )
    }

    @Test
    fun `join server code outranks conflicting http status`() {
        // 400 撞上明确的屏蔽码 → 屏蔽码优先。
        assertEquals(
            Kind.INVITE_BLOCKED,
            QrScanFeedbackPolicy.forJoinInvite(400, "GROUP_INVITE_BLOCKED", null).kind
        )
        assertEquals(
            Kind.GROUP_FULL,
            QrScanFeedbackPolicy.forJoinInvite(403, "GROUP_MEMBER_LIMIT_EXCEEDED", null).kind
        )
    }

    @Test
    fun `user lookup timeout outranks status and miss is not retryable`() {
        val timeout = QrScanFeedbackPolicy.forUserLookup(404, isTimeout = true)
        assertEquals(Kind.NETWORK, timeout.kind)
        assertTrue(timeout.retryable)
        val miss = QrScanFeedbackPolicy.forUserLookup(404)
        assertEquals(Kind.USER_NOT_FOUND, miss.kind)
        assertFalse(miss.retryable)
    }
}
