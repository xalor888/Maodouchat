package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.maodouchat.util.ChatAppearancePreferences
import com.maodouchat.util.ChatFontScale
import com.maodouchat.util.ChatWallpaperPreset

/**
 * G335：**聊天页外观偏好**——壁纸档位 / 自定义图片壁纸 / 字号档位。
 *
 * 归族理由：这三个不是「弹层开没开」，而是**同一份本地偏好的三个字段**，且带着一个原先
 * 只写在注释里、代码上毫无约束的不变量：**它们必须一起刷新**（9.150 那次修的就是「从设置页
 * 改完返回，聊天页还持有陈旧背景/字号」）。只刷壁纸不刷字号，就是半新半旧的界面。
 * Route 里原先是三行独立 `remember` + ON_RESUME 里三行独立赋值，靠人数够三个；
 * 收进来后刷新只剩 [refreshFrom] 一次调用，漏一项在类型层面就不可能了。
 *
 * `customWallpaperUri` 与另两项的关系是**互斥**（非空时优先于预设壁纸，并且不叠加纹理与
 * 纵深渐变），所以它必须和 `wallpaperPreset` 同族，不能拆到别处。
 *
 * 范式：与 [ChatDetailSendPendingState] 一样是普通持有类（`remember`，**不带 Saver**）——
 * 这三项每次 ON_RESUME 都以本地偏好为准重读，跨进程重建后保住旧值反而会让「设置页改过壁纸」
 * 要重进会话才生效。这里与第十批的 `listSaver` 写法刻意不同，是为了与原逐项
 * `remember { mutableStateOf(...) }` 的保存语义严格等价。
 */
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
