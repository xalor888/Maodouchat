package com.maodouchat.ui.screen.chatlist

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import com.maodouchat.ui.component.SearchHighlightSurface
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.maodouchat.ui.theme.UnreadRed
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.MotionTokens
import com.maodouchat.ui.theme.MotionPolicy
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSearchScreen(
    onBack: () -> Unit,
    onOpenResult: (chatId: String, messageId: String) -> Unit,
    viewModel: GlobalSearchViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val motion = LocalMotionSettings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val aiSearchEnabled = com.maodouchat.ai.AiEntryPolicy.shouldShowGlobalAiEntry(context)
    androidx.compose.runtime.LaunchedEffect(aiSearchEnabled) {
        if (!aiSearchEnabled && state.mode == GlobalSearchMode.AI) {
            viewModel.setMode(GlobalSearchMode.KEYWORD)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.global_search_title), modifier = Modifier.semantics { heading() }, color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = { Text(stringResource(R.string.global_search_placeholder), color = LocalChatPalette.current.textHint) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.outline) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onQueryChange("") }) {
                                Icon(Icons.Outlined.Close, stringResource(R.string.global_search_clear), tint = LocalChatPalette.current.textSecondary)
                            }
                        }
                    },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (aiSearchEnabled) Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlobalSearchMode.entries.forEach { mode ->
                        val selected = state.mode == mode
                        val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        val pressed by interactionSource.collectIsPressedAsState()
                        val pressScale by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = if (pressed) 0.95f else 1f,
                            animationSpec = androidx.compose.animation.core.spring(
                                dampingRatio = 0.55f, stiffness = 460f
                            ),
                            label = "globalSearchChipScale"
                        )
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.setMode(mode) },
                            label = {
                                Text(stringResource(if (mode == GlobalSearchMode.KEYWORD) R.string.global_search_mode_keyword else R.string.global_search_mode_ai))
                            },
                            modifier = Modifier.weight(1f).graphicsLayer { scaleX = pressScale; scaleY = pressScale },
                            interactionSource = interactionSource,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = MaterialTheme.colorScheme.outlineVariant,
                                selectedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            )
                        )
                    }
                }
                if (aiSearchEnabled && state.mode == GlobalSearchMode.AI) {
                    Button(
                        onClick = viewModel::requestAiSearch,
                        enabled = state.query.isNotBlank() && !state.isIndexing && !state.isSearching,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isSearching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(stringResource(R.string.global_search_ai_action))
                        }
                    }
                    Text(
                        stringResource(R.string.global_search_ai_privacy),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint
                    )
                } else {
                    // 关键词搜索模式：添加消息类型筛选
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SearchFilterType.entries.forEach { filter ->
                            val selected = state.filterType == filter
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.setFilterType(filter) },
                                label = {
                                    Text(
                                        stringResource(filter.labelRes),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                modifier = Modifier.height(28.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                                    selectedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                )
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(state.isIndexing, enter = fadeIn(), exit = fadeOut()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
            }
            state.error?.let { errorText ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(UnreadRed.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        errorText,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.unreadRed
                    )
                    if (state.query.isNotBlank() && !state.isSearching && !state.isIndexing) {
                        if (state.mode == GlobalSearchMode.AI) {
                            TextButton(onClick = viewModel::retryLastAiSearch) {
                                Text(stringResource(R.string.global_search_ai_retry), color = MaterialTheme.colorScheme.primary)
                            }
                        } else {
                            TextButton(onClick = viewModel::retryLastKeywordSearch) {
                                Text(stringResource(R.string.global_search_ai_retry), color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            if (state.excludedChatCount > 0) {
                Text(
                    if (state.mode == GlobalSearchMode.AI) {
                        pluralStringResource(R.plurals.global_search_excluded_chats, state.excludedChatCount, state.excludedChatCount)
                    } else {
                        stringResource(R.string.global_search_redacted_chats, state.excludedChatCount)
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textSecondary
                )
            }

            when {
                state.query.isBlank() -> {
                    if (state.recentSearches.isNotEmpty()) {
                        RecentSearchesSection(
                            queries = state.recentSearches,
                            onPick = viewModel::useRecentSearch,
                            onClearAll = viewModel::clearRecentSearches
                        )
                    } else {
                        GlobalSearchEmpty(stringResource(R.string.global_search_empty_initial))
                    }
                }
                state.isIndexing || state.isSearching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                state.results.isEmpty() && state.error != null -> { /* 失败横幅已展示，避免误报「无结果」 */ }
                state.results.isEmpty() -> GlobalSearchEmpty(
                    stringResource(
                        if (state.mode == GlobalSearchMode.AI && !state.aiSearchCompleted) R.string.global_search_ai_ready
                        else R.string.global_search_empty_results
                    )
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    itemsIndexed(
                        state.results,
                        key = { _, hit -> "${hit.chatId}:${hit.messageId}" },
                        contentType = { _, _ -> "search_hit" }
                    ) { index, hit ->
                        val animateInitialEntry = MotionPolicy.shouldAnimateInitialListEntry(index, motion)
                        var visible by remember(hit.chatId, hit.messageId, animateInitialEntry) {
                            mutableStateOf(!animateInitialEntry)
                        }
                        LaunchedEffect(hit.chatId, hit.messageId, animateInitialEntry) {
                            if (animateInitialEntry) kotlinx.coroutines.delay(
                                MotionPolicy.initialListEntryDelay(index, motion).toLong()
                            )
                            visible = true
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = visible,
                            enter = if (animateInitialEntry) {
                                androidx.compose.animation.expandVertically(tween(motion.duration(MotionTokens.Emphasized))) +
                                    fadeIn(tween(motion.duration(MotionTokens.Emphasized)))
                            } else {
                                EnterTransition.None
                            },
                        ) {
                            Column {
                                GlobalSearchResultRow(
                                    hit = hit,
                                    query = state.query,
                                    onClick = { onOpenResult(hit.chatId, hit.messageId) }
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.showAiConsent) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAiConsent,
            title = { Text(stringResource(R.string.chat_ai_consent_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.global_search_ai_consent_data), color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.chat_ai_consent_privacy), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::acceptAiConsent) { Text(stringResource(R.string.chat_ai_accept), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissAiConsent) { Text(stringResource(R.string.chat_later)) }
            }
        )
    }
}

@Composable
private fun GlobalSearchResultRow(hit: GlobalSearchHit, query: String, onClick: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val time = remember(locale, hit.timestamp) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(hit.timestamp)) }
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            when (hit.messageType) {
                "IMAGE", "GIF", "STICKER" -> Icons.Outlined.Image
                "VIDEO" -> Icons.Outlined.Videocam
                "FILE" -> Icons.Outlined.Description
                "VOICE" -> Icons.Outlined.Mic
                "LOCATION" -> Icons.Outlined.LocationOn
                else -> if (hit.semanticScore != null) Icons.Outlined.AutoAwesome else Icons.Outlined.Search
            },
            contentDescription = null,
            tint = if (hit.semanticScore != null) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textHint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hit.chatName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(time, style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint)
            }
            Text(hit.senderName, style = MaterialTheme.typography.labelMedium, color = LocalChatPalette.current.textSecondary)
            Text(
                highlightedText(hit.text, query),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalChatPalette.current.textSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun highlightedText(text: String, query: String): AnnotatedString {
    val (c, bg) = SearchHighlightSurface
    return com.maodouchat.ui.component.highlightedText(text, query, c, bg)
}

@Composable
private fun GlobalSearchEmpty(message: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = LocalChatPalette.current.textHint, modifier = Modifier.size(42.dp))
            Spacer(modifier = Modifier.height(10.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textHint)
        }
    }
}

@Composable
private fun RecentSearchesSection(
    queries: List<String>,
    onPick: (String) -> Unit,
    onClearAll: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.global_search_recent_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalChatPalette.current.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClearAll) {
                    Text(stringResource(R.string.global_search_recent_clear), color = LocalChatPalette.current.textSecondary)
                }
            }
        }
        items(queries, key = { "recent:$it" }) { query ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(query) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    tint = LocalChatPalette.current.textHint,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    query,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        }
    }
}
