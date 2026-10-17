package com.maodouchat.ui.screen.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.model.Chat
import com.maodouchat.ui.theme.LocalChatPalette

/**
 * 8.47：智能归档建议卡片（纯本地启发式，无 AI/服务端调用）。
 * 展示前 [suggestions] 条；每条可「归档」或「忽略」，整卡可一键关闭。
 */
@Composable
internal fun ArchiveSuggestionsCard(
    suggestions: List<com.maodouchat.ai.Suggestion>,
    chatsById: Map<String, Chat>,
    onArchive: (String) -> Unit,
    onDismissOne: (String) -> Unit,
    onDismissAll: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.ai_enhance_archive_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismissAll, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close), tint = LocalChatPalette.current.textSecondary, modifier = Modifier.size(16.dp))
            }
        }
        Text(
            stringResource(R.string.ai_enhance_archive_hint),
            style = MaterialTheme.typography.bodySmall,
            color = LocalChatPalette.current.textSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        suggestions.forEach { suggestion ->
            val chat = chatsById[suggestion.chatId]
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    text = chatNameForSuggestion(chat) ?: suggestion.chatId,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = suggestion.reason.take(40),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onArchive(suggestion.chatId) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.chat_archive), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { onDismissOne(suggestion.chatId) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(stringResource(R.string.common_later), color = LocalChatPalette.current.textSecondary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
