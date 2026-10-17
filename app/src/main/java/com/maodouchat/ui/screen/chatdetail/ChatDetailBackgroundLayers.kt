package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.maodouchat.ui.theme.LocalChatPalette


/** 聊天页壁纸与纹理层（从 ChatDetailRoute.kt 纯搬移，块字节级一致）。 */
@Composable
internal fun ChatDetailBackgroundLayers(
    appearance: ChatDetailAppearanceState,
    chatBackgroundColor: Color,
    isDarkChat: Boolean,
) {
    // 自定义图片壁纸（本地 URI，按账号隔离）：绘制在聊天内容之下，无壁纸时不引入额外层
    appearance.customWallpaperUri?.let { uri ->
        coil.compose.AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
    // 9.205：TG 风格涂鸦纹理——叠加在默认与颜色壁纸之上（TG 是颜色+图案叠加），
    // 仅当用户选了自定义图片壁纸时不叠加，避免盖住用户自选图片
    if (appearance.customWallpaperUri == null) {
        // 9.254：TG 式背景纵深——单色底上叠一层自上而下的微暗渐变，平面背景立刻有
        // 空间感（从当前背景色派生，自定义主题/深浅模式自动跟随）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(
                            chatBackgroundColor.copy(alpha = 0f),
                            chatBackgroundColor.copy(alpha = 0f),
                            Color.Black.copy(alpha = if (isDarkChat) 0.10f else 0.045f)
                        )
                    )
                )
        )
        com.maodouchat.ui.component.ChatBackgroundPattern(
            modifier = Modifier.fillMaxSize(),
            tint = LocalChatPalette.current.textSecondary.copy(alpha = if (isDarkChat) 0.07f else 0.09f)
        )
    }
}
