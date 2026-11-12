package com.maodouchat.domain.calls

import com.maodouchat.core.model.GroupPeerConnectionState

// 入站信令用到的群网格操作端口：邀请超时、群边 offer 超时与成员终态标记。
// 呼入/呼出建联还用它拉群成员画像与按确定性规则建群边。
interface CallSignalingGroupMesh {
    fun cancelInviteTimeout(userId: String)
    fun scheduleGroupPeerTimeout(userId: String)
    fun markGroupPeerTerminal(userId: String, state: GroupPeerConnectionState)
    fun loadGroupParticipantProfiles()
    fun startDeterministicMeshEdges(webRtc: CallWebRtcSession, primaryPeerId: String, session: Long)
}
