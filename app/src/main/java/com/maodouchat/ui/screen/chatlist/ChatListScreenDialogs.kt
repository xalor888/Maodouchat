package com.maodouchat.ui.screen.chatlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.model.Chat
import com.maodouchat.ui.theme.LocalChatPalette

/**
 * 会话列表的对话框组（G136 从 `ChatListScreen.kt` 拆出，原 174 行）。
 *
 * 四个语句：`menuChat?.let { }`（长按会话的菜单：置顶/未读/静音/归档/建密聊/清空/删除）、
 * `if (showThirdPartyServerDialog)`（第三方服务器信任提醒）、
 * `if (showBatchDeleteConfirm)`（批量删除已选会话确认）、
 * `clearHistoryChat?.let { }`（清空本地历史确认）。
 *
 * 六个可变状态以「值 + setter」成对传入，不抓全局单例、不读数据库。
 * 纯搬移，不改判断。
 */
@Composable
internal fun ChatListScreenDialogs(
    state: ChatListUiState,
    viewModel: ChatListViewModel,
    menuChat: Chat?,
    clearHistoryChat: Chat?,
    silentUntilChat: Chat?,
    folderMoveChat: Chat?,
    showBatchDeleteConfirm: Boolean,
    showThirdPartyServerDialog: Boolean,
    onMenuChatChange: (Chat?) -> Unit,
    onClearHistoryChatChange: (Chat?) -> Unit,
    onSilentUntilChatChange: (Chat?) -> Unit,
    onFolderMoveChatChange: (Chat?) -> Unit,
    onShowBatchDeleteConfirmChange: (Boolean) -> Unit,
    onShowThirdPartyServerDialogChange: (Boolean) -> Unit,
    onOpenMediaCenter: (String) -> Unit = {},
    onOpenStarredMessages: (String) -> Unit = {},
    onOpenProfile: (String) -> Unit = {},
    onOpenGroupDetail: (String) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val copyChatNameLabel = stringResource(R.string.chat_copy_chat_name)
    val copiedTip = stringResource(R.string.chat_copied)
    val copyChatIdLabel = stringResource(R.string.chat_copy_chat_id)
    val silentUntilSetTip = stringResource(R.string.chat_silent_until_set)

    menuChat?.let { menuSnapshot ->
        // 9.150：菜单文案与动作均以 state.chats 最新快照为准，避免长按瞬间的 Chat 快照在 WS 刷新后陈旧
        val chat = state.chats.firstOrNull { it.id == menuSnapshot.id } ?: menuSnapshot
        DropdownMenu(expanded = true, onDismissRequest = { onMenuChatChange(null)}) {
            // 1.368：多选（长按菜单进入批量模式，先勾选当前会话）
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_multi_select)) },
                onClick = {
                    onMenuChatChange(null)
                    viewModel.enterSelectionMode()
                    viewModel.toggleSelectChat(chat.id)
                }
            )
            // 1.267：全部已读（所有未读会话）
            if (state.unreadChatCount > 0) {
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_mark_all_read)) }, onClick = { viewModel.markAllUnreadChatsRead(); onMenuChatChange(null)})
            }
            DropdownMenuItem(text = { Text(stringResource(if (chat.pinnedAt > 0) R.string.chat_unpin else R.string.chat_pin)) }, onClick = { viewModel.togglePinned(chat.id); onMenuChatChange(null)})
            DropdownMenuItem(text = { Text(stringResource(if (chat.notificationsMuted) R.string.chat_unmute_notifications else R.string.chat_mute_notifications)) }, onClick = { viewModel.toggleNotificationsMuted(chat.id); onMenuChatChange(null)})
            // 1.31：临时静音至快捷项（本地，1/8/24 小时）
            if (
                com.maodouchat.security.SecretChatPolicy.canStartFromDirect(
                    isGroup = chat.isGroup,
                    chatType = chat.chatType
                )
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.secret_chat_menu_start)) },
                    onClick = {
                        val peerId = chat.participants.firstOrNull()?.id.orEmpty()
                        onMenuChatChange(null)
                        viewModel.startSecretChatWithPeer(peerId)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_silent_until_menu)) },
                onClick = { onSilentUntilChatChange(chat); onMenuChatChange(null)}
            )
            DropdownMenuItem(text = { Text(stringResource(if (chat.archived) R.string.chat_unarchive else R.string.chat_archive)) }, onClick = { viewModel.toggleArchived(chat.id); onMenuChatChange(null)})
            DropdownMenuItem(text = { Text(stringResource(if (chat.markedUnread || chat.unreadCount > 0) R.string.chat_mark_read else R.string.chat_mark_unread)) }, onClick = { viewModel.toggleMarkedUnread(chat.id); onMenuChatChange(null)})
            // 1.142：有草稿时清除草稿（本地）
            if (state.drafts[chat.id]?.text?.isNotBlank() == true) {
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_clear_draft)) }, onClick = { viewModel.clearChatDraft(chat.id); onMenuChatChange(null)})
            }
            // 1.171：清空本地聊天记录（保留会话）
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_clear_local_history), color = LocalChatPalette.current.unreadRed) },
                onClick = { onClearHistoryChatChange(chat); onMenuChatChange(null)}
            )
            // 1.185：查看共享媒体
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_view_shared_media)) },
                onClick = { onOpenMediaCenter(chat.id); onMenuChatChange(null)}
            )
            // 1.223：复制会话名称
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_copy_chat_name)) },
                onClick = {
                    val name = when {
                        chat.isChannel -> chat.groupName?.takeIf(String::isNotBlank) ?: ""
                        chat.isGroup -> chat.groupName?.takeIf(String::isNotBlank) ?: ""
                        else -> chat.participants.firstOrNull()?.displayName.orEmpty()
                    }
                    if (name.isNotBlank()) {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(copyChatNameLabel, name))
                        android.widget.Toast.makeText(context, copiedTip, android.widget.Toast.LENGTH_SHORT).show()
                    }
                    onMenuChatChange(null)
                }
            )
            // 1.249：复制会话 ID（便于反馈/排查）
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_copy_chat_id)) },
                onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(copyChatIdLabel, chat.id))
                    android.widget.Toast.makeText(context, copiedTip, android.widget.Toast.LENGTH_SHORT).show()
                    onMenuChatChange(null)
                }
            )
            // 1.251：查看资料（群聊进群详情，单聊进作者主页）
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_view_profile)) },
                onClick = {
                    if (chat.isGroup || chat.isChannel) {
                        onOpenGroupDetail(chat.id)
                    } else {
                        chat.participants.firstOrNull()?.id?.takeIf(String::isNotBlank)?.let(onOpenProfile)
                    }
                    onMenuChatChange(null)
                }
            )
            // 1.215：查看收藏
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_view_starred)) },
                onClick = { onOpenStarredMessages(chat.id); onMenuChatChange(null)}
            )
            DropdownMenuItem(text = { Text(stringResource(R.string.chat_folder_manage)) }, onClick = { onFolderMoveChatChange(chat); onMenuChatChange(null)})
            DropdownMenuItem(text = {
                Text(
                    stringResource(
                        when {
                            chat.isChannel -> R.string.chat_channel_leave
                            chat.isGroup -> R.string.chat_leave_group
                            else -> R.string.chat_delete
                        }
                    )
                )
            }, onClick = { viewModel.deleteChat(chat.id); onMenuChatChange(null)})
        }
    }

    // 1.373：多选批量删除确认（显示选中数，确认后才执行）
    // 9.286：第三方服务器提醒弹窗——确认名/地址与信任提示，「我知道了」后不再显示
    if (showThirdPartyServerDialog) {
        val thirdPartyInfo by com.maodouchat.network.ServerIdentity.current.collectAsState()
        AlertDialog(
            onDismissRequest = { onShowThirdPartyServerDialogChange(false)},
            title = { Text(stringResource(R.string.home_third_server_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            R.string.home_third_server_body,
                            thirdPartyInfo?.name?.takeIf { it.isNotBlank() }
                                ?: com.maodouchat.network.ApiConfig.BASE_URL
                        )
                    )
                    Text(
                        com.maodouchat.network.ApiConfig.BASE_URL,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    com.maodouchat.network.ServerIdentity.acknowledgeWarning(context, com.maodouchat.network.ApiConfig.BASE_URL)
                    onShowThirdPartyServerDialogChange(false)
                }) { Text(stringResource(R.string.home_third_server_ack)) }
            }
        )
    }

    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { onShowBatchDeleteConfirmChange(false)},
            title = { Text(stringResource(R.string.chat_delete_title), color = MaterialTheme.colorScheme.onSurface) },
            text = { Text(stringResource(R.string.chat_list_batch_delete_confirm, state.selectedChatIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    onShowBatchDeleteConfirmChange(false)
                    viewModel.batchDeleteSelected()
                }) { Text(stringResource(R.string.chat_delete), color = LocalChatPalette.current.unreadRed) }
            },
            dismissButton = {
                TextButton(onClick = { onShowBatchDeleteConfirmChange(false)}) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    // 1.171：清空本地聊天记录确认
    clearHistoryChat?.let { chat ->
        AlertDialog(
            onDismissRequest = { onClearHistoryChatChange(null)},
            title = { Text(stringResource(R.string.chat_clear_local_history)) },
            text = { Text(stringResource(R.string.chat_clear_local_history_confirm, chat.groupName?.takeIf(String::isNotBlank) ?: chat.participants.firstOrNull()?.displayName.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearLocalChatHistory(chat.id)
                    onClearHistoryChatChange(null)
                }) { Text(stringResource(R.string.chat_clear_history_yes), color = LocalChatPalette.current.unreadRed) }
            },
            dismissButton = {
                TextButton(onClick = { onClearHistoryChatChange(null)}) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }}
