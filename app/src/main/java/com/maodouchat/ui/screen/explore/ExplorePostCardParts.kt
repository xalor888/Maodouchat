package com.maodouchat.ui.screen.explore

// 点赞动效按钮 / 可见范围文案 / 本地化相对时间——`PostCard` 与 `CommentsDialog` 共用的小簇。

import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.LocalChatPalette
import androidx.compose.runtime.getValue

@Composable
internal fun AnimatedLikeButton(likedByMe: Boolean, onLike: () -> Unit) {
    val motion = LocalMotionSettings.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.78f else 1f,
        animationSpec = motion.springSpec(dampingRatio = 0.55f, stiffness = 520f),
        label = "likePressScale"
    )
    val likedScale by animateFloatAsState(
        targetValue = if (likedByMe) 1.12f else 1f,
        animationSpec = motion.springSpec(dampingRatio = 0.48f, stiffness = 360f),
        label = "likedScale"
    )
    val rotation by animateFloatAsState(
        targetValue = if (likedByMe) 12f else 0f,
        animationSpec = motion.springSpec(dampingRatio = 0.6f, stiffness = 280f),
        label = "likedRotation"
    )
    TextButton(onClick = onLike, interactionSource = interactionSource) {
        Icon(
            imageVector = if (likedByMe) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = stringResource(R.string.explore_like),
            tint = if (likedByMe) Color(0xFFE91E63) else LocalChatPalette.current.textSecondary,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    scaleX = pressScale * likedScale
                    scaleY = pressScale * likedScale
                    rotationZ = rotation
                }
        )
    }
}
@Composable
internal fun LoadingMoreBlock() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
internal fun visibilityLabel(value: String): String = when (value) {
    "CONTACTS" -> stringResource(R.string.explore_visibility_contacts_label)
    "PRIVATE" -> stringResource(R.string.explore_visibility_private_label)
    else -> stringResource(R.string.explore_visibility_public)
}
@Composable
internal fun relativeTimeLocalized(timestamp: Long): String {
    val now = System.currentTimeMillis()
    if (RelativeTimePolicy.shouldUseJustNow(timestamp, now)) {
        return stringResource(R.string.time_just_now)
    }
    return DateUtils.getRelativeTimeSpanString(timestamp, now, DateUtils.MINUTE_IN_MILLIS).toString()
}
