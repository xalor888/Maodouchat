package com.maodouchat.ui.screen.contacts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.model.User
import com.maodouchat.ui.theme.LocalChatPalette

/** 通讯录屏尾部对话框簇：通知/建群/建频道/长按操作/拉黑确认/删好友确认/改备注/好友申请（从 ContactsListScreen.kt 纯搬移）。 */
@Composable
internal fun ContactsDialogs(
    state: ContactsUiState,
    viewModel: ContactsViewModel,
    showGroupDialog: Boolean,
    onShowGroupDialogChange: (Boolean) -> Unit,
    showChannelDialog: Boolean,
    onShowChannelDialogChange: (Boolean) -> Unit,
    contactActionTarget: User?,
    onContactActionTargetChange: (User?) -> Unit,
    blockContactTarget: User?,
    onBlockContactTargetChange: (User?) -> Unit,
    removeFriendTarget: User?,
    onRemoveFriendTargetChange: (User?) -> Unit,
    nicknameTarget: User?,
    onNicknameTargetChange: (User?) -> Unit,
    friendRequestTarget: User?,
    onFriendRequestTargetChange: (User?) -> Unit,
) {
    val dialogMessage = state.errorMessage ?: state.infoMessage
    dialogMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text(stringResource(R.string.contacts_notice)) },
            text = { Text(message) },
            // 8.52 UX：加载失败且列表为空时提供「重试」（否则确认后落入无入口的空列表）
            dismissButton = if (state.errorMessage != null && state.contacts.isEmpty()) {
                {
                    TextButton(onClick = { viewModel.reloadContacts() }) {
                        Text(stringResource(R.string.chat_load_failed_retry))
                    }
                }
            } else null,
            confirmButton = { TextButton(onClick = { viewModel.clearError() }) { Text(stringResource(R.string.chat_acknowledge)) } }
        )
    }

    if (showGroupDialog) {
        NewGroupDialog(
            contacts = state.contacts,
            onDismiss = { onShowGroupDialogChange(false) },
            onConfirm = { groupName, members ->
                viewModel.createGroupChat(groupName, members)
                if (groupName.isNotBlank()) onShowGroupDialogChange(false)
            }
        )
    }

    if (showChannelDialog) {
        NewChannelDialog(
            contacts = state.contacts,
            onDismiss = { onShowChannelDialogChange(false) },
            onConfirm = { channelName, members ->
                viewModel.createChannelChat(channelName, members)
                if (channelName.isNotBlank()) onShowChannelDialogChange(false)
            }
        )
    }

    contactActionTarget?.let { user ->
        AlertDialog(
            onDismissRequest = { onContactActionTargetChange(null) },
            title = { Text(user.displayName) },
            text = {
                Column {
                    Text(stringResource(R.string.contacts_long_press_actions))
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        onClick = { onContactActionTargetChange(null); viewModel.startSecretChat(user) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.secret_chat_menu_start), modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        onClick = { onContactActionTargetChange(null); onNicknameTargetChange(user) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.contacts_action_set_nickname), modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        onClick = { onContactActionTargetChange(null); onRemoveFriendTargetChange(user) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.contacts_action_remove_friend), modifier = Modifier.fillMaxWidth(), color = LocalChatPalette.current.textSecondary) }
                    // 1.291：拉黑（隐私入口，与设置页黑名单管理配套）
                    TextButton(
                        onClick = { onContactActionTargetChange(null); onBlockContactTargetChange(user) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.contacts_action_block), modifier = Modifier.fillMaxWidth(), color = LocalChatPalette.current.unreadRed) }
                }
            },
            confirmButton = { TextButton(onClick = { onContactActionTargetChange(null) }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    // 1.291：拉黑确认
    blockContactTarget?.let { user ->
        AlertDialog(
            onDismissRequest = { onBlockContactTargetChange(null) },
            title = { Text(stringResource(R.string.contacts_block_title)) },
            text = { Text(stringResource(R.string.contacts_block_message, user.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.blockUser(user)
                    onBlockContactTargetChange(null)
                }) { Text(stringResource(R.string.contacts_action_block), color = LocalChatPalette.current.unreadRed) }
            },
            dismissButton = {
                TextButton(onClick = { onBlockContactTargetChange(null) }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    removeFriendTarget?.let { user ->
        AlertDialog(
            onDismissRequest = { onRemoveFriendTargetChange(null) },
            title = { Text(stringResource(R.string.contacts_remove_friend_title)) },
            text = { Text(stringResource(R.string.contacts_remove_friend_message, user.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeFriend(user)
                    onRemoveFriendTargetChange(null)
                }) { Text(stringResource(R.string.contacts_remove_friend_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { onRemoveFriendTargetChange(null) }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    nicknameTarget?.let { user ->
        var draft by remember(user.id) { mutableStateOf(user.nickname.orEmpty()) }
        AlertDialog(
            onDismissRequest = { onNicknameTargetChange(null) },
            title = { Text(stringResource(R.string.contacts_nickname_title)) },
            text = {
                Column {
                    Text(user.name, style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = draft,
                        onValueChange = { draft = it.take(50) },
                        placeholder = { Text(stringResource(R.string.contacts_nickname_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setContactNickname(user, draft)
                    onNicknameTargetChange(null)
                }) { Text(stringResource(R.string.contacts_nickname_save)) }
            },
            dismissButton = {
                Row {
                    if (!user.nickname.isNullOrBlank() || draft.isNotBlank()) {
                        TextButton(onClick = {
                            viewModel.setContactNickname(user, "")
                            onNicknameTargetChange(null)
                        }) { Text(stringResource(R.string.contacts_nickname_clear), color = LocalChatPalette.current.textSecondary) }
                    }
                    TextButton(onClick = { onNicknameTargetChange(null) }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            }
        )
    }

    friendRequestTarget?.let { user ->
        var message by remember(user.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { onFriendRequestTargetChange(null) },
            title = { Text(stringResource(R.string.contacts_friend_request_message_title)) },
            text = {
                Column {
                    Text(user.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = message,
                        onValueChange = { message = it.take(300) },
                        placeholder = { Text(stringResource(R.string.contacts_friend_request_message_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.sendFriendRequest(user, message)
                    onFriendRequestTargetChange(null)
                }) { Text(stringResource(R.string.contacts_friend_request_send)) }
            },
            dismissButton = {
                TextButton(onClick = { onFriendRequestTargetChange(null) }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}
