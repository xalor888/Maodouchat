package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallType

// 入站信令驱动的 WebRTC 操作端口：domain 侧只传纯数据（sdp 字符串与序号），
// IceCandidate / SessionDescription 的组装留在 app 侧适配器。
interface CallSignalingWebRtc {
    fun handleAnswer(payload: String)
    fun handleGroupAnswer(fromUserId: String, payload: String)
    fun addIceCandidate(sdpMid: String?, sdpMLineIndex: Int, sdp: String)
    fun addGroupIceCandidate(fromUserId: String, sdpMid: String?, sdpMLineIndex: Int, sdp: String)
    fun removeGroupPeer(fromUserId: String)
    fun hasGroupPeer(fromUserId: String): Boolean
    fun acceptGroupOffer(
        peerUserId: String,
        remoteOfferSdp: String,
        type: CallType,
        onIceCandidate: (sdpMid: String?, sdpMLineIndex: Int, sdp: String) -> Unit,
        onAnswerCreated: (description: String) -> Unit,
    )
}
