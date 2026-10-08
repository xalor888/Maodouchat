package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette

@Composable
internal fun TotpSetupDialog(
    context: android.content.Context,
    onDismiss: () -> Unit
) {
    var secret by remember { mutableStateOf<String?>(null) }
    var otpauthUrl by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var backupCodes by remember { mutableStateOf<List<String>?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /** 0.77：null=检查中；true=已启用（进入重新生成/禁用模式）；false=未启用 */
    var alreadyEnabled by remember { mutableStateOf<Boolean?>(null) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (secret == null) {
            isWorking = true
            // 0.77：先查状态——已启用则不重新 setup，进入恢复码/禁用模式
            val enabled = com.maodouchat.data.repository.TotpNetworkRepository().status().getOrDefault(false)
            alreadyEnabled = enabled
            if (!enabled) {
                com.maodouchat.data.repository.TotpNetworkRepository().setup()
                    .onSuccess { body ->
                        val obj = runCatching { org.json.JSONObject(body) }.getOrNull()
                        secret = obj?.optString("secret").orEmpty().takeIf { it.isNotBlank() }
                        otpauthUrl = obj?.optString("otpauthUrl")?.takeIf { it.isNotBlank() }
                        if (secret == null) error = context.getString(com.maodouchat.R.string.totp_setup_error)
                    }
                    .onFailure { error = context.getString(com.maodouchat.R.string.totp_setup_error) }
            }
            isWorking = false
        }
    }

    val confirmEnabled = code.trim().length == 6 && !isWorking
    AlertDialog(
        onDismissRequest = { if (!isWorking) onDismiss() },
        title = {
            Text(
                stringResource(
                    when {
                        backupCodes != null -> com.maodouchat.R.string.totp_backup_codes_title
                        alreadyEnabled == true -> com.maodouchat.R.string.totp_enabled_title
                        else -> com.maodouchat.R.string.totp_setup_title
                    }
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.unreadRed) }
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), color = MaterialTheme.colorScheme.primary)
                } else if (backupCodes != null) {
                    Text(
                        stringResource(com.maodouchat.R.string.totp_backup_codes_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textSecondary
                    )
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(backupCodes.orEmpty()) { codeItem ->
                            Text(
                                text = codeItem,
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                } else if (alreadyEnabled == true) {
                    // 0.77：已启用——输入当前验证码可重新生成恢复码或禁用
                    Text(
                        stringResource(com.maodouchat.R.string.totp_enabled_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textSecondary
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(6) },
                        placeholder = { Text(stringResource(com.maodouchat.R.string.totp_setup_code_placeholder)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    secret?.let { s ->
                        Text(stringResource(com.maodouchat.R.string.totp_setup_secret_label), style = MaterialTheme.typography.labelMedium, color = LocalChatPalette.current.textSecondary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = s,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(s)) }) {
                                Text(stringResource(com.maodouchat.R.string.common_copy), color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Text(
                            stringResource(com.maodouchat.R.string.totp_setup_scan_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalChatPalette.current.textSecondary
                        )
                    }
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(6) },
                        placeholder = { Text(stringResource(com.maodouchat.R.string.totp_setup_code_placeholder)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            if (backupCodes != null) {
                TextButton(onClick = onDismiss) { Text(stringResource(com.maodouchat.R.string.totp_backup_saved), color = MaterialTheme.colorScheme.primary) }
            } else if (alreadyEnabled == true) {
                // 0.77：重新生成恢复码（旧码作废）
                TextButton(
                    enabled = confirmEnabled,
                    onClick = {
                        isWorking = true
                        error = null
                        scope.launch {
                            com.maodouchat.data.repository.TotpNetworkRepository().regenerateBackupCodes(code = code.trim())
                                .onSuccess { codes ->
                                    backupCodes = codes
                                    code = ""
                                }
                                .onFailure { error = context.getString(com.maodouchat.R.string.totp_setup_error) }
                            isWorking = false
                        }
                    }
                ) { Text(stringResource(com.maodouchat.R.string.totp_regenerate_codes), color = MaterialTheme.colorScheme.primary) }
            } else {
                TextButton(
                    enabled = confirmEnabled,
                    onClick = {
                        isWorking = true
                        error = null
                        scope.launch {
                            com.maodouchat.data.repository.TotpNetworkRepository().confirm(code = code.trim())
                                .onSuccess { codes ->
                                    backupCodes = codes
                                    code = ""
                                }
                                .onFailure { error = context.getString(com.maodouchat.R.string.totp_setup_error) }
                            isWorking = false
                        }
                    }
                ) { Text(stringResource(com.maodouchat.R.string.totp_setup_confirm), color = MaterialTheme.colorScheme.primary) }
            }
        },
        dismissButton = {
            if (backupCodes == null) {
                if (alreadyEnabled == true) {
                    TextButton(
                        onClick = {
                            isWorking = true
                            error = null
                            scope.launch {
                                com.maodouchat.data.repository.TotpNetworkRepository().disable(code = code.trim())
                                    .onSuccess { onDismiss() }
                                    .onFailure { error = context.getString(com.maodouchat.R.string.totp_setup_error) }
                                isWorking = false
                            }
                        }
                    ) { Text(stringResource(com.maodouchat.R.string.totp_disable), color = LocalChatPalette.current.unreadRed) }
                } else {
                    TextButton(onClick = onDismiss) { Text(stringResource(com.maodouchat.R.string.common_cancel), color = LocalChatPalette.current.textSecondary) }
                }
            }
        }
    )
}
