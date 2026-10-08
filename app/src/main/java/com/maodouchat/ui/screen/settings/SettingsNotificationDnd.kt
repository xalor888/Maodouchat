package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maodouchat.ui.theme.LocalChatPalette

@Composable
private fun DndTimeRow(label: String, minute: Int, enabled: Boolean, onPick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onPick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(formatDndTime(minute), style = MaterialTheme.typography.bodyMedium, color = if (enabled) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textSecondary)
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = LocalChatPalette.current.textSecondary, modifier = Modifier.size(16.dp))
    }
}

private fun formatDndTime(minuteOfDay: Int): String {
    val safe = minuteOfDay.coerceIn(0, 1439)
    val h = safe / 60
    val m = safe % 60
    return "%02d:%02d".format(h, m)
}
