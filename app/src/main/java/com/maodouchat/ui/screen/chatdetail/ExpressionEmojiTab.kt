package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.Secondary

// 表情面板的表情页签：表情搜索、最近使用行、表情网格（纯搬移，判定逐字保留）。
@Composable
internal fun ExpressionEmojiTab(
    stickerState: ExpressionStickerState,
    onEmojiClick: (String) -> Unit,
) {
    val context = LocalContext.current
    var recentEmojis by stickerState.recentEmojis
    var emojiSearch by rememberSaveable { mutableStateOf("") }
    val filteredEmojis = remember(emojiSearch) {
        val q = emojiSearch.trim()
        if (q.isEmpty()) {
            BUILT_IN_EMOJIS
        } else {
            // emoji itself or common keyword aliases
            val aliases = EMOJI_SEARCH_ALIASES
            BUILT_IN_EMOJIS.filter { emoji ->
                emoji.contains(q) ||
                    aliases[emoji].orEmpty().any { it.contains(q, ignoreCase = true) }
            }
        }
    }
    OutlinedTextField(
        value = emojiSearch,
        onValueChange = { emojiSearch = it.take(64) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.chat_emoji_search_hint)) },
        textStyle = MaterialTheme.typography.bodySmall,
        leadingIcon = {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = Secondary, modifier = Modifier.size(18.dp))
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Primary,
            unfocusedBorderColor = Outline
        )
    )
    if (filteredEmojis.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.chat_emoji_search_empty),
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatPalette.current.textSecondary
            )
        }
    } else {
        // 点击表情：上屏 + 记录最近使用（去重，本地按账号隔离）
        val pushRecentEmoji: (String) -> Unit = { emoji ->
            onEmojiClick(emoji)
            com.maodouchat.util.EmojiRecentPreferences.recordRecent(context, emoji)
            recentEmojis = com.maodouchat.util.EmojiRecentPreferences.getRecent(context)
        }
        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            // 最近使用行：仅无搜索词时展示
            if (emojiSearch.isBlank() && recentEmojis.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.chat_emoji_recent),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    recentEmojis.forEach { item ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { pushRecentEmoji(item) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(item, fontSize = 22.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            filteredEmojis.chunked(6).forEach { rowItems ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    rowItems.forEach { item ->
                        PressScaleGlyphItem(
                            glyph = item,
                            fontSize = 24.sp,
                            enabled = true,
                            onClick = { pushRecentEmoji(item) }
                        )
                    }
                    repeat(6 - rowItems.size) { Spacer(modifier = Modifier.size(48.dp)) }
                }
            }
        }
    }
}
