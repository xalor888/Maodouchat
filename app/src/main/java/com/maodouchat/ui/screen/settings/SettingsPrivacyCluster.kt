package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maodouchat.network.UserDto
import com.maodouchat.R
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize

@Composable
internal fun PrivacyDialog(
    showOnline: Boolean,
    onlineVisibility: String,
    showStatus: Boolean,
    searchable: Boolean,
    defaultPostVisibility: String,
    visibilityOptions: List<Pair<String, String>>,
    isSaving: Boolean,
    onShowOnlineChange: (Boolean) -> Unit,
    onOnlineVisibilityChange: (String) -> Unit,
    onShowStatusChange: (Boolean) -> Unit,
    onSearchableChange: (Boolean) -> Unit,
    onDefaultVisibilityChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(R.string.settings_privacy)) },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.privacy_show_online_title), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.privacy_online_visibility_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "everyone" to stringResource(R.string.privacy_online_everyone),
                        "contacts" to stringResource(R.string.privacy_online_contacts),
                        "nobody" to stringResource(R.string.privacy_online_nobody)
                    ).forEach { (id, label) ->
                        FilterChip(
                            selected = onlineVisibility == id,
                            onClick = { onOnlineVisibilityChange(id) },
                            enabled = !isSaving,
                            label = { Text(label) }
                        )
                    }
                }
                PrivacySwitchRow(
                    title = stringResource(R.string.privacy_show_status_title),
                    subtitle = stringResource(R.string.privacy_show_status_subtitle),
                    checked = showStatus,
                    enabled = !isSaving,
                    onCheckedChange = onShowStatusChange
                )
                PrivacySwitchRow(
                    title = stringResource(R.string.privacy_searchable_title),
                    subtitle = stringResource(R.string.privacy_searchable_subtitle),
                    checked = searchable,
                    enabled = !isSaving,
                    onCheckedChange = onSearchableChange
                )
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.privacy_default_post_visibility), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        visibilityOptions.forEach { (value, _) ->
                            FilterChip(
                                selected = defaultPostVisibility == value,
                                enabled = !isSaving,
                                onClick = { onDefaultVisibilityChange(value) },
                                label = { Text(privacyVisibilityLabel(value)) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = !isSaving) {
                if (isSaving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
private fun PrivacySwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun BlockedUsersDialog(
    blockedUsers: List<UserDto>,
    isLoading: Boolean,
    isUpdating: Boolean,
    onUnblock: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(blockedUsers, query) {
        val q = query.trim()
        if (q.isEmpty()) blockedUsers
        else blockedUsers.filter { user ->
            user.name.contains(q, ignoreCase = true) ||
                user.id.contains(q, ignoreCase = true) ||
                user.status.contains(q, ignoreCase = true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_blocked_users)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                when {
                    isLoading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.blocked_loading), color = LocalChatPalette.current.textSecondary)
                        }
                    }
                    blockedUsers.isEmpty() -> Text(stringResource(R.string.blocked_empty), color = LocalChatPalette.current.textSecondary)
                    else -> {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.blocked_search_hint)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (filtered.isEmpty()) {
                            Text(stringResource(R.string.blocked_search_empty), color = LocalChatPalette.current.textSecondary)
                        } else {
                            filtered.forEach { user ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Avatar(name = user.name, avatarUrl = user.avatar, size = AvatarSize.SM, isOnline = user.isOnline)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(user.name.ifBlank { user.id }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            listOf(user.id, user.status.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LocalChatPalette.current.textHint
                                        )
                                    }
                                    TextButton(enabled = !isUpdating, onClick = { onUnblock(user.id) }) {
                                        Text(stringResource(R.string.blocked_unblock))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onRefresh, enabled = !isLoading && !isUpdating) { Text(stringResource(R.string.common_refresh)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isUpdating) { Text(stringResource(R.string.common_done)) } }
    )
}

@Composable
private fun privacyVisibilityLabel(value: String): String = when (value) {
    "CONTACTS" -> stringResource(R.string.explore_visibility_contacts)
    "PRIVATE" -> stringResource(R.string.explore_visibility_private)
    else -> stringResource(R.string.explore_visibility_public)
}
