package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.network.ReportResponse
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.component.EmptyState
import com.maodouchat.ui.component.EmptyStateType
import androidx.compose.material.icons.outlined.Search
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 「我的举报」与「黑名单」两页（G104 从 `SettingsSubScreens.kt` 拆出，原 232 行）。
 *
 * 两个页面共享同一套网络客户端（`ApiService` + `TokenManager`）与「空态」组件，
 * 故合成一个文件而不是两个小文件。
 *
 * **拆解约束**：不直接抓应用级数据库单例（`ui/` 红线，读库只能经 ViewModel/repository）。
 * 纯搬移，不改判断。
 */

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MyReportsScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val tokenManager = remember(context) { com.maodouchat.network.TokenManager.getInstance(context) }
    val scope = rememberCoroutineScope()
    val reports = remember { mutableStateOf<List<com.maodouchat.network.ReportResponse>>(emptyList()) }
    val isLoading = remember { mutableStateOf(true) }
    val error = remember { mutableStateOf<String?>(null) }
    val loadFailedText = stringResource(com.maodouchat.R.string.my_reports_load_failed)

    suspend fun load() {
        isLoading.value = true
        error.value = null
        com.maodouchat.data.repository.ModerationNetworkRepository().myReports(tokenManager.getToken().orEmpty())
            .onSuccess { reports.value = it }
            .onFailure { error.value = loadFailedText }
        isLoading.value = false
    }
    LaunchedEffect(Unit) { load() }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(com.maodouchat.R.string.settings_my_reports), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(com.maodouchat.R.string.common_back), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        when {
            isLoading.value -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            error.value != null && reports.value.isEmpty() -> EmptyState(
                type = EmptyStateType.NETWORK_ERROR,
                title = stringResource(com.maodouchat.R.string.my_reports_load_failed),
                actionText = stringResource(com.maodouchat.R.string.chat_load_failed_retry),
                onAction = { scope.launch { load() } }
            )
            reports.value.isEmpty() -> EmptyState(
                type = EmptyStateType.GENERIC,
                title = stringResource(com.maodouchat.R.string.my_reports_empty)
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(reports.value, key = { it.id }) { report ->
                    MyReportCard(report)
                    androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun MyReportCard(report: com.maodouchat.network.ReportResponse) {
    val targetLabel = when (report.targetType) {
        "MESSAGE" -> stringResource(com.maodouchat.R.string.my_reports_target_message)
        "POST" -> stringResource(com.maodouchat.R.string.my_reports_target_post)
        else -> stringResource(com.maodouchat.R.string.my_reports_target_user)
    }
    val statusLabel = if (report.status.equals("PENDING", ignoreCase = true)) {
        stringResource(com.maodouchat.R.string.report_status_pending)
    } else {
        stringResource(com.maodouchat.R.string.report_status_resolved)
    }
    val timeText = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", androidx.compose.ui.platform.LocalConfiguration.current.locales[0])
        .format(java.util.Date(report.createdAt))
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$targetLabel · ${report.reason}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (report.status.equals("PENDING", ignoreCase = true)) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textSecondary
            )
        }
        Text(
            text = timeText,
            style = MaterialTheme.typography.labelSmall,
            color = LocalChatPalette.current.textSecondary,
            modifier = Modifier.padding(top = 4.dp)
        )
        report.description?.takeIf { it.isNotBlank() }?.let { desc ->
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatPalette.current.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        report.resolutionNote?.takeIf { it.isNotBlank() }?.let { note ->
            Text(
                text = stringResource(com.maodouchat.R.string.my_reports_resolution, note),
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatPalette.current.textHint,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun BlockedUsersScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val tokenManager = remember(context) { com.maodouchat.network.TokenManager.getInstance(context) }
    val blocked = remember { mutableStateOf<List<com.maodouchat.network.UserDto>>(emptyList()) }
    val isLoading = remember { mutableStateOf(true) }
    val error = remember { mutableStateOf<String?>(null) }
    val unblockingIds = remember { mutableStateOf<Set<String>>(emptySet()) }
    // 1.144：黑名单搜索
    var blockedSearch by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val blockedLoadFailedText = stringResource(com.maodouchat.R.string.blocked_load_failed)
    val unblockFailedText = stringResource(com.maodouchat.R.string.blocked_unblock_failed)
    val filteredBlocked = remember(blocked.value, blockedSearch) {
        val q = blockedSearch.trim()
        if (q.isBlank()) blocked.value
        else blocked.value.filter { it.name.contains(q, ignoreCase = true) || it.id.contains(q, ignoreCase = true) }
    }

    suspend fun load() {
        isLoading.value = true
        error.value = null
        com.maodouchat.data.repository.AccountSecurityNetworkRepository().blockedUserDetails(tokenManager.getToken().orEmpty())
            .onSuccess { blocked.value = it }
            .onFailure { error.value = blockedLoadFailedText }
        isLoading.value = false
    }
    LaunchedEffect(Unit) { load() }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(com.maodouchat.R.string.settings_blocked_users), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(com.maodouchat.R.string.common_back), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        when {
            isLoading.value -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            error.value != null && blocked.value.isEmpty() -> EmptyState(
                type = EmptyStateType.NETWORK_ERROR,
                title = stringResource(com.maodouchat.R.string.blocked_load_failed),
                actionText = stringResource(com.maodouchat.R.string.chat_load_failed_retry),
                onAction = { scope.launch { load() } }
            )
            blocked.value.isEmpty() -> EmptyState(
                type = EmptyStateType.GENERIC,
                title = stringResource(com.maodouchat.R.string.blocked_empty)
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                item(key = "blocked_search", contentType = "search") {
                    androidx.compose.material3.OutlinedTextField(
                        value = blockedSearch,
                        onValueChange = { blockedSearch = it.take(100) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        placeholder = { Text(stringResource(com.maodouchat.R.string.blocked_search_hint)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                if (filteredBlocked.isEmpty()) {
                    item(key = "blocked_search_empty", contentType = "empty") {
                        Text(
                            stringResource(com.maodouchat.R.string.blocked_search_empty),
                            color = LocalChatPalette.current.textHint,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    items(filteredBlocked, key = { it.id }) { user ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!user.avatar.isNullOrBlank()) {
                            coil.compose.AsyncImage(
                                model = user.avatar,
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            )
                        } else {
                            Box(
                                modifier = Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(user.name.firstOrNull()?.toString() ?: "?", color = Color.White, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = user.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        val unblocking = user.id in unblockingIds.value
                        TextButton(
                            enabled = !unblocking,
                            onClick = {
                                unblockingIds.value = unblockingIds.value + user.id
                                scope.launch {
                                    com.maodouchat.data.repository.AccountSecurityNetworkRepository().unblock(tokenManager.getToken().orEmpty(), user.id)
                                        .onSuccess { blocked.value = blocked.value.filter { it.id != user.id } }
                                        .onFailure { error.value = unblockFailedText }
                                    unblockingIds.value = unblockingIds.value - user.id
                                }
                            }
                        ) { Text(stringResource(com.maodouchat.R.string.blocked_unblock), color = MaterialTheme.colorScheme.primary) }
                    }
                    androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }
}
