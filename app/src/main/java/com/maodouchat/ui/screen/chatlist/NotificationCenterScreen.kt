package com.maodouchat.ui.screen.chatlist

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.maodouchat.data.repository.NotificationCenterItem
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.MaodouDimens
import com.maodouchat.ui.theme.MotionTokens
import com.maodouchat.ui.theme.TextHint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationCenterScreen(
    onBack: () -> Unit,
    onOpenItem: (NotificationCenterItem) -> Unit = {},
    viewModel: NotificationCenterViewModel = viewModel()
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }
    // 1.170：长按某条通知 → 清除该会话全部通知
    var clearChatId by remember { mutableStateOf<String?>(null) }
    // 1.268：长按操作菜单（复制 / 清除该会话通知）
    var notifyMenuFor by remember { mutableStateOf<Pair<NotificationCenterItem, String?>?>(null) }
    var filter by rememberSaveable { mutableStateOf(NotifFilter.ALL) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val motion = LocalMotionSettings.current

    val filteredItems = remember(items, filter, searchQuery) {
        val typeFiltered = items.filter { item -> filter.matches(item) }
        val query = searchQuery.trim()
        if (query.isBlank()) {
            typeFiltered
        } else {
            typeFiltered.filter { item ->
                item.title.contains(query, ignoreCase = true) ||
                    item.subtitle.orEmpty().contains(query, ignoreCase = true) ||
                    item.preview.orEmpty().contains(query, ignoreCase = true) ||
                    item.type.contains(query, ignoreCase = true) ||
                    item.extra.values.any { it.contains(query, ignoreCase = true) }
            }
        }
    }
    val groups = remember(filteredItems) { filteredItems.groupByDay() }
    val unreadCount = items.count { !it.read }
    val showSearch = items.size >= 5

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.notif_center_title), modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                        if (unreadCount > 0) {
                            Text(
                                pluralStringResource(R.plurals.notif_center_unread_count, unreadCount, unreadCount),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.markAllRead() }, enabled = unreadCount > 0) {
                        Icon(Icons.Outlined.DoneAll, contentDescription = stringResource(R.string.notif_center_mark_all_read), tint = if (unreadCount > 0) MaterialTheme.colorScheme.onSurface else TextHint)
                    }
                    IconButton(onClick = { showClearConfirm = true }, enabled = items.isNotEmpty()) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.notif_center_clear_all), tint = if (items.isNotEmpty()) MaterialTheme.colorScheme.onSurface else TextHint)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            EmptyNotificationCenter(modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                NotificationFilterStrip(
                    selected = filter,
                    onSelect = { filter = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaodouDimens.ScreenPadding, vertical = 8.dp)
                )
                if (showSearch) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it.take(160) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        placeholder = { Text(stringResource(R.string.notif_center_search_hint)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MaodouDimens.ScreenPadding)
                            .padding(bottom = 8.dp)
                    )
                }
                if (filteredItems.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(
                                if (searchQuery.isNotBlank()) R.string.notif_center_search_empty
                                else R.string.notif_center_filter_empty
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalChatPalette.current.textSecondary
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
                    ) {
                        groups.forEach { (dayBucket, dayItems) ->
                            item(key = "header_${dayBucket.name}", contentType = "day_header") {
                                DayHeader(dayBucket.displayLabel())
                            }
                            items(dayItems, key = NotificationCenterItem::id, contentType = { "notification" }) { item ->
                                val itemChatId = resolvedNotificationChatId(item)
                                NotificationRow(
                                    item = item,
                                    // 1.284：搜索关键词高亮（与 Explore/收藏/会话列表一致）
                                    highlightQuery = searchQuery,
                                    onClick = {
                                        viewModel.markRead(item.id, item)
                                        onOpenItem(item)
                                    },
                                    onDismiss = { viewModel.remove(item.id, item) },
                                    onLongClick = { notifyMenuFor = item to itemChatId },
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = if (motion.animationsEnabled) tween(motion.duration(MotionTokens.Standard)) else null,
                                        fadeOutSpec = if (motion.animationsEnabled) tween(motion.duration(MotionTokens.Fast)) else null,
                                        placementSpec = if (motion.animationsEnabled) spring(stiffness = 420f, dampingRatio = 0.88f) else null
                                    )
                                )
                                HorizontalDivider(color = LocalChatPalette.current.textHint.copy(alpha = 0.15f), modifier = Modifier.padding(start = 72.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    NotificationCenterDialogs(
        showClearConfirm = showClearConfirm,
        clearChatId = clearChatId,
        notifyMenuFor = notifyMenuFor,
        viewModel = viewModel,
        onShowClearConfirmChange = { showClearConfirm = it },
        onClearChatIdChange = { clearChatId = it },
        onNotifyMenuForChange = { notifyMenuFor = it }
    )
}
