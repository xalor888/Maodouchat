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
    ) = IncomingCallCoordinator.PendingIncomingCall(
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
}
