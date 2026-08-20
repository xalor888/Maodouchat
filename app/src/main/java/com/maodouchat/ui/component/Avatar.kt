package com.maodouchat.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.maodouchat.network.TokenManager
import com.maodouchat.ui.theme.AvatarGradients
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.OnlineGreen
import com.maodouchat.ui.theme.Surface

enum class AvatarSize(val dp: Dp, val fontSize: TextUnit) {
    SM(36.dp, 14.sp),
    MD(48.dp, 18.sp),
    /** 9.268：TG 式会话列表头像尺寸（54dp，比 MD 大一号更接近 TG 观感）。 */
    CHAT_LIST(54.dp, 20.sp),
    LG(64.dp, 24.sp)
}

/**
 * 圆形头像组件
 *
 * @param name 用户名（用于生成首字母渐变头像）
 * @param avatarUrl 头像图片 URL（为空时显示首字母）
 * @param size 头像尺寸
 * @param isOnline 是否显示在线状态绿点
 */
@Composable
fun Avatar(
    modifier: Modifier = Modifier,
    name: String,
    avatarUrl: String? = null,
    size: AvatarSize = AvatarSize.MD,
    isOnline: Boolean = false
) {
    val context = LocalContext.current
    Box(modifier = modifier) {
        if (!avatarUrl.isNullOrBlank()) {
            val ownerUserId = TokenManager.getInstance(context).getUserId().orEmpty()
            val isolatedCacheKey = "$ownerUserId:$avatarUrl"
            // 有头像图片
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(avatarUrl)
                    .memoryCacheKey(isolatedCacheKey)
                    .diskCacheKey(isolatedCacheKey)
                    .crossfade(true)
                    .build(),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
            )
        } else {
            // 无头像，显示首字母渐变背景
            val initial = name.firstOrNull()?.toString() ?: "?"
            val gradientIndex = (name.hashCode().mod(AvatarGradients.size)).let {
                if (it < 0) it + AvatarGradients.size else it
            }
            val gradient = AvatarGradients[gradientIndex]

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(size.dp)
                    .background(
                        brush = Brush.linearGradient(gradient),
                        shape = CircleShape
                    )
            ) {
                Text(
                    text = initial.uppercase(),
                    color = Color.White,
                    fontSize = size.fontSize,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // 在线状态绿点（带脉冲动画）
        if (isOnline) {
            val motion = LocalMotionSettings.current
            // Pulsing ring outside the dot. Only built when animations are enabled to
            // avoid an infinite tween(0) loop churning recompositions when motion is off.
            if (motion.animationsEnabled) {
                val infiniteTransition = rememberInfiniteTransition(label = "onlinePulse")
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.7f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(
                            durationMillis = motion.duration(1400),
                            easing = androidx.compose.animation.core.FastOutSlowInEasing
                        ),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "pulseScale"
                )
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.55f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(
                            durationMillis = motion.duration(1400),
                            easing = androidx.compose.animation.core.FastOutSlowInEasing
                        ),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "pulseAlpha"
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = (-1).dp, y = (-1).dp)
                        .size(12.dp)
                        .graphicsLayer {
                            scaleX = pulseScale
                            scaleY = pulseScale
                            alpha = pulseAlpha
                        }
                        .background(OnlineGreen, CircleShape)
                )
            }
            // Solid dot
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-1).dp, y = (-1).dp)
                    .size(12.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .border(1.5.dp, Surface, CircleShape)
                    .background(OnlineGreen, CircleShape)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AvatarPreview() {
    MaterialTheme {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Avatar(name = "Alice", size = AvatarSize.SM, isOnline = true)
            Avatar(name = "Bob", size = AvatarSize.MD)
            Avatar(name = "Charlie", size = AvatarSize.LG, isOnline = true)
            Avatar(name = "Alice", avatarUrl = "https://example.com/avatar.jpg", size = AvatarSize.MD)
        }
    }
}
