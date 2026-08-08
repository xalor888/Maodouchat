package com.maodouchat.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.maodouchat.network.TokenManager
import com.maodouchat.ui.theme.AvatarGradients
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.SurfaceVariant

data class GroupParticipant(
    val name: String,
    val avatarUrl: String? = null
)

@Composable
fun GroupAvatar(
    participants: List<GroupParticipant>,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    val context = LocalContext.current
    val ownerUserId = TokenManager.getInstance(context).getUserId().orEmpty()
    val motionSettings = LocalMotionSettings.current

    val displayParticipants = participants.take(4)
    val cellSize = when (displayParticipants.size) {
        1 -> size
        2 -> size * 0.62f
        3 -> size * 0.52f
        else -> size * 0.48f
    }
    val gap = size * 0.02f

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(SurfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when (displayParticipants.size) {
            0 -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(AvatarGradients[0]),
                            CircleShape
                        )
                ) {
                    Text(
                        text = "G",
                        color = Color.White,
                        fontSize = (size.value * 0.4f).sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            1 -> {
                StaggeredAvatarCell(
                    participant = displayParticipants[0],
                    size = cellSize,
                    ownerUserId = ownerUserId,
                    context = context,
                    index = 0,
                    motionEnabled = motionSettings.animationsEnabled
                )
            }
            2 -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    displayParticipants.forEachIndexed { index, p ->
                        StaggeredAvatarCell(
                            participant = p,
                            size = cellSize,
                            ownerUserId = ownerUserId,
                            context = context,
                            index = index,
                            motionEnabled = motionSettings.animationsEnabled
                        )
                    }
                }
            }
            3 -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(gap)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        StaggeredAvatarCell(
                            participant = displayParticipants[0],
                            size = cellSize,
                            ownerUserId = ownerUserId,
                            context = context,
                            index = 0,
                            motionEnabled = motionSettings.animationsEnabled
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StaggeredAvatarCell(
                            participant = displayParticipants[1],
                            size = cellSize,
                            ownerUserId = ownerUserId,
                            context = context,
                            index = 1,
                            motionEnabled = motionSettings.animationsEnabled
                        )
                        StaggeredAvatarCell(
                            participant = displayParticipants[2],
                            size = cellSize,
                            ownerUserId = ownerUserId,
                            context = context,
                            index = 2,
                            motionEnabled = motionSettings.animationsEnabled
                        )
                    }
                }
            }
            else -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(gap)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StaggeredAvatarCell(
                            participant = displayParticipants[0],
                            size = cellSize,
                            ownerUserId = ownerUserId,
                            context = context,
                            index = 0,
                            motionEnabled = motionSettings.animationsEnabled
                        )
                        StaggeredAvatarCell(
                            participant = displayParticipants[1],
                            size = cellSize,
                            ownerUserId = ownerUserId,
                            context = context,
                            index = 1,
                            motionEnabled = motionSettings.animationsEnabled
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StaggeredAvatarCell(
                            participant = displayParticipants[2],
                            size = cellSize,
                            ownerUserId = ownerUserId,
                            context = context,
                            index = 2,
                            motionEnabled = motionSettings.animationsEnabled
                        )
                        StaggeredAvatarCell(
                            participant = displayParticipants[3],
                            size = cellSize,
                            ownerUserId = ownerUserId,
                            context = context,
                            index = 3,
                            motionEnabled = motionSettings.animationsEnabled
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StaggeredAvatarCell(
    participant: GroupParticipant,
    size: Dp,
    ownerUserId: String,
    context: android.content.Context,
    index: Int,
    motionEnabled: Boolean
) {
    val scaleAnimatable = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        if (motionEnabled) {
            kotlinx.coroutines.delay(index * 60L)
            scaleAnimatable.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        } else {
            scaleAnimatable.snapTo(1f)
        }
    }

    Box(
        modifier = Modifier.scale(scaleAnimatable.value),
        contentAlignment = Alignment.Center
    ) {
        if (!participant.avatarUrl.isNullOrBlank()) {
            val cacheKey = "$ownerUserId:${participant.avatarUrl}"
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(participant.avatarUrl)
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .build(),
                contentDescription = participant.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(SurfaceVariant, CircleShape)
            )
        } else {
            val initial = participant.name.firstOrNull()?.toString() ?: "?"
            val gradientIndex = (participant.name.hashCode().mod(AvatarGradients.size)).let {
                if (it < 0) it + AvatarGradients.size else it
            }
            val gradient = AvatarGradients[gradientIndex]
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(gradient))
            ) {
                Text(
                    text = initial.uppercase(),
                    color = Color.White,
                    fontSize = (size.value * 0.38f).sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
