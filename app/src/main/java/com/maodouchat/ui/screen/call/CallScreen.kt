package com.maodouchat.ui.screen.call

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.SwitchCamera
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.outlined.SignalCellular4Bar
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.SignalCellularConnectedNoInternet0Bar
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.R
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.theme.MaodouchatTheme
import com.maodouchat.ui.theme.UnreadRed
import com.maodouchat.ui.theme.rememberMotionPulse
import com.maodouchat.webrtc.CallState
import com.maodouchat.webrtc.CallAudioRoute
import com.maodouchat.webrtc.CallType
import com.maodouchat.webrtc.GroupPeerConnectionState
import com.maodouchat.webrtc.GroupCallPolicy

/**
 * 音视频通话页面
 *
 * @param contactName 联系人名称
 * @param contactAvatar 联系人头像 URL
 * @param callType 通话类型
 * @param isIncoming 是否是来电
 * @param onHangUp 挂断回调
 * @param onAccept 接听回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallScreen(
    contactName: String,
    contactAvatar: String? = null,
    callType: CallType = CallType.AUDIO,
    isIncoming: Boolean = false,
    isGroupCall: Boolean = false,
    callState: CallState = CallState.CALLING,
    duration: String = "00:00",
    isInitializing: Boolean = false,
    networkReconnecting: Boolean = false,
    networkQuality: NetworkQuality = NetworkQuality.UNKNOWN,
    iceStunOnly: Boolean = false,
    availableAudioRoutes: Set<CallAudioRoute> = emptySet(),
    selectedAudioRoute: CallAudioRoute? = null,
    groupParticipants: List<GroupCallParticipantUi> = emptyList(),
    errorMessage: String? = null,
    nativeDownloadProgress: Int = 0,
    onDismissError: () -> Unit = {},
    onHangUp: () -> Unit = {},
    onAccept: () -> Unit = {},
    onToggleMute: (Boolean) -> Unit = {},
    onToggleVideo: (Boolean) -> Unit = {},
    onSwitchCamera: () -> Unit = {},
    onSelectAudioRoute: (CallAudioRoute) -> Unit = {},
    onLocalRendererReady: ((org.webrtc.SurfaceViewRenderer) -> Unit)? = null,
    onRemoteRendererReady: ((org.webrtc.SurfaceViewRenderer) -> Unit)? = null,
    onGroupRemoteRendererReady: ((String, org.webrtc.SurfaceViewRenderer) -> Unit)? = null,
    onGroupRemoteRendererReleased: ((String, org.webrtc.SurfaceViewRenderer) -> Unit)? = null,
    onLocalRendererReleased: ((org.webrtc.SurfaceViewRenderer) -> Unit)? = null,
    onRemoteRendererReleased: ((org.webrtc.SurfaceViewRenderer) -> Unit)? = null
) {
    val context = LocalContext.current
    // rememberSaveable 保证旋转屏幕后静音/关摄像头状态不丢失（与实际 track 状态保持一致）
    var isMuted by rememberSaveable { mutableStateOf(false) }
    var isVideoOff by rememberSaveable { mutableStateOf(false) }
    var showAudioRoutes by rememberSaveable { mutableStateOf(false) }

    // 只在呼叫建立前运行；系统关闭动画后保持静态。
    val pulseScale by rememberMotionPulse(
        initialValue = 1f,
        targetValue = 1.15f,
        durationMillis = 1_200,
        label = "callingPulse",
        active = callState == CallState.CALLING,
        staticValue = 1f
    )

    // 注意：不再对 errorMessage 做 auto-hang-up —— ViewModel.startRingingTimeout
    // 已经调用 endCall(notifyPeer=true) 处理超时；重复调用会导致双 hang-up 信号

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF111111)),
        contentAlignment = Alignment.Center
    ) {
        // 远端视频（全屏背景）
        if (!isGroupCall && callType == CallType.VIDEO && callState == CallState.CONNECTED && onRemoteRendererReady != null) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    org.webrtc.SurfaceViewRenderer(ctx).also { renderer ->
                        onRemoteRendererReady(renderer)
                    }
                },
                onRelease = { onRemoteRendererReleased?.invoke(it) },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (isGroupCall && groupParticipants.isNotEmpty()) {
            GroupParticipantGrid(
                participants = groupParticipants,
                videoCall = callType == CallType.VIDEO,
                onRendererReady = onGroupRemoteRendererReady,
                onRendererReleased = onGroupRemoteRendererReleased,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 150.dp, bottom = 190.dp, start = 12.dp, end = 12.dp)
            )
        }

        // 本地视频（画中画小窗）
        if (callType == CallType.VIDEO && callState == CallState.CONNECTED && onLocalRendererReady != null) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    org.webrtc.SurfaceViewRenderer(ctx).also { renderer ->
                        onLocalRendererReady(renderer)
                    }
                },
                onRelease = { onLocalRendererReleased?.invoke(it) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // 9.247：此前硬编码 48dp 不含状态栏，打孔屏上小窗顶进挖孔区
                    .statusBarsPadding()
                    .padding(top = 12.dp, end = 16.dp)
                    .size(120.dp, 180.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        }

        // 顶部网络质量指示器 + ICE 重连提示条
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                // 9.247：同 PiP 小窗，状态栏 insets + 固定边距替代硬编码 56dp
                .statusBarsPadding()
                .padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (callState == CallState.CONNECTED) {
                NetworkQualityPill(quality = networkQuality)
            }
            if (isGroupCall && groupParticipants.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.call_group_mesh_capacity,
                        groupParticipants.size,
                        GroupCallPolicy.MAX_MESH_MEMBERS
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x99000000))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
            AnimatedVisibility(
                visible = iceStunOnly &&
                    (callState == CallState.CALLING ||
                        callState == CallState.RINGING ||
                        callState == CallState.CONNECTED),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Text(
                    text = stringResource(R.string.call_ice_stun_only),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFC107),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            AnimatedVisibility(
                visible = networkReconnecting && callState == CallState.CONNECTED,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Icon(
                        Icons.Outlined.SignalCellularConnectedNoInternet0Bar,
                        contentDescription = null,
                        tint = Color(0xFFFFC107),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.call_network_reconnecting),
                        color = Color(0xFFFFC107),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            // 底部留出 72dp（100-28 近似值），避免小屏或横屏下操作按钮被裁剪
            modifier = Modifier.fillMaxSize().fillMaxHeight().padding(horizontal = 24.dp).padding(top = 96.dp, bottom = 72.dp)
        ) {
            // 顶部：联系人信息
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (!isGroupCall) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .graphicsLayer {
                                if (callState == CallState.CALLING || callState == CallState.RINGING) {
                                    scaleX = pulseScale
                                    scaleY = pulseScale
                                } else {
                                    scaleX = 1f
                                    scaleY = 1f
                                }
                            }
                    ) {
                        Avatar(name = contactName, avatarUrl = contactAvatar, size = AvatarSize.LG)
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
                Text(contactName, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 24.sp), color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when {
                        errorMessage != null -> errorMessage
                        isInitializing && nativeDownloadProgress in 1..99 ->
                            stringResource(R.string.call_webrtc_downloading, nativeDownloadProgress)
                        isInitializing -> stringResource(R.string.call_establishing)
                        callState == CallState.CALLING -> if (callType == CallType.VIDEO) stringResource(R.string.call_calling_video) else stringResource(R.string.call_calling)
                        callState == CallState.RINGING -> if (isIncoming) stringResource(R.string.call_incoming_ringing) else stringResource(R.string.call_remote_ringing)
                        callState == CallState.CONNECTED -> if (callType == CallType.AUDIO) stringResource(R.string.call_audio_connected, duration) else stringResource(R.string.call_video_connected, duration)
                        callState == CallState.DISCONNECTED -> stringResource(R.string.call_ended)
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (errorMessage != null) UnreadRed else Color.White.copy(alpha = 0.7f)
                )
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onDismissError) {
                        Text(stringResource(R.string.chat_acknowledge), color = Color.White)
                    }
                }
            }

            // 底部：接通后次级操作一排、挂断独立居中，避免视频通话五个按钮挤出小屏。
            if (isIncoming && callState == CallState.RINGING) {
                Row(horizontalArrangement = Arrangement.spacedBy(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    FloatingActionButton(
                        onClick = onAccept,
                        containerColor = Color(0xFF34C759),
                        shape = CircleShape,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.call_accept), tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    FloatingActionButton(
                        onClick = onHangUp,
                        containerColor = UnreadRed,
                        shape = CircleShape,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(Icons.Filled.CallEnd, contentDescription = stringResource(R.string.call_hang_up), tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }
            } else if (callState == CallState.CONNECTED) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    FloatingActionButton(
                        onClick = { isMuted = !isMuted; onToggleMute(isMuted) },
                        containerColor = if (isMuted) Color.White else Color.White.copy(alpha = 0.2f),
                        shape = CircleShape,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = if (isMuted) stringResource(R.string.call_unmute) else stringResource(R.string.call_mute),
                            tint = if (isMuted) UnreadRed else Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // 0.70：通话最小化按钮（PiP 显式入口，此前只能按 HOME 触发）
                    FloatingActionButton(
                        onClick = {
                            val activity = context as? android.app.Activity
                            if (activity != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                                activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
                            ) {
                                runCatching {
                                    val builder = android.app.PictureInPictureParams.Builder()
                                        .setAspectRatio(android.util.Rational(16, 9))
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                        builder.setAutoEnterEnabled(false)
                                    }
                                    activity.enterPictureInPictureMode(builder.build())
                                }
                            }
                        },
                        containerColor = Color.White.copy(alpha = 0.2f),
                        shape = CircleShape,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            Icons.Outlined.PictureInPictureAlt,
                            contentDescription = stringResource(R.string.call_minimize),
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    if (availableAudioRoutes.isNotEmpty()) {
                        FloatingActionButton(
                            onClick = { showAudioRoutes = true },
                            containerColor = Color.White.copy(alpha = 0.2f),
                            shape = CircleShape,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Crossfade(targetState = selectedAudioRoute, label = "audioRouteIcon") { route ->
                                Icon(
                                    audioRouteIcon(route),
                                    contentDescription = stringResource(R.string.call_audio_route),
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }

                    if (callType == CallType.VIDEO) {
                    FloatingActionButton(
                        // 与静音 FAB 完全对齐：本地先翻 isVideoOff（true=已关），再取反后传给 ViewModel（true=开启）
                        onClick = { isVideoOff = !isVideoOff; onToggleVideo(!isVideoOff) },
                        containerColor = if (isVideoOff) Color.White else Color.White.copy(alpha = 0.2f),
                        shape = CircleShape,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            if (isVideoOff) Icons.Filled.VideocamOff else Icons.Filled.Videocam,
                            contentDescription = if (isVideoOff) stringResource(R.string.call_camera_on) else stringResource(R.string.call_camera_off),
                            tint = if (isVideoOff) UnreadRed else Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // 切换摄像头
                    FloatingActionButton(
                        onClick = onSwitchCamera,
                        containerColor = Color.White.copy(alpha = 0.2f),
                        shape = CircleShape,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(Icons.Filled.SwitchCamera, contentDescription = stringResource(R.string.call_switch_camera), tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    }
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    FloatingActionButton(
                        onClick = onHangUp,
                        containerColor = UnreadRed,
                        shape = CircleShape,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(Icons.Filled.CallEnd, contentDescription = stringResource(R.string.call_hang_up), tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }
            } else {
                FloatingActionButton(
                    onClick = onHangUp,
                    containerColor = UnreadRed,
                    shape = CircleShape,
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(Icons.Filled.CallEnd, contentDescription = stringResource(R.string.call_hang_up), tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
        }
    }

    if (showAudioRoutes) {
        ModalBottomSheet(
            onDismissRequest = { showAudioRoutes = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Text(
                text = stringResource(R.string.call_choose_audio_route),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            CallAudioRoute.entries.filter { it in availableAudioRoutes }.forEach { route ->
                ListItem(
                    headlineContent = { Text(stringResource(audioRouteLabel(route))) },
                    leadingContent = { Icon(audioRouteIcon(route), contentDescription = null) },
                    trailingContent = { RadioButton(selected = route == selectedAudioRoute, onClick = null) },
                    modifier = Modifier.clickable {
                        onSelectAudioRoute(route)
                        showAudioRoutes = false
                    }
                )
            }
            Spacer(modifier = Modifier.navigationBarsPadding().height(8.dp))
        }
    }
}

private fun audioRouteIcon(route: CallAudioRoute?) = when (route) {
    CallAudioRoute.BLUETOOTH -> Icons.Filled.BluetoothAudio
    CallAudioRoute.WIRED -> Icons.Filled.Headset
    CallAudioRoute.EARPIECE -> Icons.Filled.PhoneInTalk
    CallAudioRoute.SPEAKER, null -> Icons.AutoMirrored.Filled.VolumeUp
}

private fun audioRouteLabel(route: CallAudioRoute): Int = when (route) {
    CallAudioRoute.BLUETOOTH -> R.string.call_audio_route_bluetooth
    CallAudioRoute.WIRED -> R.string.call_audio_route_wired
    CallAudioRoute.EARPIECE -> R.string.call_audio_route_earpiece
    CallAudioRoute.SPEAKER -> R.string.call_audio_route_speaker
}

@Composable
private fun GroupParticipantGrid(
    participants: List<GroupCallParticipantUi>,
    videoCall: Boolean,
    onRendererReady: ((String, org.webrtc.SurfaceViewRenderer) -> Unit)?,
    onRendererReleased: ((String, org.webrtc.SurfaceViewRenderer) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val columns = GroupCallPolicy.gridColumns(participants.size)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier,
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(participants, key = { it.userId }, contentType = { "call_participant" }) { participant ->
            GroupParticipantTile(
                participant = participant,
                showVideo = videoCall && participant.videoAvailable && onRendererReady != null,
                onRendererReady = onRendererReady,
                onRendererReleased = onRendererReleased
            )
        }
    }
}

@Composable
private fun GroupParticipantTile(
    participant: GroupCallParticipantUi,
    showVideo: Boolean,
    onRendererReady: ((String, org.webrtc.SurfaceViewRenderer) -> Unit)?,
    onRendererReleased: ((String, org.webrtc.SurfaceViewRenderer) -> Unit)?
) {
    val statusColor = when (participant.connectionState) {
        GroupPeerConnectionState.CONNECTED -> Color(0xFF34C759)
        GroupPeerConnectionState.RECONNECTING -> Color(0xFFFFC107)
        GroupPeerConnectionState.CONNECTING -> Color.White.copy(alpha = 0.7f)
        else -> Color(0xFFFF453A)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(if (showVideo) 0.78f else 1f)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1A1A1A))
    ) {
        if (showVideo && onRendererReady != null) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { context ->
                    org.webrtc.SurfaceViewRenderer(context).also { renderer ->
                        onRendererReady(participant.userId, renderer)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { renderer -> onRendererReleased?.invoke(participant.userId, renderer) }
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Avatar(name = participant.name, avatarUrl = participant.avatar, size = AvatarSize.LG)
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Text(
                participant.name,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(groupParticipantStatusLabel(participant.connectionState)),
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }
    }
}

private fun groupParticipantStatusLabel(state: GroupPeerConnectionState): Int = when (state) {
    GroupPeerConnectionState.CONNECTING -> R.string.call_group_member_connecting
    GroupPeerConnectionState.CONNECTED -> R.string.call_group_member_connected
    GroupPeerConnectionState.RECONNECTING -> R.string.call_group_member_reconnecting
    GroupPeerConnectionState.DISCONNECTED -> R.string.call_group_member_left
    GroupPeerConnectionState.FAILED -> R.string.call_group_member_failed
    GroupPeerConnectionState.REJECTED -> R.string.call_group_member_rejected
    GroupPeerConnectionState.BUSY -> R.string.call_group_member_busy
    GroupPeerConnectionState.NO_ANSWER -> R.string.call_group_member_no_answer
}

/**
 * 通话顶部网络质量小药丸（绿/黄/红）
 */
@Composable
private fun NetworkQualityPill(quality: NetworkQuality) {
    val (icon, color, label) = when (quality) {
        NetworkQuality.GOOD -> Triple(Icons.Outlined.SignalCellular4Bar, Color(0xFF34C759), R.string.call_network_good)
        NetworkQuality.FAIR -> Triple(Icons.Outlined.SignalCellularAlt, Color(0xFFFFC107), R.string.call_network_fair)
        NetworkQuality.POOR -> Triple(Icons.Outlined.SignalCellularConnectedNoInternet0Bar, Color(0xFFFF453A), R.string.call_network_poor)
        NetworkQuality.UNKNOWN -> Triple(Icons.Outlined.SignalCellularAlt, Color.White.copy(alpha = 0.55f), R.string.call_network_measuring)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(5.dp))
        Text(stringResource(label), color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun CallScreenPreview() {
    MaodouchatTheme {
        CallScreen(contactName = "Alex Chen", callType = CallType.AUDIO, callState = CallState.CALLING)
    }
}
