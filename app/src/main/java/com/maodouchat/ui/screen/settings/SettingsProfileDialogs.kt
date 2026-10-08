package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.MaodouDimens
import com.maodouchat.ui.theme.LocalChatPalette

/** 资料页的编辑对话框，从 SettingsProfileCluster.kt 按簇搬出。 */

@Composable
internal fun StatusEditorDialog(
    status: String,
    isSaving: Boolean,
    errorMessage: String?,
    onStatusChange: (String) -> Unit,
    onPreset: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(R.string.status_title)) },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.status_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                OutlinedTextField(
                    value = status,
                    onValueChange = onStatusChange,
                    placeholder = { Text(stringResource(R.string.status_hint)) },
                    singleLine = false,
                    maxLines = 3,
                    shape = RoundedCornerShape(MaodouDimens.SmallRadius),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    supportingText = {
                        Text(
                            "${status.length}/${com.maodouchat.util.CustomStatusPolicy.MAX_LENGTH}",
                            color = if (status.length > com.maodouchat.util.CustomStatusPolicy.MAX_LENGTH - 10) MaterialTheme.colorScheme.error else LocalChatPalette.current.textSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(stringResource(R.string.status_presets), style = MaterialTheme.typography.labelLarge, color = LocalChatPalette.current.textSecondary)
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    com.maodouchat.util.CustomStatusPolicy.PRESETS.chunked(3).forEach { row ->
                        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                            row.forEach { preset ->
                                FilterChip(
                                    selected = status == preset,
                                    onClick = { onPreset(preset) },
                                    label = { Text(com.maodouchat.ui.component.localizedCustomStatusLabel(preset)) }
                                )
                            }
                        }
                    }
                }
                if (!errorMessage.isNullOrBlank()) {
                    Text(errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = !isSaving) {
                if (isSaving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear, enabled = !isSaving && status.isNotEmpty()) {
                    Text(stringResource(R.string.status_clear), color = LocalChatPalette.current.textSecondary)
                }
                TextButton(onClick = onDismiss, enabled = !isSaving) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        }
    )
}



@Composable
internal fun UsernameEditorDialog(
    username: String,
    isSaving: Boolean,
    errorMessage: String?,
    onUsernameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(R.string.settings_username_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.settings_username_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    placeholder = { Text(stringResource(R.string.settings_username_placeholder)) },
                    singleLine = true,
                    leadingIcon = { Text("@", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary) },
                    shape = RoundedCornerShape(MaodouDimens.SmallRadius),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    supportingText = {
                        Text(
                            "${username.length}/50",
                            color = if (username.length > 45) MaterialTheme.colorScheme.error else LocalChatPalette.current.textSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                errorMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    // 跟随当前服务器地址拼链接，自建部署不显示错误域名。
                    stringResource(
                        R.string.settings_username_profile_url,
                        com.maodouchat.network.ApiConfig.BASE_URL.removePrefix("https://").removePrefix("http://").trimEnd('/') + "/u/" + username
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (username.length >= 3) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = username.length >= 3 && !isSaving) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text(stringResource(R.string.common_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
