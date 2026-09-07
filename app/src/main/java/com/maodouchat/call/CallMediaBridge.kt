package com.maodouchat.call

import com.maodouchat.webrtc.CallAudioRoute
import com.maodouchat.webrtc.WebRTCManager
import org.webrtc.SurfaceViewRenderer

/**
 * P03：通话媒体控制端口。ViewModel 只提交 mute/video/camera/route/renderer 意图；
 * [WebRTCManager] 生命周期与媒体侧操作由本桥接器持有。
 */
class CallMediaBridge {
    @Volatile
    var manager: WebRTCManager? = null
        private set

    fun bind(manager: WebRTCManager) {
        this.manager = manager
    }

    fun release() {
        manager?.release()
        manager = null
    }

    fun toggleMute(muted: Boolean) {
        manager?.toggleMute(muted)
    }

    fun toggleVideo(enabled: Boolean) {
        manager?.toggleVideo(enabled)
    }

    fun switchCamera() {
        manager?.switchCamera()
    }

    fun selectAudioRoute(route: CallAudioRoute) {
        manager?.selectAudioRoute(route)
    }

    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        manager?.attachLocalRenderer(renderer)
    }

    fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {
        manager?.attachRemoteRenderer(renderer)
    }

    fun attachGroupRemoteRenderer(userId: String, renderer: SurfaceViewRenderer) {
        manager?.attachGroupRemoteRenderer(userId, renderer)
    }

    fun detachGroupRemoteRenderer(userId: String, renderer: SurfaceViewRenderer) {
        manager?.detachGroupRemoteRenderer(userId, renderer)
    }

    fun detachLocalRenderer(renderer: SurfaceViewRenderer) {
        manager?.detachLocalRenderer(renderer)
    }

    fun detachRemoteRenderer(renderer: SurfaceViewRenderer) {
        manager?.detachRemoteRenderer(renderer)
    }
}
