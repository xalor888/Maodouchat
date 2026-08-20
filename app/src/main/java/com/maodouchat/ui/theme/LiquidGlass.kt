package com.maodouchat.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

val LocalLiquidGlassEnabled = staticCompositionLocalOf { false }
val LocalLiquidGlassBlur = staticCompositionLocalOf { 1f }
// 9.294：alpha03 的 rememberLayerBackdrop 返回 LayerBackdrop（比 Backdrop 窄），用其类型供 layerBackdrop 直接使用
val LocalLiquidGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

private const val DEFAULT_MINIMUM_TEXT_CONTRAST = 4.5f

private data class LiquidGlassContrastContext(
    val glassColor: Color,
    val backgroundColor: Color,
    val minimumContrast: Float,
)

private val LocalLiquidGlassContrastContext =
    compositionLocalOf<LiquidGlassContrastContext?> { null }

fun Modifier.liquidGlass(
    enabled: Boolean,
    backdrop: Backdrop?,
    shape: Shape,
    surfaceColor: Color,
    blurRadius: Dp = 6.dp,
    lensHeight: Dp = 0.dp,
    lensAmount: Dp = 0.dp,
    showHighlight: Boolean = true,
): Modifier {
    if (!enabled || backdrop == null) {
        return clip(shape).background(surfaceColor)
    }

    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            blur(blurRadius.toPx())
            if (lensHeight > 0.dp && lensAmount > 0.dp) {
                lens(lensHeight.toPx(), lensAmount.toPx())
            }
        },
        highlight = if (showHighlight) {
            { Highlight.Plain }
        } else {
            null
        },
        shadow = { Shadow(radius = 3.dp, alpha = 0.08f) },
        innerShadow = { InnerShadow(radius = 2.dp, alpha = 0.08f) },
        onDrawSurface = {
            drawRect(surfaceColor)
        }
    )
}

internal fun liquidGlassContentColor(
    preferredColor: Color,
    glassColor: Color,
    backgroundColor: Color,
    minimumContrast: Float = DEFAULT_MINIMUM_TEXT_CONTRAST,
): Color {
    val visibleGlassColor = glassColor.compositeOver(backgroundColor)
    if (lgContrastRatio(preferredColor, visibleGlassColor) >= minimumContrast) {
        return preferredColor
    }

    val blackContrast = lgContrastRatio(Color.Black, visibleGlassColor)
    val whiteContrast = lgContrastRatio(Color.White, visibleGlassColor)
    return if (blackContrast >= whiteContrast) Color.Black else Color.White
}

// 9.294：重命名避免与 ThemeStyle.kt 已有的 contrastRatio 顶层函数冲突
private fun lgContrastRatio(foreground: Color, background: Color): Float {
    val visibleForeground = foreground.compositeOver(background)
    val lighter = maxOf(visibleForeground.luminance(), background.luminance())
    val darker = minOf(visibleForeground.luminance(), background.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

@Composable
fun ProvideLiquidGlassContentColor(
    glassColor: Color,
    preferredColor: Color = LocalContentColor.current,
    minimumContrast: Float = DEFAULT_MINIMUM_TEXT_CONTRAST,
    content: @Composable () -> Unit,
) {
    if (!LocalLiquidGlassEnabled.current) {
        content()
        return
    }
    val backgroundColor = MaterialTheme.colorScheme.background
    val contentColor = liquidGlassContentColor(
        preferredColor = preferredColor,
        glassColor = glassColor,
        backgroundColor = backgroundColor,
        minimumContrast = minimumContrast,
    )
    CompositionLocalProvider(
        LocalContentColor provides contentColor,
        LocalLiquidGlassContrastContext provides LiquidGlassContrastContext(
            glassColor = glassColor,
            backgroundColor = backgroundColor,
            minimumContrast = minimumContrast,
        ),
    ) {
        content()
    }
}

/** Returns the adaptive neutral while inside a glass surface and the exact fallback elsewhere. */
@Composable
internal fun resolvedLiquidGlassContentColor(fallbackColor: Color): Color {
    val context = LocalLiquidGlassContrastContext.current ?: return fallbackColor
    return liquidGlassContentColor(
        preferredColor = fallbackColor,
        glassColor = context.glassColor,
        backgroundColor = context.backgroundColor,
        minimumContrast = context.minimumContrast,
    )
}

@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape,
    color: Color,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    blurRadius: Dp = 6.dp,
    lensHeight: Dp = 0.dp,
    lensAmount: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.liquidGlass(
            enabled = LocalLiquidGlassEnabled.current,
            backdrop = LocalLiquidGlassBackdrop.current,
            shape = shape,
            surfaceColor = color,
            blurRadius = blurRadius * LocalLiquidGlassBlur.current,
            lensHeight = lensHeight,
            lensAmount = lensAmount,
            showHighlight = liquidGlassHighlightEnabled(),
        ),
        content = {
            ProvideLiquidGlassContentColor(
                glassColor = color,
                preferredColor = contentColor,
            ) {
                content()
            }
        }
    )
}

@Composable
fun liquidGlassHighlightEnabled(): Boolean =
    MaterialTheme.colorScheme.background.liquidGlassLuminance() > 0.5f

private fun Color.liquidGlassLuminance(): Float =
    (0.2126f * red) + (0.7152f * green) + (0.0722f * blue)