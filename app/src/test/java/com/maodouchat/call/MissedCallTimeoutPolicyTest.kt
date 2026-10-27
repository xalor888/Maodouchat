package com.maodouchat.call

import com.maodouchat.webrtc.CallType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MissedCallTimeoutPolicyTest {

    private fun pending(
        callId: String = "call-1",
        contactId: String = "user-a",
    ) = PendingIncomingCall(
        contactId = contactId,
        contactName = "Alice",
        callType = CallType.AUDIO,
        offerSdp = "v=0",
        callId = callId,
    )

    @Test
    fun prefersSignalingCallId() {
        assertEquals(
            "sig-42",
            MissedCallTimeoutPolicy.missedRecordId(
                signalingCallId = "sig-42",
                fromUserId = "u1",
                nowMs = 99L,
            )
        )
    }

    @Test
    fun blankCallIdFallsBackToSynthetic() {
        // 8.35：空 callId 时用固定前缀 + 联系人 + 分钟桶，保证双路径（NavGraph 计时器 /
        // CallViewModel RINGING 超时，相差 < 1 分钟）写入同一 id 幂等；
        // 8.49：分钟桶让同一联系人的不同未接来电不再互相覆盖
        assertEquals(
            "mc_u9_0",
            MissedCallTimeoutPolicy.missedRecordId(
                signalingCallId = "  ",
                fromUserId = "u9",
                nowMs = 1000L,
            )
        )
        // 双路径幂等：同一分钟内的两次生成结果一致
        assertEquals(
            MissedCallTimeoutPolicy.missedRecordId(" ", "u9", 61_000L),
            MissedCallTimeoutPolicy.missedRecordId(" ", "u9", 119_000L),
        )
        // 不同通话（跨分钟）可区分
        assertFalse(
            MissedCallTimeoutPolicy.missedRecordId(" ", "u9", 61_000L) ==
                MissedCallTimeoutPolicy.missedRecordId(" ", "u9", 121_000L)
        )
    }

    @Test
    fun samePendingObjectRecords() {
        val p = pending()
        assertTrue(MissedCallTimeoutPolicy.shouldRecordMissed(p, p))
    }

    @Test
    fun equalCallIdentityRecords() {
        assertTrue(
            MissedCallTimeoutPolicy.shouldRecordMissed(
                pending(callId = "c1", contactId = "u1"),
                pending(callId = "c1", contactId = "u1"),
            )
        )
    }

    @Test
    fun clearedPendingDoesNotRecord() {
        assertFalse(MissedCallTimeoutPolicy.shouldRecordMissed(pending(), null))
        assertFalse(MissedCallTimeoutPolicy.shouldRecordMissed(null, pending()))
    }

    @Test
    fun differentCallDoesNotRecord() {
        assertFalse(
            MissedCallTimeoutPolicy.shouldRecordMissed(
                pending(callId = "c1"),
                pending(callId = "c2"),
            )
        )
    }

    @Test
    fun trayHonorsGlobalNotificationsOnly() {
        assertTrue(MissedCallTimeoutPolicy.shouldShowTray(true))
        assertFalse(MissedCallTimeoutPolicy.shouldShowTray(false))
    }

    @Test
    fun paddedSignalingCallIdIsTrimmed() {
        assertEquals(
            "sig-7",
            MissedCallTimeoutPolicy.missedRecordId(
                signalingCallId = "  sig-7  ",
                fromUserId = "u1",
                nowMs = 99L,
            )
        )
    }

    @Test
    fun blankFromUserFallsBackToUnknownPeer() {
        assertEquals(
            "mc_unknown_1",
            MissedCallTimeoutPolicy.missedRecordId(
                signalingCallId = " ",
                fromUserId = "  ",
                nowMs = 61_000L,
            )
        )
    }

    @Test
    fun syntheticIdMinuteBucketEdge() {
        assertEquals(
            "mc_u9_0",
            MissedCallTimeoutPolicy.missedRecordId(" ", "u9", 59_999L),
        )
        assertEquals(
            "mc_u9_1",
            MissedCallTimeoutPolicy.missedRecordId(" ", "u9", 60_000L),
        )
    }

    @Test
    fun differentContactSameCallIdDoesNotRecord() {
        assertFalse(
            MissedCallTimeoutPolicy.shouldRecordMissed(
                pending(callId = "c1", contactId = "u1"),
                pending(callId = "c1", contactId = "u2"),
            )
        )
    }

    @Test
    fun peerCancelWireStateIsCaseInsensitive() {
        assertTrue(
            MissedCallTimeoutPolicy.shouldRecordPeerCancelAsMissed(
                isIncoming = true,
                callStateWire = "ringing",
            )
        )
        assertFalse(
            MissedCallTimeoutPolicy.shouldRecordPeerCancelAsMissed(
                isIncoming = true,
                callStateWire = "",
            )
        )
    }

    @Test
    fun peerCancelWhileIncomingRingingIsMissed() {
        assertTrue(
            MissedCallTimeoutPolicy.shouldRecordPeerCancelAsMissed(
                isIncoming = true,
                callStateWire = "RINGING",
            )
        )
    }

    @Test
    fun peerCancelWhileOutgoingOrConnectedIsNotMissed() {
        assertFalse(
            MissedCallTimeoutPolicy.shouldRecordPeerCancelAsMissed(
                isIncoming = false,
                callStateWire = "RINGING",
            )
        )
        assertFalse(
            MissedCallTimeoutPolicy.shouldRecordPeerCancelAsMissed(
                isIncoming = true,
                callStateWire = "CONNECTED",
            )
        )
        assertFalse(
            MissedCallTimeoutPolicy.shouldRecordPeerCancelAsMissed(
                isIncoming = true,
                callStateWire = "CALLING",
            )
        )
    }

    @Test
    fun blankCallIdsNeverMatchByIdentity() {
        // stillPending 的 callId 为空：即使联系人相同也不判同一通（防空 id 串扰）。
        assertFalse(
            MissedCallTimeoutPolicy.shouldRecordMissed(
                pending(callId = "c1", contactId = "u1"),
                pending(callId = "", contactId = "u1"),
            )
        )
    }

    @Test
    fun observedBlankCallIdDoesNotMatchNewCallId() {
        assertFalse(
            MissedCallTimeoutPolicy.shouldRecordMissed(
                pending(callId = "", contactId = "u1"),
                pending(callId = "c1", contactId = "u1"),
            )
        )
    }

    @Test
    fun syntheticIdTrimsPaddedPeer() {
        assertEquals(
            "mc_u9_1",
            MissedCallTimeoutPolicy.missedRecordId(" ", "  u9  ", 61_000L),
        )
    }

    @Test
    fun syntheticIdWithNegativeNowTruncatesTowardZero() {
        // Kotlin 整数除法向零截断：-1ms 落 0 号桶，-60001ms 落 -1 号桶。
        assertEquals("mc_u9_0", MissedCallTimeoutPolicy.missedRecordId(" ", "u9", -1L))
        assertEquals("mc_u9_-1", MissedCallTimeoutPolicy.missedRecordId(" ", "u9", -60_001L))
    }

    @Test
    fun equalByFieldsDespiteDifferentNameRecords() {
        // data 类不等（contactName 不同）时回退到 callId+contactId 字段比对。
        val a = PendingIncomingCall(
            contactId = "u1", contactName = "Alice", callType = CallType.AUDIO,
            offerSdp = "v=0", callId = "c1", receivedAtMillis = 1000L,
        )
        val b = a.copy(contactName = "Alicia")
        assertTrue(MissedCallTimeoutPolicy.shouldRecordMissed(a, b))
    }

    @Test
    fun bothPendingNullDoesNotRecord() {
        assertFalse(MissedCallTimeoutPolicy.shouldRecordMissed(null, null))
    }

    @Test
    fun sameInstanceWithBlankCallIdStillRecords() {
        // 相等分支先于空 id 检查：同一实例即使 callId 为空也判同一通。
        val p = pending(callId = "")
        assertTrue(MissedCallTimeoutPolicy.shouldRecordMissed(p, p))
    }

    @Test
    fun peerCancelWireStateIsNotTrimmed() {
        assertFalse(
            MissedCallTimeoutPolicy.shouldRecordPeerCancelAsMissed(
                isIncoming = true,
                callStateWire = "RINGING ",
            )
        )
        assertFalse(
            MissedCallTimeoutPolicy.shouldRecordPeerCancelAsMissed(
                isIncoming = true,
                callStateWire = " RINGING",
            )
        )
        assertTrue(
            MissedCallTimeoutPolicy.shouldRecordPeerCancelAsMissed(
                isIncoming = true,
                callStateWire = "Ringing",
            )
        )
    }

    @Test
    fun signalingCallIdIgnoresPeerAndTime() {
        assertEquals(
            MissedCallTimeoutPolicy.missedRecordId("sig-1", "u1", 1000L),
            MissedCallTimeoutPolicy.missedRecordId("sig-1", "u2", 999_999L),
        )
    }

    @Test
    fun syntheticIdKeepsInnerSpacesAndZeroBucket() {
        assertEquals("mc_u 9_0", MissedCallTimeoutPolicy.missedRecordId(" ", "u 9", 0L))
        assertEquals("mc_u9_0", MissedCallTimeoutPolicy.missedRecordId(" ", "u9", 0L))
    }

}
