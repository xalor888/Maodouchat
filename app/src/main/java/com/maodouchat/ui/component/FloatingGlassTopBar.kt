package com.maodouchat.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.maodouchat.ui.theme.LocalLiquidGlassBackdrop
import com.maodouchat.ui.theme.LocalLiquidGlassBlur
import com.maodouchat.ui.theme.LocalLiquidGlassEnabled

/**
 * Murexide 聊天页顶栏：状态栏下三块悬浮玻璃（返回 / 标题 / 操作）。
 */
@Composable
fun FloatingGlassTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    showOverlay: Boolean = true,
    consumeStatusBars: Boolean = true,
) {
    val liquidGlassEnabled = LocalLiquidGlassEnabled.current
    val liquidGlassBlur = LocalLiquidGlassBlur.current
    val liquidBackdrop = LocalLiquidGlassBackdrop.current
    val controlSize = 48.dp
    val buttonShape = CircleShape
    val topBarColor = MaterialTheme.colorScheme.surface
    if (!liquidGlassEnabled) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .then(if (consumeStatusBars) Modifier.statusBarsPadding() else Modifier)
                .shadow(1.dp)
                .background(topBarColor)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (navigationIcon != null) {
                Box(modifier = Modifier.size(controlSize), contentAlignment = Alignment.Center) {
                    navigationIcon()
                }
            }
            Box(
                modifier = Modifier.weight(1f).height(controlSize),
                contentAlignment = Alignment.CenterStart,
            ) {
                title()
            }
            if (actions != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    actions()
                }
            }
        }
        return
    }

    val backdrop = liquidBackdrop
    val glassSurfaceColor = topBarColor.copy(alpha = 0.75f)

    fun Modifier.glassControl(shape: Shape): Modifier =
        if (backdrop != null) {
            drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(1.dp.toPx() * liquidGlassBlur)
                    lens(16.dp.toPx(), 32.dp.toPx())
                },
                onDrawSurface = { drawRect(glassSurfaceColor) },
            )
        } else {
            shadow(2.dp, shape).clip(shape).background(topBarColor.copy(alpha = 0.92f), shape)
        }

    Box(modifier = modifier.fillMaxWidth()) {
        if (showOverlay) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                topBarColor.copy(alpha = 0.8f),
                                topBarColor.copy(alpha = 0.6f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (consumeStatusBars) Modifier.statusBarsPadding() else Modifier)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (navigationIcon != null) {
                Box(
                    modifier = Modifier
                        .size(controlSize)
                        .glassControl(buttonShape),
                    contentAlignment = Alignment.Center,
                ) {
                    navigationIcon()
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(controlSize)
                    .glassControl(RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.CenterStart,
            ) {
                title()
            }
            if (actions != null) {
                Row(
                    modifier = Modifier
                        .height(controlSize)
                        .glassControl(buttonShape),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actions()
                }
            }
        }
    }
}
