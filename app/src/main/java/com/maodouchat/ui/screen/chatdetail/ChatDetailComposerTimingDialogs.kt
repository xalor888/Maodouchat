package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.util.DisappearingMessagePolicy

@Composable
internal fun ChatQuietHoursDialog(
    current: com.maodouchat.notification.ChatQuietHoursStore.QuietWindow,
    onPick: (com.maodouchat.notification.ChatQuietHoursStore.QuietWindow) -> Unit,
    onDismiss: () -> Unit
) {
    // 快捷时段：预置的常用静音窗口（start, end, labelRes）
    val presets = listOf(
        Triple(22 * 60, 7 * 60, R.string.chat_quiet_hours_night),
        Triple(12 * 60, 14 * 60, R.string.chat_quiet_hours_lunch),
        Triple(23 * 60, 8 * 60, R.string.chat_quiet_hours_sleep),
        Triple(9 * 60, 18 * 60, R.string.chat_quiet_hours_workday)
    )
    val enabled = current.enabled
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_quiet_hours_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(
                        if (enabled) R.string.chat_quiet_hours_active_summary
                        else R.string.chat_quiet_hours_inactive_summary
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
                presets.forEach { (start, end, labelRes) ->
                    val selected = enabled && current.startMinute == start && current.endMinute == end
                    TextButton(
                        onClick = {
                            onPick(
                                com.maodouchat.notification.ChatQuietHoursStore.QuietWindow(
                                    enabled = true,
                                    startMinute = start,
                                    endMinute = end
                                )
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                    ) {
                        Text(stringResource(labelRes), color = if (selected) Primary else OnSurface, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (enabled) {
                    TextButton(
                        onClick = {
                            onPick(com.maodouchat.notification.ChatQuietHoursStore.QuietWindow.OFF)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.chat_quiet_hours_clear), color = LocalChatPalette.current.unreadRed, modifier = Modifier.fillMaxWidth())
                    }
                }
                Text(
                    stringResource(R.string.chat_quiet_hours_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        }
    )
}

@Composable
internal fun DisappearingMessagesDialog(
    selectedSeconds: Int,
    isUpdating: Boolean,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = DisappearingMessagePolicy.ALLOWED_SECONDS
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.disappear_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.disappear_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
                Text(
                    text = stringResource(R.string.disappear_limit_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textHint
                )
                Spacer(modifier = Modifier.height(4.dp))
                options.forEach { seconds ->
                    val selected = seconds == selectedSeconds
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isUpdating) { onSelect(seconds) }
                            .padding(vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (seconds == 0) Icons.Outlined.Schedule else Icons.Outlined.VisibilityOff,
                            contentDescription = null,
                            tint = if (selected) Primary else TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = disappearSecondsLabel(seconds),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) Primary else OnSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                if (isUpdating) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
internal fun disappearSecondsLabel(seconds: Int): String = when (seconds) {
    0 -> stringResource(R.string.disappear_off)
    30 -> stringResource(R.string.disappear_30s)
    60 -> stringResource(R.string.disappear_1m)
    2 * 60 -> stringResource(R.string.disappear_2m)
    5 * 60 -> stringResource(R.string.disappear_5m)
    15 * 60 -> stringResource(R.string.disappear_15m)
    60 * 60 -> stringResource(R.string.disappear_1h)
    2 * 60 * 60 -> stringResource(R.string.disappear_2h)
    4 * 60 * 60 -> stringResource(R.string.disappear_4h)
    8 * 60 * 60 -> stringResource(R.string.disappear_8h)
    12 * 60 * 60 -> stringResource(R.string.disappear_12h)
    24 * 60 * 60 -> stringResource(R.string.disappear_24h)
    7 * 24 * 60 * 60 -> stringResource(R.string.disappear_7d)
    30 * 24 * 60 * 60 -> stringResource(R.string.disappear_30d)
    else -> stringResource(R.string.disappear_off)
}


@Composable
