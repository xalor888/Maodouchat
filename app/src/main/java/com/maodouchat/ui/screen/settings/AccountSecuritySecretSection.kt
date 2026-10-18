package com.maodouchat.ui.screen.settings

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.util.Secret2faGatePrefs
import com.maodouchat.util.SecretAutoDestroyPrefs
import com.maodouchat.util.SecretDeviceVerifyPrefs
import com.maodouchat.util.SecretForwardWhitelistPrefs
import com.maodouchat.util.SecretNewDeviceRiskPrefs
import com.maodouchat.util.SecretScreenshotBurnPrefs
import com.maodouchat.util.SecretSessionNoticePrefs
import com.maodouchat.util.SecretSimChangePrefs

/** B2 密聊安全分区：8 个 surface 开关（从 SettingsAccountSecurityScreen.kt 纯搬移；if (false) 禁用块原样保留）。 */
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调内读取，非组合作用域
@Composable
internal fun AccountSecuritySecretSection(
    userId: String
) {
    val context = LocalContext.current
    // B2 密聊安全 8 开关（服务端 status 下发同步；本地默认值兜底）
    var secretScreenshotBurnEnabled by remember(userId) { mutableStateOf(SecretScreenshotBurnPrefs.isEnabled(context)) }
    var secretAutoDestroyEnabled by remember(userId) { mutableStateOf(SecretAutoDestroyPrefs.isEnabled(context)) }
    var secretForwardWhitelistEnabled by remember(userId) { mutableStateOf(SecretForwardWhitelistPrefs.isEnabled(context)) }
    var secretSimChangeEnabled by remember(userId) { mutableStateOf(SecretSimChangePrefs.isEnabled(context)) }
    var secret2faGateEnabled by remember(userId) { mutableStateOf(Secret2faGatePrefs.isEnabled(context)) }
    var secretNewDeviceRiskEnabled by remember(userId) { mutableStateOf(SecretNewDeviceRiskPrefs.isEnabled(context)) }
    var secretDeviceVerifyEnabled by remember(userId) { mutableStateOf(SecretDeviceVerifyPrefs.isEnabled(context)) }
    var secretSessionNoticeEnabled by remember(userId) { mutableStateOf(SecretSessionNoticePrefs.isEnabled(context)) }

    // B2 密聊安全（8 个 surface 开关，仅本机生效）
    Spacer(modifier = Modifier.height(12.dp))
    SecuritySectionLabel(stringResource(R.string.security_section_secret))
    SecurityGroup {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(stringResource(R.string.settings_secret_security), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.settings_secret_security_hint), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
            }
        }
        HorizontalDividerLite()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.secret_screenshot_burn_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.secret_screenshot_burn_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
            }
            Switch(
                checked = secretScreenshotBurnEnabled,
                onCheckedChange = { enabled ->
                    SecretScreenshotBurnPrefs.setEnabled(context, enabled)
                    secretScreenshotBurnEnabled = enabled
                    Toast.makeText(
                        context,
                        context.getString(if (enabled) R.string.secret_screenshot_burn_enabled_toast else R.string.secret_screenshot_burn_disabled_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }

    if (false) {
    SecurityGroup {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.secret_auto_destroy_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.secret_auto_destroy_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
            }
            Switch(
                checked = secretAutoDestroyEnabled,
                onCheckedChange = { enabled ->
                    SecretAutoDestroyPrefs.setEnabled(context, enabled)
                    secretAutoDestroyEnabled = enabled
                    Toast.makeText(
                        context,
                        context.getString(if (enabled) R.string.secret_auto_destroy_enabled_toast else R.string.secret_auto_destroy_disabled_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
        HorizontalDividerLite()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.secret_forward_whitelist_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.secret_forward_whitelist_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
            }
            Switch(
                checked = secretForwardWhitelistEnabled,
                onCheckedChange = { enabled ->
                    SecretForwardWhitelistPrefs.setEnabled(context, enabled)
                    secretForwardWhitelistEnabled = enabled
                    Toast.makeText(
                        context,
                        context.getString(if (enabled) R.string.secret_forward_whitelist_enabled_toast else R.string.secret_forward_whitelist_disabled_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
        HorizontalDividerLite()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.secret_sim_change_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.secret_sim_change_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
            }
            Switch(
                checked = secretSimChangeEnabled,
                onCheckedChange = { enabled ->
                    SecretSimChangePrefs.setEnabled(context, enabled)
                    secretSimChangeEnabled = enabled
                    Toast.makeText(
                        context,
                        context.getString(if (enabled) R.string.secret_sim_change_enabled_toast else R.string.secret_sim_change_disabled_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
        HorizontalDividerLite()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.secret_2fa_gate_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.secret_2fa_gate_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
            }
            Switch(
                checked = secret2faGateEnabled,
                onCheckedChange = { enabled ->
                    Secret2faGatePrefs.setEnabled(context, enabled)
                    secret2faGateEnabled = enabled
                    if (enabled) Secret2faGatePrefs.clearGate(context)
                    Toast.makeText(
                        context,
                        context.getString(if (enabled) R.string.secret_2fa_gate_enabled_toast else R.string.secret_2fa_gate_disabled_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
        HorizontalDividerLite()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.secret_new_device_risk_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.secret_new_device_risk_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
            }
            Switch(
                checked = secretNewDeviceRiskEnabled,
                onCheckedChange = { enabled ->
                    SecretNewDeviceRiskPrefs.setEnabled(context, enabled)
                    secretNewDeviceRiskEnabled = enabled
                    Toast.makeText(
                        context,
                        context.getString(if (enabled) R.string.secret_new_device_risk_enabled_toast else R.string.secret_new_device_risk_disabled_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
        HorizontalDividerLite()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.secret_device_verify_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.secret_device_verify_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
            }
            Switch(
                checked = secretDeviceVerifyEnabled,
                onCheckedChange = { enabled ->
                    SecretDeviceVerifyPrefs.setEnabled(context, enabled)
                    secretDeviceVerifyEnabled = enabled
                    Toast.makeText(
                        context,
                        context.getString(if (enabled) R.string.secret_device_verify_enabled_toast else R.string.secret_device_verify_disabled_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
        HorizontalDividerLite()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.secret_session_notice_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.secret_session_notice_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
            }
            Switch(
                checked = secretSessionNoticeEnabled,
                onCheckedChange = { enabled ->
                    SecretSessionNoticePrefs.setEnabled(context, enabled)
                    secretSessionNoticeEnabled = enabled
                    Toast.makeText(
                        context,
                        context.getString(if (enabled) R.string.secret_session_notice_enabled_toast else R.string.secret_session_notice_disabled_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }
    }
}
