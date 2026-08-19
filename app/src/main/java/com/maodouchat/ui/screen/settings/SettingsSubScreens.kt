@file:Suppress("DEPRECATION")

package com.maodouchat.ui.screen.settings

import com.maodouchat.util.RuntimeFlags
import android.app.Activity
import android.annotation.SuppressLint
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cloud
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import com.maodouchat.ai.AiWritingStylePolicy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.maodouchat.network.AiAuditLogResponse
import com.maodouchat.network.ApiService
import com.maodouchat.network.DeviceInfoDto
import com.maodouchat.network.TokenManager
import com.maodouchat.network.ReportResponse
import com.maodouchat.network.RiskEventResponse
import com.maodouchat.network.ModerationRuleResponse
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.security.AppLockManager
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.security.ScreenSecureManager
import com.maodouchat.security.SensitiveAction
import com.maodouchat.security.SensitiveActionGate
import com.maodouchat.util.AppLocaleManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.maodouchat.ui.theme.Background
import com.maodouchat.ui.theme.Divider
import com.maodouchat.ui.theme.Error
import com.maodouchat.ui.theme.MaodouchatTheme
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.OnlineGreen
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.Surface
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.UnreadRed
import com.maodouchat.ui.theme.LocalChatPalette
import androidx.compose.foundation.layout.heightIn
import com.maodouchat.ui.component.EmptyState
import com.maodouchat.ui.component.EmptyStateType
import androidx.compose.material.icons.outlined.Search
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 安全中心：设备列表、E2EE 状态、应用锁、账号操作一页聚合。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调内读取，非组合作用域
fun AccountSecurityScreen(
    onBack: () -> Unit = {},
    onOpenMyQrCode: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val securityToken = remember(context) { com.maodouchat.network.TokenManager.getInstance(context).getToken().orEmpty() }
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
    val tokenManager = remember(context) { TokenManager.getInstance(context) }
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
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )

    LaunchedEffect(state.userId) {
        val ownerUserId = state.userId
        if (ownerUserId.isBlank()) return@LaunchedEffect
        val token = tokenManager.getToken().orEmpty()
        if (token.isBlank() || !isCurrentTotpOwner(ownerUserId)) return@LaunchedEffect
        totpBusy = true
        try {
            ApiService.getTotpStatus(token).fold(
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

    LaunchedEffect(state.userId) {
        if (state.userId.isBlank()) return@LaunchedEffect
        viewModel.loadMyDevices()
        viewModel.pullSecurityClientPrefs { timeout, secure, sensitiveGate ->
            appLockTimeout = timeout
            screenSecureEnabled = secure
            sensitiveGateEnabled = sensitiveGate
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(R.string.security_center_title), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = Primary, modifier = Modifier.size(28.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
        )

        Column(modifier = Modifier.verticalScroll(rememberScrollState()).imePadding()) {
            Spacer(modifier = Modifier.height(8.dp))
            SecurityStatusCard(
                e2eeReady = e2eeReady,
                appLockOn = appLockEnabled,
                confirmedDeviceCount = confirmedDeviceCount,
                fingerprint = localFingerprint
            )
            Spacer(modifier = Modifier.height(16.dp))
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
                        color = TextSecondary
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
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
                    token = securityToken,
                    onDismiss = { showTotpSetupDialog = false }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            SecurityGroup {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Smartphone, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.account_devices), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.account_devices_hint), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                    IconButton(onClick = viewModel::loadMyDevices, enabled = !state.isLoadingDevices) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.account_refresh_devices), tint = Primary)
                    }
                }
                HorizontalDividerLite()
                when {
                    state.isLoadingDevices -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.account_loading_devices), color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    state.devices.isEmpty() -> {
                        Text(
                            stringResource(R.string.account_no_devices),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
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
                                color = TextSecondary
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
            // 8.62：退出所有设备（远程撤销全部会话，含当前设备）
            SecurityGroup {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .clickable(enabled = !state.isLoggingOutAll) { showLogoutAllConfirm = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.isLoggingOutAll) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Primary)
                    } else {
                        Icon(Icons.Outlined.Warning, contentDescription = null, tint = Error, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.account_logout_all), style = MaterialTheme.typography.bodyLarge, color = Error)
                        Text(stringResource(R.string.account_logout_all_subtitle), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
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
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = Error, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.account_delete), style = MaterialTheme.typography.bodyLarge, color = Error)
                        Text(stringResource(R.string.account_delete_subtitle), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
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
                        Icon(Icons.Outlined.Lock, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_app_lock), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(R.string.settings_app_lock_subtitle), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
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
                            color = Error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                    if (appLockEnabled) {
                        HorizontalDivider(color = TextHint.copy(alpha = 0.25f))
                        Text(
                            stringResource(R.string.settings_app_lock_timeout),
                            style = MaterialTheme.typography.labelLarge,
                            color = TextSecondary,
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
                                        color = if (appLockTimeout == minutes) Primary else TextSecondary,
                                        fontWeight = if (appLockTimeout == minutes) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = TextHint.copy(alpha = 0.25f))
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
                                    color = TextSecondary
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
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp))
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
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = screenSecureEnabled,
                        onCheckedChange = { enabled ->
                            ScreenSecureManager.setEnabled(context, enabled)
                            screenSecureEnabled = enabled
                            viewModel.pushSecurityClientPrefs(screenSecureEnabled = enabled)
                        }
                    )
                }
            }

            // B2 密聊安全（8 个 surface 开关，仅本机生效）
            Spacer(modifier = Modifier.height(12.dp))
            SecurityGroup {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Security, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(stringResource(R.string.settings_secret_security), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.settings_secret_security_hint), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }
                HorizontalDividerLite()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.secret_screenshot_burn_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.secret_screenshot_burn_subtitle), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
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
                HorizontalDividerLite()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.secret_auto_destroy_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.secret_auto_destroy_subtitle), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
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
                        Text(stringResource(R.string.secret_forward_whitelist_subtitle), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
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
                        Text(stringResource(R.string.secret_sim_change_subtitle), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
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
                        Text(stringResource(R.string.secret_2fa_gate_subtitle), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
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
                        Text(stringResource(R.string.secret_new_device_risk_subtitle), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
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
                        Text(stringResource(R.string.secret_device_verify_subtitle), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
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
                        Text(stringResource(R.string.secret_session_notice_subtitle), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
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

            // TOTP 2FA
            Spacer(modifier = Modifier.height(12.dp))
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(16.dp),
                color = Surface,
                tonalElevation = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.settings_totp_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.settings_totp_desc), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text(
                        stringResource(if (totpEnabled) R.string.settings_totp_status_enabled else R.string.settings_totp_status_disabled),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (totpEnabled) OnlineGreen else TextSecondary
                    )
                    totpMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Primary) }
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
                                    tint = Primary
                                )
                            }
                        }
                        totpUri?.let { uri ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    uri,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextHint,
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
                                        tint = Primary
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
                                val ownerUserId = state.userId
                                val token = tokenManager.getToken().orEmpty()
                                if (token.isBlank() || !isCurrentTotpOwner(ownerUserId)) {
                                    totpMessage = sessionExpiredMessage
                                    return@Button
                                }
                                val code = totpCodeInput
                                totpBusy = true
                                totpScope.launch {
                                    try {
                                        val result = ApiService.confirmTotp(token, code)
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
                                val ownerUserId = state.userId
                                val token = tokenManager.getToken().orEmpty()
                                if (token.isBlank() || !isCurrentTotpOwner(ownerUserId)) {
                                    totpMessage = sessionExpiredMessage
                                    return@Button
                                }
                                totpBusy = true
                                totpScope.launch {
                                    try {
                                        val result = ApiService.setupTotp(token)
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
                                val ownerUserId = state.userId
                                val token = tokenManager.getToken().orEmpty()
                                if (token.isBlank() || !isCurrentTotpOwner(ownerUserId)) {
                                    totpMessage = sessionExpiredMessage
                                    return@Button
                                }
                                val code = totpCodeInput
                                // 9.140：关闭 2FA 属破坏性安全操作——与注销/删号/关闭 App 锁一致，
                                // 先过 SensitiveActionGate step-up（App 锁 + 敏感操作验证开启时）
                                com.maodouchat.security.SensitiveActionGate.confirm(
                                    context = context,
                                    action = com.maodouchat.security.SensitiveAction.DISABLE_TOTP,
                                    title = context.getString(R.string.settings_totp_disable),
                                    onSuccess = {
                                        totpBusy = true
                                        totpScope.launch {
                                            try {
                                                val disable = ApiService.disableTotp(token, code)
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
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
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
                    Text(stringResource(R.string.account_logout_all), color = Error)
                }
            },
            dismissButton = {
                TextButton(enabled = !state.isLoggingOutAll, onClick = { showLogoutAllConfirm = false }) {
                    Text(stringResource(R.string.common_cancel), color = TextSecondary)
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
                    Text(if (isPendingDevice) stringResource(R.string.account_reject) else stringResource(R.string.chat_remove), color = Error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingRemoveDeviceId = null }) { Text(stringResource(R.string.common_cancel), color = TextSecondary) } }
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
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
                        cursorColor = Primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameMyDevice(device.deviceId, renameDraft)
                    renameDeviceId = null
                }) {
                    Text(stringResource(R.string.common_save), color = Primary)
                }
            },
            dismissButton = { TextButton(onClick = { renameDeviceId = null }) { Text(stringResource(R.string.common_cancel), color = TextSecondary) } }
        )
    }
}

@Composable
private fun DeviceRow(
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Smartphone, contentDescription = null, tint = if (isCurrent) Primary else TextSecondary, modifier = Modifier.size(22.dp))
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
                    Text(stringResource(R.string.account_current_device), style = MaterialTheme.typography.labelSmall, color = Primary)
                }
                if (isPending) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.account_pending_device), style = MaterialTheme.typography.labelSmall, color = Error)
                }
            }
            Text(
                stringResource(R.string.account_device_fingerprint, device.deviceId, device.identityKey.take(8), device.identityKey.takeLast(6)),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (isPending) {
                Text(
                    if (isCurrent) stringResource(R.string.account_pending_current_hint) else stringResource(R.string.account_pending_other_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Error
                )
            }
        }
        TextButton(onClick = onRename, enabled = !isMutationInProgress) {
            if (isRenaming) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Primary)
            else Text(stringResource(R.string.account_rename_device), color = Primary)
        }
        if (isPending && !isCurrent) {
            TextButton(onClick = onConfirm, enabled = canConfirm && !isMutationInProgress) {
                if (isConfirming) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Primary)
                else Text(stringResource(R.string.account_approve_device), color = Primary)
            }
        }
        if (!isCurrent) {
            TextButton(onClick = onRemove, enabled = !isMutationInProgress) {
                if (isRemoving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Error)
                } else {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = Error, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isPending) stringResource(R.string.account_reject) else stringResource(R.string.chat_remove), color = Error)
                }
            }
        }
    }
}

@Composable
private fun SecurityStatusCard(
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
            color = TextSecondary
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
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            stringResource(R.string.security_local_db_encrypted),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            stringResource(if (appLockOn) R.string.security_app_lock_on else R.string.security_app_lock_off),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        val shortFp = fingerprint
            ?.replace(" ", "")
            ?.take(8)
            ?.uppercase(Locale.US)
            ?: stringResource(R.string.security_fingerprint_unavailable)
        Text(
            stringResource(R.string.security_devices_confirmed, confirmedDeviceCount, shortFp),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun SecurityGroup(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
    ) { content() }
}

@Composable
private fun HorizontalDividerLite() {
    androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, modifier = Modifier.width(108.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ActionRow(label: String, subtitle: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .clickableRow(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) OnSurface else TextHint)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (enabled) TextSecondary else TextHint)
        }
        Text("›", color = if (enabled) TextHint else TextHint, fontSize = 18.sp)
    }
}

@Composable
private fun Modifier.clickableRow(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.clickable(enabled = enabled) { onClick() }

@Composable
private fun DeleteAccountDialog(
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
                    color = TextSecondary
                )
                PasswordField(label = stringResource(R.string.account_current_password), value = password, onValueChange = onPasswordChange)
                if (!errorMessage.isNullOrBlank()) Text(errorMessage, color = Error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !isDeleting && password.isNotBlank()) {
                if (isDeleting) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Error)
                else Text(stringResource(R.string.account_confirm_delete), color = Error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isDeleting) { Text(stringResource(R.string.common_cancel), color = TextSecondary) } }
    )
}

@Composable
private fun ChangePasswordDialog(
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
                if (!errorMessage.isNullOrBlank()) Text(errorMessage, color = Error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !isSaving && strength.level != com.maodouchat.util.PasswordStrength.Level.WEAK) {
                if (isSaving) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Primary)
                else Text(stringResource(R.string.common_save), color = Primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text(stringResource(R.string.common_cancel), color = TextSecondary) } }
    )
}

@Composable
private fun PasswordStrengthIndicator(strength: com.maodouchat.util.PasswordStrength.Result) {
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
        com.maodouchat.util.PasswordStrength.Level.STRONG -> Primary to stringResource(R.string.password_strength_strong)
        com.maodouchat.util.PasswordStrength.Level.VERY_STRONG -> Primary to stringResource(R.string.password_strength_very_strong)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(stringResource(R.string.password_strength_label), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
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
                color = TextHint
            )
        }
    }
}

private fun com.maodouchat.util.PasswordStrength.Suggestion.labelRes(): Int = when (this) {
    com.maodouchat.util.PasswordStrength.Suggestion.ENTER_PASSWORD -> R.string.password_suggestion_enter
    com.maodouchat.util.PasswordStrength.Suggestion.USE_MINIMUM_LENGTH -> R.string.password_suggestion_length
    com.maodouchat.util.PasswordStrength.Suggestion.ADD_DIGIT -> R.string.password_suggestion_digit
    com.maodouchat.util.PasswordStrength.Suggestion.ADD_LOWERCASE -> R.string.password_suggestion_lowercase
    com.maodouchat.util.PasswordStrength.Suggestion.ADD_UPPERCASE -> R.string.password_suggestion_uppercase
}

@Composable
private fun PasswordField(label: String, value: String, onValueChange: (String) -> Unit) {
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
            focusedBorderColor = Primary,
            unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
            cursorColor = Primary
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * 「AI 助手与隐私」页 — 全局 AI 开关 + 本机授权 + 调用审计
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiPrivacySettingsScreen(
    onBack: () -> Unit = {},
    viewModel: AiPrivacySettingsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showResetConsentDialog by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var imageOcrEnabled by remember { mutableStateOf(com.maodouchat.ai.ImageOcrPreferences.isEnabled(context)) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_ai_privacy), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = Primary, modifier = Modifier.size(28.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
        )

        Column(modifier = Modifier.verticalScroll(rememberScrollState()).imePadding()) {
            Spacer(modifier = Modifier.height(8.dp))
            SecurityGroup {
                // 总开关：与聊天内主入口 / 长按场景入口配套（M5-1）
                SwitchRow(
                    title = stringResource(R.string.ai_privacy_global),
                    subtitle = if (state.userEnabled) {
                        stringResource(R.string.ai_privacy_global_enabled_hint)
                    } else {
                        stringResource(R.string.ai_privacy_global_disabled_hint)
                    },
                    checked = state.userEnabled,
                    enabled = !state.isSaving && !state.isLoading,
                    onCheckedChange = { viewModel.setUserAiEnabled(it) }
                )
                HorizontalDividerLite()
                SwitchRow(
                    title = stringResource(R.string.ai_privacy_local_consent),
                    subtitle = stringResource(R.string.ai_privacy_local_consent_hint),
                    checked = state.aiConsentAccepted,
                    enabled = !state.isSaving,
                    onCheckedChange = { viewModel.setAiConsentAccepted(it) }
                )
                HorizontalDividerLite()
                SwitchRow(
                    title = stringResource(R.string.ai_privacy_local_safety),
                    subtitle = stringResource(R.string.ai_privacy_local_safety_hint),
                    checked = state.localSafetyEnabled,
                    enabled = !state.isSaving,
                    onCheckedChange = { viewModel.setLocalSafetyEnabled(it) }
                )
                HorizontalDividerLite()
                // 自动图片 OCR：识别图内文字并写入搜索索引（本机开关，默认开）
                SwitchRow(
                    title = stringResource(R.string.ai_privacy_image_ocr),
                    subtitle = stringResource(R.string.ai_privacy_image_ocr_hint),
                    checked = imageOcrEnabled,
                    enabled = !state.isSaving,
                    onCheckedChange = { enabled ->
                        imageOcrEnabled = enabled
                        com.maodouchat.ai.ImageOcrPreferences.setEnabled(context, enabled)
                        if (enabled) {
                            // 开启后立即扫描一轮，让已有图片尽快可被搜索
                            com.maodouchat.MaodouchatApp.instance.applicationScope.launch {
                                runCatching {
                                    com.maodouchat.MaodouchatApp.instance.imageOcrAutoIndexer.runOnce()
                                }
                            }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            SecurityGroup {
                SwitchRow(
                    title = stringResource(R.string.ai_privacy_writing_style),
                    subtitle = stringResource(R.string.ai_privacy_writing_style_hint),
                    checked = state.writingStyleEnabled,
                    enabled = !state.isSaving,
                    onCheckedChange = { viewModel.setWritingStyleEnabled(it) }
                )
                if (state.writingStyleEnabled) {
                    HorizontalDividerLite()
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            stringResource(R.string.ai_privacy_writing_style_preset),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                AiWritingStylePolicy.Preset.NONE to R.string.ai_privacy_writing_style_preset_none,
                                AiWritingStylePolicy.Preset.CONCISE to R.string.ai_privacy_writing_style_preset_concise,
                                AiWritingStylePolicy.Preset.FORMAL to R.string.ai_privacy_writing_style_preset_formal,
                                AiWritingStylePolicy.Preset.WARM to R.string.ai_privacy_writing_style_preset_warm,
                                AiWritingStylePolicy.Preset.PROFESSIONAL to R.string.ai_privacy_writing_style_preset_professional,
                                AiWritingStylePolicy.Preset.CASUAL to R.string.ai_privacy_writing_style_preset_casual,
                                AiWritingStylePolicy.Preset.WITTY to R.string.ai_privacy_writing_style_preset_witty,
                                AiWritingStylePolicy.Preset.EMPATHETIC to R.string.ai_privacy_writing_style_preset_empathetic,
                                AiWritingStylePolicy.Preset.DIRECT to R.string.ai_privacy_writing_style_preset_direct,
                                AiWritingStylePolicy.Preset.ENTHUSIASTIC to R.string.ai_privacy_writing_style_preset_enthusiastic,
                                AiWritingStylePolicy.Preset.DIPLOMATIC to R.string.ai_privacy_writing_style_preset_diplomatic
                            ).forEach { (preset, labelRes) ->
                                FilterChip(
                                    selected = state.writingStylePresetId == preset.id,
                                    onClick = { viewModel.setWritingStylePreset(preset.id) },
                                    enabled = !state.isSaving,
                                    label = { Text(stringResource(labelRes)) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = state.writingStyleCustomNote,
                            onValueChange = { viewModel.setWritingStyleCustomNote(it) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isSaving,
                            singleLine = false,
                            maxLines = 3,
                            label = { Text(stringResource(R.string.ai_privacy_writing_style_custom)) },
                            supportingText = {
                                Text(
                                    stringResource(
                                        R.string.ai_privacy_writing_style_custom_count,
                                        state.writingStyleCustomNote.length,
                                        AiWritingStylePolicy.MAX_CUSTOM_CHARS
                                    )
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Primary,
                                cursorColor = Primary
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.ai_privacy_writing_style_local_only),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = { viewModel.clearWritingStyle() },
                            enabled = !state.isSaving
                        ) {
                            Text(stringResource(R.string.ai_privacy_writing_style_clear), color = Error)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            SecurityGroup {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.ai_privacy_recent_calls), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.ai_privacy_audit_hint), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                    TextButton(onClick = { viewModel.refresh() }, enabled = !state.isLoading) {
                        Text(stringResource(R.string.common_refresh))
                    }
                }
                HorizontalDividerLite()
                when {
                    state.isLoading -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.ai_privacy_loading), color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    state.auditLogs.isEmpty() -> {
                        Text(
                            stringResource(R.string.ai_privacy_empty),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                    else -> {
                        var auditSearch by rememberSaveable { mutableStateOf("") }
                        val filteredAuditLogs = remember(state.auditLogs, auditSearch) {
                            val query = auditSearch.trim()
                            if (query.isBlank()) {
                                state.auditLogs
                            } else {
                                state.auditLogs.filter { log ->
                                    log.feature.contains(query, ignoreCase = true) ||
                                        log.status.contains(query, ignoreCase = true) ||
                                        log.model.orEmpty().contains(query, ignoreCase = true) ||
                                        log.error.orEmpty().contains(query, ignoreCase = true) ||
                                        log.chatId.orEmpty().contains(query, ignoreCase = true)
                                }
                            }
                        }
                        if (state.auditLogs.size >= 5) {
                            OutlinedTextField(
                                value = auditSearch,
                                onValueChange = { auditSearch = it.take(120) },
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.ai_privacy_audit_search_hint)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        if (filteredAuditLogs.isEmpty()) {
                            Text(
                                stringResource(R.string.ai_privacy_audit_search_empty),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        } else {
                            filteredAuditLogs.forEachIndexed { index, log ->
                                AiAuditLogRow(log = log)
                                if (index != filteredAuditLogs.lastIndex) HorizontalDividerLite()
                            }
                        }
                    }
                }
            }

            if (!state.infoMessage.isNullOrBlank() || !state.errorMessage.isNullOrBlank()) {
                Text(
                    text = state.infoMessage ?: state.errorMessage.orEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.errorMessage == null) Primary else Error
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            SecurityGroup {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = Error, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.ai_privacy_reset_consent), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.ai_privacy_reset_consent_subtitle), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                    TextButton(
                        onClick = { showResetConsentDialog = true },
                        enabled = !state.isSaving
                    ) { Text(stringResource(R.string.ai_privacy_reset_consent), color = Error) }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showResetConsentDialog) {
        AlertDialog(
            onDismissRequest = { showResetConsentDialog = false },
            title = { Text(stringResource(R.string.ai_privacy_reset_consent)) },
            text = { Text(stringResource(R.string.ai_privacy_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetConsentDialog = false
                    viewModel.revokeLocalConsent()
                }) { Text(stringResource(R.string.ai_privacy_reset_confirm_ok), color = Error) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConsentDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
private fun AiAuditLogRow(log: AiAuditLogResponse) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(aiFeatureLabel(log.feature), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    aiStatusLabel(log.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = aiStatusColor(log.status)
                )
            }
            Text(
                listOfNotNull(
                    log.model?.takeIf { it.isNotBlank() },
                    stringResource(R.string.ai_privacy_input_chars, log.inputChars),
                    if (log.contextMessages > 0) stringResource(R.string.ai_privacy_context_messages, log.contextMessages) else null,
                    log.durationMs?.let { "${it}ms" }
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            log.error?.takeIf { it.isNotBlank() }?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = Error)
            }
        }
        Text(formatAuditTime(log.createdAt), style = MaterialTheme.typography.labelSmall, color = TextHint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModerationScreen(
    onBack: () -> Unit = {},
    viewModel: ModerationViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedReport by remember { mutableStateOf<ReportResponse?>(null) }
    var selectedRule by remember { mutableStateOf<ModerationRuleResponse?>(null) }

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_moderation), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = Primary, modifier = Modifier.size(28.dp))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refreshAll, enabled = !state.isLoading) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.common_refresh), tint = Primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            SecurityGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "REPORTS" to stringResource(R.string.moderation_section_reports),
                        "RISKS" to stringResource(R.string.moderation_section_risks),
                        "RULES" to stringResource(R.string.moderation_section_rules)
                    ).forEach { (section, label) ->
                        val selected = state.section == section
                        TextButton(
                            onClick = { viewModel.setSection(section) },
                            modifier = Modifier.background(
                                if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                        ) {
                            Text(label, color = if (selected) Primary else TextSecondary, maxLines = 1)
                        }
                    }
                }
            }
            if (state.section == "REPORTS") {
                Spacer(modifier = Modifier.height(8.dp))
                SecurityGroup {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        viewModel.filters.forEach { status ->
                            val selected = state.statusFilter == status
                            TextButton(
                                onClick = { viewModel.setFilter(status) },
                                modifier = Modifier.background(
                                    if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent,
                                    RoundedCornerShape(10.dp)
                                )
                            ) {
                                Text(reportStatusLabel(status), color = if (selected) Primary else TextSecondary, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            state.errorMessage?.let {
                Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Error, style = MaterialTheme.typography.bodySmall)
            }
            state.infoMessage?.let {
                Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Primary, style = MaterialTheme.typography.bodySmall)
            }
            SecurityGroup {
                when (state.section) {
                    "RISKS" -> {
                        if (state.riskEvents.isEmpty()) {
                            Text(stringResource(R.string.moderation_empty_risks), modifier = Modifier.fillMaxWidth().padding(16.dp), color = TextSecondary)
                        } else {
                            var riskSearch by rememberSaveable { mutableStateOf("") }
                            val filteredRisks = remember(state.riskEvents, riskSearch) {
                                val query = riskSearch.trim()
                                if (query.isBlank()) {
                                    state.riskEvents
                                } else {
                                    state.riskEvents.filter {
                                        it.userId.contains(query, ignoreCase = true) ||
                                            it.source.contains(query, ignoreCase = true) ||
                                            it.action.contains(query, ignoreCase = true) ||
                                            it.matched.orEmpty().contains(query, ignoreCase = true) ||
                                            it.ruleId.orEmpty().contains(query, ignoreCase = true)
                                    }
                                }
                            }
                            if (state.riskEvents.size >= 5) {
                                OutlinedTextField(
                                    value = riskSearch,
                                    onValueChange = { riskSearch = it.take(120) },
                                    singleLine = true,
                                    placeholder = { Text(stringResource(R.string.moderation_risk_search_hint)) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            if (filteredRisks.isEmpty()) {
                                Text(
                                    stringResource(R.string.moderation_risk_search_empty),
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    color = TextSecondary
                                )
                            } else {
                                filteredRisks.forEachIndexed { index, event ->
                                    RiskEventRow(event = event, enabled = !state.isUpdating, onAcknowledge = { viewModel.acknowledgeRiskEvent(event.id) })
                                    if (index != filteredRisks.lastIndex) HorizontalDividerLite()
                                }
                            }
                        }
                    }
                    "RULES" -> {
                        if (state.rules.isEmpty()) {
                            Text(stringResource(R.string.moderation_empty_rules), modifier = Modifier.fillMaxWidth().padding(16.dp), color = TextSecondary)
                        } else {
                            var ruleSearch by rememberSaveable { mutableStateOf("") }
                            val filteredRules = remember(state.rules, ruleSearch) {
                                val query = ruleSearch.trim()
                                if (query.isBlank()) {
                                    state.rules
                                } else {
                                    state.rules.filter {
                                        it.name.contains(query, ignoreCase = true) ||
                                            it.description.orEmpty().contains(query, ignoreCase = true) ||
                                            it.scope.contains(query, ignoreCase = true) ||
                                            it.action.contains(query, ignoreCase = true) ||
                                            it.matchType.contains(query, ignoreCase = true)
                                    }
                                }
                            }
                            if (state.rules.size >= 4) {
                                OutlinedTextField(
                                    value = ruleSearch,
                                    onValueChange = { ruleSearch = it.take(120) },
                                    singleLine = true,
                                    placeholder = { Text(stringResource(R.string.moderation_rule_search_hint)) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            if (filteredRules.isEmpty()) {
                                Text(
                                    stringResource(R.string.moderation_rule_search_empty),
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    color = TextSecondary
                                )
                            } else {
                                filteredRules.forEachIndexed { index, rule ->
                                    ModerationRuleRow(
                                        rule = rule,
                                        enabled = !state.isUpdating,
                                        onClick = { selectedRule = rule },
                                        onEnabledChange = { viewModel.setRuleEnabled(rule.id, it) }
                                    )
                                    if (index != filteredRules.lastIndex) HorizontalDividerLite()
                                }
                            }
                        }
                    }
                    else -> when {
                        state.isLoading -> Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.moderation_loading_reports), color = TextSecondary)
                        }
                        state.reports.isEmpty() -> Text(
                            stringResource(R.string.moderation_empty_reports),
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        else -> {
                            var reportSearch by rememberSaveable { mutableStateOf("") }
                            val filteredReports = remember(state.reports, reportSearch) {
                                val query = reportSearch.trim()
                                if (query.isBlank()) {
                                    state.reports
                                } else {
                                    state.reports.filter {
                                        it.reason.contains(query, ignoreCase = true) ||
                                            it.status.contains(query, ignoreCase = true) ||
                                            it.targetType.contains(query, ignoreCase = true) ||
                                            it.targetId.contains(query, ignoreCase = true) ||
                                            it.reporterId.contains(query, ignoreCase = true) ||
                                            it.description.orEmpty().contains(query, ignoreCase = true) ||
                                            it.resolutionNote.orEmpty().contains(query, ignoreCase = true)
                                    }
                                }
                            }
                            if (state.reports.size >= 5) {
                                OutlinedTextField(
                                    value = reportSearch,
                                    onValueChange = { reportSearch = it.take(120) },
                                    singleLine = true,
                                    placeholder = { Text(stringResource(R.string.moderation_report_search_hint)) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            if (filteredReports.isEmpty()) {
                                Text(
                                    stringResource(R.string.moderation_report_search_empty),
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            } else {
                                filteredReports.forEachIndexed { index, report ->
                                    ReportModerationRow(report = report, onClick = { selectedReport = report })
                                    if (index != filteredReports.lastIndex) HorizontalDividerLite()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedReport?.let { report ->
        ReportReviewDialog(
            report = report,
            isUpdating = state.isUpdating,
            onDismiss = {
                selectedReport = null
                viewModel.clearMessages()
            },
            onUpdate = { status, note ->
                viewModel.updateReport(report.id, status, note)
                selectedReport = null
            },
            onAction = { action, note ->
                viewModel.applyReportAction(report.id, action, note)
                selectedReport = null
            }
        )
    }

    selectedRule?.let { rule ->
        ModerationRuleDialog(
            rule = rule,
            isUpdating = state.isUpdating,
            onDismiss = { selectedRule = null },
            onSave = { action, threshold, windowMinutes ->
                viewModel.updateRuleSettings(rule.id, action, threshold, windowMinutes)
                selectedRule = null
            }
        )
    }
}

@Composable
private fun RiskEventRow(event: RiskEventResponse, enabled: Boolean, onAcknowledge: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(riskActionLabel(event.action), style = MaterialTheme.typography.bodyLarge, color = if (event.needsReview) Error else OnSurface)
            Text(
                stringResource(R.string.moderation_risk_summary, reportTargetLabel(event.source), event.userId, formatAuditTime(event.createdAt)),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            event.matched?.let { Text(stringResource(R.string.moderation_matched, it), style = MaterialTheme.typography.bodySmall, color = TextHint, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        TextButton(onClick = onAcknowledge, enabled = enabled) { Text(stringResource(R.string.moderation_acknowledge)) }
    }
}

@Composable
private fun ModerationRuleRow(
    rule: ModerationRuleResponse,
    enabled: Boolean,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(rule.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                rule.description.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(R.string.moderation_rule_summary, reportTargetLabel(rule.scope), riskActionLabel(rule.action), rule.hitThreshold),
                style = MaterialTheme.typography.labelSmall,
                color = TextHint
            )
        }
        Switch(checked = rule.enabled, onCheckedChange = onEnabledChange, enabled = enabled)
    }
}

@Composable
private fun ModerationRuleDialog(
    rule: ModerationRuleResponse,
    isUpdating: Boolean,
    onDismiss: () -> Unit,
    onSave: (action: String, hitThreshold: Int, windowMinutes: Long) -> Unit
) {
    var selectedAction by rememberSaveable(rule.id) { mutableStateOf(rule.action) }
    var threshold by rememberSaveable(rule.id) { mutableStateOf(rule.hitThreshold.coerceAtLeast(1).toString()) }
    var windowMinutes by rememberSaveable(rule.id) { mutableStateOf((rule.windowMs / 60_000L).coerceAtLeast(1).toString()) }
    val actions = listOf("WARN_MOD", "AUTO_RATE_LIMIT", "AUTO_HOLD", "AUTO_DELETE")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(rule.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rule.description?.let { Text(it, color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    actions.forEach { action ->
                        TextButton(
                            onClick = { selectedAction = action },
                            modifier = Modifier.background(
                                if (selectedAction == action) Primary.copy(alpha = 0.12f) else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                        ) { Text(riskActionLabel(action), color = if (selectedAction == action) Primary else TextSecondary) }
                    }
                }
                OutlinedTextField(
                    value = threshold,
                    onValueChange = { threshold = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.moderation_hit_threshold)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = windowMinutes,
                    onValueChange = { windowMinutes = it.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(R.string.moderation_window_minutes)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isUpdating && threshold.toIntOrNull() != null && windowMinutes.toLongOrNull() != null,
                onClick = { onSave(selectedAction, threshold.toIntOrNull() ?: 1, windowMinutes.toLongOrNull() ?: 1L) }
            ) { Text(stringResource(R.string.common_save), color = Primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isUpdating) { Text(stringResource(R.string.common_cancel), color = TextSecondary) } }
    )
}

@Composable
private fun ReportModerationRow(report: ReportResponse, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(reportTargetLabel(report.targetType), style = MaterialTheme.typography.labelMedium, color = Primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(reportStatusLabel(report.status), style = MaterialTheme.typography.labelMedium, color = reportStatusColor(report.status))
            }
            Text(report.reason, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${report.targetId} · ${formatAuditTime(report.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ReportReviewDialog(
    report: ReportResponse,
    isUpdating: Boolean,
    onDismiss: () -> Unit,
    onUpdate: (status: String, note: String?) -> Unit,
    onAction: (action: String, note: String?) -> Unit
) {
    var note by rememberSaveable(report.id) { mutableStateOf(report.resolutionNote.orEmpty()) }
    val canDeleteContent = report.targetType in setOf("MESSAGE", "POST", "COMMENT")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.moderation_report_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${reportTargetLabel(report.targetType)} · ${reportStatusLabel(report.status)}", color = Primary, style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.moderation_target, report.targetId), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                report.chatId?.let { Text(stringResource(R.string.moderation_chat, it), color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.moderation_reason, report.reason), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                report.description?.let { Text(stringResource(R.string.moderation_description, it), color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.moderation_reporter, report.reporterId), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                report.actionTaken?.let {
                    Text(stringResource(R.string.moderation_action_taken, reportActionLabel(it)), color = Primary, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(500) },
                    label = { Text(stringResource(R.string.moderation_resolution_note)) },
                    minLines = 2,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
                        cursorColor = Primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (canDeleteContent) {
                        TextButton(enabled = !isUpdating, onClick = { onAction("DELETE_CONTENT", note.trim().takeIf { it.isNotBlank() }) }) {
                            Text(stringResource(R.string.moderation_delete_content), color = Error)
                        }
                    }
                    TextButton(enabled = !isUpdating, onClick = { onAction("RESTRICT_MESSAGES_24H", note.trim().takeIf { it.isNotBlank() }) }) {
                        Text(stringResource(R.string.moderation_restrict_messages), color = Error)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = !isUpdating, onClick = { onAction("RESTRICT_POSTS_7D", note.trim().takeIf { it.isNotBlank() }) }) {
                        Text(stringResource(R.string.moderation_restrict_posts), color = Error)
                    }
                    TextButton(enabled = !isUpdating, onClick = { onAction("SUSPEND_24H", note.trim().takeIf { it.isNotBlank() }) }) {
                        Text(stringResource(R.string.moderation_suspend_account), color = Error)
                    }
                    TextButton(enabled = !isUpdating, onClick = { onAction("NO_ACTION", note.trim().takeIf { it.isNotBlank() }) }) {
                        Text(stringResource(R.string.moderation_no_action), color = TextSecondary)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = !isUpdating, onClick = { onUpdate("IN_REVIEW", note.trim().takeIf { it.isNotBlank() }) }) {
                        Text(stringResource(R.string.moderation_mark_in_review), color = Primary)
                    }
                    TextButton(enabled = !isUpdating, onClick = { onUpdate("REJECTED", note.trim().takeIf { it.isNotBlank() }) }) {
                        Text(stringResource(R.string.moderation_reject), color = Error)
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isUpdating) { Text(stringResource(R.string.common_cancel), color = TextSecondary) } }
    )
}

/**
 * 「新消息通知」页 — 当前使用 DataStore / SharedPreferences 控制 App 内通知设置
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 部分资源字符串在回调内读取，lint 无法区分；组合作用域内已用 stringResource
fun NotificationSettingsScreen(
    onBack: () -> Unit = {},
    viewModel: NotificationSettingsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDndDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val ringtoneDefault = stringResource(R.string.notifications_ringtone_default)
    // 8.48：通知铃声选择（RingtoneManager picker；空 = 系统默认）
    var ringtoneUri by remember { mutableStateOf(com.maodouchat.notification.NotificationPreferences.ringtoneUri(context)) }
    val ringtoneTitle = remember(ringtoneUri) {
        ringtoneUri?.let { uri ->
            runCatching {
                android.media.RingtoneManager.getRingtone(context, android.net.Uri.parse(uri))?.getTitle(context)
            }.getOrNull()
        } ?: ringtoneDefault
    }
    val ringtonePicker = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.getParcelableExtra<android.net.Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)?.toString()
        ringtoneUri = uri
        com.maodouchat.notification.NotificationPreferences.setRingtoneUri(context, uri)
        com.maodouchat.util.AppNotifier.ensureChannels(context)
    }

    // 0.72：群聊独立通知铃声
    var groupRingtoneUri by remember { mutableStateOf(com.maodouchat.notification.NotificationPreferences.groupRingtoneUri(context)) }
    val groupRingtoneTitle = remember(groupRingtoneUri) {
        groupRingtoneUri?.let { uri ->
            runCatching {
                android.media.RingtoneManager.getRingtone(context, android.net.Uri.parse(uri))?.getTitle(context)
            }.getOrNull()
        } ?: ringtoneDefault
    }
    val groupRingtonePicker = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.getParcelableExtra<android.net.Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)?.toString()
        groupRingtoneUri = uri
        com.maodouchat.notification.NotificationPreferences.setGroupRingtoneUri(context, uri)
        com.maodouchat.util.AppNotifier.ensureChannels(context)
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_notifications), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = Primary, modifier = Modifier.size(28.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
        )

        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Spacer(modifier = Modifier.height(8.dp))
            if (state.isLoading || state.isSaving || state.errorMessage != null || state.infoMessage != null) {
                Text(
                    text = when {
                        state.isLoading -> stringResource(R.string.notifications_syncing)
                        state.isSaving -> stringResource(R.string.notifications_saving)
                        state.errorMessage != null -> state.errorMessage
                        else -> state.infoMessage
                    } ?: "",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.errorMessage != null) Error else TextSecondary
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            ) {
                val pushSubtitle = when {
                    state.pushReady -> stringResource(R.string.notifications_push_ready)
                    state.pushConfigured -> stringResource(R.string.notifications_push_configured_not_ready)
                    else -> stringResource(R.string.notifications_push_missing)
                }
                ActionRow(
                    label = stringResource(R.string.notifications_push_channel_title),
                    subtitle = pushSubtitle + "\n" + stringResource(R.string.notifications_push_vendor_note),
                    onClick = { viewModel.refreshPushStatus() }
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                SwitchRow(
                    title = stringResource(R.string.notifications_enable_title),
                    subtitle = stringResource(R.string.notifications_enable_subtitle),
                    checked = state.enableNotifications,
                    onCheckedChange = { viewModel.setEnableNotifications(it) }
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                SwitchRow(
                    title = stringResource(R.string.notifications_sound_title),
                    subtitle = stringResource(R.string.notifications_sound_subtitle),
                    checked = state.soundEnabled,
                    onCheckedChange = { viewModel.setSoundEnabled(it) },
                    enabled = state.enableNotifications
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                // 1.133：震动开关（渠道级）
                SwitchRow(
                    title = stringResource(R.string.notifications_vibration_title),
                    subtitle = stringResource(R.string.notifications_vibration_subtitle),
                    checked = state.vibrationEnabled,
                    onCheckedChange = { viewModel.setVibrationEnabled(it) },
                    enabled = state.enableNotifications
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                // 1.119：发送测试通知（验证铃声/震动设置生效）
                ActionRow(
                    label = stringResource(R.string.notifications_test_title),
                    subtitle = stringResource(R.string.notifications_test_subtitle),
                    enabled = state.enableNotifications
                ) {
                    com.maodouchat.util.AppNotifier.showTestNotification(context)
                    Toast.makeText(context, context.getString(R.string.notifications_test_sent), Toast.LENGTH_SHORT).show()
                }
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                ActionRow(
                    label = stringResource(R.string.notifications_ringtone_title),
                    subtitle = ringtoneTitle,
                    enabled = state.enableNotifications
                ) {
                    val intent = android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_NOTIFICATION)
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, context.getString(R.string.notifications_ringtone_title))
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        ringtoneUri?.let { putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, android.net.Uri.parse(it)) }
                    }
                    ringtonePicker.launch(intent)
                }
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                // 0.72：群聊独立铃声（单独选择器，空 = 回退单聊铃声）
                ActionRow(
                    label = stringResource(R.string.notifications_group_ringtone_title),
                    subtitle = groupRingtoneTitle,
                    enabled = state.enableNotifications
                ) {
                    val intent = android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_NOTIFICATION)
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, context.getString(R.string.notifications_group_ringtone_title))
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        groupRingtoneUri?.let { putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, android.net.Uri.parse(it)) }
                    }
                    groupRingtonePicker.launch(intent)
                }
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                SwitchRow(
                    title = stringResource(R.string.notifications_preview_title),
                    subtitle = stringResource(R.string.notifications_preview_subtitle),
                    checked = state.previewEnabled,
                    onCheckedChange = { viewModel.setPreviewEnabled(it) },
                    enabled = state.enableNotifications
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                SwitchRow(
                    title = stringResource(R.string.notifications_task_reminders_title),
                    subtitle = stringResource(R.string.notifications_task_reminders_subtitle),
                    checked = state.taskRemindersEnabled,
                    onCheckedChange = { viewModel.setTaskRemindersEnabled(it) },
                    enabled = state.enableNotifications
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            ) {
                ActionRow(label = stringResource(R.string.notifications_ringtone_title), subtitle = stringResource(R.string.notifications_ringtone_subtitle), enabled = state.enableNotifications, onClick = { viewModel.setRingtoneEnabled(!state.ringtoneEnabled) })
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                SwitchRow(
                    title = stringResource(R.string.notifications_dnd_schedule_title),
                    subtitle = stringResource(R.string.notifications_dnd_schedule_subtitle),
                    checked = state.dndEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.setDndSchedule(enabled, state.dndStartMinute, state.dndEndMinute)
                    },
                    enabled = state.enableNotifications
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                ActionRow(
                    label = stringResource(R.string.notifications_dnd_title),
                    subtitle = if (state.dndEnabled) {
                        stringResource(
                            R.string.notifications_dnd_schedule_active,
                            formatDndTime(state.dndStartMinute),
                            formatDndTime(state.dndEndMinute)
                        )
                    } else {
                        stringResource(R.string.notifications_dnd_schedule_off)
                    },
                    enabled = state.enableNotifications,
                    onClick = { showDndDialog = true }
                )
            }
        }

        if (showDndDialog) {
            // 以已保存值为键：弹窗打开后 VM 从服务端同步更新 state 时，本地草稿重新初始化为最新值，
            // 避免出现“已保存 23:30-06:00，弹窗却显示默认 22:00-07:00”的陈旧值。
            var enabled by remember(state.dndEnabled) { mutableStateOf(state.dndEnabled) }
            var startMinute by remember(state.dndStartMinute) { mutableIntStateOf(state.dndStartMinute) }
            var endMinute by remember(state.dndEndMinute) { mutableIntStateOf(state.dndEndMinute) }
            val activity = LocalContext.current.findActivity()
            val dndPresets = listOf(
                Triple(22 * 60, 7 * 60, R.string.notifications_dnd_preset_night),
                Triple(12 * 60, 14 * 60, R.string.notifications_dnd_preset_noon),
                Triple(0, 8 * 60, R.string.notifications_dnd_preset_sleep),
                Triple(18 * 60, 9 * 60, R.string.notifications_dnd_preset_evening),
                Triple(9 * 60, 18 * 60, R.string.notifications_dnd_preset_workday),
                Triple(14 * 60, 17 * 60, R.string.notifications_dnd_preset_focus),
                Triple(8 * 60, 12 * 60, R.string.notifications_dnd_preset_morning),
                Triple(13 * 60, 15 * 60, R.string.notifications_dnd_preset_siesta),
                Triple(21 * 60, 23 * 60, R.string.notifications_dnd_preset_late_evening),
                Triple(23 * 60, 6 * 60, R.string.notifications_dnd_preset_deep_night),
                Triple(5 * 60, 9 * 60, R.string.notifications_dnd_preset_early_morning)
            )
            AlertDialog(
                onDismissRequest = { showDndDialog = false },
                title = { Text(stringResource(R.string.notifications_dnd_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.notifications_dnd_subtitle), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.notifications_dnd_schedule_title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            Switch(checked = enabled, onCheckedChange = { enabled = it })
                        }
                        Text(stringResource(R.string.notifications_dnd_presets), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            dndPresets.forEach { (start, end, labelRes) ->
                                FilterChip(
                                    selected = startMinute == start && endMinute == end,
                                    onClick = {
                                        startMinute = start
                                        endMinute = end
                                    },
                                    label = { Text(stringResource(labelRes)) }
                                )
                            }
                        }
                        DndTimeRow(
                            label = stringResource(R.string.notifications_dnd_start_time),
                            minute = startMinute,
                            enabled = enabled,
                            onPick = {
                                if (activity != null) {
                                    android.app.TimePickerDialog(
                                        activity,
                                        { _, h, m -> startMinute = h * 60 + m },
                                        startMinute / 60,
                                        startMinute % 60,
                                        true
                                    ).show()
                                }
                            }
                        )
                        DndTimeRow(
                            label = stringResource(R.string.notifications_dnd_end_time),
                            minute = endMinute,
                            enabled = enabled,
                            onPick = {
                                if (activity != null) {
                                    android.app.TimePickerDialog(
                                        activity,
                                        { _, h, m -> endMinute = h * 60 + m },
                                        endMinute / 60,
                                        endMinute % 60,
                                        true
                                    ).show()
                                }
                            }
                        )
                        Text(
                            stringResource(R.string.notifications_dnd_overnight_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.setDndSchedule(enabled, startMinute, endMinute)
                        showDndDialog = false
                    }) { Text(stringResource(R.string.common_save), color = Primary) }
                },
                dismissButton = { TextButton(onClick = { showDndDialog = false }) { Text(stringResource(R.string.common_cancel), color = TextSecondary) } }
            )
        }
    }
}

@Composable
private fun DndTimeRow(label: String, minute: Int, enabled: Boolean, onPick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onPick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(formatDndTime(minute), style = MaterialTheme.typography.bodyMedium, color = if (enabled) Primary else TextSecondary)
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
    }
}

private fun formatDndTime(minuteOfDay: Int): String {
    val safe = minuteOfDay.coerceIn(0, 1439)
    val h = safe / 60
    val m = safe % 60
    return "%02d:%02d".format(h, m)
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) OnSurface else TextHint)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (enabled) TextSecondary else TextHint)
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * 「通用」页 — 深色模式 + 缓存清理 + 关于
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调内读取，非组合作用域
fun GeneralSettingsScreen(
    onBack: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenWatermarkForensic: () -> Unit = {},
    onOpenDeveloperBots: () -> Unit = {},
    viewModel: GeneralSettingsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showClearConfirm by remember { mutableStateOf(false) }
    // 1.04：语言选择
    var showLanguageDialog by remember { mutableStateOf(false) }
    // 9.200：主题风格选择（TG 1:1 还原主题）
    var showThemeStyleDialog by remember { mutableStateOf(false) }
    var customWallpaperUri by remember {
        mutableStateOf(com.maodouchat.util.ChatAppearancePreferences.getCustomWallpaperUri(context))
    }
    val customWallpaperPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val stored = com.maodouchat.util.ChatAppearancePreferences.persistCustomWallpaper(context, uri.toString())
            if (stored != null) {
                customWallpaperUri = stored
                Toast.makeText(context, context.getString(R.string.general_custom_wallpaper_set), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, context.getString(R.string.general_custom_wallpaper_set_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_general), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = Primary, modifier = Modifier.size(28.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
        )

        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            ) {
                ThemeRow(currentTheme = state.themeMode, onThemeChange = viewModel::setThemeMode)
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                ActionRow(
                    label = stringResource(R.string.general_theme_style_title),
                    subtitle = stringResource(
                        R.string.general_theme_style_summary,
                        themeStyleName(state.themeStyle)
                    )
                ) { showThemeStyleDialog = true }
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                GlassBottomBarSwitchRow(
                    enabled = state.glassBottomBar,
                    onEnabledChange = viewModel::setGlassBottomBar
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                LanguageRow(
                    currentLanguage = state.languageMode,
                    onLanguageChange = { mode ->
                        viewModel.setLanguageMode(mode)
                        // 预 Android 13：AppLocaleManager.setMode 收到的是 Application 上下文，
                        // (context as? Activity)?.recreate() 不会触发，必须在此用 Activity 上下文重建
                        // 才能使 attachBaseContext 的 wrap() 重新套用语言，否则需重启 App 才生效。
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            context.findActivity()?.recreate()
                        }
                    }
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                LinkPreviewSwitchRow(
                    enabled = state.linkPreviewEnabled,
                    onEnabledChange = viewModel::setLinkPreviewEnabled
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                UnreadPrioritySwitchRow(
                    enabled = state.unreadPriorityEnabled,
                    onEnabledChange = viewModel::setUnreadPriorityEnabled
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                // 1.175：回车发送（本地偏好）
                EnterToSendSwitchRow(
                    enabled = com.maodouchat.util.ComposerPreferences.enterToSend(context),
                    onEnabledChange = { com.maodouchat.util.ComposerPreferences.setEnterToSend(context, it) }
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                // 1.89：媒体自动下载（仅 Wi-Fi / 始终 / 关闭）
                MediaAutoDownloadRow(
                    currentMode = state.mediaAutoDownloadMode,
                    onModeChange = viewModel::setMediaAutoDownloadMode
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                ChatWallpaperRow(
                    current = state.chatWallpaper,
                    onChange = viewModel::setChatWallpaper,
                    customWallpaperUri = customWallpaperUri,
                    onPickCustomWallpaper = {
                        customWallpaperPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onClearCustomWallpaper = {
                        com.maodouchat.util.ChatAppearancePreferences.clearCustomWallpaperUri(context)
                        customWallpaperUri = null
                        Toast.makeText(context, context.getString(R.string.general_custom_wallpaper_cleared), Toast.LENGTH_SHORT).show()
                    }
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                ChatBubbleColorRow(
                    current = com.maodouchat.util.ChatAppearancePreferences.getBubbleColor(context),
                    onChange = { id ->
                        com.maodouchat.util.ChatAppearancePreferences.setBubbleColor(context, id)
                    }
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                ChatFontScaleRow(
                    current = state.chatFontScale,
                    onChange = viewModel::setChatFontScale
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                // 1.04：语言设置（系统/中文/English，云同步）
                ActionRow(
                    label = stringResource(R.string.general_language),
                    subtitle = when (state.languageMode) {
                        com.maodouchat.util.AppLocaleManager.MODE_CHINESE -> stringResource(R.string.general_language_chinese)
                        com.maodouchat.util.AppLocaleManager.MODE_ENGLISH -> stringResource(R.string.general_language_english)
                        else -> stringResource(R.string.general_language_system)
                    }
                ) { showLanguageDialog = true }
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                ActionRow(label = stringResource(R.string.general_clear_cache), subtitle = stringResource(R.string.general_cache_summary, state.cacheSizeText)) {
                    showClearConfirm = true
                }
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                ActionRow(label = stringResource(R.string.general_about), subtitle = stringResource(R.string.general_about_summary)) {
                    onOpenAbout()
                }
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                ActionRow(label = stringResource(R.string.watermark_forensic_entry), subtitle = stringResource(R.string.watermark_forensic_desc)) {
                    onOpenWatermarkForensic()
                }
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                ActionRow(label = stringResource(R.string.developer_bots_entry), subtitle = stringResource(R.string.developer_bots_desc)) {
                    onOpenDeveloperBots()
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.general_clear_cache)) },
            text = { Text(stringResource(R.string.general_clear_cache_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clearCache()
                }) { Text(stringResource(R.string.common_clear), color = Primary) }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text(stringResource(R.string.common_cancel), color = TextSecondary) } }
        )
    }

    // 9.200：主题风格选择（含 TG 1:1 还原主题，浅/深双变体预览）
    if (showThemeStyleDialog) {
        ThemeStylePickerDialog(
            currentStyle = state.themeStyle,
            onSelect = { style ->
                viewModel.setThemeStyle(style)
                showThemeStyleDialog = false
            },
            onDismiss = { showThemeStyleDialog = false }
        )
    }

    // 1.04：语言选择（系统/中文/English）
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.general_language), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column {
                    listOf(
                        com.maodouchat.util.AppLocaleManager.MODE_SYSTEM to R.string.general_language_system,
                        com.maodouchat.util.AppLocaleManager.MODE_CHINESE to R.string.general_language_chinese,
                        com.maodouchat.util.AppLocaleManager.MODE_ENGLISH to R.string.general_language_english
                    ).forEach { (mode, labelRes) ->
                        TextButton(
                            onClick = {
                                showLanguageDialog = false
                                // 9.155：此前直接 AppLocaleManager.setMode——绕过 VM 状态与
                                // pushClientPrefs，多端/重登后被云端旧值拉回；与上方
                                // LanguageRow 统一走 viewModel.setLanguageMode（含云同步），
                                // 预 Android 13 同样用 Activity 上下文重建使语言即时生效
                                viewModel.setLanguageMode(mode)
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                    context.findActivity()?.recreate()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(labelRes), color = MaterialTheme.colorScheme.onSurface) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLanguageDialog = false }) { Text(stringResource(R.string.common_cancel), color = TextSecondary) }
            }
        )
    }
}

@Composable
private fun GlassBottomBarSwitchRow(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.general_glass_bottom_bar_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                stringResource(R.string.general_glass_bottom_bar_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
}

@Composable
private fun themeStyleName(style: String): String = when (com.maodouchat.ui.theme.ThemeFamily.normalize(style)) {
    com.maodouchat.ui.theme.ThemeFamily.MAODOU -> stringResource(R.string.general_theme_style_maodou)
    com.maodouchat.ui.theme.ThemeFamily.TG_CLASSIC -> stringResource(R.string.general_theme_style_tg_classic)
    com.maodouchat.ui.theme.ThemeFamily.TG_MIDNIGHT -> stringResource(R.string.general_theme_style_tg_midnight)
    com.maodouchat.ui.theme.ThemeFamily.TG_GRAPHITE -> stringResource(R.string.general_theme_style_tg_graphite)
}

/**
 * 主题风格选择对话框：每个家族展示浅/深双变体的迷你聊天预览（背景 + 收发气泡），
 * TG 系列附「1:1 还原」标记。
 */
@Composable
private fun ThemeStylePickerDialog(
    currentStyle: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val currentFamily = com.maodouchat.ui.theme.ThemeFamily.normalize(currentStyle)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.general_theme_style_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                com.maodouchat.ui.theme.ThemeFamily.ALL.forEach { family ->
                    ThemeStyleCard(
                        family = family,
                        selected = family == currentFamily,
                        onClick = { onSelect(family.id) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = TextSecondary) }
        }
    )
}

@Composable
private fun ThemeStyleCard(
    family: com.maodouchat.ui.theme.ThemeFamily,
    selected: Boolean,
    onClick: () -> Unit
) {
    val lightPaint = remember(family) { com.maodouchat.ui.theme.resolveThemePaint(family, dark = false) }
    val darkPaint = remember(family) { com.maodouchat.ui.theme.resolveThemePaint(family, dark = true) }
    val name = when (family) {
        com.maodouchat.ui.theme.ThemeFamily.MAODOU -> stringResource(R.string.general_theme_style_maodou)
        com.maodouchat.ui.theme.ThemeFamily.TG_CLASSIC -> stringResource(R.string.general_theme_style_tg_classic)
        com.maodouchat.ui.theme.ThemeFamily.TG_MIDNIGHT -> stringResource(R.string.general_theme_style_tg_midnight)
        com.maodouchat.ui.theme.ThemeFamily.TG_GRAPHITE -> stringResource(R.string.general_theme_style_tg_graphite)
    }
    val isTg = family != com.maodouchat.ui.theme.ThemeFamily.MAODOU
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
            .then(
                if (selected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                else Modifier.border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            )
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (isTg) {
                Text(
                    stringResource(R.string.general_theme_style_tg_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        listOf(lightPaint, darkPaint).forEach { paint ->
            ThemePreviewStrip(paint = paint)
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

/** 迷你聊天预览条：主题背景 + 收/发气泡，直观展示配色。 */
@Composable
private fun ThemePreviewStrip(paint: com.maodouchat.ui.theme.ThemePaint) {
    // maodou 家族无专属发送气泡：深色变体用深色系品牌蓝，与聊天页一致
    val sentColor = paint.sentBubbleSpec?.color
        ?: if (paint.chatPalette === com.maodouchat.ui.theme.DarkChatPalette) Color(0xFF0A84FF)
        else com.maodouchat.ui.theme.ChatBubbleSent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(paint.chatPalette.chatBackground)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(paint.chatPalette.chatBubbleReceived)
                .then(
                    Modifier.border(
                        0.5.dp,
                        paint.chatPalette.chatBubbleReceivedBorder,
                        RoundedCornerShape(9.dp)
                    )
                )
        )
        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .width(52.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(sentColor)
        )
    }
}

@Composable
private fun ThemeRow(currentTheme: String, onThemeChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(stringResource(R.string.general_theme_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChoiceChip(stringResource(R.string.general_theme_system), selected = currentTheme == "system", onClick = { onThemeChange("system") })
            ThemeChoiceChip(stringResource(R.string.general_theme_light), selected = currentTheme == "light", onClick = { onThemeChange("light") })
            ThemeChoiceChip(stringResource(R.string.general_theme_dark), selected = currentTheme == "dark", onClick = { onThemeChange("dark") })
        }
    }
}

private fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun LanguageRow(currentLanguage: String, onLanguageChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(stringResource(R.string.general_language_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChoiceChip(stringResource(R.string.general_language_system), selected = currentLanguage == AppLocaleManager.MODE_SYSTEM, onClick = { onLanguageChange(AppLocaleManager.MODE_SYSTEM) })
            ThemeChoiceChip(stringResource(R.string.general_language_chinese), selected = currentLanguage == AppLocaleManager.MODE_CHINESE, onClick = { onLanguageChange(AppLocaleManager.MODE_CHINESE) })
            ThemeChoiceChip(stringResource(R.string.general_language_english), selected = currentLanguage == AppLocaleManager.MODE_ENGLISH, onClick = { onLanguageChange(AppLocaleManager.MODE_ENGLISH) })
        }
    }
}

@Composable
private fun LinkPreviewSwitchRow(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.general_link_preview_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                stringResource(R.string.general_link_preview_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
}

@Composable
private fun UnreadPrioritySwitchRow(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.general_unread_priority_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                stringResource(R.string.general_unread_priority_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
}

// 1.175：回车发送
@Composable
private fun EnterToSendSwitchRow(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.general_enter_to_send_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                stringResource(R.string.general_enter_to_send_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
}

// 1.89：媒体自动下载档位（仅 Wi-Fi / 始终 / 关闭）
@Composable
private fun MediaAutoDownloadRow(
    currentMode: String,
    onModeChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(
            stringResource(R.string.general_media_auto_download_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            stringResource(R.string.general_media_auto_download_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChoiceChip(
                stringResource(R.string.general_media_auto_download_wifi),
                selected = currentMode == com.maodouchat.util.MediaAutoDownloadPreferences.MODE_WIFI_ONLY,
                onClick = { onModeChange(com.maodouchat.util.MediaAutoDownloadPreferences.MODE_WIFI_ONLY) }
            )
            ThemeChoiceChip(
                stringResource(R.string.general_media_auto_download_always),
                selected = currentMode == com.maodouchat.util.MediaAutoDownloadPreferences.MODE_ALWAYS,
                onClick = { onModeChange(com.maodouchat.util.MediaAutoDownloadPreferences.MODE_ALWAYS) }
            )
            ThemeChoiceChip(
                stringResource(R.string.general_media_auto_download_off),
                selected = currentMode == com.maodouchat.util.MediaAutoDownloadPreferences.MODE_OFF,
                onClick = { onModeChange(com.maodouchat.util.MediaAutoDownloadPreferences.MODE_OFF) }
            )
        }
    }
}

@Composable
private fun ChatWallpaperRow(
    current: String,
    onChange: (String) -> Unit,
    customWallpaperUri: String? = null,
    onPickCustomWallpaper: () -> Unit = {},
    onClearCustomWallpaper: () -> Unit = {}
) {
    val options = listOf(
        com.maodouchat.util.ChatWallpaperPreset.DEFAULT.id to stringResource(R.string.general_chat_wallpaper_default),
        com.maodouchat.util.ChatWallpaperPreset.MINT.id to stringResource(R.string.general_chat_wallpaper_mint),
        com.maodouchat.util.ChatWallpaperPreset.LAVENDER.id to stringResource(R.string.general_chat_wallpaper_lavender),
        com.maodouchat.util.ChatWallpaperPreset.SAND.id to stringResource(R.string.general_chat_wallpaper_sand),
        com.maodouchat.util.ChatWallpaperPreset.NIGHT.id to stringResource(R.string.general_chat_wallpaper_night),
        com.maodouchat.util.ChatWallpaperPreset.ROSE.id to stringResource(R.string.general_chat_wallpaper_rose),
        com.maodouchat.util.ChatWallpaperPreset.SKY.id to stringResource(R.string.general_chat_wallpaper_sky),
        com.maodouchat.util.ChatWallpaperPreset.SLATE.id to stringResource(R.string.general_chat_wallpaper_slate),
        com.maodouchat.util.ChatWallpaperPreset.PEACH.id to stringResource(R.string.general_chat_wallpaper_peach),
        com.maodouchat.util.ChatWallpaperPreset.OLIVE.id to stringResource(R.string.general_chat_wallpaper_olive),
        com.maodouchat.util.ChatWallpaperPreset.CORAL.id to stringResource(R.string.general_chat_wallpaper_coral),
        com.maodouchat.util.ChatWallpaperPreset.PLUM.id to stringResource(R.string.general_chat_wallpaper_plum),
        com.maodouchat.util.ChatWallpaperPreset.INDIGO.id to stringResource(R.string.general_chat_wallpaper_indigo),
        com.maodouchat.util.ChatWallpaperPreset.AMBER.id to stringResource(R.string.general_chat_wallpaper_amber),
            com.maodouchat.util.ChatWallpaperPreset.TEAL.id to stringResource(R.string.general_chat_wallpaper_teal),
            com.maodouchat.util.ChatWallpaperPreset.GRAPHITE.id to stringResource(R.string.general_chat_wallpaper_graphite),
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(stringResource(R.string.general_chat_wallpaper_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(2.dp))
        Text(stringResource(R.string.general_chat_wallpaper_subtitle), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            options.forEach { (id, label) ->
                ThemeChoiceChip(label, selected = current == id, onClick = { onChange(id) })
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChoiceChip(
                stringResource(if (customWallpaperUri != null) R.string.general_custom_wallpaper_active else R.string.general_custom_wallpaper),
                selected = customWallpaperUri != null,
                onClick = onPickCustomWallpaper
            )
            if (customWallpaperUri != null) {
                TextButton(onClick = onClearCustomWallpaper) {
                    Text(stringResource(R.string.general_custom_wallpaper_clear), color = UnreadRed)
                }
            }
        }
        Text(
            stringResource(R.string.general_custom_wallpaper_hint),
            style = MaterialTheme.typography.labelSmall,
            color = TextHint
        )
    }
}

@Composable
private fun ChatBubbleColorRow(
    current: String,
    onChange: (String) -> Unit
) {
    val options = listOf(
        com.maodouchat.ui.theme.ChatBubbleColorPalette.BLUE to stringResource(R.string.general_chat_bubble_blue),
        com.maodouchat.ui.theme.ChatBubbleColorPalette.GREEN to stringResource(R.string.general_chat_bubble_green),
        com.maodouchat.ui.theme.ChatBubbleColorPalette.PURPLE to stringResource(R.string.general_chat_bubble_purple),
        com.maodouchat.ui.theme.ChatBubbleColorPalette.ORANGE to stringResource(R.string.general_chat_bubble_orange),
        com.maodouchat.ui.theme.ChatBubbleColorPalette.PINK to stringResource(R.string.general_chat_bubble_pink),
        com.maodouchat.ui.theme.ChatBubbleColorPalette.TEAL to stringResource(R.string.general_chat_bubble_teal)
    )
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(stringResource(R.string.general_chat_bubble_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(2.dp))
        Text(stringResource(R.string.general_chat_bubble_subtitle), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (id, label) ->
                val color = if (isDark) com.maodouchat.ui.theme.ChatBubbleColorPalette.dark(id)
                else com.maodouchat.ui.theme.ChatBubbleColorPalette.light(id)
                ThemeChoiceChip(
                    label = label,
                    selected = current == id,
                    onClick = { onChange(id) }
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatFontScaleRow(
    current: String,
    onChange: (String) -> Unit
) {
    val options = listOf(
        com.maodouchat.util.ChatFontScale.SMALL.id to stringResource(R.string.general_chat_font_small),
        com.maodouchat.util.ChatFontScale.NORMAL.id to stringResource(R.string.general_chat_font_normal),
        com.maodouchat.util.ChatFontScale.LARGE.id to stringResource(R.string.general_chat_font_large),
        com.maodouchat.util.ChatFontScale.XLARGE.id to stringResource(R.string.general_chat_font_xlarge),
        com.maodouchat.util.ChatFontScale.XXLARGE.id to stringResource(R.string.general_chat_font_xxlarge),
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(stringResource(R.string.general_chat_font_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(2.dp))
        Text(stringResource(R.string.general_chat_font_subtitle), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (id, label) ->
                ThemeChoiceChip(label, selected = current == id, onClick = { onChange(id) })
            }
        }
    }
}

@Composable
private fun ThemeChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null
) {
    val backgroundColor by animateColorAsState(if (selected) Primary else LocalChatPalette.current.chatInputBackground, tween(180), label = "choiceBackground")
    val textColor by animateColorAsState(if (selected) Color.White else OnSurface, tween(180), label = "choiceText")
    val scale by animateFloatAsState(if (selected) 1f else 0.98f, tween(180), label = "choiceScale")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(backgroundColor, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clickable { onClick() }
    ) {
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(label, color = textColor, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun aiFeatureLabel(feature: String): String = when (feature.lowercase()) {
    "rewrite" -> stringResource(R.string.ai_feature_rewrite)
    "suggest_replies" -> stringResource(R.string.ai_feature_suggest_replies)
    "summarize" -> stringResource(R.string.ai_feature_summarize)
    "transcribe_voice" -> stringResource(R.string.ai_feature_transcribe_voice)
    "translate_message" -> stringResource(R.string.ai_feature_translate_message)
    "semantic_search" -> stringResource(R.string.ai_feature_semantic_search)
    "global_semantic_search" -> stringResource(R.string.ai_feature_global_semantic_search)
    "group_assistant" -> stringResource(R.string.ai_feature_group_assistant)
    "image_analyze" -> stringResource(R.string.ai_feature_image_analyze)
    "file_analyze" -> stringResource(R.string.ai_feature_file_analyze)
    else -> if (feature.isBlank()) stringResource(R.string.ai_feature_generic) else feature
}

@Composable
private fun aiStatusLabel(status: String): String = when (status.lowercase()) {
    "success" -> stringResource(R.string.ai_status_success)
    "failed", "error" -> stringResource(R.string.ai_status_failed)
    "disabled" -> stringResource(R.string.ai_status_disabled)
    "rate_limited" -> stringResource(R.string.ai_status_rate_limited)
    else -> if (status.isBlank()) stringResource(R.string.ai_status_unknown) else status
}

@Composable
private fun reportStatusLabel(status: String): String = when (status) {
    "OPEN" -> stringResource(R.string.moderation_status_open)
    "IN_REVIEW" -> stringResource(R.string.moderation_status_in_review)
    "RESOLVED" -> stringResource(R.string.moderation_status_resolved)
    "REJECTED" -> stringResource(R.string.moderation_status_rejected)
    "ALL" -> stringResource(R.string.moderation_status_all)
    else -> status
}

@Composable
private fun reportTargetLabel(type: String): String = when (type) {
    "USER" -> stringResource(R.string.moderation_target_user)
    "MESSAGE" -> stringResource(R.string.moderation_target_message)
    "MESSAGE_META" -> stringResource(R.string.moderation_target_message_meta)
    "POST" -> stringResource(R.string.moderation_target_post)
    "COMMENT" -> stringResource(R.string.moderation_target_comment)
    "ALL" -> stringResource(R.string.moderation_target_all_public)
    else -> type
}

@Composable
private fun riskActionLabel(action: String): String = when (action) {
    "WARN_MOD" -> stringResource(R.string.moderation_action_review)
    "AUTO_HOLD" -> stringResource(R.string.moderation_action_hold)
    "AUTO_DELETE" -> stringResource(R.string.moderation_action_delete)
    "AUTO_RATE_LIMIT" -> stringResource(R.string.moderation_action_rate_limit)
    "OBSERVED" -> stringResource(R.string.moderation_action_observed)
    else -> action
}

@Composable
private fun reportActionLabel(action: String): String = when (action) {
    "DELETE_CONTENT" -> stringResource(R.string.moderation_result_deleted)
    "NO_ACTION" -> stringResource(R.string.moderation_no_action)
    "RESTRICT_MESSAGES_24H" -> stringResource(R.string.moderation_result_messages_restricted)
    "RESTRICT_POSTS_7D" -> stringResource(R.string.moderation_result_posts_restricted)
    "SUSPEND_24H" -> stringResource(R.string.moderation_result_suspended)
    else -> action
}

@Composable
private fun aiStatusColor(status: String): Color = when (status.lowercase()) {
    "success" -> Primary
    "failed", "error", "rate_limited" -> Error
    else -> TextHint
}

@Composable
private fun reportStatusColor(status: String): Color = when (status) {
    "OPEN" -> Error
    "IN_REVIEW" -> Primary
    "RESOLVED" -> TextSecondary
    "REJECTED" -> TextHint
    else -> TextSecondary
}

private fun formatAuditTime(timestamp: Long): String =
    SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(timestamp))

@Composable
@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
private fun SettingsSubScreensPreview() {
    MaodouchatTheme { GeneralSettingsScreen(onBack = {}) }
}

/**
 * 「服务器」页 — 运行时配置 API 服务器地址（8.45，免重新构建 APK）。
 *
 * 部署方安装通用 APK 后，在此填写自建服务器地址即可使用。服务器属于独立信任域；
 * 切换时会清理当前账号凭据和本机加密数据，再要求使用目标服务器账号登录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsScreen(
    onBack: () -> Unit = {},
    onServerChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val currentBase = remember { com.maodouchat.network.ApiConfig.BASE_URL }
    val currentWs = remember { com.maodouchat.network.ApiConfig.WS_URL }
    var input by remember { mutableStateOf(currentBase) }
    var result by remember { mutableStateOf<String?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val serverSavedText = stringResource(R.string.settings_server_saved)
    val serverTestingText = stringResource(R.string.settings_server_testing)
    val serverTestSuccessText = stringResource(R.string.settings_server_test_success)
    val serverTestFailedText = stringResource(R.string.settings_server_test_failed)
    val serverResetDoneText = stringResource(R.string.settings_server_reset_done)

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_server), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = Primary, modifier = Modifier.size(28.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
        )

        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Spacer(modifier = Modifier.height(8.dp))
            // 9.202：第三方服务器模式身份卡（名称/简介/公告/版本）
            if (com.maodouchat.network.ApiConfig.isUsingRuntimeServer) {
                ThirdPartyServerCard()
                Spacer(modifier = Modifier.height(12.dp))
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            ) {
                Text(
                    text = stringResource(R.string.settings_server_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(16.dp)
                )
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = Divider, modifier = Modifier.padding(start = 16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.settings_server_current),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = currentBase,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (com.maodouchat.network.ApiConfig.isUsingRuntimeServer) Primary else TextHint
                    )
                }
                androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = Divider, modifier = Modifier.padding(start = 16.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.settings_server_input_label)) },
                    placeholder = { Text("https://chat.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Outline
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TextButton(
                    onClick = {
                        if (isWorking) return@TextButton
                        isWorking = true
                        result = serverTestingText
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                com.maodouchat.network.ApiConfig.testConnection(input)
                            }
                            result = if (ok) serverTestSuccessText else serverTestFailedText
                            isWorking = false
                        }
                    },
                    enabled = !isWorking,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.settings_server_test))
                }

                Button(
                    onClick = {
                        if (isWorking) return@Button
                        val validationError = com.maodouchat.network.ApiConfig.validateServerAddress(input, context)
                        if (validationError != null) {
                            result = validationError
                            return@Button
                        }
                        isWorking = true
                        result = serverTestingText
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                com.maodouchat.network.ApiConfig.testConnection(input)
                            }
                            if (!ok) {
                                result = serverTestFailedText
                                isWorking = false
                            } else {
                                when (val change = com.maodouchat.network.ApiConfig.switchServer(input, context)) {
                                    is com.maodouchat.network.ApiConfig.ServerChangeResult.Failed -> {
                                        result = change.message
                                    }
                                    com.maodouchat.network.ApiConfig.ServerChangeResult.Unchanged -> {
                                        result = serverTestSuccessText
                                    }
                                    com.maodouchat.network.ApiConfig.ServerChangeResult.Changed -> {
                                        result = serverSavedText
                                        com.maodouchat.MaodouchatApp.instance.rebuildImageLoader()
                                        com.maodouchat.network.WebSocketClient.disconnect()
                                        com.maodouchat.slim.OnDemandStickerStore.invalidateServerState()
                                        com.maodouchat.network.ServerIdentity.refreshAsync()
                                        onServerChanged()
                                    }
                                }
                                isWorking = false
                            }
                        }
                    },
                    enabled = !isWorking,
                    modifier = Modifier.weight(1.4f)
                ) {
                    Text(stringResource(R.string.settings_server_save))
                }
            }

            Text(
                text = result ?: stringResource(R.string.settings_server_ws_hint),
                style = MaterialTheme.typography.bodySmall,
                color = result?.let {
                    if (it == serverSavedText || it == serverTestSuccessText || it == serverTestingText) Primary else Error
                } ?: TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            ) {
                ActionRow(label = stringResource(R.string.settings_server_reset), subtitle = stringResource(R.string.settings_server_reset_subtitle)) {
                    showResetConfirm = true
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.settings_server_reset)) },
            text = { Text(stringResource(R.string.settings_server_reset_confirm)) },
            confirmButton = {
                TextButton(enabled = !isWorking, onClick = {
                    showResetConfirm = false
                    isWorking = true
                    scope.launch {
                        when (val change = com.maodouchat.network.ApiConfig.resetToDefault(context)) {
                            is com.maodouchat.network.ApiConfig.ServerChangeResult.Failed -> {
                                result = change.message
                            }
                            com.maodouchat.network.ApiConfig.ServerChangeResult.Unchanged -> {
                                input = com.maodouchat.network.ApiConfig.BASE_URL
                                result = serverResetDoneText
                            }
                            com.maodouchat.network.ApiConfig.ServerChangeResult.Changed -> {
                                input = com.maodouchat.network.ApiConfig.BASE_URL
                                result = serverResetDoneText
                                com.maodouchat.MaodouchatApp.instance.rebuildImageLoader()
                                com.maodouchat.network.WebSocketClient.disconnect()
                                com.maodouchat.slim.OnDemandStickerStore.invalidateServerState()
                                com.maodouchat.network.ServerIdentity.clear()
                                onServerChanged()
                            }
                        }
                        isWorking = false
                    }
                }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/**
 * 9.202：第三方服务器身份卡：展示当前连接服务器的名称/简介/版本与运营方公告。
 * 官方默认服务器不展示此卡片。
 */
@Composable
private fun ThirdPartyServerCard() {
    val info by com.maodouchat.network.ServerIdentity.current.collectAsState()
    LaunchedEffect(Unit) {
        com.maodouchat.network.ServerIdentity.refresh()
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = Icons.Outlined.Cloud,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = stringResource(R.string.settings_server_third_party_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.settings_server_third_party_badge),
                style = MaterialTheme.typography.labelSmall,
                color = Primary,
                modifier = Modifier
                    .background(Primary.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.settings_server_third_party_desc),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        val serverInfo = info
        if (serverInfo != null) {
            Spacer(modifier = Modifier.height(10.dp))
            androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = Divider)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = serverInfo.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (serverInfo.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = serverInfo.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            if (serverInfo.version.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_server_info_version, serverInfo.version),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextHint
                )
            }
            if (serverInfo.announcement.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_server_info_announcement),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = serverInfo.announcement,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}
@Composable
private fun TotpSetupDialog(
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
            val enabled = com.maodouchat.network.ApiService.totpStatus(token).getOrDefault(false)
            alreadyEnabled = enabled
            if (!enabled) {
                com.maodouchat.network.ApiService.setupTotp(token)
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
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = UnreadRed) }
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), color = Primary)
                } else if (backupCodes != null) {
                    Text(
                        stringResource(com.maodouchat.R.string.totp_backup_codes_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
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
                        color = TextSecondary
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
                        Text(stringResource(com.maodouchat.R.string.totp_setup_secret_label), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = s,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(s)) }) {
                                Text(stringResource(com.maodouchat.R.string.common_copy), color = Primary)
                            }
                        }
                        Text(
                            stringResource(com.maodouchat.R.string.totp_setup_scan_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
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
                TextButton(onClick = onDismiss) { Text(stringResource(com.maodouchat.R.string.totp_backup_saved), color = Primary) }
            } else if (alreadyEnabled == true) {
                // 0.77：重新生成恢复码（旧码作废）
                TextButton(
                    enabled = confirmEnabled,
                    onClick = {
                        isWorking = true
                        error = null
                        scope.launch {
                            com.maodouchat.network.ApiService.regenerateTotpCodes(token, code.trim())
                                .onSuccess { codes ->
                                    backupCodes = codes
                                    code = ""
                                }
                                .onFailure { error = context.getString(com.maodouchat.R.string.totp_setup_error) }
                            isWorking = false
                        }
                    }
                ) { Text(stringResource(com.maodouchat.R.string.totp_regenerate_codes), color = Primary) }
            } else {
                TextButton(
                    enabled = confirmEnabled,
                    onClick = {
                        isWorking = true
                        error = null
                        scope.launch {
                            com.maodouchat.network.ApiService.confirmTotp(token, code.trim())
                                .onSuccess { codes ->
                                    backupCodes = codes
                                    code = ""
                                }
                                .onFailure { error = context.getString(com.maodouchat.R.string.totp_setup_error) }
                            isWorking = false
                        }
                    }
                ) { Text(stringResource(com.maodouchat.R.string.totp_setup_confirm), color = Primary) }
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
                                com.maodouchat.network.ApiService.disableTotp(token, code.trim())
                                    .onSuccess { onDismiss() }
                                    .onFailure { error = context.getString(com.maodouchat.R.string.totp_setup_error) }
                                isWorking = false
                            }
                        }
                    ) { Text(stringResource(com.maodouchat.R.string.totp_disable), color = UnreadRed) }
                } else {
                    TextButton(onClick = onDismiss) { Text(stringResource(com.maodouchat.R.string.common_cancel), color = TextSecondary) }
                }
            }
        }
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MyReportsScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val tokenManager = remember(context) { com.maodouchat.network.TokenManager.getInstance(context) }
    val scope = rememberCoroutineScope()
    val reports = remember { mutableStateOf<List<com.maodouchat.network.ReportResponse>>(emptyList()) }
    val isLoading = remember { mutableStateOf(true) }
    val error = remember { mutableStateOf<String?>(null) }
    val loadFailedText = stringResource(com.maodouchat.R.string.my_reports_load_failed)

    suspend fun load() {
        isLoading.value = true
        error.value = null
        com.maodouchat.network.ApiService.getMyReports(tokenManager.getToken().orEmpty())
            .onSuccess { reports.value = it }
            .onFailure { error.value = loadFailedText }
        isLoading.value = false
    }
    LaunchedEffect(Unit) { load() }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(com.maodouchat.R.string.settings_my_reports), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(com.maodouchat.R.string.common_back), tint = Primary, modifier = Modifier.size(28.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
        )
        when {
            isLoading.value -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Primary)
            }
            error.value != null && reports.value.isEmpty() -> EmptyState(
                type = EmptyStateType.NETWORK_ERROR,
                title = stringResource(com.maodouchat.R.string.my_reports_load_failed),
                actionText = stringResource(com.maodouchat.R.string.chat_load_failed_retry),
                onAction = { scope.launch { load() } }
            )
            reports.value.isEmpty() -> EmptyState(
                type = EmptyStateType.GENERIC,
                title = stringResource(com.maodouchat.R.string.my_reports_empty)
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(reports.value, key = { it.id }) { report ->
                    MyReportCard(report)
                    androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun MyReportCard(report: com.maodouchat.network.ReportResponse) {
    val targetLabel = when (report.targetType) {
        "MESSAGE" -> stringResource(com.maodouchat.R.string.my_reports_target_message)
        "POST" -> stringResource(com.maodouchat.R.string.my_reports_target_post)
        else -> stringResource(com.maodouchat.R.string.my_reports_target_user)
    }
    val statusLabel = if (report.status.equals("PENDING", ignoreCase = true)) {
        stringResource(com.maodouchat.R.string.report_status_pending)
    } else {
        stringResource(com.maodouchat.R.string.report_status_resolved)
    }
    val timeText = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", androidx.compose.ui.platform.LocalConfiguration.current.locales[0])
        .format(java.util.Date(report.createdAt))
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$targetLabel · ${report.reason}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (report.status.equals("PENDING", ignoreCase = true)) Primary else TextSecondary
            )
        }
        Text(
            text = timeText,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp)
        )
        report.description?.takeIf { it.isNotBlank() }?.let { desc ->
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        report.resolutionNote?.takeIf { it.isNotBlank() }?.let { note ->
            Text(
                text = stringResource(com.maodouchat.R.string.my_reports_resolution, note),
                style = MaterialTheme.typography.bodySmall,
                color = TextHint,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun BlockedUsersScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val tokenManager = remember(context) { com.maodouchat.network.TokenManager.getInstance(context) }
    val blocked = remember { mutableStateOf<List<com.maodouchat.network.UserDto>>(emptyList()) }
    val isLoading = remember { mutableStateOf(true) }
    val error = remember { mutableStateOf<String?>(null) }
    val unblockingIds = remember { mutableStateOf<Set<String>>(emptySet()) }
    // 1.144：黑名单搜索
    var blockedSearch by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val blockedLoadFailedText = stringResource(com.maodouchat.R.string.blocked_load_failed)
    val unblockFailedText = stringResource(com.maodouchat.R.string.blocked_unblock_failed)
    val filteredBlocked = remember(blocked.value, blockedSearch) {
        val q = blockedSearch.trim()
        if (q.isBlank()) blocked.value
        else blocked.value.filter { it.name.contains(q, ignoreCase = true) || it.id.contains(q, ignoreCase = true) }
    }

    suspend fun load() {
        isLoading.value = true
        error.value = null
        com.maodouchat.network.ApiService.getBlockedUserDetails(tokenManager.getToken().orEmpty())
            .onSuccess { blocked.value = it }
            .onFailure { error.value = blockedLoadFailedText }
        isLoading.value = false
    }
    LaunchedEffect(Unit) { load() }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(com.maodouchat.R.string.settings_blocked_users), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(com.maodouchat.R.string.common_back), tint = Primary, modifier = Modifier.size(28.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
        )
        when {
            isLoading.value -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Primary)
            }
            error.value != null && blocked.value.isEmpty() -> EmptyState(
                type = EmptyStateType.NETWORK_ERROR,
                title = stringResource(com.maodouchat.R.string.blocked_load_failed),
                actionText = stringResource(com.maodouchat.R.string.chat_load_failed_retry),
                onAction = { scope.launch { load() } }
            )
            blocked.value.isEmpty() -> EmptyState(
                type = EmptyStateType.GENERIC,
                title = stringResource(com.maodouchat.R.string.blocked_empty)
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                item(key = "blocked_search", contentType = "search") {
                    androidx.compose.material3.OutlinedTextField(
                        value = blockedSearch,
                        onValueChange = { blockedSearch = it.take(100) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        placeholder = { Text(stringResource(com.maodouchat.R.string.blocked_search_hint)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                if (filteredBlocked.isEmpty()) {
                    item(key = "blocked_search_empty", contentType = "empty") {
                        Text(
                            stringResource(com.maodouchat.R.string.blocked_search_empty),
                            color = TextHint,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    items(filteredBlocked, key = { it.id }) { user ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!user.avatar.isNullOrBlank()) {
                            coil.compose.AsyncImage(
                                model = user.avatar,
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            )
                        } else {
                            Box(
                                modifier = Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(Primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(user.name.firstOrNull()?.toString() ?: "?", color = Color.White, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = user.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        val unblocking = user.id in unblockingIds.value
                        TextButton(
                            enabled = !unblocking,
                            onClick = {
                                unblockingIds.value = unblockingIds.value + user.id
                                scope.launch {
                                    com.maodouchat.network.ApiService.unblockUser(tokenManager.getToken().orEmpty(), user.id)
                                        .onSuccess { blocked.value = blocked.value.filter { it.id != user.id } }
                                        .onFailure { error.value = unblockFailedText }
                                    unblockingIds.value = unblockingIds.value - user.id
                                }
                            }
                        ) { Text(stringResource(com.maodouchat.R.string.blocked_unblock), color = Primary) }
                    }
                    androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }
}
