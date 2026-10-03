package com.maodouchat.call

import com.maodouchat.call.IncomingCallCoordinator.PendingIncomingCall
import com.maodouchat.webrtc.CallType
import com.maodouchat.webrtc.WebRTCSignaling.SignalMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolledIncomingBatchPolicyTest {

    private val nowMs = 1_000_000L

    private fun msg(
        id: String = "m",
        type: String = "offer",
        callId: String = "c1",
        from: String = "u2",
        groupId: String = "",
        groupInvite: Boolean = false,
        timestamp: Long = nowMs - 1_000L,
    ) = SignalMessage(
        id = id, fromUserId = from, type = type, payload = "",
        timestamp = timestamp, callId = callId, groupId = groupId, groupInvite = groupInvite,
    )

    private fun pending(
        callId: String = "c1",
        contactId: String = "u2",
        contactName: String = "Alice",
        callType: CallType = CallType.AUDIO,
        groupId: String = "",
    ) = PendingIncomingCall(
        contactId = contactId,
        contactName = contactName,
        callType = callType,
        offerSdp = "sdp",
        callId = callId,
        groupId = groupId,
        receivedAtMillis = nowMs - 1_000L,
    )

    private fun decide(
        messages: List<SignalMessage>,
        preferCallId: String = "",
        existingPending: PendingIncomingCall? = null,
    ) = PolledIncomingBatchPolicy.decide(messages, preferCallId, existingPending, nowMs)

    @Test
    fun `a hang-up matching the ringing pending records a missed call tombstone`() {
        val decision = decide(
            messages = listOf(msg(type = "hang-up", callId = "c1")),
            existingPending = pending(),
        )

        assertEquals(1, decision.terminals.size)
        val terminal = decision.terminals.single()
        assertEquals("c1", terminal.callId)
        assertEquals(
            PolledIncomingBatchPolicy.MissedRecordParams(
                signalingCallId = "c1",
                fromUserId = "u2",
                callerName = "Alice",
                isVideo = false,
                isGroup = false,
            ),
            terminal.missedRecord,
            "对方在响铃中途挂断：必须清 pending 并留一条未接墓碑：${terminal.missedRecord}",
        )
    }

    @Test
    fun `a video pending produces a video tombstone`() {
        val decision = decide(
            messages = listOf(msg(type = "hang-up", callId = "c1")),
            existingPending = pending(callType = CallType.VIDEO, groupId = "g1"),
        )

        val missed = decision.terminals.single().missedRecord
        assertTrue(missed!!.isVideo, "视频来电的墓碑必须标记视频：$missed")
        assertTrue(missed.isGroup, "带 groupId 的来电墓碑必须标记群组：$missed")
    }

    @Test
    fun `a busy matching the ringing pending does not record a missed call`() {
        val decision = decide(
            messages = listOf(msg(type = "busy", callId = "c1")),
            existingPending = pending(),
        )

        assertEquals(1, decision.terminals.size, "busy 仍要取消通知并转发")
        assertNull(
            decision.terminals.single().missedRecord,
            "busy/reject 不走未接墓碑语义（只有 hang-up 记未接）：${decision.terminals.single().missedRecord}",
        )
    }

    @Test
    fun `a terminal for an unknown call only cancels and buses`() {
        val decision = decide(
            messages = listOf(msg(type = "hang-up", callId = "c-other")),
            existingPending = pending(callId = "c1"),
        )

        assertEquals(1, decision.terminals.size)
        assertNull(decision.terminals.single().missedRecord, "没命中的终端不能凭空记墓碑")
    }

    @Test
    fun `a terminal with blank call id is ignored`() {
        val decision = decide(
            messages = listOf(msg(type = "hang-up", callId = "")),
            existingPending = pending(),
        )

        assertTrue(decision.terminals.isEmpty(), "空 callId 的终端信令没有可取消/可转发的对象")
    }

    @Test
    fun `a prefer call id killed by a terminal is cancelled`() {
        val decision = decide(
            messages = listOf(msg(type = "hang-up", callId = "c1")),
            preferCallId = "c1",
            existingPending = pending(),
        )

        assertTrue(decision.cancelPreferCallId, "FCM 指定的 callId 已被终端覆盖：不能再响铃")
        assertNull(decision.primary, "被终端覆盖的 offer 不能再被选中")
    }

    @Test
    fun `a prefer call id with no offer left is a ghost ring and is cancelled`() {
        val decision = decide(
            messages = listOf(msg(type = "offer", callId = "c1")),
            preferCallId = "c9",
        )

        assertTrue(decision.cancelPreferCallId, "FCM 唤醒但库中已无该 call 的 offer：防幽灵响铃")
        assertEquals("c1", decision.primary!!.callId)
        assertTrue(decision.shouldNavigate)
    }

    @Test
    fun `the prefer call id wins the primary slot and is not cancelled`() {
        val decision = decide(
            messages = listOf(msg(type = "offer", callId = "c1"), msg(type = "offer", callId = "c2")),
            preferCallId = "c2",
        )

        assertFalse(decision.cancelPreferCallId)
        assertEquals("c2", decision.primary!!.callId, "FCM 指定的 offer 必须优先")
        assertEquals(listOf("c1"), decision.busyReplies.map { it.callId }, "其余 offer 逐条回 busy")
        assertTrue(decision.shouldNavigate)
    }

    @Test
    fun `stale offers are dropped and cannot ring`() {
        val decision = decide(
            messages = listOf(
                msg(type = "offer", callId = "c1", timestamp = nowMs - IncomingCallCoordinator.STALE_MS - 1L),
            ),
            preferCallId = "c1",
        )

        assertNull(decision.primary, "超过 STALE 窗口的 offer 不能再响铃（冷启动轮询不得重响古董 offer）")
        assertTrue(decision.cancelPreferCallId, "古董 offer 等同于库中无 offer：幽灵响铃要掐掉")
    }

    @Test
    fun `group mesh edge offers never enter the incoming route`() {
        val edge = msg(type = "offer", callId = "c1", groupId = "g1", groupInvite = false)
        val decision = decide(messages = listOf(edge))

        assertNull(decision.primary, "群 mesh 边 offer 走来电路由会把群内边当新来电 RINGING（8.56 回归）")
    }

    @Test
    fun `a group invite offer still enters the incoming route`() {
        val invite = msg(type = "offer", callId = "c1", groupId = "g1", groupInvite = true)
        val decision = decide(messages = listOf(invite))

        assertEquals("c1", decision.primary!!.callId, "真正的群邀请 offer 不该被边过滤误杀")
    }

    @Test
    fun `an offer already handled by the WS path is not rung twice`() {
        val decision = decide(
            messages = listOf(msg(type = "offer", callId = "c1")),
            existingPending = pending(callId = "c1"),
        )

        assertEquals("c1", decision.primary!!.callId)
        assertFalse(decision.shouldNavigate, "WS 已送达同 callId：轮询不再重复导航，避免重复响铃与 30s 计时重置")
        assertTrue(decision.busyReplies.isEmpty())
    }

    @Test
    fun `a blank call id offer dedups against the same contact`() {
        val decision = decide(
            messages = listOf(msg(type = "offer", callId = "", from = "u2")),
            existingPending = pending(callId = "", contactId = "u2"),
        )

        assertFalse(decision.shouldNavigate, "空 callId 时按联系人去重")
    }

    @Test
    fun `non-offer signaling is neither rung nor bussed`() {
        val decision = decide(messages = listOf(msg(type = "answer", callId = "c1")))

        assertNull(decision.primary)
        assertTrue(decision.busyReplies.isEmpty())
        assertTrue(decision.terminals.isEmpty())
        assertFalse(decision.shouldNavigate)
    }
}
