package com.maodouchat.ui.screen.call

import com.maodouchat.core.model.CallType
import com.maodouchat.domain.calls.CallWebRtcSession
import com.maodouchat.webrtc.WebRTCManager
import org.webrtc.IceCandidate

// WebRTCManager 到入站信令端口的适配：IceCandidate / SessionDescription 在此组装。
internal class WebRtcCallSignalingWebRtc(val manager: WebRTCManager) : CallWebRtcSession {
    override fun handleAnswer(payload: String) = manager.handleAnswer(payload)
    override fun handleGroupAnswer(fromUserId: String, payload: String) =
        manager.handleGroupAnswer(fromUserId, payload)

    override fun addIceCandidate(sdpMid: String?, sdpMLineIndex: Int, sdp: String) {
        manager.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, sdp))
    }

    override fun addGroupIceCandidate(fromUserId: String, sdpMid: String?, sdpMLineIndex: Int, sdp: String) {
        manager.addGroupIceCandidate(fromUserId, IceCandidate(sdpMid, sdpMLineIndex, sdp))
    }

    override fun removeGroupPeer(fromUserId: String) = manager.removeGroupPeer(fromUserId)
    override fun hasGroupPeer(fromUserId: String): Boolean = manager.hasGroupPeer(fromUserId)

    override fun acceptGroupOffer(
        peerUserId: String,
        remoteOfferSdp: String,
        type: CallType,
        onIceCandidate: (sdpMid: String?, sdpMLineIndex: Int, sdp: String) -> Unit,
        onAnswerCreated: (description: String) -> Unit,
    ) {
        manager.acceptGroupOffer(
            peerUserId = peerUserId,
            remoteOfferSdp = remoteOfferSdp,
            type = type,
            onIceCandidate = { candidate ->
                onIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            },
            onAnswerCreated = { sdp -> onAnswerCreated(sdp.description) },
        )
    }

    override fun initialize() = manager.initialize()

    override fun release() = manager.release()

    override fun answerCall(
        remoteOfferSdp: String,
        type: CallType,
        onIceCandidate: (sdpMid: String?, sdpMLineIndex: Int, sdp: String) -> Unit,
        onAnswerCreated: (description: String) -> Unit,
    ) {
        manager.answerCall(
            remoteOfferSdp = remoteOfferSdp,
            type = type,
            onIceCandidate = { candidate ->
                onIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            },
            onAnswerCreated = { sdp -> onAnswerCreated(sdp.description) },
        )
    }

    override fun startCall(
        type: CallType,
        onIceCandidate: (sdpMid: String?, sdpMLineIndex: Int, sdp: String) -> Unit,
        onOfferCreated: (description: String) -> Unit,
    ) {
        manager.startCall(
            type = type,
            onIceCandidate = { candidate ->
                onIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            },
            onOfferCreated = { sdp -> onOfferCreated(sdp.description) },
        )
    }

    override fun startGroupCallToPeer(
        peerUserId: String,
        type: CallType,
        onIceCandidate: (sdpMid: String?, sdpMLineIndex: Int, sdp: String) -> Unit,
        onOfferCreated: (description: String) -> Unit,
    ) {
        manager.startGroupCallToPeer(
            peerUserId = peerUserId,
            type = type,
            onIceCandidate = { candidate ->
                onIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            },
            onOfferCreated = { sdp -> onOfferCreated(sdp.description) },
        )
    }
}
