package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallType

// 呼入/呼出建联的 WebRTC 操作端口：domain 侧只传纯数据（sdp 字符串与序号），
// IceCandidate / SessionDescription 的组装留在 app 侧适配器。
// 入站信令侧的操作见父接口 CallSignalingWebRtc。
interface CallWebRtcSession : CallSignalingWebRtc {
    fun initialize()
    fun release()
    fun answerCall(
        remoteOfferSdp: String,
        type: CallType,
        onIceCandidate: (sdpMid: String?, sdpMLineIndex: Int, sdp: String) -> Unit,
        onAnswerCreated: (description: String) -> Unit,
    )
    fun startCall(
        type: CallType,
        onIceCandidate: (sdpMid: String?, sdpMLineIndex: Int, sdp: String) -> Unit,
        onOfferCreated: (description: String) -> Unit,
    )
    fun startGroupCallToPeer(
        peerUserId: String,
        type: CallType,
        onIceCandidate: (sdpMid: String?, sdpMLineIndex: Int, sdp: String) -> Unit,
        onOfferCreated: (description: String) -> Unit,
    )
}
