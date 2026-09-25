package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import com.maodouchat.security.findActivity
import com.maodouchat.util.RuntimeFlags
import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import com.maodouchat.network.ApiService
import com.maodouchat.network.DeviceInfoDto
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.ui.screen.settings.SettingsViewModel
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.security.AppLockManager
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.security.ScreenSecureManager
import com.maodouchat.security.SensitiveAction
import com.maodouchat.security.SensitiveActionGate
import android.widget.Toast
import com.maodouchat.ui.theme.OnlineGreen
import com.maodouchat.ui.theme.LocalChatPalette
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 账号安全页本体（G117 从 `SettingsAccountSecurity.kt` 拆出，原 1051 行）。
 *
 * 安全中心：设备列表、E2EE 状态、应用锁、账号操作一页聚合。
 * 它专属的 14 个小组件（`DeviceRow` / `SecurityStatusCard` / `DeleteAccountDialog` /
 * `ChangePasswordDialog` / `PasswordField` / `TotpSetupDialog` 等）留在
 * `SettingsAccountSecurity.kt`，那个文件现在是「账号安全组件库」。
 *
 * **拆解约束**：不直接抓应用级数据库单例（`ui/` 红线，读库只能经 ViewModel/repository）；
 * E2EE 状态经应用实例的 `signalProtocol` 只读查询，属既有形态，搬移时不改。
 */

/**
 * 安全中心：设备列表、E2EE 状态、应用锁、账号操作一页聚合。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调内读取，非组合作用域
fun AccountSecurityScreen(
    onBack: () -> Unit = {},
    onOpenMyQrCode: () -> Unit = {},
    onLogout: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val appLockUnavailableMsg = stringResource(R.string.settings_app_lock_unavailable)
    var oldPassword by rememberSaveable(state.userId) { mutableStateOf("") }
    var newPassword by rememberSaveable(state.userId) { mutableStateOf("") }
    var confirmPassword by rememberSaveable(state.userId) { mutableStateOf("") }
    var showChangeDialog by remember(state.userId) { mutableStateOf(false) }
    // 9.159：只存 deviceId，弹窗渲染时按 state.devices 现查——此前存整个 DeviceInfoDto 快照，
    // 列表刷新（他端确认 PENDING→CONFIRMED）后弹窗仍按旧 status/名称渲染，确认动作可能
    // 作用到已变化会话；设备从列表消失（他端删除）时自动关闭弹窗
    var pendingRemoveDeviceId by remember(state.userId) { mutableStateOf<Int?>(null) }
    var renameDeviceId by remember(state.userId) { mutableStateOf<Int?>(null) }
    var renameDraft by rememberSaveable(state.userId) { mutableStateOf("") }
    var showDeleteAccountDialog by remember(state.userId) { mutableStateOf(false) }
    var deletePassword by rememberSaveable(state.userId) { mutableStateOf("") }
    var showLogoutAllConfirm by remember(state.userId) { mutableStateOf(false) }
    // 0.75：两步验证设置对话框
    var showTotpSetupDialog by remember(state.userId) { mutableStateOf(false) }

    val e2eeReady = remember(state.userId) {
        val userId = state.userId
        userId.isNotBlank() && runCatching {
            MaodouchatApp.instance.signalProtocol.isInitializedFor(userId)
        }.getOrDefault(false)
    }
    val localFingerprint = remember(e2eeReady, state.userId) {
        if (!e2eeReady) null
        else runCatching { MaodouchatApp.instance.signalProtocol.getLocalIdentityFingerprint() }.getOrNull()
    }
    val confirmedDeviceCount = state.devices.count { it.status == "CONFIRMED" }
    var appLockEnabled by remember(state.userId) { mutableStateOf(AppLockManager.isEnabled(context)) }
    var appLockTimeout by remember(state.userId) { mutableLongStateOf(AppLockManager.getTimeoutMinutes(context)) }
    val appLockRuntimeOn = remember { RuntimeFlags.isEnabled(context, RuntimeFlags.APP_LOCK) }
    var appLockError by remember(state.userId) { mutableStateOf<String?>(null) }
    var sensitiveGateEnabled by remember(state.userId) { mutableStateOf(SensitiveActionGate.isEnabled(context)) }
    var screenSecureEnabled by remember(state.userId) { mutableStateOf(ScreenSecureManager.isEnabled(context)) }

    // B2 密聊安全 8 开关（服务端 status 下发同步；本地默认值兜底）
    var secretScreenshotBurnEnabled by remember(state.userId) { mutableStateOf(com.maodouchat.util.SecretScreenshotBurnPrefs.isEnabled(context)) }
    var secretAutoDestroyEnabled by remember(state.userId) { mutableStateOf(com.maodouchat.util.SecretAutoDestroyPrefs.isEnabled(context)) }
    var secretForwardWhitelistEnabled by remember(state.userId) { mutableStateOf(com.maodouchat.util.SecretForwardWhitelistPrefs.isEnabled(context)) }
    var secretSimChangeEnabled by remember(state.userId) { mutableStateOf(com.maodouchat.util.SecretSimChangePrefs.isEnabled(context)) }
    var secret2faGateEnabled by remember(state.userId) { mutableStateOf(com.maodouchat.util.Secret2faGatePrefs.isEnabled(context)) }
    var secretNewDeviceRiskEnabled by remember(state.userId) { mutableStateOf(com.maodouchat.util.SecretNewDeviceRiskPrefs.isEnabled(context)) }
    var secretDeviceVerifyEnabled by remember(state.userId) { mutableStateOf(com.maodouchat.util.SecretDeviceVerifyPrefs.isEnabled(context)) }
    var secretSessionNoticeEnabled by remember(state.userId) { mutableStateOf(com.maodouchat.util.SecretSessionNoticePrefs.isEnabled(context)) }

    var totpEnabled by remember(state.userId) { mutableStateOf(false) }
    var totpBusy by remember(state.userId) { mutableStateOf(false) }
    var totpSecret by remember(state.userId) { mutableStateOf<String?>(null) }
    var totpUri by remember(state.userId) { mutableStateOf<String?>(null) }
    var totpCodeInput by rememberSaveable(state.userId) { mutableStateOf("") }
    var totpMessage by remember(state.userId) { mutableStateOf<String?>(null) }
    val totpScope = rememberCoroutineScope()


    Column(modifier = Modifier.verticalScroll(rememberScrollState()).imePadding()) {
        Spacer(modifier = Modifier.height(8.dp))
        SecurityStatusCard(
            e2eeReady = e2eeReady,
            appLockOn = appLockEnabled,
            confirmedDeviceCount = confirmedDeviceCount,
            fingerprint = localFingerprint
        )
        Spacer(modifier = Modifier.height(16.dp))
        SecuritySectionLabel(stringResource(R.string.security_section_about))
        SecurityGroup {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    stringResource(R.string.security_scope_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.security_scope_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        SecuritySectionLabel(stringResource(R.string.security_section_account))
        SecurityGroup {
            InfoRow(label = stringResource(R.string.account_id_label), value = state.userId.ifBlank { "—" })
            HorizontalDividerLite()
            InfoRow(label = stringResource(R.string.account_nickname), value = state.userName.ifBlank { "—" })
            HorizontalDividerLite()
            InfoRow(
                label = stringResource(R.string.account_status),
                value = if (state.userStatus.isBlank()) stringResource(R.string.account_not_set) else state.userStatus
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        SecurityGroup {
            ActionRow(label = stringResource(R.string.account_change_password), subtitle = stringResource(R.string.account_password_hint), onClick = { showChangeDialog = true })
            HorizontalDividerLite()
            ActionRow(label = stringResource(R.string.account_qr), subtitle = stringResource(R.string.account_qr_hint), onClick = onOpenMyQrCode)
            HorizontalDividerLite()
            // 0.75：两步验证（TOTP + 恢复码）
            ActionRow(
                label = stringResource(R.string.settings_two_factor),
                subtitle = stringResource(R.string.settings_two_factor_hint),
                onClick = { showTotpSetupDialog = true }
            )
        }
        if (showTotpSetupDialog) {
            TotpSetupDialog(
                context = context,
                onDismiss = { showTotpSetupDialog = false }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        SecuritySectionLabel(stringResource(R.string.security_section_devices))
        SecurityGroup {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Smartphone, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.account_devices), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.account_devices_hint), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                }
                IconButton(onClick = viewModel::loadMyDevices, enabled = !state.isLoadingDevices) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.account_refresh_devices), tint = MaterialTheme.colorScheme.primary)
                }
            }
            HorizontalDividerLite()
            when {
                state.isLoadingDevices -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.account_loading_devices), color = LocalChatPalette.current.textSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                state.devices.isEmpty() -> {
                    Text(
                        stringResource(R.string.account_no_devices),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalChatPalette.current.textSecondary
                    )
                }
                else -> {
                    var deviceSearch by rememberSaveable(state.userId) { mutableStateOf("") }
                    val filteredDevices = remember(state.devices, deviceSearch) {
                        val query = deviceSearch.trim()
                        if (query.isBlank()) {
                            state.devices
                        } else {
                            state.devices.filter {
                                it.deviceName.contains(query, ignoreCase = true) ||
                                    it.deviceId.toString().contains(query, ignoreCase = true) ||
                                    it.status.contains(query, ignoreCase = true)
                            }
                        }
                    }
                    if (state.devices.size >= 4) {
                        OutlinedTextField(
                            value = deviceSearch,
                            onValueChange = { deviceSearch = it.take(120) },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.account_devices_search_hint)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    if (filteredDevices.isEmpty()) {
                        Text(
                            stringResource(R.string.account_devices_search_empty),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalChatPalette.current.textSecondary
                        )
                    } else {
                        val currentDeviceConfirmed = state.devices.any {
                            (it.isCurrent || it.deviceId == state.currentDeviceId) && it.status == "CONFIRMED"
                        }
                        val isDeviceMutationInProgress = state.removingDeviceId != null ||
                            state.renamingDeviceId != null ||
                            state.confirmingDeviceId != null
                        filteredDevices.forEachIndexed { index, device ->
                            DeviceRow(
                                device = device,
                                currentDeviceId = state.currentDeviceId,
                                isRemoving = state.removingDeviceId == device.deviceId,
                                isRenaming = state.renamingDeviceId == device.deviceId,
                                isConfirming = state.confirmingDeviceId == device.deviceId,
                                isMutationInProgress = isDeviceMutationInProgress,
                                canConfirm = currentDeviceConfirmed && device.deviceId != state.currentDeviceId,
                                onRename = {
                                    renameDeviceId = device.deviceId
                                    renameDraft = device.deviceName
                                },
                                onRemove = { pendingRemoveDeviceId = device.deviceId },
                                onConfirm = { viewModel.confirmMyDevice(device.deviceId) }
                            )
                            if (index != filteredDevices.lastIndex) HorizontalDividerLite()
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        SecuritySectionLabel(stringResource(R.string.security_section_danger))
        // 8.62：退出所有设备（远程撤销全部会话，含当前设备）
        SecurityGroup {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .clickable(enabled = !state.isLoggingOutAll) { showLogoutAllConfirm = true },
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.isLoggingOutAll) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.Outlined.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.account_logout_all), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.account_logout_all_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        SecurityGroup {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .clickable { showDeleteAccountDialog = true },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.account_delete), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.account_delete_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                }
            }
        }
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
                                            appLockEnabled = false
                                            appLockError = null
                                        } else {
                                            appLockEnabled = AppLockManager.isEnabled(context)
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
                                    appLockEnabled = false
                                    return@Switch
                                }
                                val changed = AppLockManager.setEnabled(context, enabled)
                                if (changed) {
                                    appLockEnabled = enabled
                                    appLockError = null
                                    if (enabled) {
                                        sensitiveGateEnabled = SensitiveActionGate.isEnabled(context)
                                    }
                                } else {
                                    appLockEnabled = AppLockManager.isEnabled(context)
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
                                    fontWeight = if (appLockTimeout == minutes) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
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
                        (context.findActivity() as? com.maodouchat.MainActivity)
                            ?.notifyScreenSecurePreferenceChanged()
                    }
                )
            }
        }

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
                        com.maodouchat.util.SecretScreenshotBurnPrefs.setEnabled(context, enabled)
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
                        com.maodouchat.util.SecretAutoDestroyPrefs.setEnabled(context, enabled)
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
                        com.maodouchat.util.SecretForwardWhitelistPrefs.setEnabled(context, enabled)
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
                        com.maodouchat.util.SecretSimChangePrefs.setEnabled(context, enabled)
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
                        com.maodouchat.util.Secret2faGatePrefs.setEnabled(context, enabled)
                        secret2faGateEnabled = enabled
                        if (enabled) com.maodouchat.util.Secret2faGatePrefs.clearGate(context)
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
                        com.maodouchat.util.SecretNewDeviceRiskPrefs.setEnabled(context, enabled)
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
                        com.maodouchat.util.SecretDeviceVerifyPrefs.setEnabled(context, enabled)
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
                        com.maodouchat.util.SecretSessionNoticePrefs.setEnabled(context, enabled)
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

        // G125：TOTP 2FA 区（261 行）抽到 SettingsTotpSection.kt，纯搬移不改判断。
        SettingsTotpSection(userId = state.userId)
        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showChangeDialog) {
        ChangePasswordDialog(
            oldPassword = oldPassword,
            newPassword = newPassword,
            confirmPassword = confirmPassword,
            isSaving = state.isSaving,
            errorMessage = state.errorMessage,
            onOldChange = { oldPassword = it },
            onNewChange = { newPassword = it },
            onConfirmChange = { confirmPassword = it },
            onDismiss = {
                showChangeDialog = false
                oldPassword = ""; newPassword = ""; confirmPassword = ""
                viewModel.clearErrorMessage()
            },
            onSubmit = {
                viewModel.changePassword(oldPassword, newPassword, confirmPassword) {
                    // 成功：清空输入并关闭弹窗
                    showChangeDialog = false
                    oldPassword = ""; newPassword = ""; confirmPassword = ""
                }
            }
        )
    }

    // 8.62：退出所有设备确认
    if (showLogoutAllConfirm) {
        AlertDialog(
            onDismissRequest = {
                if (!state.isLoggingOutAll) showLogoutAllConfirm = false
            },
            title = { Text(stringResource(R.string.account_logout_all)) },
            text = { Text(stringResource(R.string.account_logout_all_confirm)) },
            confirmButton = {
                TextButton(enabled = !state.isLoggingOutAll, onClick = {
                    showLogoutAllConfirm = false
                    viewModel.logoutAllDevices()
                }) {
                    Text(stringResource(R.string.account_logout_all), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(enabled = !state.isLoggingOutAll, onClick = { showLogoutAllConfirm = false }) {
                    Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary)
                }
            }
        )
    }

    if (showDeleteAccountDialog) {
        val deleteAuthTitle = stringResource(R.string.sensitive_auth_title)
        val deleteAuthSubtitle = stringResource(R.string.sensitive_auth_delete_account)
        val deleteAuthFailed = stringResource(R.string.sensitive_auth_failed)
        DeleteAccountDialog(
            password = deletePassword,
            isDeleting = state.isDeletingAccount,
            errorMessage = state.errorMessage,
            onPasswordChange = { deletePassword = it },
            onDismiss = {
                if (!state.isDeletingAccount) {
                    showDeleteAccountDialog = false
                    deletePassword = ""
                    viewModel.clearErrorMessage()
                }
            },
            onSubmit = {
                SensitiveActionGate.confirm(
                    context = context,
                    action = SensitiveAction.DELETE_ACCOUNT,
                    title = deleteAuthTitle,
                    subtitle = deleteAuthSubtitle,
                    onSuccess = { viewModel.deleteAccount(deletePassword) },
                    onFailure = { msg ->
                        Toast.makeText(
                            context,
                            msg?.takeIf { it.isNotBlank() } ?: deleteAuthFailed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
        )
    }

    // 9.159：目标设备从他端被删除/确认后自动关闭弹窗（与 9.150 会话/成员守卫同口径）
    LaunchedEffect(state.devices) {
        val ids = state.devices.mapTo(hashSetOf()) { it.deviceId }
        if (pendingRemoveDeviceId?.let { it !in ids } == true) pendingRemoveDeviceId = null
        if (renameDeviceId?.let { it !in ids } == true) renameDeviceId = null
    }

    val pendingRemoveDevice = pendingRemoveDeviceId?.let { id -> state.devices.firstOrNull { it.deviceId == id } }
    pendingRemoveDevice?.let { device ->
        val isPendingDevice = device.status == "PENDING"
        AlertDialog(
            onDismissRequest = { pendingRemoveDeviceId = null },
            title = { Text(if (isPendingDevice) stringResource(R.string.account_reject_device) else stringResource(R.string.account_remove_device)) },
            text = {
                Text(
                    if (isPendingDevice) {
                        stringResource(R.string.account_reject_device_confirm, device.deviceId)
                    } else {
                        stringResource(R.string.account_remove_device_confirm, device.deviceId)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemoveDeviceId = null
                    viewModel.removeMyDevice(device.deviceId)
                }) {
                    Text(if (isPendingDevice) stringResource(R.string.account_reject) else stringResource(R.string.chat_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingRemoveDeviceId = null }) { Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary) } }
        )
    }

    val renameDevice = renameDeviceId?.let { id -> state.devices.firstOrNull { it.deviceId == id } }
    renameDevice?.let { device ->
        AlertDialog(
            onDismissRequest = { renameDeviceId = null },
            title = { Text(stringResource(R.string.account_device_name_title)) },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { if (it.length <= 50) renameDraft = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.account_device_name)) },
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
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameMyDevice(device.deviceId, renameDraft)
                    renameDeviceId = null
                }) {
                    Text(stringResource(R.string.common_save), color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = { TextButton(onClick = { renameDeviceId = null }) { Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary) } }
        )
    }
}
