package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary

// 表情面板的贴纸页签：搜索框、包页签行、贴纸网格与空态（纯搬移，判定逐字保留）。
@Composable
internal fun ExpressionStickerTab(
    stickerState: ExpressionStickerState,
    stickersEnabled: Boolean,
    onStickerClick: (String) -> Unit,
) {
    var stickerTab by stickerState.stickerTab
    var stickerQuery by stickerState.stickerQuery
    var recentStickers by stickerState.recentStickers
    val enabledPacks = stickerState.enabledPacks
    val displayItems = stickerState.displayItems
    OutlinedTextField(
        value = stickerQuery,
        onValueChange = { stickerQuery = it.take(64) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.sticker_search_hint)) },
        textStyle = MaterialTheme.typography.bodySmall,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Primary,
            unfocusedBorderColor = Outline
        )
    )
    if (stickerQuery.isBlank()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StickerPackChip(
                label = stringResource(R.string.sticker_pack_recent),
                selected = stickerTab == com.maodouchat.util.StickerCatalog.PACK_RECENT,
                onClick = { stickerTab = com.maodouchat.util.StickerCatalog.PACK_RECENT }
            )
            enabledPacks.forEach { pack ->
                StickerPackChip(
                    label = stickerPackLabel(pack.nameKey),
                    selected = stickerTab == pack.id,
                    onClick = { stickerTab = pack.id }
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
    val displayItems = stickerState.displayItems
    if (displayItems.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(
                    when {
                        stickerQuery.isNotBlank() -> R.string.sticker_search_empty
                        stickerTab == com.maodouchat.util.StickerCatalog.PACK_RECENT -> R.string.sticker_recent_empty
                        else -> R.string.sticker_search_empty
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatPalette.current.textSecondary
            )
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            displayItems.chunked(6).forEach { rowItems ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    rowItems.forEach { item ->
                        // 9.223：贴纸按压回弹（TG 式手感，尊重系统动效开关）
                        PressScaleGlyphItem(
                            glyph = item,
                            fontSize = 32.sp,
                            enabled = stickersEnabled,
                            onClick = {
                                onStickerClick(item)
                                recentStickers = com.maodouchat.util.StickerPolicy.pushRecent(recentStickers, item)
                            }
                        )
                    }
                    repeat(6 - rowItems.size) { Spacer(modifier = Modifier.size(48.dp)) }
                }
            }
        }
    }
}
