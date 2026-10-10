package com.maodouchat.ui.screen.settings

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
