package com.maodouchat.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.ShimmerDark
import com.maodouchat.ui.theme.ShimmerLight

/**
 * Shimmer placeholder box — used for skeleton loading states.
 * Respects system animation scale settings.
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 4.dp
) {
    val motion = LocalMotionSettings.current
    val baseColor = ShimmerLight
    val highlightColor = ShimmerLight.copy(alpha = 0.4f)

    if (motion.animationsEnabled) {
        val transition = rememberInfiniteTransition(label = "shimmer")
        val alpha by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.75f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = motion.duration(900)),
                repeatMode = RepeatMode.Reverse
            ),
            label = "shimmerAlpha"
        )
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(cornerRadius))
                .background(baseColor.copy(alpha = alpha))
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(cornerRadius))
                .background(baseColor.copy(alpha = 0.5f))
        )
    }
}

/**
 * A complete skeleton row for a chat list item.
 */
@Composable
fun ShimmerChatRow(
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        ShimmerBox(modifier = Modifier.size(48.dp), cornerRadius = 24.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            ShimmerBox(modifier = Modifier.width(130.dp).height(14.dp))
            Spacer(modifier = Modifier.height(8.dp))
            ShimmerBox(modifier = Modifier.width(200.dp).height(12.dp))
        }
    }
}

/**
 * A skeleton row for a contact list item.
 */
@Composable
fun ShimmerContactRow(
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        ShimmerBox(modifier = Modifier.size(40.dp), cornerRadius = 20.dp)
        Spacer(modifier = Modifier.width(12.dp))
        ShimmerBox(modifier = Modifier.width(120.dp).height(14.dp))
    }
}

/**
 * A skeleton card for a moments/explore post.
 */
@Composable
fun ShimmerPostCard(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            ShimmerBox(modifier = Modifier.size(40.dp), cornerRadius = 20.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                ShimmerBox(modifier = Modifier.width(100.dp).height(12.dp))
                Spacer(modifier = Modifier.height(4.dp))
                ShimmerBox(modifier = Modifier.width(60.dp).height(10.dp))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        ShimmerBox(modifier = Modifier.fillMaxWidth().height(14.dp))
        Spacer(modifier = Modifier.height(6.dp))
        ShimmerBox(modifier = Modifier.fillMaxWidth(0.85f).height(14.dp))
        Spacer(modifier = Modifier.height(12.dp))
        ShimmerBox(modifier = Modifier.fillMaxWidth().height(180.dp), cornerRadius = 12.dp)
    }
}
