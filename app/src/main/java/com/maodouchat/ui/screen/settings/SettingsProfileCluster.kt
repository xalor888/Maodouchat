package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import com.maodouchat.R
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.theme.MaodouDimens
import com.maodouchat.ui.theme.LocalChatPalette

@Composable
internal fun ProfileCard(
    name: String,
    userId: String,
    avatarUrl: String?,
    status: String = "",
    username: String? = null,
    isEditing: Boolean,
    editName: String,
    isUploading: Boolean,
    isSaving: Boolean,
    onEditNameChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onChangeAvatar: () -> Unit,
    onRemoveAvatar: () -> Unit,
    onOpenMyQr: () -> Unit = {},
    onEditStatus: () -> Unit = {},
    onSetUsername: () -> Unit = {}
) {
    var showAvatarMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // clickable 放在最后一个 padding 之后，使卡片整体可点击
        modifier = Modifier.fillMaxWidth().padding(horizontal = MaodouDimens.ScreenPadding)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface).padding(horizontal = 14.dp, vertical = 12.dp)
            .clickable { if (!isEditing) onStartEdit() }
    ) {
        // 头像（点击更换）
        Box(modifier = Modifier.clickable(enabled = !isUploading) { showAvatarMenu = true }) {
            Box(modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(32.dp)).clip(RoundedCornerShape(32.dp))) {
                Avatar(name = name, avatarUrl = avatarUrl, size = AvatarSize.LG)
            }
            // 相机图标叠加
            if (isUploading) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp).align(Alignment.Center), strokeWidth = 2.dp)
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(24.dp).align(Alignment.BottomEnd)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                ) {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = stringResource(R.string.profile_change_avatar), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                }
            }
            DropdownMenu(expanded = showAvatarMenu, onDismissRequest = { showAvatarMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profile_change_avatar)) },
                    leadingIcon = { Icon(Icons.Outlined.CameraAlt, contentDescription = null) },
                    onClick = {
                        showAvatarMenu = false
                        onChangeAvatar()
                    }
                )
                if (!avatarUrl.isNullOrBlank()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.profile_remove_avatar), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showAvatarMenu = false
                            onRemoveAvatar()
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            if (isEditing) {
                // 编辑模式
                OutlinedTextField(
                    value = editName,
                    onValueChange = { if (it.length <= 30) onEditNameChange(it) },
                    singleLine = true,
                    shape = RoundedCornerShape(MaodouDimens.SmallRadius),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    supportingText = {
                        Text(
                            "${editName.length}/30",
                            color = if (editName.length > 25) MaterialTheme.colorScheme.error else LocalChatPalette.current.textSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    TextButton(onClick = onSaveEdit, enabled = !isSaving) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isSaving) stringResource(R.string.profile_saving) else stringResource(R.string.common_save), color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = onCancelEdit, enabled = !isSaving) {
                        Icon(Icons.Outlined.Close, null, tint = LocalChatPalette.current.textSecondary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary)
                    }
                }
            } else {
                // 显示模式
                Text(name, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp), color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(4.dp))
                // 9.281：ID 视觉减重——完整 u_uuid 太长，展示缩写（前段+…+尾段），
                // 点按复制完整 ID（加好友/客服排查仍可用全值）
                val clipboard = LocalClipboardManager.current
                val idCopiedMsg = stringResource(R.string.profile_id_copied)
                val handle = username?.takeIf { it.isNotBlank() }?.let { "@$it" }
                    ?: if (userId.length > 16) userId.take(8) + "…" + userId.takeLast(4) else userId
                Text(
                    stringResource(R.string.profile_maodou_id, handle),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textHint,
                    modifier = Modifier.clickable {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(userId))
                        Toast.makeText(context, idCopiedMsg, Toast.LENGTH_SHORT).show()
                    }
                )
                // 用户名显示（可点击设置）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onSetUsername() }
                ) {
                    val uname = username?.let { "@$it" } ?: stringResource(R.string.settings_set_username)
                    Text(
                        text = uname,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (username != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                    if (username == null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.settings_set_username),
                            tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(14.dp))
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = com.maodouchat.ui.component.localizedCustomStatusLabel(status).ifBlank { stringResource(R.string.status_empty_placeholder) },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.isBlank()) MaterialTheme.colorScheme.outline else LocalChatPalette.current.textSecondary,
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEditStatus() }
                )
            }
        }

        if (!isEditing) {
            IconButton(onClick = onOpenMyQr) {
                Icon(Icons.Outlined.QrCode, contentDescription = stringResource(R.string.profile_my_qr), tint = LocalChatPalette.current.textSecondary, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
internal fun StatusEditorDialog(
    status: String,
    isSaving: Boolean,
    errorMessage: String?,
    onStatusChange: (String) -> Unit,
    onPreset: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(R.string.status_title)) },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.status_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                OutlinedTextField(
                    value = status,
                    onValueChange = onStatusChange,
                    placeholder = { Text(stringResource(R.string.status_hint)) },
                    singleLine = false,
                    maxLines = 3,
                    shape = RoundedCornerShape(MaodouDimens.SmallRadius),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    supportingText = {
                        Text(
                            "${status.length}/${com.maodouchat.util.CustomStatusPolicy.MAX_LENGTH}",
                            color = if (status.length > com.maodouchat.util.CustomStatusPolicy.MAX_LENGTH - 10) MaterialTheme.colorScheme.error else LocalChatPalette.current.textSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(stringResource(R.string.status_presets), style = MaterialTheme.typography.labelLarge, color = LocalChatPalette.current.textSecondary)
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    com.maodouchat.util.CustomStatusPolicy.PRESETS.chunked(3).forEach { row ->
                        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                            row.forEach { preset ->
                                FilterChip(
                                    selected = status == preset,
                                    onClick = { onPreset(preset) },
                                    label = { Text(com.maodouchat.ui.component.localizedCustomStatusLabel(preset)) }
                                )
                            }
                        }
                    }
                }
                if (!errorMessage.isNullOrBlank()) {
                    Text(errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = !isSaving) {
                if (isSaving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear, enabled = !isSaving && status.isNotEmpty()) {
                    Text(stringResource(R.string.status_clear), color = LocalChatPalette.current.textSecondary)
                }
                TextButton(onClick = onDismiss, enabled = !isSaving) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        }
    )
}

@Composable
internal fun UsernameEditorDialog(
    username: String,
    isSaving: Boolean,
    errorMessage: String?,
    onUsernameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(R.string.settings_username_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.settings_username_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    placeholder = { Text(stringResource(R.string.settings_username_placeholder)) },
                    singleLine = true,
                    leadingIcon = { Text("@", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary) },
                    shape = RoundedCornerShape(MaodouDimens.SmallRadius),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    supportingText = {
                        Text(
                            "${username.length}/50",
                            color = if (username.length > 45) MaterialTheme.colorScheme.error else LocalChatPalette.current.textSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                errorMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    // 9.287：修复 URL 域名重复嵌套（字符串模板已含前缀又传了完整 URL），
                    // 并改为跟随当前服务器地址（自建部署不再显示错误的 chat.mdou.me）
                    stringResource(
                        R.string.settings_username_profile_url,
                        com.maodouchat.network.ApiConfig.BASE_URL.removePrefix("https://").removePrefix("http://").trimEnd('/') + "/u/" + username
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (username.length >= 3) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textHint
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = username.length >= 3 && !isSaving) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text(stringResource(R.string.common_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
