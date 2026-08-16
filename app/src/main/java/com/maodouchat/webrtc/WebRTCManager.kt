package com.maodouchat.webrtc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 通话状态
 */
enum class CallState {
    IDLE, CALLING, RINGING, CONNECTED, DISCONNECTED
}

/**
 * 通话类型
 */
enum class CallType { AUDIO, VIDEO, GROUP;

    companion object {
        /**
         * 从 SDP 文本推断通话类型。仅当存在真实视频媒体行（以 `m=video ` 开头且端口非 0）时判为视频，
         * 避免把 `m=video` 子串误匹配进属性行（如 `a=...`），或把被拒绝/禁用的 `m=video 0 ...` 当作视频。
         */
        fun detectFromSdp(sdp: String?): CallType {
            if (sdp.isNullOrBlank()) return AUDIO
            for (line in sdp.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("m=video", ignoreCase = true)) {
                    // 9.166：标签已按忽略大小写匹配，直接按固定长度 7 剥离（removePrefix 无
                    // ignoreCase 重载）——此前大小写剥离不一致，`M=video` 行端口解析为 0，
                    // 视频通话被误判为 AUDIO，相机永不协商
                    val port = trimmed.substring(7).trimStart()
                        .takeWhile { it.isDigit() }.toIntOrNull() ?: 0
                    if (port != 0) return VIDEO
                }
            }
            return AUDIO
        }
    }
}

enum class GroupPeerConnectionState { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, FAILED, REJECTED, BUSY, NO_ANSWER }

class WebRTCException(message: String) : IllegalStateException(message)

data class CallIceServer(
    val urls: List<String>,
    val username: String = "",
    val credential: String = ""
) {
    companion object {
        fun defaultStun(): List<CallIceServer> = listOf(
            CallIceServer(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"))
        )

        /** True when no TURN/TURNS URL is present (NAT may fail; public STUN only). */
        fun isStunOnly(servers: List<CallIceServer>): Boolean {
            if (servers.isEmpty()) return true
            return servers.none { server ->
                server.urls.any { url ->
                    url.startsWith("turn:", ignoreCase = true) ||
                        url.startsWith("turns:", ignoreCase = true)
                }
            }
        }
    }
}

/**
 * WebRTC 音视频通话管理器
 */
class WebRTCManager(
    context: Context,
    initialIceServers: List<CallIceServer> = CallIceServer.defaultStun()
) {

    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val audioController = CallAudioController(appContext)

    // 8.35：ICE 服务器可在通话中热替换（TURN 短期凭据 1h 到期后，新 PeerConnection
    // 与重连时使用新凭据；由 CallViewModel 定期 fetchIceServers 刷新）
    @Volatile
    private var configuredIceServers: List<CallIceServer> =
        initialIceServers.ifEmpty { CallIceServer.defaultStun() }

    /** 通话中替换 ICE 服务器列表（TURN 凭据刷新）。空列表回退公共 STUN。 */
    fun refreshIceServers(servers: List<CallIceServer>) {
        configuredIceServers = servers.ifEmpty { CallIceServer.defaultStun() }
        val freshConfig = PeerConnection.RTCConfiguration(buildIceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        // 活跃直连/群 PeerConnection 立即换上新 TURN 凭据；之后触发 restartIce 时
        // 新候选会使用刷新后的配置，不再等到通话重建才生效。
        runCatching { peerConnection?.setConfiguration(freshConfig) }
        peerConnections.values.forEach { connection ->
            runCatching { connection.setConfiguration(freshConfig) }
        }
    }

    /** 当前 ICE 服务器列表（UI 判断是否仅 STUN 时使用）。 */
    fun currentIceServers(): List<CallIceServer> = configuredIceServers

    init {
        audioController.setOnRoutesChangedListener { available, selected ->
            onAudioRoutesChanged?.invoke(available, selected)
        }
        audioController.setOnAudioFocusChangedListener { granted ->
            // 9.166：焦点回调跑在音频系统线程——released 后不得触碰已销毁的本地音轨
            if (released) return@setOnAudioFocusChangedListener
            runCatching { localAudioTrack?.setEnabled(granted && !userMuted) }
            if (!granted) onAudioFocusLost?.invoke()
        }
    }

    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _callType = MutableStateFlow(CallType.AUDIO)
    val callType: StateFlow<CallType> = _callType.asStateFlow()

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    @Volatile private var released = false
    @Volatile private var peerConnection: PeerConnection? = null
    private val directCallGate = CallSessionGate()
    private var directCallSession = 0L
    // 群通话：每对端一个 PeerConnection，key 为对端 userId
    private val peerConnections: MutableMap<String, PeerConnection> = ConcurrentHashMap()
    private val peerTypes: MutableMap<String, CallType> = ConcurrentHashMap()
    private val groupPeerSessions = ConcurrentHashMap<String, Long>()
    private val groupPeerSessionCounter = AtomicLong(0L)
    private val groupRemoteVideoTracks: MutableMap<String, VideoTrack> = ConcurrentHashMap()
    private val groupRemoteRenderers: MutableMap<String, SurfaceViewRenderer> = ConcurrentHashMap()
    // BUG 1 fix: 缓冲在 remote description 设置前到达的 ICE candidate
    private val remoteDescriptionSet = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pendingIceCandidates = java.util.concurrent.CopyOnWriteArrayList<IceCandidate>()
    private val groupRemoteDescriptionSet = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>()
    private val groupPendingIceCandidates = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<IceCandidate>>()
    private var localAudioSource: AudioSource? = null
    // 9.166：焦点/信令回调线程读取，须 @Volatile 保证可见性（清理路径在锁内置空）
    @Volatile private var localAudioTrack: AudioTrack? = null
    @Volatile private var userMuted = false
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var onIceCandidate: ((IceCandidate) -> Unit)? = null
    private var onRemoteStream: ((MediaStream) -> Unit)? = null
    private var remoteMediaStream: MediaStream? = null

    // 由 UI 层设置的渲染器 — WebRTCManager 负责在 onTrack 时添加 sink
    var localRenderer: SurfaceViewRenderer? = null
    var remoteRenderer: SurfaceViewRenderer? = null

    // 远端视频轨道缓存：onTrack 到达时可能 remoteRenderer 还未 attach，
    // 先缓存 track，等 attachRemoteRenderer 时再补 addSink，解决视频黑屏竞态
    private var remoteVideoTrack: VideoTrack? = null

    // Bug #21: ICE 断连宽限期 — 网络切换(WiFi→4G)时 ICE 会短暂 DISCONNECTED 然后自动恢复
    // 不应立即终止通话，而是等待 grace period 后再通知断连

    /**
     * 设置远程媒体流回调
     */
    fun setOnRemoteStreamListener(listener: (MediaStream) -> Unit) {
        if (!released) onRemoteStream = listener
    }

    /**
     * 初始化 WebRTC 引擎
     */
    @Synchronized
    fun initialize() {
        ensureNotReleased()
        if (peerConnectionFactory != null) return

        val base = EglBase.create()
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            val factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(base.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(base.eglBaseContext))
                .createPeerConnectionFactory()
            eglBase = base
            peerConnectionFactory = factory
        } catch (error: Throwable) {
            runCatching { base.release() }
            throw error
        }
    }

    /**
     * 发起通话
     *
     * @param type 通话类型
     * @param onIceCandidate ICE 候选回调（用于信令）
     * @param onOfferCreated Offer SDP 回调（用于信令）
     */
    @Synchronized
    fun startCall(
        type: CallType,
        onIceCandidate: (IceCandidate) -> Unit,
        onOfferCreated: (SessionDescription) -> Unit
    ) {
        ensureNotReleased()
        initialize()
        checkNoActiveCall()
        val session = directCallGate.begin().also { directCallSession = it }
        this.onIceCandidate = onIceCandidate
        _callType.value = type
        try {
            audioController.start(type)
            if (!createPeerConnection(type, session)) throw WebRTCException("peer connection unavailable")
            _callState.value = CallState.CALLING

            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                if (type == CallType.VIDEO) {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                }
            }

            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    if (!isCurrentDirectCall(session)) return
                    val created = sdp ?: return reportDirectOperationError(session, "offer creation returned no SDP")
                    val connection = peerConnection
                        ?: return reportDirectOperationError(session, "peer connection released before local offer")
                    runCatching {
                        connection.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                if (isCurrentDirectCall(session)) invokeSafely { onOfferCreated(created) }
                            }
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(error: String?) {
                                reportDirectOperationError(session, "set local offer failed: ${error.orEmpty()}")
                            }
                        }, created)
                    }.onFailure { error ->
                        reportDirectOperationError(session, "set local offer failed: ${error.message.orEmpty()}")
                    }
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) { reportDirectOperationError(session, "create offer failed: ${error.orEmpty()}") }
                override fun onSetFailure(error: String?) {}
            }, constraints)
        } catch (error: Throwable) {
            cleanupCallResources()
            _callState.value = CallState.IDLE
            throw error
        }
    }

    /**
     * 接听来电
     *
     * @param remoteOfferSdp 对方的 Offer SDP
     * @param onAnswerCreated Answer SDP 回调（用于信令）
     */
    @Synchronized
    fun answerCall(
        remoteOfferSdp: String,
        type: CallType,
        onIceCandidate: (IceCandidate) -> Unit,
        onAnswerCreated: (SessionDescription) -> Unit
    ) {
        ensureNotReleased()
        initialize()
        checkNoActiveCall()
        val session = directCallGate.begin().also { directCallSession = it }
        this.onIceCandidate = onIceCandidate
        _callType.value = type
        try {
            audioController.start(type)
            if (!createPeerConnection(type, session)) throw WebRTCException("peer connection unavailable")
            _callState.value = CallState.RINGING

            val offer = SessionDescription(SessionDescription.Type.OFFER, remoteOfferSdp)
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {
                    if (!isCurrentDirectCall(session)) return
                    // BUG 1 fix: remote description 已设置，flush 缓冲的 ICE candidate
                    remoteDescriptionSet.set(true)
                    val pending = ArrayList(pendingIceCandidates)
                    pendingIceCandidates.clear()
                    pending.forEach { runCatching { peerConnection?.addIceCandidate(it) } }
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                        if (type == CallType.VIDEO) {
                            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                        }
                    }

                    val connection = peerConnection
                        ?: return reportDirectOperationError(session, "peer connection released before answer")
                    runCatching { connection.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(sdp: SessionDescription?) {
                            if (!isCurrentDirectCall(session)) return
                            val created = sdp ?: return reportDirectOperationError(session, "answer creation returned no SDP")
                            val activeConnection = peerConnection
                                ?: return reportDirectOperationError(session, "peer connection released before local answer")
                            runCatching {
                                activeConnection.setLocalDescription(object : SdpObserver {
                                    override fun onCreateSuccess(p0: SessionDescription?) {}
                                    override fun onSetSuccess() {
                                        if (!isCurrentDirectCall(session)) return
                                        // BUG 3 fix: 不在此处设 CONNECTED，等 ICE onIceConnectionChange 回调设
                                        invokeSafely { onAnswerCreated(created) }
                                    }
                                    override fun onCreateFailure(p0: String?) {}
                                    override fun onSetFailure(error: String?) {
                                        reportDirectOperationError(session, "set local answer failed: ${error.orEmpty()}")
                                    }
                                }, created)
                            }.onFailure { error ->
                                reportDirectOperationError(session, "set local answer failed: ${error.message.orEmpty()}")
                            }
                        }
                        override fun onSetSuccess() {}
                        override fun onCreateFailure(error: String?) { reportDirectOperationError(session, "create answer failed: ${error.orEmpty()}") }
                        override fun onSetFailure(error: String?) {}
                    }, constraints) }.onFailure { error ->
                        reportDirectOperationError(session, "create answer failed: ${error.message.orEmpty()}")
                    }
                }
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(error: String?) { reportDirectOperationError(session, "set remote offer failed: ${error.orEmpty()}") }
            }, offer)
        } catch (error: Throwable) {
            cleanupCallResources()
            _callState.value = CallState.IDLE
            throw error
        }
    }

    /**
     * 处理对方的 Answer SDP
     */
    fun handleAnswer(sdp: String) {
        if (released) return
        val session = directCallSession
        if (!isCurrentDirectCall(session)) return
        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        val connection = peerConnection ?: return
        runCatching { connection.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                if (!isCurrentDirectCall(session)) return
                // BUG 1 fix: remote description 已设置，flush 缓冲的 ICE candidate
                // BUG 3 fix: 不在此处设 CONNECTED，等 ICE onIceConnectionChange 回调设
                remoteDescriptionSet.set(true)
                val pending = ArrayList(pendingIceCandidates)
                pendingIceCandidates.clear()
                pending.forEach { runCatching { peerConnection?.addIceCandidate(it) } }
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(error: String?) { reportDirectOperationError(session, "set remote answer failed: ${error.orEmpty()}") }
        }, answer) }.onFailure { error ->
            reportDirectOperationError(session, "set remote answer failed: ${error.message.orEmpty()}")
        }
    }

    /**
     * 添加 ICE 候选
     */
    fun addIceCandidate(candidate: IceCandidate) {
        if (released) return
        // 7th-round scan: 丢弃上一通/已结束通话残留的 ICE candidate，避免误应用到新连接
        val session = directCallSession
        if (!isCurrentDirectCall(session)) return
        if (!remoteDescriptionSet.get()) {
            pendingIceCandidates.add(candidate)
            return
        }
        runCatching { peerConnection?.addIceCandidate(candidate) }
    }

    /**
     * 挂断通话
     */
    @Synchronized
    fun hangUp() {
        cleanupCallResources()
        _callState.value = CallState.DISCONNECTED
    }

    /**
     * 切换摄像头
     */
    fun switchCamera() {
        (videoCapturer as? org.webrtc.CameraVideoCapturer)?.switchCamera(
            object : org.webrtc.CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    // 切换成功：UI 状态由 CameraX/本地状态维护，此处无需动作
                }

                override fun onCameraSwitchError(error: String?) {
                    android.util.Log.w("WebRTCManager", "switchCamera failed: $error")
                }
            }
        )
    }

    /**
     * 静音/取消静音
     */
    fun toggleMute(muted: Boolean) {
        userMuted = muted
        localAudioTrack?.setEnabled(!muted)
    }

    fun selectAudioRoute(route: CallAudioRoute): Boolean = audioController.selectRoute(route)

    /**
     * 开关摄像头
     */
    fun toggleVideo(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    /**
     * 初始化远端视频渲染器（UI 层创建后传入）
     * 如果远端视频轨道已到达（onTrack 先于 UI attach），此时补上 addSink
     */
    @Synchronized
    fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {
        val eglContext = eglBase?.eglBaseContext
        if (released || eglContext == null) {
            runCatching { renderer.release() }
            return
        }
        renderer.init(eglContext, null)
        remoteRenderer?.takeIf { it !== renderer }?.let { old ->
            remoteVideoTrack?.let { track -> runCatching { track.removeSink(old) } }
            runCatching { old.release() }
        }
        remoteRenderer = renderer
        // 竞态修复：如果 onTrack 已经收到远端视频但当时 renderer 还没准备好，现在补上 sink
        remoteVideoTrack?.let { track ->
            runCatching { track.addSink(renderer) }
        }
    }

    /**
     * 初始化本地视频渲染器（UI 层创建后传入）
     */
    @Synchronized
    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        val eglContext = eglBase?.eglBaseContext
        if (released || eglContext == null) {
            runCatching { renderer.release() }
            return
        }
        renderer.init(eglContext, null)
        localRenderer?.takeIf { it !== renderer }?.let { old ->
            localVideoTrack?.let { track -> runCatching { track.removeSink(old) } }
            runCatching { old.release() }
        }
        localVideoTrack?.addSink(renderer)
        localRenderer = renderer
    }

    @Synchronized
    fun detachRemoteRenderer(renderer: SurfaceViewRenderer) {
        if (remoteRenderer !== renderer) return
        remoteVideoTrack?.let { track -> runCatching { track.removeSink(renderer) } }
        remoteRenderer = null
        runCatching { renderer.release() }
    }

    @Synchronized
    fun detachLocalRenderer(renderer: SurfaceViewRenderer) {
        if (localRenderer !== renderer) return
        localVideoTrack?.let { track -> runCatching { track.removeSink(renderer) } }
        localRenderer = null
        runCatching { renderer.release() }
    }

    @Synchronized
    fun attachGroupRemoteRenderer(peerUserId: String, renderer: SurfaceViewRenderer) {
        val eglContext = eglBase?.eglBaseContext
        if (released || eglContext == null) {
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

    @Synchronized
    fun detachGroupRemoteRenderer(peerUserId: String, renderer: SurfaceViewRenderer) {
        if (groupRemoteRenderers[peerUserId] !== renderer) return
        groupRemoteVideoTracks[peerUserId]?.let { track -> runCatching { track.removeSink(renderer) } }
        groupRemoteRenderers.remove(peerUserId)
        runCatching { renderer.release() }
    }

    /**
     * 释放渲染器资源
     */
    @Synchronized
    fun releaseRenderers() {
        localRenderer?.let { renderer -> localVideoTrack?.let { track -> runCatching { track.removeSink(renderer) } } }
        remoteRenderer?.let { renderer -> remoteVideoTrack?.let { track -> runCatching { track.removeSink(renderer) } } }
        runCatching { localRenderer?.release() }
        runCatching { remoteRenderer?.release() }
        groupRemoteRenderers.forEach { (peerId, renderer) ->
            groupRemoteVideoTracks[peerId]?.let { track -> runCatching { track.removeSink(renderer) } }
            runCatching { renderer.release() }
        }
        groupRemoteRenderers.clear()
        localRenderer = null
        remoteRenderer = null
    }

    /**
     * 释放所有资源
     */
    // 回调：ICE 连接状态变化时通知 CallViewModel
    var onIceConnectionDisconnected: (() -> Unit)? = null

    /** ICE 短暂断连后自动恢复时回调，调用方应撤销"重连中"提示 */
    var onIceConnectionRecovered: (() -> Unit)? = null

    /** ICE 已进入不可恢复的 FAILED 状态。 */
    var onIceConnectionFailed: (() -> Unit)? = null

    /** Available output devices or the selected route changed during a call. */
    var onAudioRoutesChanged: ((Set<CallAudioRoute>, CallAudioRoute) -> Unit)? = null

    var onAudioFocusLost: (() -> Unit)? = null

    var onOperationError: ((String?, String) -> Unit)? = null

    var onGroupPeerStateChanged: ((String, GroupPeerConnectionState) -> Unit)? = null
    var onGroupPeerVideoChanged: ((String, Boolean) -> Unit)? = null

    @Synchronized
    fun release() {
        if (released) return
        released = true
        onIceConnectionDisconnected = null
        onIceConnectionRecovered = null
        onIceConnectionFailed = null
        onAudioRoutesChanged = null
        onAudioFocusLost = null
        onOperationError = null
        onGroupPeerStateChanged = null
        onGroupPeerVideoChanged = null
        onIceCandidate = null
        onRemoteStream = null
        audioController.setOnRoutesChangedListener(null)
        audioController.setOnAudioFocusChangedListener(null)
        cleanupCallResources()
        runCatching { peerConnectionFactory?.dispose() }
        peerConnectionFactory = null
        runCatching { eglBase?.release() }
        eglBase = null
        lastStatsSnapshot = null
        statsRequestInFlight = false
        _callState.value = CallState.DISCONNECTED
    }

    /**
     * 群通话：给指定对端创建一个 PeerConnection
     */
    @Synchronized
    fun startGroupCallToPeer(peerUserId: String, type: CallType, onIceCandidate: (IceCandidate) -> Unit, onOfferCreated: (SessionDescription) -> Unit) {
        ensureNotReleased()
        initialize()
        check(peerConnection == null) { "direct call already active" }
        val mediaReady = try {
            ensureLocalMedia(type)
        } catch (error: Throwable) {
            cleanupUnusedGroupMedia()
            throw error
        }
        if (!mediaReady) {
            cleanupUnusedGroupMedia()
            return reportOperationError(peerUserId, "local media unavailable")
        }
        val pc = try {
            createPeerConnectionFor(peerUserId, type, onIceCandidate)
        } catch (error: Throwable) {
            cleanupUnusedGroupMedia()
            throw error
        } ?: run {
            cleanupUnusedGroupMedia()
            return reportOperationError(peerUserId, "peer connection unavailable")
        }
        val session = groupPeerSessions[peerUserId]
            ?: throw WebRTCException("group peer session unavailable")
        peerConnections.put(peerUserId, pc)?.let { previous -> runCatching { previous.dispose() } }
        peerTypes[peerUserId] = type
        invokeSafely { onGroupPeerStateChanged?.invoke(peerUserId, GroupPeerConnectionState.CONNECTING) }
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            if (type == CallType.VIDEO) mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        try {
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    if (!isCurrentGroupPeer(peerUserId, session)) return
                    val created = sdp ?: return reportGroupOperationError(peerUserId, session, "offer creation returned no SDP")
                    runCatching {
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                if (isCurrentGroupPeer(peerUserId, session)) invokeSafely { onOfferCreated(created) }
                            }
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(error: String?) {
                                if (isCurrentGroupPeer(peerUserId, session)) {
                                    reportGroupOperationError(peerUserId, session, "set local offer failed: ${error.orEmpty()}")
                                }
                            }
                        }, created)
                    }.onFailure { error ->
                        reportGroupOperationError(peerUserId, session, "set local offer failed: ${error.message.orEmpty()}")
                    }
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    if (isCurrentGroupPeer(peerUserId, session)) {
                        reportGroupOperationError(peerUserId, session, "create offer failed: ${error.orEmpty()}")
                    }
                }
                override fun onSetFailure(error: String?) {}
            }, constraints)
        } catch (error: Throwable) {
            removeGroupPeer(peerUserId)
            cleanupUnusedGroupMedia()
            throw error
        }
    }

    /** 群通话：处理收到的 Answer */
    fun handleGroupAnswer(peerUserId: String, sdp: String) {
        val session = groupPeerSessions[peerUserId] ?: return
        val pc = peerConnections[peerUserId] ?: return
        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        runCatching { pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                // 8.49 修复：回调在途时 pc 可能已被 removeGroupPeer 替换/销毁——与
                // acceptGroupOffer 的 onSetSuccess 一致做 session 门禁，且不把 stale
                // 的 remoteDescriptionSet 留给该 peer 的下一个 session（否则新 session
                // 的候选会在远端描述未设置时被直接 add 丢弃）
                if (!isCurrentGroupPeer(peerUserId, session)) return@onSetSuccess
                // BUG 1 fix: remote description 已设置，flush 缓冲的 ICE candidate
                val set = groupRemoteDescriptionSet.computeIfAbsent(peerUserId) { java.util.concurrent.atomic.AtomicBoolean(false) }
                set.set(true)
                val pending = ArrayList(groupPendingIceCandidates[peerUserId].orEmpty())
                groupPendingIceCandidates[peerUserId]?.clear()
                pending.forEach { runCatching { pc.addIceCandidate(it) } }
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(error: String?) {
                if (!isCurrentGroupPeer(peerUserId, session)) return@onSetFailure
                reportGroupOperationError(peerUserId, session, "set remote answer failed: ${error.orEmpty()}")
            }
        }, answer) }.onFailure { error ->
            reportGroupOperationError(peerUserId, session, "set remote answer failed: ${error.message.orEmpty()}")
        }
    }

    /** 群通话：处理收到的 Offer 并应答（主动加入方） */
    @Synchronized
    fun acceptGroupOffer(peerUserId: String, remoteOfferSdp: String, type: CallType, onIceCandidate: (IceCandidate) -> Unit, onAnswerCreated: (SessionDescription) -> Unit) {
        ensureNotReleased()
        initialize()
        check(peerConnection == null) { "direct call already active" }
        val mediaReady = try {
            ensureLocalMedia(type)
        } catch (error: Throwable) {
            cleanupUnusedGroupMedia()
            throw error
        }
        if (!mediaReady) {
            cleanupUnusedGroupMedia()
            return reportOperationError(peerUserId, "local media unavailable")
        }
        val pc = try {
            createPeerConnectionFor(peerUserId, type, onIceCandidate)
        } catch (error: Throwable) {
            cleanupUnusedGroupMedia()
            throw error
        } ?: run {
            cleanupUnusedGroupMedia()
            return reportOperationError(peerUserId, "peer connection unavailable")
        }
        val session = groupPeerSessions[peerUserId]
            ?: throw WebRTCException("group peer session unavailable")
        peerConnections.put(peerUserId, pc)?.let { previous -> runCatching { previous.dispose() } }
        peerTypes[peerUserId] = type
        invokeSafely { onGroupPeerStateChanged?.invoke(peerUserId, GroupPeerConnectionState.CONNECTING) }
        val offer = SessionDescription(SessionDescription.Type.OFFER, remoteOfferSdp)
        try {
            pc.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {
                    if (!isCurrentGroupPeer(peerUserId, session)) return
                    // BUG 1 fix: remote description 已设置，flush 缓冲的 ICE candidate
                    val set = groupRemoteDescriptionSet.computeIfAbsent(peerUserId) { java.util.concurrent.atomic.AtomicBoolean(false) }
                    set.set(true)
                    val pending = ArrayList(groupPendingIceCandidates[peerUserId].orEmpty())
                    groupPendingIceCandidates[peerUserId]?.clear()
                    pending.forEach { runCatching { pc.addIceCandidate(it) } }
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                        if (type == CallType.VIDEO) mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                    }
                    runCatching { pc.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(sdp: SessionDescription?) {
                            if (!isCurrentGroupPeer(peerUserId, session)) return
                            val created = sdp ?: return reportGroupOperationError(peerUserId, session, "answer creation returned no SDP")
                            runCatching {
                                pc.setLocalDescription(object : SdpObserver {
                                    override fun onCreateSuccess(p0: SessionDescription?) {}
                                    override fun onSetSuccess() {
                                        if (isCurrentGroupPeer(peerUserId, session)) invokeSafely { onAnswerCreated(created) }
                                    }
                                    override fun onCreateFailure(p0: String?) {}
                                    override fun onSetFailure(error: String?) {
                                        if (isCurrentGroupPeer(peerUserId, session)) {
                                            reportGroupOperationError(peerUserId, session, "set local answer failed: ${error.orEmpty()}")
                                        }
                                    }
                                }, created)
                            }.onFailure { error ->
                                reportGroupOperationError(peerUserId, session, "set local answer failed: ${error.message.orEmpty()}")
                            }
                        }
                        override fun onSetSuccess() {}
                        override fun onCreateFailure(error: String?) {
                            if (isCurrentGroupPeer(peerUserId, session)) {
                                reportGroupOperationError(peerUserId, session, "create answer failed: ${error.orEmpty()}")
                            }
                        }
                        override fun onSetFailure(p0: String?) {}
                    }, constraints) }.onFailure { error ->
                        reportGroupOperationError(peerUserId, session, "create answer failed: ${error.message.orEmpty()}")
                    }
                }
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(error: String?) {
                    if (isCurrentGroupPeer(peerUserId, session)) {
                        reportGroupOperationError(peerUserId, session, "set remote offer failed: ${error.orEmpty()}")
                    }
                }
            }, offer)
        } catch (error: Throwable) {
            removeGroupPeer(peerUserId)
            cleanupUnusedGroupMedia()
            throw error
        }
    }

    private fun createPeerConnectionFor(peerUserId: String, type: CallType, onIceCandidate: (IceCandidate) -> Unit): PeerConnection? {
        val factory = peerConnectionFactory ?: return null
        val session = groupPeerSessionCounter.incrementAndGet()
        val iceServers = buildIceServers()
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // 持续收集 ICE 候选：网络切换时自动尝试新路径，弱网重连的基础
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                if (!isCurrentGroupPeer(peerUserId, session)) return
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> invokeSafely {
                        onGroupPeerStateChanged?.invoke(peerUserId, GroupPeerConnectionState.CONNECTED)
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        // 群 mesh 弱网：主动 ICE 重启加速恢复
                        try { peerConnections[peerUserId]?.restartIce() } catch (_: Exception) {}
                        invokeSafely { onGroupPeerStateChanged?.invoke(peerUserId, GroupPeerConnectionState.RECONNECTING) }
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        // FAILED 也尝试一次 ICE 重启
                        try { peerConnections[peerUserId]?.restartIce() } catch (_: Exception) {}
                        invokeSafely { onGroupPeerStateChanged?.invoke(peerUserId, GroupPeerConnectionState.FAILED) }
                    }
                    PeerConnection.IceConnectionState.CLOSED -> invokeSafely {
                        onGroupPeerStateChanged?.invoke(peerUserId, GroupPeerConnectionState.DISCONNECTED)
                    }
                    else -> Unit
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (isCurrentGroupPeer(peerUserId, session)) candidate?.let { value ->
                    invokeSafely { onIceCandidate(value) }
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out MediaStream>?) {
                if (isCurrentGroupPeer(peerUserId, session)) {
                    receiver?.track()?.let { track -> handleGroupRemoteTrack(peerUserId, track) }
                }
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                if (isCurrentGroupPeer(peerUserId, session)) {
                    transceiver?.receiver?.track()?.let { track -> handleGroupRemoteTrack(peerUserId, track) }
                }
            }
        }
        val connection = factory.createPeerConnection(rtcConfig, observer) ?: run {
            return null
        }
        val tracksAdded = runCatching {
            localAudioTrack?.let { connection.addTrack(it, listOf()) ?: error("audio track unavailable") }
            if (type == CallType.VIDEO) {
                localVideoTrack?.let { connection.addTrack(it, listOf()) ?: error("video track unavailable") }
            }
        }.isSuccess
        if (!tracksAdded) {
            runCatching { connection.dispose() }
            return null
        }
        groupPeerSessions[peerUserId] = session
        // 8.49：新 session 重置该 peer 的远端描述/候选缓冲标志——上一 session 的 stale
        // 状态不能留给新连接（否则新 session 候选会在远端描述未设置时被直接 add 丢弃）
        groupRemoteDescriptionSet[peerUserId]?.set(false)
        groupPendingIceCandidates[peerUserId]?.clear()
        return connection
    }

    private fun ensureLocalMedia(type: CallType): Boolean {
        // 8.39：与直连路径（createPeerConnection 显式检查）一致的权限预检——
        // 群 mesh 此前缺检查，权限缺失时静默返回 null 且无权限类提示，通话中撤销权限直接哑掉
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw WebRTCException("microphone_permission_required")
        }
        if (type == CallType.VIDEO &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw WebRTCException("camera_permission_required")
        }
        audioController.start(type)
        if (localAudioTrack == null) {
            val created = createLocalAudioTrack("AUDIO_TRACK")
            if (created == null) {
                // 9.166：本地音轨创建失败时回滚刚获取的音频焦点/通话模式——调用方
                // cleanupUnusedGroupMedia 只在无对端连接时清理，已有对端时焦点泄漏到进程结束
                audioController.stop()
                return false
            }
        }
        if (type == CallType.VIDEO && localVideoTrack == null) {
            val track = createLocalVideoTrack("VIDEO_TRACK") ?: return false
            track.setEnabled(true)
        }
        return true
    }

    /**
     * 群通话：把 ICE 候选发给对应对端
     */
    fun addGroupIceCandidate(peerUserId: String, candidate: IceCandidate) {
        // 7th-round scan: 仅当该对端仍属于当前群通话 session 才处理，丢弃残留/已退出 peer 的候选
        val session = groupPeerSessions[peerUserId] ?: return
        if (!isCurrentGroupPeer(peerUserId, session)) return
        val set = groupRemoteDescriptionSet.computeIfAbsent(peerUserId) { java.util.concurrent.atomic.AtomicBoolean(false) }
        if (!set.get()) {
            groupPendingIceCandidates.computeIfAbsent(peerUserId) { java.util.concurrent.CopyOnWriteArrayList() }.add(candidate)
            return
        }
        runCatching { peerConnections[peerUserId]?.addIceCandidate(candidate) }
    }

    /** 群通话：是否已经存在与该对端的连接 */
    fun hasGroupPeer(peerUserId: String): Boolean = peerConnections.containsKey(peerUserId)

    /** 群通话：当前对端数 */
    fun groupPeerCount(): Int = peerConnections.size

    /** 群通话：关闭与某对端的连接 */
    @Synchronized
    fun removeGroupPeer(peerUserId: String) {
        groupPeerSessions.remove(peerUserId)
        peerConnections.remove(peerUserId)?.let { runCatching { it.dispose() } }
        peerTypes.remove(peerUserId)
        // BUG 1 fix: 清理该对端的 ICE candidate 缓冲
        groupRemoteDescriptionSet.remove(peerUserId)
        groupPendingIceCandidates.remove(peerUserId)
        groupRemoteVideoTracks.remove(peerUserId)?.let { track ->
            groupRemoteRenderers[peerUserId]?.let { renderer -> runCatching { track.removeSink(renderer) } }
        }
        groupRemoteRenderers.remove(peerUserId)?.let { renderer -> runCatching { renderer.release() } }
        invokeSafely { onGroupPeerVideoChanged?.invoke(peerUserId, false) }
    }

    private fun handleGroupRemoteTrack(peerUserId: String, track: org.webrtc.MediaStreamTrack) {
        if (track !is VideoTrack) return
        synchronized(this) {
            val previous = groupRemoteVideoTracks.put(peerUserId, track)
            if (previous !== track) {
                groupRemoteRenderers[peerUserId]?.let { renderer ->
                    previous?.let { old -> runCatching { old.removeSink(renderer) } }
                    runCatching { track.addSink(renderer) }
                }
            }
        }
        invokeSafely { onGroupPeerVideoChanged?.invoke(peerUserId, true) }
    }

    private fun createPeerConnection(type: CallType, session: Long): Boolean {
        val iceServers = buildIceServers()
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        // 持续收集 ICE 候选：网络切换时自动尝试新路径，弱网重连的基础
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (isCurrentDirectCall(session)) candidate?.let { value ->
                    invokeSafely { onIceCandidate?.invoke(value) }
                }
            }
            override fun onAddStream(stream: MediaStream?) {
                if (isCurrentDirectCall(session)) stream?.let { value ->
                    invokeSafely { onRemoteStream?.invoke(value) }
                }
            }
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (!isCurrentDirectCall(session)) return
                if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                    // ICE 恢复连接，取消宽限期定时器
                    _callState.value = CallState.CONNECTED
                    invokeSafely { onIceConnectionRecovered?.invoke() }
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                    // 宽限期由 CallViewModel 统一管理；恢复时必须显式回调取消超时。
                    // 主动触发 ICE 重启以加速恢复（弱网/网络切换场景）
                    try { peerConnection?.restartIce() } catch (_: Exception) {}
                    invokeSafely { onIceConnectionDisconnected?.invoke() }
                } else if (state == PeerConnection.IceConnectionState.FAILED) {
                    // FAILED 时也尝试一次 ICE 重启，给弱网最后一次恢复机会
                    try { peerConnection?.restartIce() } catch (_: Exception) {}
                    _callState.value = CallState.DISCONNECTED
                    invokeSafely { onIceConnectionFailed?.invoke() }
                }
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onTrack(transceiver: RtpTransceiver?) {
                if (!isCurrentDirectCall(session)) return
                transceiver?.receiver?.track()?.let { track ->
                    if (track is VideoTrack) {
                        // 缓存远端视频轨道，解决 onTrack 先于 attachRemoteRenderer 的竞态
                        // attach/detachRemoteRenderer 都持 this 锁；onTrack 必须同一把锁，
                        // 避免信令线程与 UI 线程的可见性/复合读写竞态。
                        synchronized(this) {
                            if (released) return@let
                            remoteVideoTrack = track
                            // 如果 renderer 已就绪，立即添加 sink；否则等 attachRemoteRenderer 时补上
                            remoteRenderer?.let { runCatching { track.addSink(it) } }
                        }
                        val listener = onRemoteStream
                        if (listener != null) {
                            peerConnectionFactory?.createLocalMediaStream("remoteStream")?.let { stream ->
                                stream.addTrack(track)
                                val previousStream = remoteMediaStream
                                remoteMediaStream = stream
                                runCatching { previousStream?.dispose() }
                                invokeSafely { listener(stream) }
                            }
                        }
                    }
                }
            }
        })

        val connection = peerConnection ?: return false

        // BUG 4 fix: 创建音频轨道前检查 RECORD_AUDIO 权限
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw WebRTCException("microphone_permission_required")
        }

        val audioTrack = createLocalAudioTrack("localAudio") ?: run {
            runCatching { connection.dispose() }
            peerConnection = null
            return false
        }
        val audioAdded = runCatching {
            connection.addTrack(audioTrack, listOf("ARDAMSa0")) ?: error("audio sender unavailable")
        }.isSuccess
        if (!audioAdded) return false

        // BUG 4 fix: 创建视频轨道前检查 CAMERA 权限
        if (type == CallType.VIDEO) {
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw WebRTCException("camera_permission_required")
            }
            if (!addVideoTrack()) {
                return false
            }
        }
        return true
    }

    private fun buildIceServers(): List<PeerConnection.IceServer> =
        configuredIceServers.ifEmpty { CallIceServer.defaultStun() }.map { server ->
            PeerConnection.IceServer.builder(server.urls).apply {
                if (server.username.isNotBlank()) setUsername(server.username)
                if (server.credential.isNotBlank()) setPassword(server.credential)
            }.createIceServer()
        }

    private fun addVideoTrack(): Boolean {
        val track = createLocalVideoTrack("localVideo") ?: return false
        return runCatching { peerConnection?.addTrack(track, listOf("ARDAMS")) ?: error("peer connection unavailable") }.isSuccess
    }

    private fun createLocalAudioTrack(trackId: String): AudioTrack? {
        val factory = peerConnectionFactory ?: return null
        val source = runCatching { factory.createAudioSource(createAudioConstraints()) }.getOrNull() ?: return null
        val track = runCatching { factory.createAudioTrack(trackId, source) }.getOrNull() ?: run {
            runCatching { source.dispose() }
            return null
        }
        localAudioSource = source
        localAudioTrack = track
        track.setEnabled(!userMuted)
        return track
    }

    private fun createLocalVideoTrack(trackId: String): VideoTrack? {
        val factory = peerConnectionFactory ?: return null
        val eglContext = eglBase?.eglBaseContext ?: return null
        val capturer = createCameraCapturer() ?: return null
        val textureHelper = runCatching { SurfaceTextureHelper.create("CaptureThread", eglContext) }.getOrNull() ?: run {
            runCatching { capturer.dispose() }
            return null
        }
        val videoSource = runCatching { factory.createVideoSource(capturer.isScreencast) }.getOrNull() ?: run {
            runCatching { capturer.dispose() }
            runCatching { textureHelper.dispose() }
            return null
        }

        val captureStarted = runCatching {
            capturer.initialize(textureHelper, appContext, videoSource.capturerObserver)
            capturer.startCapture(1280, 720, 30)
        }.isSuccess
        if (!captureStarted) {
            disposeVideoResources(null, videoSource, capturer, textureHelper)
            return null
        }

        val track = runCatching { factory.createVideoTrack(trackId, videoSource) }.getOrNull()
        if (track == null) {
            disposeVideoResources(null, videoSource, capturer, textureHelper)
            return null
        }
        localVideoTrack = track
        localVideoSource = videoSource
        videoCapturer = capturer
        surfaceTextureHelper = textureHelper
        localRenderer?.let { renderer -> runCatching { track.addSink(renderer) } }
        return track
    }

    private fun ensureNotReleased() {
        if (released) throw WebRTCException("WebRTC manager already released")
    }

    private fun checkNoActiveCall() {
        check(
            peerConnection == null &&
                peerConnections.isEmpty() &&
                localAudioTrack == null &&
                localAudioSource == null &&
                localVideoTrack == null &&
                localVideoSource == null &&
                videoCapturer == null &&
                surfaceTextureHelper == null
        ) { "call already active" }
    }

    private fun isCurrentDirectCall(session: Long): Boolean =
        !released && session != 0L && directCallGate.isCurrent(session)

    private fun isCurrentGroupPeer(peerUserId: String, session: Long): Boolean =
        !released && groupPeerSessions[peerUserId] == session

    private fun cleanupUnusedGroupMedia() {
        if (peerConnection == null && peerConnections.isEmpty()) cleanupCallResources()
    }

    private fun cleanupCallResources() {
        val session = directCallSession
        if (session != 0L && !directCallGate.isCurrent(session)) {
            // 9.166：晚到的旧会话清理回调（异步错误路径）——新通话已 begin()，
            // 直接返回：既不动新会话的门/标记，也不销毁其 PeerConnection/轨道
            return
        }
        if (session != 0L) {
            directCallGate.invalidate(session)
            directCallSession = 0L
        }
        groupPeerSessions.clear()
        releaseRenderers()

        val directConnection = peerConnection
        peerConnection = null
        val groupConnections = peerConnections.values.toList()
        peerConnections.clear()
        peerTypes.clear()
        groupRemoteVideoTracks.clear()
        // BUG 1 fix: 清理 ICE candidate 缓冲
        remoteDescriptionSet.set(false)
        pendingIceCandidates.clear()
        groupRemoteDescriptionSet.clear()
        groupPendingIceCandidates.clear()
        remoteVideoTrack = null
        val syntheticRemoteStream = remoteMediaStream
        remoteMediaStream = null
        runCatching { syntheticRemoteStream?.dispose() }
        runCatching { directConnection?.dispose() }
        groupConnections.forEach { connection -> runCatching { connection.dispose() } }

        val audioTrack = localAudioTrack
        localAudioTrack = null
        val audioSource = localAudioSource
        localAudioSource = null
        runCatching { audioTrack?.dispose() }
        runCatching { audioSource?.dispose() }

        val videoTrack = localVideoTrack
        localVideoTrack = null
        val videoSource = localVideoSource
        localVideoSource = null
        val capturer = videoCapturer
        videoCapturer = null
        val textureHelper = surfaceTextureHelper
        surfaceTextureHelper = null
        disposeVideoResources(videoTrack, videoSource, capturer, textureHelper)

        audioController.stop()
        userMuted = false
        onIceCandidate = null
        lastStatsSnapshot = null
        statsRequestGeneration.incrementAndGet()
        statsRequestInFlight = false
    }

    private fun disposeVideoResources(
        track: VideoTrack?,
        source: VideoSource?,
        capturer: VideoCapturer?,
        textureHelper: SurfaceTextureHelper?
    ) {
        runCatching { capturer?.stopCapture() }
        runCatching { track?.dispose() }
        runCatching { source?.dispose() }
        runCatching { capturer?.dispose() }
        runCatching { textureHelper?.dispose() }
    }

    private fun reportDirectOperationError(session: Long, detail: String) {
        if (!isCurrentDirectCall(session)) return
        mainExecutor.execute {
            synchronized(this) {
                if (!isCurrentDirectCall(session)) return@synchronized
                cleanupCallResources()
                _callState.value = CallState.DISCONNECTED
                reportOperationError(null, detail)
            }
        }
    }

    private fun reportGroupOperationError(peerUserId: String, session: Long, detail: String) {
        if (!isCurrentGroupPeer(peerUserId, session)) return
        mainExecutor.execute {
            synchronized(this) {
                if (!isCurrentGroupPeer(peerUserId, session)) return@synchronized
                invokeSafely { onGroupPeerStateChanged?.invoke(peerUserId, GroupPeerConnectionState.FAILED) }
                removeGroupPeer(peerUserId)
                cleanupUnusedGroupMedia()
                reportOperationError(peerUserId, detail)
            }
        }
    }

    private fun reportOperationError(peerUserId: String?, detail: String) {
        if (released) return
        android.util.Log.w("WebRTC", detail)
        invokeSafely { onOperationError?.invoke(peerUserId, detail) }
    }

    private inline fun invokeSafely(callback: () -> Unit) {
        runCatching(callback).onFailure { error ->
            android.util.Log.w("WebRTC", "call callback failed", error)
        }
    }

    /** 标准音频约束：回声消除 + 自动增益 + 噪声抑制（使用标准名称，非已废弃的 goog* 前缀）。 */
    private fun createAudioConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("echoCancellation", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("autoGainControl", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("noiseSuppression", "true"))
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(appContext)
        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        for (deviceName in enumerator.deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        return null
    }

    /**
     * 当前主链路的连接统计快照。用于驱动 UI 上的"网络质量"指示器（绿/黄/红）。
     * 群通话取当前状态为 CONNECTED/successful 的第一个 PeerConnection。
     *
     * getStats 为异步回调；本方法立即返回最近一次采样结果，并触发下一次采样。
     */
    data class ConnectionStatsSnapshot(
        val rttMs: Long?,
        val packetLossPercent: Double?,
        val availableOutgoingBitrateKbps: Double?
    )

    @Volatile
    private var lastStatsSnapshot: ConnectionStatsSnapshot? = null

    @Volatile
    private var statsRequestInFlight: Boolean = false
    private val statsRequestGeneration = AtomicLong(0L)

    @Synchronized
    fun getConnectionStatsSnapshot(): ConnectionStatsSnapshot? {
        if (released) return null
        val pc = selectStatsPeerConnection()
        if (pc != null && !statsRequestInFlight) {
            val requestGeneration = statsRequestGeneration.incrementAndGet()
            statsRequestInFlight = true
            runCatching {
                pc.getStats { report ->
                    try {
                        if (
                            !released &&
                            statsRequestGeneration.get() == requestGeneration &&
                            isManagedPeerConnection(pc)
                        ) {
                            lastStatsSnapshot = parseStatsReport(report)
                        }
                    } finally {
                        if (statsRequestGeneration.get() == requestGeneration) {
                            statsRequestInFlight = false
                        }
                    }
                }
            }.onFailure {
                if (statsRequestGeneration.get() == requestGeneration) {
                    statsRequestInFlight = false
                }
            }
        }
        return lastStatsSnapshot
    }

    private fun selectStatsPeerConnection(): PeerConnection? {
        peerConnection?.let { return it }
        peerConnections.values.firstOrNull()?.let { return it }
        return null
    }

    private fun isManagedPeerConnection(connection: PeerConnection): Boolean =
        peerConnection === connection || peerConnections.values.any { candidate -> candidate === connection }

    private fun parseStatsReport(report: org.webrtc.RTCStatsReport): ConnectionStatsSnapshot? {
        var rttMs: Long? = null
        var packetsLost = 0L
        var packetsReceived = 0L
        var availableBitrateKbps: Double? = null
        try {
            val statsMap = report.statsMap ?: return null
            for ((_, stats) in statsMap) {
                val type = stats.type?.lowercase().orEmpty()
                val members = stats.members ?: continue
                when (type) {
                    "candidate-pair" -> {
                        val selected = members["selected"] as? Boolean
                            ?: (members["nominated"] as? Boolean)
                        if (selected == true) {
                            readNumber(members["currentRoundTripTime"])?.let { rttSec ->
                                rttMs = (rttSec * 1000.0).toLong()
                            }
                            readNumber(members["availableOutgoingBitrate"])?.let { bps ->
                                availableBitrateKbps = bps / 1000.0
                            }
                        }
                    }
                    "inbound-rtp" -> {
                        val media = (members["kind"] as? String)
                            ?: (members["mediaType"] as? String)
                            ?: ""
                        if (media == "audio" || media == "video" || media.isEmpty()) {
                            readNumber(members["packetsLost"])?.let { packetsLost += it.toLong() }
                            readNumber(members["packetsReceived"])?.let { packetsReceived += it.toLong() }
                        }
                    }
                    "remote-inbound-rtp" -> {
                        if (rttMs == null) {
                            readNumber(members["roundTripTime"])?.let { rttSec ->
                                rttMs = (rttSec * 1000.0).toLong()
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            return lastStatsSnapshot
        }
        val lossPercent = if (packetsReceived + packetsLost > 0) {
            packetsLost.toDouble() * 100.0 / (packetsReceived + packetsLost).toDouble()
        } else {
            null
        }
        if (rttMs == null && lossPercent == null && availableBitrateKbps == null) {
            return lastStatsSnapshot
        }
        return ConnectionStatsSnapshot(
            rttMs = rttMs,
            packetLossPercent = lossPercent,
            availableOutgoingBitrateKbps = availableBitrateKbps
        )
    }

    private fun readNumber(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }
}
