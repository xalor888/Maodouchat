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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
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
 * 「我的举报」页。
 *
 * **拆解约束**：不直接抓应用级数据库单例（`ui/` 红线，读库只能经 ViewModel/repository）。
 */

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MyReportsScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reports = remember { mutableStateOf<List<com.maodouchat.network.ReportResponse>>(emptyList()) }
    val isLoading = remember { mutableStateOf(true) }
    val error = remember { mutableStateOf<String?>(null) }
    val loadFailedText = stringResource(com.maodouchat.R.string.my_reports_load_failed)

    suspend fun load() {
        isLoading.value = true
        error.value = null
        com.maodouchat.data.repository.ModerationNetworkRepository().myReports()
            .onSuccess { reports.value = it }
            .onFailure { error.value = loadFailedText }
        isLoading.value = false
    }
    LaunchedEffect(Unit) { load() }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(com.maodouchat.R.string.settings_my_reports), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() }) },
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
    // 举报卡片每次重组都 new 一个 SimpleDateFormat；locale 变化时 remember 键失效重建。
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val reportDateFormat = remember(locale) { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", locale) }
    val timeText = reportDateFormat.format(java.util.Date(report.createdAt))
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
