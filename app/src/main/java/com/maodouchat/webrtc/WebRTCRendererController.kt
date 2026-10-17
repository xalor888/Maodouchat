package com.maodouchat.webrtc

import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

// 渲染器 attach/detach 一族：从 WebRTCManager 纯搬移过来。锁不在这——委托层保持
// @Synchronized，onTrack 那边是 synchronized(this@WebRTCManager)，必须同一把锁。
internal class WebRTCRendererController(
    private val eglContext: () -> EglBase.Context?,
    private val released: () -> Boolean,
    private val getLocalRenderer: () -> SurfaceViewRenderer?,
    private val setLocalRenderer: (SurfaceViewRenderer?) -> Unit,
    private val localVideoTrack: () -> VideoTrack?,
    private val getRemoteRenderer: () -> SurfaceViewRenderer?,
    private val setRemoteRenderer: (SurfaceViewRenderer?) -> Unit,
    private val remoteVideoTrack: () -> VideoTrack?,
    private val groupRemoteRenderers: MutableMap<String, SurfaceViewRenderer>,
    private val groupRemoteVideoTracks: MutableMap<String, VideoTrack>,
) {
    /**
     * 初始化本地视频渲染器（UI 层创建后传入）
     */
    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        val eglContext = eglContext()
        if (released() || eglContext == null) {
            runCatching { renderer.release() }
            return
        }
        renderer.init(eglContext, null)
        getLocalRenderer()?.takeIf { it !== renderer }?.let { old ->
            localVideoTrack()?.let { track -> runCatching { track.removeSink(old) } }
            runCatching { old.release() }
        }
        localVideoTrack()?.addSink(renderer)
        setLocalRenderer(renderer)
    }

    /**
     * 初始化远端视频渲染器（UI 层创建后传入）
     * 如果远端视频轨道已到达（onTrack 先于 UI attach），此时补上 addSink
     */
    fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {
        val eglContext = eglContext()
        if (released() || eglContext == null) {
            runCatching { renderer.release() }
            return
        }
        renderer.init(eglContext, null)
        getRemoteRenderer()?.takeIf { it !== renderer }?.let { old ->
            remoteVideoTrack()?.let { track -> runCatching { track.removeSink(old) } }
            runCatching { old.release() }
        }
        setRemoteRenderer(renderer)
        // 竞态修复：如果 onTrack 已经收到远端视频但当时 renderer 还没准备好，现在补上 sink
        remoteVideoTrack()?.let { track ->
            runCatching { track.addSink(renderer) }
        }
    }

    fun detachRemoteRenderer(renderer: SurfaceViewRenderer) {
        if (getRemoteRenderer() !== renderer) return
        remoteVideoTrack()?.let { track -> runCatching { track.removeSink(renderer) } }
        setRemoteRenderer(null)
        runCatching { renderer.release() }
    }

    fun detachLocalRenderer(renderer: SurfaceViewRenderer) {
        if (getLocalRenderer() !== renderer) return
        localVideoTrack()?.let { track -> runCatching { track.removeSink(renderer) } }
        setLocalRenderer(null)
        runCatching { renderer.release() }
    }

    fun attachGroupRemoteRenderer(peerUserId: String, renderer: SurfaceViewRenderer) {
        val eglContext = eglContext()
        if (released() || eglContext == null) {
            runCatching { renderer.release() }
            return
        }
        renderer.init(eglContext, null)
        groupRemoteRenderers.put(peerUserId, renderer)?.takeIf { it !== renderer }?.let { old ->
            groupRemoteVideoTracks[peerUserId]?.let { track -> runCatching { track.removeSink(old) } }
            runCatching { old.release() }
        }
        groupRemoteVideoTracks[peerUserId]?.let { track -> runCatching { track.addSink(renderer) } }
    }

    fun detachGroupRemoteRenderer(peerUserId: String, renderer: SurfaceViewRenderer) {
        if (groupRemoteRenderers[peerUserId] !== renderer) return
        groupRemoteVideoTracks[peerUserId]?.let { track -> runCatching { track.removeSink(renderer) } }
        groupRemoteRenderers.remove(peerUserId)
        runCatching { renderer.release() }
    }

    /**
     * 释放渲染器资源
     */
    fun releaseRenderers() {
        getLocalRenderer()?.let { renderer -> localVideoTrack()?.let { track -> runCatching { track.removeSink(renderer) } } }
        getRemoteRenderer()?.let { renderer -> remoteVideoTrack()?.let { track -> runCatching { track.removeSink(renderer) } } }
        runCatching { getLocalRenderer()?.release() }
        runCatching { getRemoteRenderer()?.release() }
        groupRemoteRenderers.forEach { (peerId, renderer) ->
            groupRemoteVideoTracks[peerId]?.let { track -> runCatching { track.removeSink(renderer) } }
            runCatching { renderer.release() }
        }
        groupRemoteRenderers.clear()
        setLocalRenderer(null)
        setRemoteRenderer(null)
    }
}
