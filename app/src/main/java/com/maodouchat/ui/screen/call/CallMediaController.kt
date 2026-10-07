package com.maodouchat.ui.screen.call

import com.maodouchat.call.CallMediaBridge
import com.maodouchat.webrtc.CallAudioRoute
import org.webrtc.SurfaceViewRenderer

// 媒体簇：从 CallViewModel 纯搬移——音视频开关/摄像头/音频路由透传、
// 本地+对端+群成员渲染器挂载/卸载透传。VM 只留同签名公开委托。
internal class CallMediaController(
    private val mediaBridge: CallMediaBridge,
) {
    fun toggleMute(muted: Boolean) { mediaBridge.toggleMute(muted) }
    fun toggleVideo(enabled: Boolean) { mediaBridge.toggleVideo(enabled) }
    fun switchCamera() { mediaBridge.switchCamera() }
    fun selectAudioRoute(route: CallAudioRoute) { mediaBridge.selectAudioRoute(route) }

    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        mediaBridge.attachLocalRenderer(renderer)
    }
    fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {
        mediaBridge.attachRemoteRenderer(renderer)
    }
    fun attachGroupRemoteRenderer(userId: String, renderer: SurfaceViewRenderer) {
        mediaBridge.attachGroupRemoteRenderer(userId, renderer)
    }
    fun detachGroupRemoteRenderer(userId: String, renderer: SurfaceViewRenderer) {
        mediaBridge.detachGroupRemoteRenderer(userId, renderer)
    }
    fun detachLocalRenderer(renderer: SurfaceViewRenderer) {
        mediaBridge.detachLocalRenderer(renderer)
    }
    fun detachRemoteRenderer(renderer: SurfaceViewRenderer) {
        mediaBridge.detachRemoteRenderer(renderer)
    }
}
