package com.maodouchat.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.network.ApiService
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.security.SensitiveAction
import com.maodouchat.security.SensitiveActionGate
import com.maodouchat.ui.theme.LocalChatPalette
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import com.maodouchat.ui.theme.OnlineGreen
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 两步验证（TOTP 2FA）设置区（G125 从 `SettingsAccountSecurityScreen.kt` 拆出，原 261 行）。
 *
 * 内含加载状态、生成密钥（二维码 URI + 手动密钥）、确认启用、禁用整条链路，
 * 以及**会话守卫**：每次网络回调都重新校验 `BackgroundSessionGate.mayContinue`——
 * 换号/登出后迟到的响应不得改当前账号的 2FA 状态。
 *
 * **拆解约束**：不直接抓应用级数据库单例；网络经 `ApiService`，凭据经 `TokenManager`。
 * 纯搬移，不改判断。
 *
 * @param userId 当前用户 id（加载与守卫的基准）
 */
@Composable
internal fun SettingsTotpSection(userId: String) {
    val context = LocalContext.current
    // G228b：资源读取提升到 composable 作用域——onClick 等非 @Composable 回调里
    // 不能用 stringResource()，只能在组合时取好值再用。
    val disableTotpTitle = stringResource(R.string.settings_totp_disable)
    var totpEnabled by remember(userId) { mutableStateOf(false) }
    var totpBusy by remember(userId) { mutableStateOf(false) }
    var totpSecret by remember(userId) { mutableStateOf<String?>(null) }
    var totpUri by remember(userId) { mutableStateOf<String?>(null) }
    var totpCodeInput by rememberSaveable(userId) { mutableStateOf("") }
    var totpMessage by remember(userId) { mutableStateOf<String?>(null) }
    val totpScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    val sessionExpiredMessage = stringResource(R.string.error_session_expired)
    val totpLoadFailedMessage = stringResource(R.string.settings_totp_load_failed)
    val totpSetupHint = stringResource(R.string.settings_totp_setup_hint)
    val totpSetupFailedMessage = stringResource(R.string.settings_totp_setup_failed)
    val totpEnabledMessage = stringResource(R.string.settings_totp_enabled)
    val totpConfirmFailedMessage = stringResource(R.string.settings_totp_confirm_failed)
    val totpDisableCodeRequiredMessage = stringResource(R.string.settings_totp_disable_code_required)
    val totpDisabledMessage = stringResource(R.string.settings_totp_disabled)
    val totpDisableFailedMessage = stringResource(R.string.settings_totp_disable_failed)
    val totpSecretCopiedMessage = stringResource(R.string.settings_totp_secret_copied)
    val totpUriCopiedMessage = stringResource(R.string.settings_totp_uri_copied)

    fun isCurrentTotpOwner(expectedUserId: String): Boolean =
        BackgroundSessionGate.mayContinue(
            expectedUserId = expectedUserId,
        )

    LaunchedEffect(userId) {
        val ownerUserId = userId
        if (ownerUserId.isBlank()) return@LaunchedEffect
        if (!isCurrentTotpOwner(ownerUserId)) return@LaunchedEffect
        totpBusy = true
        try {
            com.maodouchat.data.repository.TotpNetworkRepository().statusRaw().fold(
                onSuccess = { raw ->
                    if (!isCurrentTotpOwner(ownerUserId)) return@fold
                    runCatching { org.json.JSONObject(raw).optBoolean("enabled", false) }
                        .onSuccess { enabled -> totpEnabled = enabled }
                        .onFailure { totpMessage = totpLoadFailedMessage }
                },
                onFailure = {
                    if (isCurrentTotpOwner(ownerUserId)) totpMessage = it.message ?: totpLoadFailedMessage
                }
            )
        } finally {
            if (isCurrentTotpOwner(ownerUserId)) {
                totpBusy = false
            }
        }
    }

    LaunchedEffect(userId) {
        val ownerUserId = userId
        if (ownerUserId.isBlank()) return@LaunchedEffect
        if (!isCurrentTotpOwner(ownerUserId)) return@LaunchedEffect
        totpBusy = true
        try {
            com.maodouchat.data.repository.TotpNetworkRepository().statusRaw().fold(
                onSuccess = { raw ->
                    if (!isCurrentTotpOwner(ownerUserId)) return@fold
                    runCatching { org.json.JSONObject(raw).optBoolean("enabled", false) }
                        .onSuccess { enabled -> totpEnabled = enabled }
                        .onFailure { totpMessage = totpLoadFailedMessage }
                },
                onFailure = {
                    if (isCurrentTotpOwner(ownerUserId)) totpMessage = it.message ?: totpLoadFailedMessage
                }
            )
        } finally {
            if (isCurrentTotpOwner(ownerUserId)) {
                totpBusy = false
            }
        }
    }

            // TOTP 2FA
            Spacer(modifier = Modifier.height(12.dp))
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.settings_totp_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.settings_totp_desc), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                    Text(
                        stringResource(if (totpEnabled) R.string.settings_totp_status_enabled else R.string.settings_totp_status_disabled),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (totpEnabled) OnlineGreen else LocalChatPalette.current.textSecondary
                    )
                    totpMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    totpSecret?.let { secret ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.settings_totp_secret, secret),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                @Suppress("DEPRECATION")
                                clipboardManager.setText(AnnotatedString(secret))
                                totpMessage = totpSecretCopiedMessage
                            }) {
                                Icon(
                                    Icons.Outlined.ContentCopy,
                                    contentDescription = stringResource(R.string.settings_totp_copy_secret),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        totpUri?.let { uri ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    uri,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LocalChatPalette.current.textHint,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    @Suppress("DEPRECATION")
                                    clipboardManager.setText(AnnotatedString(uri))
                                    totpMessage = totpUriCopiedMessage
                                }) {
                                    Icon(
                                        Icons.Outlined.ContentCopy,
                                        contentDescription = stringResource(R.string.settings_totp_copy_uri),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = totpCodeInput,
                            onValueChange = { totpCodeInput = it.filter(Char::isDigit).take(6) },
                            label = { Text(stringResource(R.string.login_totp_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                if (totpBusy) return@Button
                                val ownerUserId = userId
                                if (!isCurrentTotpOwner(ownerUserId)) {
                                    totpMessage = sessionExpiredMessage
                                    return@Button
                                }
                                val code = totpCodeInput
                                totpBusy = true
                                totpScope.launch {
                                    try {
                                        val result = com.maodouchat.data.repository.TotpNetworkRepository().confirm(code = code)
                                        if (!isCurrentTotpOwner(ownerUserId)) return@launch
                                        result.onSuccess {
                                            totpEnabled = true
                                            totpSecret = null
                                            totpUri = null
                                            totpCodeInput = ""
                                            totpMessage = totpEnabledMessage
                                        }.onFailure {
                                            totpMessage = it.message ?: totpConfirmFailedMessage
                                        }
                                    } finally {
                                        if (isCurrentTotpOwner(ownerUserId)) totpBusy = false
                                    }
                                }
                            },
                            enabled = !totpBusy && totpCodeInput.length == 6,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.settings_totp_confirm_enable)) }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (totpBusy) return@Button
                                val ownerUserId = userId
                                if (!isCurrentTotpOwner(ownerUserId)) {
                                    totpMessage = sessionExpiredMessage
                                    return@Button
                                }
                                totpBusy = true
                                totpScope.launch {
                                    try {
                                        val result = com.maodouchat.data.repository.TotpNetworkRepository().setup()
                                        if (!isCurrentTotpOwner(ownerUserId)) return@launch
                                        result.onSuccess { raw ->
                                            runCatching { org.json.JSONObject(raw) }
                                                .onSuccess { payload ->
                                                    val secret = payload.optString("secret").trim()
                                                    val uri = payload.optString("otpauthUrl").trim()
                                                    if (secret.isBlank() || uri.isBlank()) {
                                                        totpSecret = null
                                                        totpUri = null
                                                        totpMessage = totpSetupFailedMessage
                                                    } else {
                                                        totpSecret = secret
                                                        totpUri = uri
                                                        totpMessage = totpSetupHint
                                                    }
                                                }
                                                .onFailure { totpMessage = totpSetupFailedMessage }
                                        }.onFailure {
                                            totpMessage = it.message ?: totpSetupFailedMessage
                                        }
                                    } finally {
                                        if (isCurrentTotpOwner(ownerUserId)) totpBusy = false
                                    }
                                }
                            },
                            enabled = !totpBusy && !totpEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.settings_totp_setup)) }
                        Button(
                            onClick = {
                                if (totpBusy) return@Button
                                if (totpCodeInput.length != 6) {
                                    totpMessage = totpDisableCodeRequiredMessage
                                    return@Button
                                }
                                val ownerUserId = userId
                                if (!isCurrentTotpOwner(ownerUserId)) {
                                    totpMessage = sessionExpiredMessage
                                    return@Button
                                }
                                val code = totpCodeInput
                                // 9.140：关闭 2FA 属破坏性安全操作——与注销/删号/关闭 App 锁一致，
                                // 先过 SensitiveActionGate step-up（App 锁 + 敏感操作验证开启时）
                                com.maodouchat.security.SensitiveActionGate.confirm(
                                    context = context,
                                    action = com.maodouchat.security.SensitiveAction.DISABLE_TOTP,
                                    title = disableTotpTitle,
                                    onSuccess = {
                                        totpBusy = true
                                        totpScope.launch {
                                            try {
                                                val disable = com.maodouchat.data.repository.TotpNetworkRepository().disable(code = code)
                                                if (!isCurrentTotpOwner(ownerUserId)) return@launch
                                                disable.onSuccess {
                                                    totpEnabled = false
                                                    totpSecret = null
                                                    totpUri = null
                                                    totpCodeInput = ""
                                                    totpMessage = totpDisabledMessage
                                                }.onFailure {
                                                    totpMessage = it.message ?: totpDisableFailedMessage
                                                }
                                            } finally {
                                                if (isCurrentTotpOwner(ownerUserId)) totpBusy = false
                                            }
                                        }
                                    },
                                )
                            },
                            enabled = !totpBusy && totpEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.settings_totp_disable)) }
                    }
                }
            }}
