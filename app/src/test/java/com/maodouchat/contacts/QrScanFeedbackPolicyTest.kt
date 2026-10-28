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

    @Test
    fun `user lookup null and unexpected statuses are unknown retryable`() {
        val nullStatus = QrScanFeedbackPolicy.forUserLookup(null)
        assertEquals(Kind.UNKNOWN, nullStatus.kind)
        assertTrue(nullStatus.retryable)
        val forbidden = QrScanFeedbackPolicy.forUserLookup(403)
        assertEquals(Kind.UNKNOWN, forbidden.kind)
        assertTrue(forbidden.retryable)
        val ok = QrScanFeedbackPolicy.forUserLookup(200)
        assertEquals(Kind.UNKNOWN, ok.kind)
        assertTrue(ok.retryable)
    }

    @Test
    fun `join timeout outranks server code and network passes raw code`() {
        val timeout = QrScanFeedbackPolicy.forJoinInvite(403, "GROUP_INVITE_BLOCKED", null, isTimeout = true)
        assertEquals(Kind.NETWORK, timeout.kind)
        assertTrue(timeout.retryable)
        assertEquals("GROUP_INVITE_BLOCKED", timeout.serverCode)
        val spaced = QrScanFeedbackPolicy.forJoinInvite(null, "  group_member_limit_exceeded  ", null, isNetwork = true)
        assertEquals(Kind.NETWORK, spaced.kind)
        assertEquals("  group_member_limit_exceeded  ", spaced.serverCode)
        val noCode = QrScanFeedbackPolicy.forJoinInvite(null, null, null, isNetwork = true)
        assertEquals(Kind.NETWORK, noCode.kind)
        assertEquals(null, noCode.serverCode)
    }

    @Test
    fun `join session invalid and blocked statuses are not retryable`() {
        val session = QrScanFeedbackPolicy.forJoinInvite(401, null, null)
        assertEquals(Kind.SESSION_EXPIRED, session.kind)
        assertFalse(session.retryable)
        val invalid = QrScanFeedbackPolicy.forJoinInvite(400, null, null)
        assertEquals(Kind.INVITE_INVALID_OR_EXPIRED, invalid.kind)
        assertFalse(invalid.retryable)
        val blocked = QrScanFeedbackPolicy.forJoinInvite(403, null, null)
        assertEquals(Kind.INVITE_BLOCKED, blocked.kind)
        assertFalse(blocked.retryable)
    }

    @Test
    fun `join message english keywords check block before limit`() {
        assertEquals(Kind.GROUP_FULL, QrScanFeedbackPolicy.forJoinInvite(500, null, "member limit reached").kind)
        assertEquals(Kind.INVITE_BLOCKED, QrScanFeedbackPolicy.forJoinInvite(500, null, "blocked by admin").kind)
        assertEquals(Kind.INVITE_BLOCKED, QrScanFeedbackPolicy.forJoinInvite(500, null, "blocked, limit reached").kind)
        val neutral = QrScanFeedbackPolicy.forJoinInvite(500, null, "something else")
        assertEquals(Kind.UNKNOWN, neutral.kind)
        assertTrue(neutral.retryable)
    }

    @Test
    fun `join unknown server code falls through to status and keeps normalized code`() {
        val blocked = QrScanFeedbackPolicy.forJoinInvite(403, "something_else", null)
        assertEquals(Kind.INVITE_BLOCKED, blocked.kind)
        assertEquals("SOMETHING_ELSE", blocked.serverCode)
        val unknown = QrScanFeedbackPolicy.forJoinInvite(500, "something_else", "neutral")
        assertEquals(Kind.UNKNOWN, unknown.kind)
        assertTrue(unknown.retryable)
        assertEquals("SOMETHING_ELSE", unknown.serverCode)
    }

    @Test
    fun `join chinese invalid keywords map through message branch`() {
        assertEquals(
            Kind.INVITE_INVALID_OR_EXPIRED,
            QrScanFeedbackPolicy.forJoinInvite(500, null, "此邀请已失效").kind
        )
        assertEquals(
            Kind.INVITE_INVALID_OR_EXPIRED,
            QrScanFeedbackPolicy.forJoinInvite(500, null, "邀请码无效").kind
        )
    }

    @Test
    fun `join chinese block keyword outranks limit keyword`() {
        assertEquals(
            Kind.INVITE_BLOCKED,
            QrScanFeedbackPolicy.forJoinInvite(500, null, "已被屏蔽且人数已达上限").kind
        )
    }

    @Test
    fun `lookup network and timeout ignore the http status`() {
        val net = QrScanFeedbackPolicy.forUserLookup(404, isNetwork = true)
        assertEquals(Kind.NETWORK, net.kind)
        assertTrue(net.retryable)
        val timeout = QrScanFeedbackPolicy.forUserLookup(401, isTimeout = true)
        assertEquals(Kind.NETWORK, timeout.kind)
        assertTrue(timeout.retryable)
    }

    @Test
    fun `fixed feedback kinds are not retryable and carry no code`() {
        val invalid = QrScanFeedbackPolicy.forUnparseablePayload()
        assertEquals(Kind.INVALID_PAYLOAD, invalid.kind)
        assertFalse(invalid.retryable)
        assertEquals(null, invalid.serverCode)
        val miss = QrScanFeedbackPolicy.forUserLookupMiss()
        assertFalse(miss.retryable)
        val expired = QrScanFeedbackPolicy.forSessionExpired()
        assertFalse(expired.retryable)
        assertEquals(null, expired.serverCode)
    }

    @Test
    fun `join blank server code falls back to status with null code`() {
        val full = QrScanFeedbackPolicy.forJoinInvite(409, "   ", null)
        assertEquals(Kind.GROUP_FULL, full.kind)
        assertEquals(null, full.serverCode)
        val session = QrScanFeedbackPolicy.forJoinInvite(401, "  ", null)
        assertEquals(Kind.SESSION_EXPIRED, session.kind)
        assertEquals(null, session.serverCode)
    }
}
