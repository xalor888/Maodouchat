package com.maodouchat.ui.screen.call

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.call.CallLogStore
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.UnreadRed
import kotlinx.coroutines.launch
import com.maodouchat.ui.theme.LocalChatPalette

/** 1.29：通话记录页——展示本地 CallLogStore 全量历史，点击回拨，可清空。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
// 资源字符串均在回调/协程内读取，非组合作用域
@SuppressLint("LocalContextGetResourceValueCall")
fun CallHistoryScreen(
    onBack: () -> Unit,
    onCall: (peerId: String, peerName: String, callType: String) -> Unit,
    // 1.366：长按单条支持查看对方资料（与联系人/未接来电交互一致）
    onOpenProfile: (userId: String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // clear 后通过 revision 触发重读
    var revision by remember { mutableIntStateOf(0) }
    val logs = remember(revision) { CallLogStore.list(context) }
    // 1.282：长按单条弹出操作菜单（删除该条 / 查看对方资料）
    var entryMenuId by rememberSaveable { mutableStateOf<String?>(null) }
    // 1.323：通话记录搜索（对端名/ID）
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // 1.333：只看未接
    var onlyMissed by rememberSaveable { mutableStateOf(false) }
    val filteredLogs = remember(logs, searchQuery, onlyMissed) {
        var base = if (onlyMissed) logs.filter { it.state == CallLogStore.State.MISSED } else logs
        val q = searchQuery.trim()
        if (q.isBlank()) base
        else base.filter { it.peerName.contains(q, ignoreCase = true) || it.peerId.contains(q, ignoreCase = true) }
    }
    val showSearch = logs.size >= 6

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.call_log_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (logs.isNotEmpty()) {
                        TextButton(onClick = {
                            // 1.42：与会话列表未接来电「清空」一致——同时清 CallLogStore + Room 未接记录
                            val app = context.applicationContext as? com.maodouchat.MaodouchatApp
                            if (app != null) {
                                scope.launch {
                                    com.maodouchat.data.repository.MissedCallRepository(app.database.missedCallDao()).clearAll()
                                }
                            }
                            CallLogStore.clear(context)
                            revision++
                        }) {
                            Text(stringResource(R.string.missed_calls_clear_all), color = UnreadRed)
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.call_history_empty), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)            }
            return@Scaffold
        }
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 1.333：只看未接（有未接记录时显示）
            if (logs.any { it.state == CallLogStore.State.MISSED }) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    androidx.compose.material3.FilterChip(
                        selected = onlyMissed,
                        onClick = { onlyMissed = !onlyMissed },
                        label = { Text(stringResource(R.string.call_history_only_missed)) }
                    )
                }
            }
            // 1.323：通话记录搜索（≥6 条时显示）
            if (showSearch) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it.take(100) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.missed_calls_search_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            if (filteredLogs.isEmpty() && searchQuery.isNotBlank()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.missed_calls_search_empty), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary)
                }
                return@Column
            }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)
        ) {
            items(filteredLogs, key = { it.id }) { entry ->
                val isMissed = entry.state == CallLogStore.State.MISSED
                val isIncoming = entry.direction == CallLogStore.Direction.INCOMING
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        // 1.282：长按单条弹出删除确认（点击仍回拨）
                        .combinedClickable(
                            onClick = {
                                if (!entry.isGroup) {
                                    onCall(entry.peerId, entry.peerName.ifBlank { entry.peerId }, if (entry.isVideo) "VIDEO" else "AUDIO")
                                }
                            },
                            // 1.366：长按弹出操作菜单（删除该条 / 查看对方资料）
                            onLongClick = { entryMenuId = entry.id }
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                if (isMissed) UnreadRed.copy(alpha = 0.12f) else Primary.copy(alpha = 0.10f),
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (isMissed) Icons.Filled.CallEnd else Icons.Filled.Call,
                            contentDescription = null,
                            tint = if (isMissed) UnreadRed else Primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            // 1.323：搜索时高亮匹配对端名
                            if (searchQuery.isBlank()) androidx.compose.ui.text.AnnotatedString(entry.peerName.ifBlank { entry.peerId })
                            else highlightedText(entry.peerName.ifBlank { entry.peerId }, searchQuery),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (entry.isVideo) {
                                Icon(Icons.Filled.Videocam, contentDescription = null, tint = LocalChatPalette.current.textSecondary, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = buildString {
                                    append(
                                        when {
                                            isMissed -> context.getString(R.string.missed_calls_badge)
                                            isIncoming -> context.getString(R.string.call_history_incoming)
                                            else -> context.getString(R.string.call_history_outgoing)
                                        }
                                    )
                                    append(" · ")
                                    append(callHistoryRelativeTime(context, entry.startedAt))
                                    if (entry.state == CallLogStore.State.ANSWERED && entry.durationMs > 0L) {
                                        append(" · ")
                                        append(formatCallHistoryDuration(entry.durationMs))
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isMissed) UnreadRed else TextSecondary
                            )
                        }
                    }
                    Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.missed_calls_callback), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
        }
        } // Column
    }

    // 1.366：长按单条弹出操作菜单（删除该条 / 查看对方资料）
    entryMenuId?.let { entryId ->
        val target = logs.firstOrNull { it.id == entryId }
        val displayName = target?.peerName?.takeIf(String::isNotBlank)
            ?: target?.peerId
            ?: stringResource(R.string.call_history_unknown_peer)
        ModalBottomSheet(
            onDismissRequest = { entryMenuId = null },
            sheetState = rememberModalBottomSheetState()
        ) {
            TextButton(
                onClick = {
                    val removed = CallLogStore.remove(context, entryId)
                    if (removed && target?.state == CallLogStore.State.MISSED) {
                        // 未接来电同 id 记录同步删除，保持会话列表角标/卡片一致（与清空一致走 Room）
                        val app = context.applicationContext as? com.maodouchat.MaodouchatApp
                        if (app != null) {
                            scope.launch {
                                com.maodouchat.data.repository.MissedCallRepository(app.database.missedCallDao()).delete(entryId)
                            }
                        }
                    }
                    entryMenuId = null
                    revision++
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.call_history_delete, displayName), modifier = Modifier.fillMaxWidth(), color = UnreadRed)
            }
            TextButton(
                onClick = {
                    entryMenuId = null
                    onOpenProfile(target?.peerId.orEmpty())
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.call_history_open_profile), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

private fun callHistoryRelativeTime(context: android.content.Context, millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    return when {
        diff < 60_000L -> context.getString(R.string.time_just_now)
        diff < 3_600_000L -> {
            val m = (diff / 60_000L).toInt()
            context.resources.getQuantityString(R.plurals.time_minutes_ago, m, m)
        }
        diff < 86_400_000L -> {
            val h = (diff / 3_600_000L).toInt()
            context.resources.getQuantityString(R.plurals.time_hours_ago, h, h)
        }
        diff < 7L * 86_400_000L -> {
            val d = (diff / 86_400_000L).toInt()
            context.resources.getQuantityString(R.plurals.time_days_ago, d, d)
        }
        else -> java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()).format(java.util.Date(millis))
    }
}

private fun formatCallHistoryDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000L).coerceAtLeast(0L)
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

/** 1.323：通话记录搜索关键词高亮（复用 GlobalSearchTextHighlight，与 Explore/收藏/通知中心一致）。 */
@Composable
private fun highlightedText(text: String, query: String): androidx.compose.ui.text.AnnotatedString {
    val snippet = remember(text, query) {
        com.maodouchat.ui.screen.chatlist.GlobalSearchTextHighlight.buildSnippet(text, query)
    }
    return androidx.compose.ui.text.buildAnnotatedString {
        if (snippet.highlights.isEmpty()) {
            append(snippet.text)
            return@buildAnnotatedString
        }
        var cursor = 0
        snippet.highlights.forEach { span ->
            if (span.start > cursor) append(snippet.text.substring(cursor, span.start))
            pushStyle(androidx.compose.ui.text.SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, background = Primary.copy(alpha = 0.12f)))
            append(snippet.text.substring(span.start, span.end))
            pop()
            cursor = span.end
        }
        if (cursor < snippet.text.length) append(snippet.text.substring(cursor))
    }
}
