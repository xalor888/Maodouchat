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
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.core.model.DisappearingMessagePolicy
import com.maodouchat.core.model.QuietWindow
import com.maodouchat.domain.messaging.DisappearDurationLabel
import com.maodouchat.domain.messaging.DisappearingMessageLabel
import com.maodouchat.domain.messaging.QuietHoursPreset
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextSecondary

@Composable
internal fun ChatQuietHoursDialog(
    current: QuietWindow,
    onPick: (QuietWindow) -> Unit,
    onDismiss: () -> Unit
) {
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
                QuietHoursPreset.entries.forEach { preset ->
                    val selected = preset.matches(current)
                    TextButton(
                        onClick = { onPick(preset.toWindow()) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                    ) {
                        Text(stringResource(quietHoursPresetLabelRes(preset)), color = if (selected) Primary else OnSurface, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (enabled) {
                    TextButton(
                        onClick = {
                            onPick(QuietWindow.OFF)
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

private fun quietHoursPresetLabelRes(preset: QuietHoursPreset): Int = when (preset) {
    QuietHoursPreset.Night -> R.string.chat_quiet_hours_night
    QuietHoursPreset.Lunch -> R.string.chat_quiet_hours_lunch
    QuietHoursPreset.Sleep -> R.string.chat_quiet_hours_sleep
    QuietHoursPreset.Workday -> R.string.chat_quiet_hours_workday
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
internal fun disappearSecondsLabel(seconds: Int): String =
    when (DisappearingMessageLabel.disappearDurationLabel(seconds)) {
        DisappearDurationLabel.Off -> stringResource(R.string.disappear_off)
        DisappearDurationLabel.Seconds30 -> stringResource(R.string.disappear_30s)
        DisappearDurationLabel.Minute1 -> stringResource(R.string.disappear_1m)
        DisappearDurationLabel.Minute2 -> stringResource(R.string.disappear_2m)
        DisappearDurationLabel.Minute5 -> stringResource(R.string.disappear_5m)
        DisappearDurationLabel.Minute15 -> stringResource(R.string.disappear_15m)
        DisappearDurationLabel.Hour1 -> stringResource(R.string.disappear_1h)
        DisappearDurationLabel.Hour2 -> stringResource(R.string.disappear_2h)
        DisappearDurationLabel.Hour4 -> stringResource(R.string.disappear_4h)
        DisappearDurationLabel.Hour8 -> stringResource(R.string.disappear_8h)
        DisappearDurationLabel.Hour12 -> stringResource(R.string.disappear_12h)
        DisappearDurationLabel.Hour24 -> stringResource(R.string.disappear_24h)
        DisappearDurationLabel.Days7 -> stringResource(R.string.disappear_7d)
        DisappearDurationLabel.Days30 -> stringResource(R.string.disappear_30d)
    }
