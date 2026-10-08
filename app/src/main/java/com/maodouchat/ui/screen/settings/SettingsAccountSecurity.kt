package com.maodouchat.ui.screen.settings


/**
 * 账号安全一页（G98 从 `SettingsSubScreens.kt` 拆出，原 1428 行）。
 *
 * 安全中心：设备列表、E2EE 状态、应用锁、账号操作一页聚合。
 * 展示行组件簇（设备行、安全状态卡、分区标签、信息行、操作行）已于 2026-10-08
 * 按簇拆至同包 `AccountSecurityDisplayRows.kt`；TOTP 设置对话框已拆至同包 `SettingsTotpDialog.kt`，其余对话框留守本文件。
 *
 * **拆解约束**：不直接抓应用级数据库单例（`ui/` 红线，读库只能经 ViewModel/repository）；
 * E2EE 状态经 `MaodouchatApp.instance.signalProtocol` 只读查询，不落库。
 * 纯搬移，不改判断。
 */



import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.maodouchat.R
import com.maodouchat.ui.theme.Error
import com.maodouchat.ui.theme.UnreadRed
import com.maodouchat.ui.theme.LocalChatPalette

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
