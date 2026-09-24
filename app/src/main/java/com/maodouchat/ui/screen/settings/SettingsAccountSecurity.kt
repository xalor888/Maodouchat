package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import com.maodouchat.security.findActivity
import com.maodouchat.util.RuntimeFlags
import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.maodouchat.network.ApiService
import com.maodouchat.network.DeviceInfoDto
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.network.TokenManager
import com.maodouchat.ui.screen.settings.SettingsViewModel
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.security.AppLockManager
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.security.ScreenSecureManager
import com.maodouchat.security.SensitiveAction
import com.maodouchat.security.SensitiveActionGate
import android.widget.Toast
import com.maodouchat.ui.theme.Error
import com.maodouchat.ui.theme.OnlineGreen
import com.maodouchat.ui.theme.UnreadRed
import com.maodouchat.ui.theme.LocalChatPalette
import java.util.Locale

/**
 * 账号安全一页（G98 从 `SettingsSubScreens.kt` 拆出，原 1428 行）。
 *
 * 安全中心：设备列表、E2EE 状态、应用锁、账号操作一页聚合。
 * 含它专属的一批小组件（设备行、安全状态卡、分区标签、信息行、操作行、
 * 删除账号对话框、修改密码对话框、密码强度指示、密码输入框）。
 *
 * **拆解约束**：不直接抓应用级数据库单例（`ui/` 红线，读库只能经 ViewModel/repository）；
 * E2EE 状态经 `MaodouchatApp.instance.signalProtocol` 只读查询，不落库。
 * 纯搬移，不改判断。
 */


@Composable
internal fun DeviceRow(
    device: DeviceInfoDto,
    currentDeviceId: Int,
    isRemoving: Boolean,
    isRenaming: Boolean,
    isConfirming: Boolean,
    isMutationInProgress: Boolean,
    canConfirm: Boolean,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onConfirm: () -> Unit
) {
    val isCurrent = device.isCurrent || device.deviceId == currentDeviceId
    val isPending = device.status == "PENDING"
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Smartphone, contentDescription = null, tint = if (isCurrent) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textSecondary, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (device.deviceName.isBlank()) stringResource(R.string.account_device_fallback, device.deviceId) else device.deviceName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isCurrent) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.account_current_device), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (isPending) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.account_pending_device), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Text(
                    stringResource(R.string.account_device_fingerprint, device.deviceId, device.identityKey.take(8), device.identityKey.takeLast(6)),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isPending) {
                    Text(
                        if (isCurrent) stringResource(R.string.account_pending_current_hint) else stringResource(R.string.account_pending_other_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onRename, enabled = !isMutationInProgress) {
                if (isRenaming) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                else Text(stringResource(R.string.account_rename_device), color = MaterialTheme.colorScheme.primary)
            }
            if (isPending && !isCurrent) {
                TextButton(onClick = onConfirm, enabled = canConfirm && !isMutationInProgress) {
                    if (isConfirming) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    else Text(stringResource(R.string.account_approve_device), color = MaterialTheme.colorScheme.primary)
                }
            }
            if (!isCurrent) {
                TextButton(onClick = onRemove, enabled = !isMutationInProgress) {
                    if (isRemoving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(if (isPending) stringResource(R.string.account_reject) else stringResource(R.string.chat_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SecurityStatusCard(
    e2eeReady: Boolean,
    appLockOn: Boolean,
    confirmedDeviceCount: Int,
    fingerprint: String?
) {
    val accent = if (e2eeReady) OnlineGreen else Error
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text(
            stringResource(R.string.security_center_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = LocalChatPalette.current.textSecondary
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(accent, RoundedCornerShape(5.dp))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(if (e2eeReady) R.string.security_e2ee_ready else R.string.security_e2ee_not_ready),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            stringResource(if (e2eeReady) R.string.security_e2ee_hint else R.string.security_e2ee_hint_degraded),
            style = MaterialTheme.typography.bodySmall,
            color = LocalChatPalette.current.textSecondary
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            stringResource(R.string.security_local_db_encrypted),
            style = MaterialTheme.typography.bodySmall,
            color = LocalChatPalette.current.textSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            stringResource(if (appLockOn) R.string.security_app_lock_on else R.string.security_app_lock_off),
            style = MaterialTheme.typography.bodySmall,
            color = LocalChatPalette.current.textSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        val shortFp = fingerprint
            ?.replace(" ", "")
            ?.take(8)
            ?.uppercase(Locale.US)
            ?: stringResource(R.string.security_fingerprint_unavailable)
        Text(
            pluralStringResource(R.plurals.security_devices_confirmed, confirmedDeviceCount, confirmedDeviceCount, shortFp),
            style = MaterialTheme.typography.bodySmall,
            color = LocalChatPalette.current.textSecondary
        )
    }
}

@Composable
internal fun SecuritySectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = LocalChatPalette.current.textSecondary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
    )
}

@Composable
internal fun SecurityGroup(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
    ) { content() }
}

@Composable
internal fun HorizontalDividerLite() {
    androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textSecondary, modifier = Modifier.width(108.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun ActionRow(label: String, subtitle: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .clickableRow(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else LocalChatPalette.current.textHint
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) LocalChatPalette.current.textSecondary else LocalChatPalette.current.textHint
                )
            }
        }
        Text("›", color = LocalChatPalette.current.textHint, fontSize = 18.sp)
    }
}

@Composable
internal fun Modifier.clickableRow(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.clickable(enabled = enabled) { onClick() }

@Composable
internal fun DeleteAccountDialog(
    password: String,
    isDeleting: Boolean,
    errorMessage: String?,
    onPasswordChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        title = { Text(stringResource(R.string.account_delete)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.account_delete_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
                PasswordField(label = stringResource(R.string.account_current_password), value = password, onValueChange = onPasswordChange)
                if (!errorMessage.isNullOrBlank()) Text(errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !isDeleting && password.isNotBlank()) {
                if (isDeleting) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.error)
                else Text(stringResource(R.string.account_confirm_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isDeleting) { Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary) } }
    )
}

@Composable
internal fun ChangePasswordDialog(
    oldPassword: String,
    newPassword: String,
    confirmPassword: String,
    isSaving: Boolean,
    errorMessage: String?,
    onOldChange: (String) -> Unit,
    onNewChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit
) {
    val strength = com.maodouchat.util.PasswordStrength.evaluate(newPassword)
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(R.string.account_change_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PasswordField(label = stringResource(R.string.account_old_password), value = oldPassword, onValueChange = onOldChange)
                PasswordField(label = stringResource(R.string.account_new_password), value = newPassword, onValueChange = onNewChange)
                if (newPassword.isNotEmpty()) {
                    PasswordStrengthIndicator(strength = strength)
                }
                PasswordField(label = stringResource(R.string.account_confirm_new_password), value = confirmPassword, onValueChange = onConfirmChange)
                if (!errorMessage.isNullOrBlank()) Text(errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !isSaving && strength.level != com.maodouchat.util.PasswordStrength.Level.WEAK) {
                if (isSaving) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                else Text(stringResource(R.string.common_save), color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary) } }
    )
}

@Composable
internal fun PasswordStrengthIndicator(strength: com.maodouchat.util.PasswordStrength.Result) {
    val suggestionEnter = stringResource(R.string.password_suggestion_enter)
    val suggestionLength = stringResource(R.string.password_suggestion_length)
    val suggestionDigit = stringResource(R.string.password_suggestion_digit)
    val suggestionLowercase = stringResource(R.string.password_suggestion_lowercase)
    val suggestionUppercase = stringResource(R.string.password_suggestion_uppercase)
    val suggestionLabels = remember(
        suggestionEnter,
        suggestionLength,
        suggestionDigit,
        suggestionLowercase,
        suggestionUppercase,
    ) {
        mapOf(
            com.maodouchat.util.PasswordStrength.Suggestion.ENTER_PASSWORD to suggestionEnter,
            com.maodouchat.util.PasswordStrength.Suggestion.USE_MINIMUM_LENGTH to suggestionLength,
            com.maodouchat.util.PasswordStrength.Suggestion.ADD_DIGIT to suggestionDigit,
            com.maodouchat.util.PasswordStrength.Suggestion.ADD_LOWERCASE to suggestionLowercase,
            com.maodouchat.util.PasswordStrength.Suggestion.ADD_UPPERCASE to suggestionUppercase,
        )
    }
    val (color, label) = when (strength.level) {
        com.maodouchat.util.PasswordStrength.Level.WEAK -> Error to stringResource(R.string.password_strength_weak)
        com.maodouchat.util.PasswordStrength.Level.FAIR -> com.maodouchat.ui.theme.UnreadRed to stringResource(R.string.password_strength_fair)
        com.maodouchat.util.PasswordStrength.Level.STRONG -> MaterialTheme.colorScheme.primary to stringResource(R.string.password_strength_strong)
        com.maodouchat.util.PasswordStrength.Level.VERY_STRONG -> MaterialTheme.colorScheme.primary to stringResource(R.string.password_strength_very_strong)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(stringResource(R.string.password_strength_label), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
            Text(label, style = MaterialTheme.typography.bodySmall, color = color)
        }
        // 简易强度条
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(4) { idx ->
                val filled = idx < strength.score
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(if (filled) color else LocalChatPalette.current.chatInputBorder, RoundedCornerShape(2.dp))
                )
            }
        }
        if (strength.suggestions.isNotEmpty() && strength.level != com.maodouchat.util.PasswordStrength.Level.VERY_STRONG) {
            Text(
                stringResource(
                    R.string.password_suggestions,
                    strength.suggestions.joinToString(", ") { suggestion ->
                        suggestionLabels[suggestion] ?: suggestion.name
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatPalette.current.textHint
            )
        }
    }
}

internal fun com.maodouchat.util.PasswordStrength.Suggestion.labelRes(): Int = when (this) {
    com.maodouchat.util.PasswordStrength.Suggestion.ENTER_PASSWORD -> R.string.password_suggestion_enter
    com.maodouchat.util.PasswordStrength.Suggestion.USE_MINIMUM_LENGTH -> R.string.password_suggestion_length
    com.maodouchat.util.PasswordStrength.Suggestion.ADD_DIGIT -> R.string.password_suggestion_digit
    com.maodouchat.util.PasswordStrength.Suggestion.ADD_LOWERCASE -> R.string.password_suggestion_lowercase
    com.maodouchat.util.PasswordStrength.Suggestion.ADD_UPPERCASE -> R.string.password_suggestion_uppercase
}

@Composable
internal fun PasswordField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = LocalChatPalette.current.chatInputBackground,
            unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
            cursorColor = MaterialTheme.colorScheme.primary
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun TotpSetupDialog(
    context: android.content.Context,
    token: String,
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
            val enabled = com.maodouchat.data.repository.TotpNetworkRepository().status(token).getOrDefault(false)
            alreadyEnabled = enabled
            if (!enabled) {
                com.maodouchat.data.repository.TotpNetworkRepository().setup(token)
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
                            com.maodouchat.data.repository.TotpNetworkRepository().regenerateBackupCodes(token, code.trim())
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
                            com.maodouchat.data.repository.TotpNetworkRepository().confirm(token, code.trim())
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
                                com.maodouchat.data.repository.TotpNetworkRepository().disable(token, code.trim())
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
