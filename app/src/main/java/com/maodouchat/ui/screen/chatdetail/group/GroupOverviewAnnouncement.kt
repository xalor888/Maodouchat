package com.maodouchat.ui.screen.chatdetail.group

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette

@Composable
fun GroupAnnouncementCard(
    announcement: String,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable(announcement) { mutableStateOf(false) }
    var overflowsFourLines by remember(announcement) { mutableStateOf(false) }
    val canToggle = expanded || overflowsFourLines

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(enabled = canToggle) { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Campaign,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.group_detail_announcement),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = announcement.ifBlank { stringResource(R.string.group_detail_no_announcement) },
                style = MaterialTheme.typography.bodyMedium,
                color = if (announcement.isBlank()) LocalChatPalette.current.textHint else MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result ->
                    if (!expanded) overflowsFourLines = result.hasVisualOverflow
                }
            )
            if (canToggle) {
                Text(
                    text = stringResource(if (expanded) R.string.chat_transcript_collapse else R.string.chat_transcript_expand),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
fun AnnouncementRow(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSave: () -> Unit,
    saveEnabled: Boolean,
    canManage: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp, 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.group_detail_announcement), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        TextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            minLines = 3,
            maxLines = 6,
            placeholder = { Text(if (canManage) stringResource(R.string.group_detail_announcement_input) else stringResource(R.string.group_detail_no_announcement)) },
            modifier = Modifier.fillMaxWidth(),
            colors = groupTextFieldColors(),
            shape = RoundedCornerShape(10.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${value.length}/1200", style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint, modifier = Modifier.weight(1f))
            if (canManage) {
                TextButton(onClick = { onValueChange("") }, enabled = enabled && value.isNotBlank()) { Text(stringResource(R.string.common_clear)) }
                Spacer(modifier = Modifier.width(6.dp))
                Button(onClick = onSave, enabled = saveEnabled) { Text(stringResource(R.string.group_detail_save_announcement)) }
            }
        }
    }
}
