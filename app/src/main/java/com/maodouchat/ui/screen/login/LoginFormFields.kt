package com.maodouchat.ui.screen.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.MaodouDimens
import com.maodouchat.util.PasswordStrength

// 登录表单字段区：按 tab 切换用户名 / 邮箱 / TOTP / 验证码 / 密码（含强度条）与错误提示
@Composable
internal fun LoginFormFields(
    state: LoginUiState,
    onNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onTotpCodeChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPasswordConfirmChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onSendVerificationCode: () -> Unit,
) {
    // 注册模式：用户名
    if (state.selectedTab == 1) {
        OutlinedTextField(
            value = state.name, onValueChange = { onNameChange(it) },
            placeholder = { Text(stringResource(R.string.username), color = LocalChatPalette.current.textHint) },
            leadingIcon = { Icon(Icons.Outlined.Person, null, tint = MaterialTheme.colorScheme.outline) },
            singleLine = true, shape = RoundedCornerShape(MaodouDimens.ControlRadius),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = LocalChatPalette.current.chatInputBackground, unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
                cursorColor = MaterialTheme.colorScheme.primary, focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface
            ), modifier = Modifier.fillMaxWidth()
        )
    }

    // 邮箱
    OutlinedTextField(
        value = state.email, onValueChange = { onEmailChange(it) },
        placeholder = { Text(stringResource(R.string.email_address), color = LocalChatPalette.current.textHint) },
        leadingIcon = { Icon(Icons.Outlined.Email, null, tint = MaterialTheme.colorScheme.outline) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        shape = RoundedCornerShape(MaodouDimens.ControlRadius),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = LocalChatPalette.current.chatInputBackground, unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
            focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
            cursorColor = MaterialTheme.colorScheme.primary, focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface
        ), modifier = Modifier.fillMaxWidth()
    )

    if (state.selectedTab == 0 && state.requiresTotp) {
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = state.totpCode,
            onValueChange = { onTotpCodeChange(it) },
            placeholder = { Text(stringResource(R.string.login_totp_label), color = LocalChatPalette.current.textHint) },
            leadingIcon = { Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.outline) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            shape = RoundedCornerShape(MaodouDimens.ControlRadius),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }

    // 注册 / 找回密码：验证码
    if (state.selectedTab == 1 || state.selectedTab == 2) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = state.code, onValueChange = { onCodeChange(it) },
                placeholder = { Text(stringResource(R.string.verification_code), color = LocalChatPalette.current.textHint) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(MaodouDimens.ControlRadius),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = LocalChatPalette.current.chatInputBackground, unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                    focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
                    cursorColor = MaterialTheme.colorScheme.primary, focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                ), modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = { onSendVerificationCode() },
                enabled = !state.isCodeSending && state.codeCountdown == 0
            ) {
                if (state.isCodeSending) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (state.codeCountdown > 0) {
                    Text("${state.codeCountdown}s", color = LocalChatPalette.current.textSecondary)
                } else {
                    Text(stringResource(R.string.send_verification_code), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    // 密码（找回密码时为新密码）
    OutlinedTextField(
        value = state.password, onValueChange = { onPasswordChange(it) },
        placeholder = {
            Text(
                if (state.selectedTab == 2) stringResource(R.string.new_password) else stringResource(R.string.password),
                color = LocalChatPalette.current.textHint
            )
        },
        leadingIcon = { Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.outline) },
        trailingIcon = {
            IconButton(onClick = { onTogglePasswordVisibility() }) {
                Icon(if (state.passwordVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    if (state.passwordVisible) stringResource(R.string.hide_password) else stringResource(R.string.show_password), tint = MaterialTheme.colorScheme.outline)
            }
        },
        singleLine = true,
        visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = RoundedCornerShape(MaodouDimens.ControlRadius),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = LocalChatPalette.current.chatInputBackground, unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
            focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
            cursorColor = MaterialTheme.colorScheme.primary, focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface
        ), modifier = Modifier.fillMaxWidth()
    )

    // 注册：确认密码
    if (state.selectedTab == 1) {
        OutlinedTextField(
            value = state.passwordConfirm,
            onValueChange = { onPasswordConfirmChange(it) },
            placeholder = { Text(stringResource(R.string.login_confirm_password), color = LocalChatPalette.current.textHint) },
            leadingIcon = { Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.outline) },
            singleLine = true,
            visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(MaodouDimens.ControlRadius),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }

    // 注册 / 找回：密码强度提示
    if ((state.selectedTab == 1 || state.selectedTab == 2) && state.password.isNotEmpty()) {
        val strength = remember(state.password) {
            PasswordStrength.evaluate(state.password)
        }
        val strengthColor = when (strength.level) {
            PasswordStrength.Level.WEAK -> MaterialTheme.colorScheme.error
            PasswordStrength.Level.FAIR -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        }
        val strengthLabel = when (strength.level) {
            PasswordStrength.Level.WEAK -> stringResource(R.string.password_strength_weak)
            PasswordStrength.Level.FAIR -> stringResource(R.string.password_strength_fair)
            PasswordStrength.Level.STRONG -> stringResource(R.string.password_strength_strong)
            PasswordStrength.Level.VERY_STRONG -> stringResource(R.string.password_strength_very_strong)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.password_strength_label), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                Text(strengthLabel, style = MaterialTheme.typography.bodySmall, color = strengthColor)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                repeat(4) { idx ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .background(
                                if (idx < strength.score) strengthColor else LocalChatPalette.current.chatInputBorder,
                                RoundedCornerShape(2.dp)
                            )
                    )
                }
            }
        }
    }

    // 错误 / 成功提示
    state.errorMessage?.let { msg ->
            Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    state.infoMessage?.let { msg ->
            Text(msg, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }

    Spacer(modifier = Modifier.height(8.dp))
}
