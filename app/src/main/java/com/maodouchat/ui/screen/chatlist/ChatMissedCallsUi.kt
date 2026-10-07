package com.maodouchat.ui.screen.chatlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.maodouchat.R
import com.maodouchat.data.model.MissedCall
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.LocalChatPalette
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MissedCallsCard(calls: List<MissedCall>, onOpen: () -> Unit) {
    val unread = calls.count { !it.isRead }
    // plurals 的 quantity 与格式化参数都要传，否则 %1$d 会原样显示
    val title = if (unread > 0) pluralStringResource(R.plurals.missed_calls_with_unread, calls.size, calls.size, unread) else pluralStringResource(R.plurals.missed_calls, calls.size, calls.size)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f))
            .combinedClickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Call, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(calls.firstOrNull()?.callerName.orEmpty(), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onOpen) { Text(stringResource(R.string.schedule_view_all)) }
    }
}

internal data class CallLogRow(
    val id: String,
    val peerId: String,
    val peerName: String,
    val video: Boolean,
    val direction: com.maodouchat.call.CallLogStore.Direction,
    val state: com.maodouchat.call.CallLogStore.State,
    val at: Long,
    val durationMs: Long
)

private fun formatCallDuration(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSec = (durationMs / 1000).toInt()
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MissedCallsSheet(
    rows: List<CallLogRow>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onOpenChat: (userId: String, name: String, video: Boolean) -> Unit,
    onDeleteRow: (CallLogRow) -> Unit = {}
) {
    val motion = LocalMotionSettings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.call_log_title)) },
        text = {
            if (rows.isEmpty()) Text(stringResource(R.string.missed_calls_empty))
            else LazyColumn {
                items(rows, key = { it.id }) { call ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(
                                fadeInSpec = motion.listItemFadeInSpec(),
                                fadeOutSpec = motion.listItemFadeOutSpec(),
                                placementSpec = motion.listItemPlacementSpec()
                            )
                            .combinedClickable(
                                onClick = { onOpenChat(call.peerId, call.peerName, call.video) },
                                onLongClick = { onDeleteRow(call) }
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            // CallMade/CallReceived 不在 material-icons-core；用 Call + tint 区分状态
                            imageVector = Icons.Filled.Call,
                            contentDescription = null,
                            tint = if (call.state == com.maodouchat.call.CallLogStore.State.MISSED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(call.peerName, fontWeight = FontWeight.Medium)
                            Text(
                                (if (call.video) stringResource(R.string.call_video) else stringResource(R.string.call_audio)) +
                                    " · " + relativeTime(call.at) +
                                    when (call.state) {
                                        com.maodouchat.call.CallLogStore.State.MISSED -> " · " + stringResource(R.string.missed_calls_badge)
                                        com.maodouchat.call.CallLogStore.State.ANSWERED -> {
                                            val d = formatCallDuration(call.durationMs)
                                            if (d.isNotEmpty()) " · " + d else ""
                                        }
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalChatPalette.current.textSecondary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClear) { Text(stringResource(R.string.chat_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}

private fun relativeTime(ts: Long): String {
    if (ts <= 0L) return ""
    return android.text.format.DateUtils.getRelativeTimeSpanString(
        ts,
        System.currentTimeMillis(),
        android.text.format.DateUtils.MINUTE_IN_MILLIS
    ).toString()
}
