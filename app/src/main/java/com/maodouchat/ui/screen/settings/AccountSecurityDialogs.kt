package com.maodouchat.ui.screen.settings

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.security.SensitiveAction
import com.maodouchat.security.SensitiveActionGate
import com.maodouchat.settings.model.SettingsUiState
import com.maodouchat.ui.theme.LocalChatPalette

/** 账号安全屏尾部对话框簇：改密码 / 退出全部设备 / 删除账号 / 移除设备 / 重命名设备（从 SettingsAccountSecurityScreen.kt 纯搬移）。 */
@Composable
internal fun AccountSecurityDialogs(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    showChangeDialog: Boolean,
    onShowChangeDialogChange: (Boolean) -> Unit,
    oldPassword: String,
    onOldPasswordChange: (String) -> Unit,
    newPassword: String,
    onNewPasswordChange: (String) -> Unit,
    confirmPassword: String,
    onConfirmPasswordChange: (String) -> Unit,
    showLogoutAllConfirm: Boolean,
    onShowLogoutAllConfirmChange: (Boolean) -> Unit,
    showDeleteAccountDialog: Boolean,
    onShowDeleteAccountDialogChange: (Boolean) -> Unit,
    deletePassword: String,
    onDeletePasswordChange: (String) -> Unit,
    pendingRemoveDeviceId: Int?,
    onPendingRemoveDeviceIdChange: (Int?) -> Unit,
    renameDeviceId: Int?,
    onRenameDeviceIdChange: (Int?) -> Unit,
    renameDraft: String,
    onRenameDraftChange: (String) -> Unit,
) {
    val context = LocalContext.current

    if (showChangeDialog) {
        ChangePasswordDialog(
            oldPassword = oldPassword,
            newPassword = newPassword,
            confirmPassword = confirmPassword,
            isSaving = state.isSaving,
            errorMessage = state.errorMessage,
            onOldChange = { onOldPasswordChange(it) },
            onNewChange = { onNewPasswordChange(it) },
            onConfirmChange = { onConfirmPasswordChange(it) },
            onDismiss = {
                onShowChangeDialogChange(false)
                onOldPasswordChange(""); onNewPasswordChange(""); onConfirmPasswordChange("")
                viewModel.clearErrorMessage()
            },
            onSubmit = {
                viewModel.changePassword(oldPassword, newPassword, confirmPassword) {
                    // 成功：清空输入并关闭弹窗
                    onShowChangeDialogChange(false)
                    onOldPasswordChange(""); onNewPasswordChange(""); onConfirmPasswordChange("")
                }
            }
        )
    }

    // 8.62：退出所有设备确认
    if (showLogoutAllConfirm) {
        AlertDialog(
            onDismissRequest = {
                if (!state.isLoggingOutAll) onShowLogoutAllConfirmChange(false)
            },
            title = { Text(stringResource(R.string.account_logout_all)) },
            text = { Text(stringResource(R.string.account_logout_all_confirm)) },
            confirmButton = {
                TextButton(enabled = !state.isLoggingOutAll, onClick = {
                    onShowLogoutAllConfirmChange(false)
                    viewModel.logoutAllDevices()
                }) {
                    Text(stringResource(R.string.account_logout_all), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(enabled = !state.isLoggingOutAll, onClick = { onShowLogoutAllConfirmChange(false) }) {
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
            onPasswordChange = { onDeletePasswordChange(it) },
            onDismiss = {
                if (!state.isDeletingAccount) {
                    onShowDeleteAccountDialogChange(false)
                    onDeletePasswordChange("")
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
        if (pendingRemoveDeviceId?.let { it !in ids } == true) onPendingRemoveDeviceIdChange(null)
        if (renameDeviceId?.let { it !in ids } == true) onRenameDeviceIdChange(null)
    }

    val pendingRemoveDevice = pendingRemoveDeviceId?.let { id -> state.devices.firstOrNull { it.deviceId == id } }
    pendingRemoveDevice?.let { device ->
        val isPendingDevice = device.status == "PENDING"
        AlertDialog(
            onDismissRequest = { onPendingRemoveDeviceIdChange(null) },
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
                    onPendingRemoveDeviceIdChange(null)
                    viewModel.removeMyDevice(device.deviceId)
                }) {
                    Text(if (isPendingDevice) stringResource(R.string.account_reject) else stringResource(R.string.chat_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { onPendingRemoveDeviceIdChange(null) }) { Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary) } }
        )
    }

    val renameDevice = renameDeviceId?.let { id -> state.devices.firstOrNull { it.deviceId == id } }
    renameDevice?.let { device ->
        AlertDialog(
            onDismissRequest = { onRenameDeviceIdChange(null) },
            title = { Text(stringResource(R.string.account_device_name_title)) },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { if (it.length <= 50) onRenameDraftChange(it) },
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
                    onRenameDeviceIdChange(null)
                }) {
                    Text(stringResource(R.string.common_save), color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = { TextButton(onClick = { onRenameDeviceIdChange(null) }) { Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary) } }
        )
    }
}
