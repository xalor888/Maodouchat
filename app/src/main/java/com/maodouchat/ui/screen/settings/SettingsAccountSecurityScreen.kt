package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.maodouchat.security.AppLockManager
import com.maodouchat.ui.screen.settings.SettingsViewModel
import com.maodouchat.ui.theme.LocalChatPalette

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
            com.maodouchat.security.SignalIdentityAccess.isInitializedFor(userId)
        }.getOrDefault(false)
    }
    val localFingerprint = remember(e2eeReady, state.userId) {
        if (!e2eeReady) null
        else runCatching { com.maodouchat.security.SignalIdentityAccess.localIdentityFingerprint() }.getOrNull()
    }
    val confirmedDeviceCount = state.devices.count { it.status == "CONFIRMED" }
    var appLockEnabled by remember(state.userId) { mutableStateOf(AppLockManager.isEnabled(context)) }

    var totpEnabled by remember(state.userId) { mutableStateOf(false) }
    var totpBusy by remember(state.userId) { mutableStateOf(false) }
    var totpSecret by remember(state.userId) { mutableStateOf<String?>(null) }
    var totpUri by remember(state.userId) { mutableStateOf<String?>(null) }
    var totpCodeInput by rememberSaveable(state.userId) { mutableStateOf("") }
    var totpMessage by remember(state.userId) { mutableStateOf<String?>(null) }
    val totpScope = rememberCoroutineScope()

    // G333：本页整屏且没有 TopAppBar，必须自己消费系统栏 inset——此前只有 `imePadding()`，
    // 真机上首屏内容画在状态栏底下（「Devices, E2EE…」被时钟盖住）。safeDrawing 含状态栏+手势条+IME。
    Column(modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) {
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
        // 2026-10-19：应用锁分区抽出 AccountSecurityAppLockSection（纯搬移；appLockEnabled 留母文件走参数）。
        AccountSecurityAppLockSection(
            userId = state.userId,
            appLockEnabled = appLockEnabled,
            onAppLockEnabledChange = { appLockEnabled = it }
        )

        // 2026-10-19：B2 密聊安全分区抽出 AccountSecuritySecretSection（纯搬移；if (false) 禁用块原样保留）。
        AccountSecuritySecretSection(userId = state.userId)

        // G125：TOTP 2FA 区（261 行）抽到 SettingsTotpSection.kt，纯搬移不改判断。
        SettingsTotpSection(userId = state.userId)
        Spacer(modifier = Modifier.height(32.dp))
    }

    AccountSecurityDialogs(
        state = state,
        viewModel = viewModel,
        showChangeDialog = showChangeDialog,
        onShowChangeDialogChange = { showChangeDialog = it },
        oldPassword = oldPassword,
        onOldPasswordChange = { oldPassword = it },
        newPassword = newPassword,
        onNewPasswordChange = { newPassword = it },
        confirmPassword = confirmPassword,
        onConfirmPasswordChange = { confirmPassword = it },
        showLogoutAllConfirm = showLogoutAllConfirm,
        onShowLogoutAllConfirmChange = { showLogoutAllConfirm = it },
        showDeleteAccountDialog = showDeleteAccountDialog,
        onShowDeleteAccountDialogChange = { showDeleteAccountDialog = it },
        deletePassword = deletePassword,
        onDeletePasswordChange = { deletePassword = it },
        pendingRemoveDeviceId = pendingRemoveDeviceId,
        onPendingRemoveDeviceIdChange = { pendingRemoveDeviceId = it },
        renameDeviceId = renameDeviceId,
        onRenameDeviceIdChange = { renameDeviceId = it },
        renameDraft = renameDraft,
        onRenameDraftChange = { renameDraft = it },
    )
}
