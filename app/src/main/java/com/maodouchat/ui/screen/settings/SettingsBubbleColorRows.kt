package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette

@Composable
internal fun ChatBubbleColorRow(
    current: String,
    onChange: (String) -> Unit
) {
    val options = listOf(
        com.maodouchat.theme.ChatBubbleColorPalette.BLUE to stringResource(R.string.general_chat_bubble_blue),
        com.maodouchat.theme.ChatBubbleColorPalette.GREEN to stringResource(R.string.general_chat_bubble_green),
        com.maodouchat.theme.ChatBubbleColorPalette.PURPLE to stringResource(R.string.general_chat_bubble_purple),
        com.maodouchat.theme.ChatBubbleColorPalette.ORANGE to stringResource(R.string.general_chat_bubble_orange),
        com.maodouchat.theme.ChatBubbleColorPalette.PINK to stringResource(R.string.general_chat_bubble_pink),
        com.maodouchat.theme.ChatBubbleColorPalette.TEAL to stringResource(R.string.general_chat_bubble_teal)
    )
    val isDark = com.maodouchat.ui.theme.LocalDarkTheme.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(stringResource(R.string.general_chat_bubble_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(2.dp))
        Text(stringResource(R.string.general_chat_bubble_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (id, label) ->
                val color = if (isDark) com.maodouchat.theme.ChatBubbleColorPalette.dark(id)
                else com.maodouchat.theme.ChatBubbleColorPalette.light(id)
                ThemeChoiceChip(
                    label = label,
                    selected = current == id,
                    onClick = { onChange(id) }
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }
        }
    }
}




/** 强调色 id → 本地化颜色名（无障碍朗读用）。 */
internal fun accentLabelRes(id: String): Int = when (id) {
    "blue" -> R.string.general_accent_blue
    "green" -> R.string.general_accent_green
    "purple" -> R.string.general_accent_purple
    "orange" -> R.string.general_accent_orange
    "pink" -> R.string.general_accent_pink
    "red" -> R.string.general_accent_red
    else -> R.string.general_accent_teal
}
