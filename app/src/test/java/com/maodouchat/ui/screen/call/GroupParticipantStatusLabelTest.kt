package com.maodouchat.ui.screen.call

import com.maodouchat.R
import com.maodouchat.webrtc.GroupPeerConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G155：`CallScreen.groupParticipantStatusLabel` 的测试。
 *
 * 群通话里每个成员的状态文案。最容易错的是 **DISCONNECTED 显示「已离开」而不是「已断开」**——
 * 前者是用户主动退群，后者是网络问题，文案说错会让用户误判。
 */
class GroupParticipantStatusLabelTest {

    @Test
    fun `every state maps to its own label`() {
        assertEquals(R.string.call_group_member_connecting, groupParticipantStatusLabel(GroupPeerConnectionState.CONNECTING))
        assertEquals(R.string.call_group_member_connected, groupParticipantStatusLabel(GroupPeerConnectionState.CONNECTED))
        assertEquals(R.string.call_group_member_reconnecting, groupParticipantStatusLabel(GroupPeerConnectionState.RECONNECTING))
        assertEquals(R.string.call_group_member_left, groupParticipantStatusLabel(GroupPeerConnectionState.DISCONNECTED))
        assertEquals(R.string.call_group_member_failed, groupParticipantStatusLabel(GroupPeerConnectionState.FAILED))
        assertEquals(R.string.call_group_member_rejected, groupParticipantStatusLabel(GroupPeerConnectionState.REJECTED))
        assertEquals(R.string.call_group_member_busy, groupParticipantStatusLabel(GroupPeerConnectionState.BUSY))
        assertEquals(R.string.call_group_member_no_answer, groupParticipantStatusLabel(GroupPeerConnectionState.NO_ANSWER))
    }

    @Test
    fun `all eight labels are distinct`() {
        val labels = GroupPeerConnectionState.entries.map { groupParticipantStatusLabel(it) }
        assertEquals("有状态共用同一句文案", labels.size, labels.toSet().size)
    }

    @Test
    fun `disconnected reads as left not as disconnected`() {
        // 「已离开」而不是「已断开」——语义差别必须钉住
        assertEquals(R.string.call_group_member_left, groupParticipantStatusLabel(GroupPeerConnectionState.DISCONNECTED))
        assertTrue(groupParticipantStatusLabel(GroupPeerConnectionState.DISCONNECTED) != R.string.call_group_member_reconnecting)
    }

    @Test
    fun `the three connecting phases are three different strings`() {
        val set = setOf(
            groupParticipantStatusLabel(GroupPeerConnectionState.CONNECTING),
            groupParticipantStatusLabel(GroupPeerConnectionState.CONNECTED),
            groupParticipantStatusLabel(GroupPeerConnectionState.RECONNECTING),
        )
        assertEquals(3, set.size)
    }
}
