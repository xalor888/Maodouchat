package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GifBox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.TextSecondary


/**
 * 输入框的表情/贴纸/GIF 面板（G107 从 `ChatDetailComponents.kt` 拆出，原 363 行）。
 *
 * 三种模式共用一个面板：最近表情 + 表情网格、贴纸包（含包管理/搜索/远程按需下载）、GIF 库。
 *
 * 内含几条容易在后续改动中被破坏的判定：
 * - **贴纸页签默认落在「最近使用」包**，且「最近使用」行只在无搜索词时展示；
 * - 点击表情上屏的同时记录最近使用（去重、按账号隔离）；
 * - 贴纸按压回弹（9.223，TG 式手感，尊重系统动效开关）；
 * - 远程贴纸包按需拉取清单 + 下载（B1 包体瘦身闭环）；
 * - 贴纸入口整体受 `stickersEnabled` 控制（群/频道禁言场景）。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所需输入全部经参数显式传入。纯搬移，不改判断。
 */

@Composable
internal fun ExpressionPanel(
    mode: String,
    onModeChange: (String) -> Unit,
    onEmojiClick: (String) -> Unit,
    onStickerClick: (String) -> Unit,
    onGifClick: () -> Unit,
    stickersEnabled: Boolean
) {
    val stickerState = rememberExpressionStickerState()
    var showPackManager by stickerState.showPackManager

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (mode == "STICKER") 280.dp else 224.dp)
            .background(LocalChatPalette.current.chatInputBackground)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("EMOJI" to stringResource(R.string.chat_emoji), "STICKER" to stringResource(R.string.chat_sticker)).forEach { (key, label) ->
                    val selected = mode == key
                    TextButton(
                        onClick = { onModeChange(key) },
                        modifier = Modifier.background(
                            if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                    ) { Text(label, color = if (selected) Primary else TextSecondary) }
                }
            }
            if (mode == "STICKER") {
                IconButton(onClick = { showPackManager = true }) {
                    Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.sticker_pack_manage), tint = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(onClick = onGifClick, enabled = stickersEnabled) {
                Icon(Icons.Outlined.GifBox, contentDescription = stringResource(R.string.chat_select_gif), tint = if (stickersEnabled) Primary else TextHint)
            }
        }

        if (mode == "STICKER") {
            ExpressionStickerTab(
                stickerState = stickerState,
                stickersEnabled = stickersEnabled,
                onStickerClick = onStickerClick,
            )
        } else {
            ExpressionEmojiTab(
                stickerState = stickerState,
                onEmojiClick = onEmojiClick,
            )
        }
    }

    if (showPackManager) {
        StickerPackManagerDialog(stickerState = stickerState)
    }
}
