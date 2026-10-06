package com.maodouchat.ui.screen.call

import com.maodouchat.webrtc.CallAudioRoute
import com.maodouchat.webrtc.CallState
import com.maodouchat.webrtc.CallType
import com.maodouchat.webrtc.GroupPeerConnectionState

/** WebRTC 原生库加载失败（下载/预加载），用于向用户呈现友好错误而非原生 UnsatisfiedLinkError。 */
class WebRtcNativeLoadException(message: String) : Exception(message)

data class CallUiState(
    val contactId: String = "",
    val contactName: String = "",
    val contactAvatar: String? = null,
    val callType: CallType = CallType.AUDIO,
    val callState: CallState = CallState.IDLE,
    val isIncoming: Boolean = false,
    val isGroupCall: Boolean = false,
    val duration: String = "00:00",
    val isInitializing: Boolean = false,
    val networkReconnecting: Boolean = false,
    val networkStats: NetworkQuality = NetworkQuality.UNKNOWN,
    /** True when ICE uses public STUN only (no TURN credentials). */
    val iceStunOnly: Boolean = false,
    val availableAudioRoutes: Set<CallAudioRoute> = emptySet(),
    val selectedAudioRoute: CallAudioRoute? = null,
    val groupParticipants: List<GroupCallParticipantUi> = emptyList(),
    val errorMessage: String? = null,
    /** 0–100 while downloading the self-hosted WebRTC .so; 0 when idle. */
    val nativeDownloadProgress: Int = 0
)

/** 通话链路质量分级，供顶部小条 + ICE reconnect 提示使用 */
enum class NetworkQuality { GOOD, FAIR, POOR, UNKNOWN }

data class GroupCallParticipantUi(
    val userId: String,
    val name: String = userId,
    val avatar: String? = null,
    val connectionState: GroupPeerConnectionState = GroupPeerConnectionState.CONNECTING,
    val videoAvailable: Boolean = false
)
