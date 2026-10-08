package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
