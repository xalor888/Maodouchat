package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.Secondary
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.UnreadRed

@Composable
internal fun ChatSearchBar(
    query: String,
    mode: ChatSearchMode,
    scope: ChatSearchScope,
    window: ChatSearchWindow,
    resultIndex: Int,
    resultCount: Int,
    semanticCandidateCount: Int,
    isSemanticSearching: Boolean,
    semanticSearchQuery: String,
    semanticSearchResultCount: Int,
    semanticSearchError: String?,
    onQueryChange: (String) -> Unit,
    onModeChange: (ChatSearchMode) -> Unit,
    onScopeChange: (ChatSearchScope) -> Unit,
    onWindowChange: (ChatSearchWindow) -> Unit,
    onSemanticSearch: () -> Unit,
    onNextResult: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = {
                    Text(
                        stringResource(
                            when {
                                mode == ChatSearchMode.SEMANTIC -> R.string.chat_semantic_search_placeholder
                                scope == ChatSearchScope.STARRED -> R.string.chat_search_starred
                                else -> R.string.chat_search_content
                            }
                        ),
                        color = TextHint
                    )
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                    unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Primary,
                    focusedTextColor = OnSurface,
                    unfocusedTextColor = OnSurface
                )
            )
            if (mode == ChatSearchMode.SEMANTIC) {
                Spacer(modifier = Modifier.width(4.dp))
                if (isSemanticSearching) {
                    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Primary)
                    }
                } else {
                    val canSearch = query.isNotBlank() && semanticCandidateCount > 0
                    IconButton(enabled = canSearch, onClick = onSemanticSearch) {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = stringResource(R.string.chat_semantic_search_action),
                            tint = if (canSearch) Primary else TextHint
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (resultCount == 0) "0/0" else "${resultIndex + 1}/$resultCount",
                style = MaterialTheme.typography.labelMedium,
                color = Secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(40.dp)
            )
            IconButton(enabled = resultCount > 0, onClick = onNextResult) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.chat_search_next), tint = Secondary)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.chat_search_close), tint = Secondary)
            }
        }
        SearchChoices(ChatSearchMode.entries, mode, ChatSearchMode::localizedLabel, onModeChange)
        SearchChoices(ChatSearchScope.entries, scope, ChatSearchScope::localizedLabel, onScopeChange)
        SearchChoices(ChatSearchWindow.entries, window, ChatSearchWindow::localizedLabel, onWindowChange)
        if (mode == ChatSearchMode.SEMANTIC) {
            Text(
                text = if (semanticCandidateCount > 0) {
                    stringResource(R.string.chat_semantic_search_candidates, semanticCandidateCount)
                } else {
                    stringResource(R.string.chat_semantic_search_candidates_empty)
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextHint
            )
            semanticSearchError?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = UnreadRed)
            }
            if (
                !isSemanticSearching &&
                semanticSearchQuery.isNotBlank() &&
                semanticSearchQuery == query.trim() &&
                semanticSearchResultCount == 0 &&
                semanticSearchError == null
            ) {
                Text(stringResource(R.string.chat_semantic_search_no_results), style = MaterialTheme.typography.bodySmall, color = TextHint)
            }
            // 8.52 UX：关键词模式无匹配时明确提示（此前只有 "0/0" 计数器）
            if (mode == ChatSearchMode.KEYWORD && query.trim().isNotBlank() && resultCount == 0) {
                Text(stringResource(R.string.chat_search_no_results), style = MaterialTheme.typography.bodySmall, color = TextHint)
            }
        }
    }
}

@Composable
private fun <T> SearchChoices(
    entries: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        entries.forEach { entry ->
            SearchChoiceChip(label = label(entry), selected = selected == entry, onClick = { onSelect(entry) })
        }
    }
}

@Composable
private fun SearchChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) Primary else LocalChatPalette.current.chatInputBackground
    val content = if (selected) Color.White else OnSurface
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
    }
}

@Composable
private fun ChatSearchMode.localizedLabel(): String = stringResource(when (this) {
    ChatSearchMode.KEYWORD -> R.string.chat_search_mode_keyword
    ChatSearchMode.SEMANTIC -> R.string.chat_search_mode_semantic
})

@Composable
private fun ChatSearchScope.localizedLabel(): String = stringResource(when (this) {
    ChatSearchScope.ALL -> R.string.chat_search_scope_all
    ChatSearchScope.TEXT -> R.string.chat_search_scope_text
    ChatSearchScope.VOICE -> R.string.chat_search_scope_voice
    ChatSearchScope.TRANSLATION -> R.string.chat_search_scope_translation
    ChatSearchScope.STARRED -> R.string.chat_search_scope_starred
    ChatSearchScope.MEDIA -> R.string.chat_search_scope_media
    ChatSearchScope.MENTIONS -> R.string.chat_search_scope_mentions
})

@Composable
private fun ChatSearchWindow.localizedLabel(): String = stringResource(when (this) {
    ChatSearchWindow.ALL -> R.string.chat_search_window_all
    ChatSearchWindow.TODAY -> R.string.chat_search_window_today
    ChatSearchWindow.SEVEN_DAYS -> R.string.chat_search_window_week
    ChatSearchWindow.THIRTY_DAYS -> R.string.chat_search_window_month
})
