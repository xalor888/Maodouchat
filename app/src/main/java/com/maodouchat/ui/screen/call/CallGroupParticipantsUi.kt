package com.maodouchat.ui.screen.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.webrtc.GroupCallPolicy
import com.maodouchat.webrtc.GroupPeerConnectionState

@Composable
internal fun GroupParticipantGrid(
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

internal fun groupParticipantStatusLabel(state: GroupPeerConnectionState): Int = when (state) {
    GroupPeerConnectionState.CONNECTING -> R.string.call_group_member_connecting
    GroupPeerConnectionState.CONNECTED -> R.string.call_group_member_connected
    GroupPeerConnectionState.RECONNECTING -> R.string.call_group_member_reconnecting
    GroupPeerConnectionState.DISCONNECTED -> R.string.call_group_member_left
    GroupPeerConnectionState.FAILED -> R.string.call_group_member_failed
    GroupPeerConnectionState.REJECTED -> R.string.call_group_member_rejected
    GroupPeerConnectionState.BUSY -> R.string.call_group_member_busy
    GroupPeerConnectionState.NO_ANSWER -> R.string.call_group_member_no_answer
}
