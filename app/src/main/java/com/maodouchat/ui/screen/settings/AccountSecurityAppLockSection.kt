package com.maodouchat.ui.screen.settings

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.MainActivity
import com.maodouchat.R
import com.maodouchat.security.AppLockManager
import com.maodouchat.security.ScreenSecureManager
import com.maodouchat.security.SensitiveAction
import com.maodouchat.security.SensitiveActionGate
import com.maodouchat.security.findActivity
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.util.RuntimeFlags

/** 应用锁分区：应用锁开关/超时、敏感操作门禁、防截屏（从 SettingsAccountSecurityScreen.kt 纯搬移；appLockEnabled 留母文件走参数）。 */
@Composable
internal fun AccountSecurityAppLockSection(
    userId: String,
    appLockEnabled: Boolean,
    onAppLockEnabledChange: (Boolean) -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val appLockUnavailableMsg = stringResource(R.string.settings_app_lock_unavailable)
    var appLockTimeout by remember(userId) { mutableLongStateOf(AppLockManager.getTimeoutMinutes(context)) }
    val appLockRuntimeOn = remember { RuntimeFlags.isEnabled(context, RuntimeFlags.APP_LOCK) }
    var appLockError by remember(userId) { mutableStateOf<String?>(null) }
    var sensitiveGateEnabled by remember(userId) { mutableStateOf(SensitiveActionGate.isEnabled(context)) }
    var screenSecureEnabled by remember(userId) { mutableStateOf(ScreenSecureManager.isEnabled(context)) }

    Spacer(modifier = Modifier.height(16.dp))
    SecuritySectionLabel(stringResource(R.string.security_section_lock))
    // App 锁定分组 — 轻量化安全功能，复用设备已有 PIN / 图案 / 指纹，应用不接触凭据数据
    val sensitiveAuthTitle = stringResource(R.string.sensitive_auth_title)
    val sensitiveAuthDisableLock = stringResource(R.string.sensitive_auth_disable_app_lock)
    val sensitiveAuthFailed = stringResource(R.string.sensitive_auth_failed)
    SecurityGroup {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_app_lock), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.settings_app_lock_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                }
                Switch(
                    checked = appLockEnabled,
                    onCheckedChange = { enabled ->
                        if (!enabled && appLockEnabled) {
                            SensitiveActionGate.confirm(
                                context = context,
                                action = SensitiveAction.DISABLE_APP_LOCK,
                                title = sensitiveAuthTitle,
                                subtitle = sensitiveAuthDisableLock,
                                onSuccess = {
                                    val changed = AppLockManager.setEnabled(context, false)
                                    if (changed) {
                                        onAppLockEnabledChange(false)
                                        appLockError = null
                                    } else {
                                        onAppLockEnabledChange(AppLockManager.isEnabled(context))
                                        appLockError = appLockUnavailableMsg
                                    }
                                },
                                onFailure = { msg ->
                                    Toast.makeText(
                                        context,
                                        msg?.takeIf { it.isNotBlank() } ?: sensitiveAuthFailed,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        } else {
                            if (!appLockRuntimeOn && enabled) {
                                onAppLockEnabledChange(false)
                                return@Switch
                            }
                            val changed = AppLockManager.setEnabled(context, enabled)
                            if (changed) {
                                onAppLockEnabledChange(enabled)
                                appLockError = null
                                if (enabled) {
                                    sensitiveGateEnabled = SensitiveActionGate.isEnabled(context)
                                }
                            } else {
                                onAppLockEnabledChange(AppLockManager.isEnabled(context))
                                appLockError = appLockUnavailableMsg
                            }
                        }
                    }
                )
            }
            appLockError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            if (appLockEnabled) {
                HorizontalDivider(color = LocalChatPalette.current.textHint.copy(alpha = 0.25f))
                Text(
                    stringResource(R.string.settings_app_lock_timeout),
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalChatPalette.current.textSecondary,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp)
                )
                val timeoutOptions = listOf(
                    1L to R.string.settings_app_lock_timeout_1min,
                    2L to R.string.settings_app_lock_timeout_2min,
                    5L to R.string.settings_app_lock_timeout_5min,
                    10L to R.string.settings_app_lock_timeout_10min,
                    15L to R.string.settings_app_lock_timeout_15min,
                    30L to R.string.settings_app_lock_timeout_30min,
                    60L to R.string.settings_app_lock_timeout_60min,
                    120L to R.string.settings_app_lock_timeout_120min,
                    240L to R.string.settings_app_lock_timeout_240min,
                    360L to R.string.settings_app_lock_timeout_360min
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    timeoutOptions.forEach { (minutes, label) ->
                        TextButton(onClick = {
                            appLockTimeout = minutes
                            AppLockManager.setTimeoutMinutes(context, minutes)
                            viewModel.pushSecurityClientPrefs(appLockTimeoutMinutes = minutes)
                        }) {
                            Text(
                                stringResource(label),
                                color = if (appLockTimeout == minutes) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textSecondary,
                                fontWeight = if (appLockTimeout == minutes) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
                HorizontalDivider(color = LocalChatPalette.current.textHint.copy(alpha = 0.25f))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_sensitive_gate),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            stringResource(R.string.settings_sensitive_gate_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalChatPalette.current.textSecondary
                        )
                    }
                    Switch(
                        checked = sensitiveGateEnabled,
                        onCheckedChange = { enabled ->
                            SensitiveActionGate.setEnabled(context, enabled)
                            sensitiveGateEnabled = enabled
                            viewModel.pushSecurityClientPrefs(sensitiveGateEnabled = enabled)
                        }
                    )
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    SecurityGroup {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_screen_secure),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    stringResource(R.string.settings_screen_secure_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
            }
            Switch(
                checked = screenSecureEnabled,
                onCheckedChange = { enabled ->
                    ScreenSecureManager.setEnabled(context, enabled)
                    screenSecureEnabled = enabled
                    viewModel.pushSecurityClientPrefs(screenSecureEnabled = enabled)
                    (context.findActivity() as? MainActivity)
                        ?.notifyScreenSecurePreferenceChanged()
                }
            )
        }
    }
}
