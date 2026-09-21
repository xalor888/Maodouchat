package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.maodouchat.R
import com.maodouchat.data.model.Chat
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextHint

/**
 * 聊天页的联系人卡片（G132 从 `ChatDetailMiscDialogs.kt` 拆出，原 221 行）。
 *
 * 三个声明：`ContactProfileSheet`（联系人资料底部弹层）、
 * `ContactCardPickerDialog`（选联系人发名片）、`ProfileAction`（资料行内的单个操作项）。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`。
 * 纯搬移，不改判断。
 */

/** 单聊联系人资料卡：头像、ID、状态、最后在线、常用操作。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactProfileSheet(
    contact: com.maodouchat.data.model.User,
    isBlocked: Boolean,
    isBlocking: Boolean,
    hideCalls: Boolean = false,
    onDismiss: () -> Unit,
    onMessage: () -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    onToggleBlock: () -> Unit,
    onReport: () -> Unit
) {
    val context = LocalContext.current
    var showAvatarFull by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (showAvatarFull && !contact.avatar.isNullOrBlank()) {
                // 头像大图：全屏缩放查看，点按关闭
                Dialog(onDismissRequest = { showAvatarFull = false }) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.92f))
                            .clickable { showAvatarFull = false }
                    ) {
                        com.maodouchat.ui.component.ZoomableAsyncImage(
                            model = contact.avatar,
                            contentDescription = contact.displayName,
                            modifier = Modifier.fillMaxSize(),
                            onSingleTap = { showAvatarFull = false }
                        )
                    }
                }
            }
            com.maodouchat.ui.component.Avatar(
                name = contact.displayName,
                avatarUrl = contact.avatar,
                size = com.maodouchat.ui.component.AvatarSize.LG,
                isOnline = contact.isOnline,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(enabled = !contact.avatar.isNullOrBlank()) { showAvatarFull = true }
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                contact.displayName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                contact.id,
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatPalette.current.textSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (contact.status.isNotBlank()) {
                Text(
                    contact.status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                when {
                    contact.isOnline -> stringResource(R.string.chat_online)
                    contact.lastSeen > 0 -> stringResource(R.string.user_last_seen_prefix) + " " +
                        android.text.format.DateUtils.getRelativeTimeSpanString(
                            contact.lastSeen,
                            System.currentTimeMillis(),
                            android.text.format.DateUtils.MINUTE_IN_MILLIS
                        )
                    else -> stringResource(R.string.chat_offline)
                },
                style = MaterialTheme.typography.labelMedium,
                color = LocalChatPalette.current.textSecondary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ProfileAction(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.chat_send), onClick = onMessage)
                if (!hideCalls) {
                    ProfileAction(Icons.Outlined.Call, stringResource(R.string.chat_voice_call), onClick = onVoiceCall)
                    ProfileAction(Icons.Outlined.Videocam, stringResource(R.string.chat_video_call), onClick = onVideoCall)
                }
                ProfileAction(
                    if (isBlocked) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
                    stringResource(if (isBlocked) R.string.chat_unblock_user else R.string.chat_block_user),
                    enabled = !isBlocking,
                    onClick = onToggleBlock
                )
                ProfileAction(Icons.Outlined.Warning, stringResource(R.string.chat_report_user), onClick = onReport)
            }
        }
    }
}

@Composable
internal fun ContactCardPickerDialog(
    contacts: List<Pair<Chat, com.maodouchat.data.model.User>>,
    onDismiss: () -> Unit,
    onPick: (Chat, com.maodouchat.data.model.User) -> Unit
) {
    var pickerQuery by rememberSaveable { mutableStateOf("") }
    val filteredContacts = remember(contacts, pickerQuery) {
        val q = pickerQuery.trim()
        if (q.isEmpty()) {
            contacts
        } else {
            contacts.filter { (_, user) ->
                user.displayName.contains(q, ignoreCase = true) || user.name.contains(q, ignoreCase = true)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contact_card_picker_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = pickerQuery,
                    onValueChange = { pickerQuery = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.contact_card_picker_search)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (filteredContacts.isEmpty()) {
                    Text(
                        stringResource(if (contacts.isEmpty()) R.string.contact_card_picker_empty else R.string.contact_card_picker_no_match),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textSecondary
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        filteredContacts.forEach { (chat, user) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(chat, user) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp)
                            ) {
                                Avatar(name = user.displayName, avatarUrl = user.avatar, size = AvatarSize.SM)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        user.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "@${user.name}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = LocalChatPalette.current.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
internal fun ProfileAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) Primary else TextHint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) OnSurface else TextHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
