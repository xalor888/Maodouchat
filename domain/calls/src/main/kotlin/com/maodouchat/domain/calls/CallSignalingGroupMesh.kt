package com.maodouchat.domain.calls

import com.maodouchat.core.model.GroupPeerConnectionState

// 入站信令用到的群网格操作端口：邀请超时、群边 offer 超时与成员终态标记。
interface CallSignalingGroupMesh {
    fun cancelInviteTimeout(userId: String)
    fun scheduleGroupPeerTimeout(userId: String)
    fun markGroupPeerTerminal(userId: String, state: GroupPeerConnectionState)
}
