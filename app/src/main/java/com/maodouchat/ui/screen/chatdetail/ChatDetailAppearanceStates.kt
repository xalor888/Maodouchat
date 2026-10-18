package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalDarkTheme
import com.maodouchat.util.ChatAppearancePolicy
import com.maodouchat.util.ChatAppearancePreferences
import com.maodouchat.util.ChatFontScale
import com.maodouchat.util.ChatWallpaperPreset

internal class ChatDetailAppearanceState(
    wallpaperPreset: ChatWallpaperPreset,
    customWallpaperUri: String?,
    fontScale: ChatFontScale,
) {
    /** 预设（颜色）壁纸档位。 */
    var wallpaperPreset by mutableStateOf(wallpaperPreset)

    /** 自定义图片壁纸的本地 URI；非空时优先于 [wallpaperPreset]。 */
    var customWallpaperUri by mutableStateOf(customWallpaperUri)

    /** 字号档位，参与 `scaledDensity` 计算。 */
    var fontScale by mutableStateOf(fontScale)

    /** ON_RESUME：从本地偏好整体刷新三项（必须一起，见类注释）。 */
    fun refreshFrom(context: Context) {
        wallpaperPreset = ChatAppearancePreferences.getWallpaper(context)
        customWallpaperUri = ChatAppearancePreferences.getCustomWallpaperUri(context)
        fontScale = ChatAppearancePreferences.getFontScale(context)
    }
}

/** 与原来的逐项 `remember { mutableStateOf(...) }` 等价：初值一律现读偏好。 */
@Composable
internal fun rememberChatDetailAppearanceState(context: Context): ChatDetailAppearanceState =
    remember {
        ChatDetailAppearanceState(
            wallpaperPreset = ChatAppearancePreferences.getWallpaper(context),
            customWallpaperUri = ChatAppearancePreferences.getCustomWallpaperUri(context),
            fontScale = ChatAppearancePreferences.getFontScale(context),
        )
    }

internal class ChatDetailAppearanceDerived(
    val backgroundColor: Color,
    val scaledDensity: Density,
)

/** 外观派生量：壁纸背景色与字号密度，原在 ChatDetailRoute 内联计算，纯搬移。 */
@Composable
internal fun rememberChatDetailAppearanceDerived(
    appearance: ChatDetailAppearanceState,
): ChatDetailAppearanceDerived {
    val isDark = LocalDarkTheme.current
    val palette = LocalChatPalette.current
    val backgroundColor = remember(appearance.wallpaperPreset, isDark, palette) {
        ChatAppearancePolicy.resolveBackground(
            preset = appearance.wallpaperPreset,
            isDark = isDark,
            fallback = palette.chatBackground
        )
    }
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, appearance.fontScale) {
        Density(
            density = baseDensity.density,
            fontScale = (baseDensity.fontScale * appearance.fontScale.multiplier).coerceIn(0.85f, 1.6f)
        )
    }
    return ChatDetailAppearanceDerived(
        backgroundColor = backgroundColor,
        scaledDensity = scaledDensity,
    )
}
