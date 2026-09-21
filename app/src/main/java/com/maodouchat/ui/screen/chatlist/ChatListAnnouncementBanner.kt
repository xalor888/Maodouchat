package com.maodouchat.ui.screen.chatlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import androidx.compose.runtime.Composable

/**
 * 会话列表的「置顶公告条」（G139 从 `ChatListScreen.kt` 拆出，原 35 行）。
 *
 * 公告中心里高优先级（EMERGENCY / MAINTENANCE）未读公告的强制确认弹窗——
 * 不可跳过，确认后 ack（重复点击由 ViewModel 防重入）。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`。
 * 纯搬移，不改判断。
 */
@Composable
internal fun ChatListAnnouncementBanner(
    priorityAnnouncement: com.maodouchat.notification.AnnouncementPolicy.AnnouncementData?,
    onAck: (String) -> Unit,
) {
    if (priorityAnnouncement != null) {
        AlertDialog(
            onDismissRequest = { /* 高优先级公告不可跳过，必须确认 */ },
            title = { Text(priorityAnnouncement.title.ifBlank { stringResource(R.string.announcement_title_default) }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when (priorityAnnouncement.level) {
                            "EMERGENCY" -> stringResource(R.string.announcement_level_emergency)
                            else -> stringResource(R.string.announcement_level_maintenance)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (priorityAnnouncement.level == "EMERGENCY") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Text(
                        priorityAnnouncement.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onAck(priorityAnnouncement.id) }
                ) { Text(stringResource(R.string.common_confirm)) }
            }
        )
    }
}
