package com.maodouchat.ui.screen.chatlist

import com.maodouchat.data.model.Chat
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 会话列表的「未拨来电 + 文件夹管理」弹层（G137 从 `ChatListScreen.kt` 拆出，原 222 行）。
 *
 * 六个语句：`if (showMissedCallsSheet)`（未接来电底部弹层）、
 * `if (showCreateFolder)`（新建文件夹）、`if (showFolderManager)`（文件夹管理：重命名/排序/删除）、
 * `renameFolderId?.let { }`（重命名对话框）、`folderMoveChat?.let { }`（移动到文件夹）、
 * `state.ownerTransferRequiredChatId?.let { }`（群主转移提醒）。
 *
 * 九个可变状态以「值 + setter」成对传入，不抓全局单例、不读数据库。
 * 纯搬移，不改判断。
 */
@Composable
internal fun ChatListFolderDialogs(
    state: ChatListUiState,
    viewModel: ChatListViewModel,
    showMissedCallsSheet: Boolean,
    showCreateFolder: Boolean,
    showFolderManager: Boolean,
    createFolderName: String,
    createFolderError: String?,
    renameFolderId: String?,
    renameFolderName: String,
    renameFolderError: String?,
    folderMoveChat: Chat?,
    onShowMissedCallsSheetChange: (Boolean) -> Unit,
    onShowCreateFolderChange: (Boolean) -> Unit,
    onShowFolderManagerChange: (Boolean) -> Unit,
    onCreateFolderNameChange: (String) -> Unit,
    onCreateFolderErrorChange: (String?) -> Unit,
    onRenameFolderIdChange: (String?) -> Unit,
    onRenameFolderNameChange: (String) -> Unit,
    onRenameFolderErrorChange: (String?) -> Unit,
    onFolderMoveChatChange: (Chat?) -> Unit,
    onOpenGroupDetail: (String) -> Unit = {},
    onChatClick: (String) -> Unit = {},
    onVoiceCall: (String, String) -> Unit = { _, _ -> },
    onVideoCall: (String, String) -> Unit = { _, _ -> },
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val folderCreateFailedTip = stringResource(R.string.chat_folder_create_failed)
    val folderRenameFailedTip = stringResource(R.string.chat_folder_rename_failed)

    if (showMissedCallsSheet) {
        // 8.52：升级为全量通话记录——Room 未接（历史）+ CallLogStore（呼出/已接/未接）合并去重
        val callLogRows = remember(state.missedCalls) {
            val storeLog = runCatching { com.maodouchat.call.CallLogStore.list(context) }.getOrDefault(emptyList())
            val seen = HashSet<String>()
            buildList {
                // 8.53：CallLogStore 优先——同 id 竞态下（接听瞬间对端挂断）已接记录不被 Room 的 MISSED 覆盖
                storeLog.forEach { e ->
                    if (seen.add(e.id)) {
                        add(
                            CallLogRow(
                                id = e.id,
                                peerId = e.peerId,
                                peerName = e.peerName,
                                video = e.isVideo,
                                direction = e.direction,
                                state = e.state,
                                at = e.startedAt,
                                durationMs = e.durationMs
                            )
                        )
                    }
                }
                state.missedCalls.forEach { mc ->
                    if (seen.add(mc.id)) {
                        add(
                            CallLogRow(
                                id = mc.id,
                                peerId = mc.callerId,
                                peerName = mc.callerName,
                                video = mc.callType.equals("VIDEO", ignoreCase = true),
                                direction = com.maodouchat.call.CallLogStore.Direction.INCOMING,
                                state = com.maodouchat.call.CallLogStore.State.MISSED,
                                at = mc.receivedAt,
                                durationMs = 0L
                            )
                        )
                    }
                }
            }.sortedByDescending { it.at }
        }
        MissedCallsSheet(
            rows = callLogRows,
            onDismiss = { onShowMissedCallsSheetChange(false)},
            onClear = {
                viewModel.clearMissedCalls()
                com.maodouchat.call.CallLogStore.clear(context)
                onShowMissedCallsSheetChange(false)
            },
            onOpenChat = { userId, name, video ->
                onShowMissedCallsSheetChange(false)
                val chatId = viewModel.findDirectChatIdForUser(userId)
                if (chatId != null) onChatClick(chatId)
                else if (video) onVideoCall(userId, name) else onVoiceCall(userId, name)
            },
            // 1.289：长按单条删除（与通话记录页一致；Room + CallLogStore + 本地 state 同步清理）
            onDeleteRow = { row ->
                com.maodouchat.call.CallLogStore.remove(context, row.id)
                viewModel.removeMissedCallLocally(row.id)
            }
        )
    }

    if (showCreateFolder) {
        AlertDialog(
            onDismissRequest = { onShowCreateFolderChange(false); onCreateFolderErrorChange(null)},
            title = { Text(stringResource(R.string.chat_folder_create)) },
            text = {
                Column {
                    TextField(value = createFolderName, onValueChange = { onCreateFolderNameChange(it)}, singleLine = true)
                    createFolderError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.createFolder(createFolderName)) { onShowCreateFolderChange(false); onCreateFolderNameChange(""); onCreateFolderErrorChange(null)}
                    else onCreateFolderErrorChange(folderCreateFailedTip)
                }) { Text(stringResource(R.string.chat_folder_create)) }
            },
            dismissButton = { TextButton(onClick = { onShowCreateFolderChange(false)}) { Text(stringResource(android.R.string.cancel)) } }
        )
    }

    if (showFolderManager) {
        // 9.233：文件夹拖拽排序（TG 式）——长按拖柄拖动，实时预览目标插入位，松手提交
        val sortedFolders = remember(state.folders) { state.folders.sortedBy { it.sortOrder } }
        var dragFolder by remember { mutableStateOf<Pair<String, Float>?>(null) }
        var rowPitchPx by remember { mutableStateOf(0f) }
        val localDensity = LocalDensity.current
        val dragIndex = dragFolder?.let { st -> sortedFolders.indexOfFirst { it.id == st.first } } ?: -1
        val previewTarget = if (dragIndex >= 0 && rowPitchPx > 0f) {
            val shift = ((dragFolder?.second ?: 0f) / rowPitchPx).roundToInt()
            (dragIndex + shift).coerceIn(0, sortedFolders.size - 1)
        } else -1
        AlertDialog(
            onDismissRequest = { onShowFolderManagerChange(false); dragFolder = null },
            title = { Text(stringResource(R.string.chat_folder_manage)) },
            text = {
                Column {
                    if (sortedFolders.isEmpty()) Text(stringResource(R.string.chat_folder_list_empty))
                    else sortedFolders.forEachIndexed { index, folder ->
                        val isDragging = dragFolder?.first == folder.id
                        val offsetY = if (isDragging) dragFolder?.second ?: 0f else 0f
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .zIndex(if (isDragging) 1f else 0f)
                                .onGloballyPositioned { coords ->
                                    // 行距 = 行高 + 上下 padding，用于把拖动位移换算成目标位置
                                    if (rowPitchPx == 0f) {
                                        rowPitchPx = coords.size.height.toFloat() + with(localDensity) { 12.dp.toPx() }
                                    }
                                }
                                .graphicsLayer {
                                    translationY = offsetY
                                    if (isDragging) {
                                        shadowElevation = 12f
                                        alpha = 0.92f
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 拖柄：长按拖动排序（箭头按钮保留作无障碍替代）
                            Icon(
                                Icons.Outlined.DragIndicator,
                                contentDescription = stringResource(R.string.chat_folder_move_up),
                                tint = LocalChatPalette.current.textHint,
                                modifier = Modifier
                                    .size(24.dp)
                                    .pointerInput(folder.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { dragFolder = folder.id to 0f },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragFolder = dragFolder?.let { it.first to it.second + dragAmount.y }
                                            },
                                            onDragEnd = {
                                                val st = dragFolder
                                                if (st != null && previewTarget >= 0 && previewTarget != index) {
                                                    viewModel.reorderFolder(st.first, previewTarget)
                                                }
                                                dragFolder = null
                                            },
                                            onDragCancel = { dragFolder = null }
                                        )
                                    }
                            )
                            Text(
                                folder.name,
                                modifier = Modifier.weight(1f).padding(start = 6.dp),
                                color = if (previewTarget == index && !isDragging) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                            // 9.222：文件夹排序（TG 式顺序自定义，云端同步）
                            IconButton(onClick = { viewModel.moveFolder(folder.id, -1) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = stringResource(R.string.chat_folder_move_up), tint = LocalChatPalette.current.textSecondary)
                            }
                            IconButton(onClick = { viewModel.moveFolder(folder.id, 1) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.chat_folder_move_down), tint = LocalChatPalette.current.textSecondary)
                            }
                            TextButton(onClick = { onRenameFolderIdChange(folder.id); onRenameFolderNameChange(folder.name)}) { Text(stringResource(R.string.chat_folder_rename)) }
                            TextButton(onClick = { viewModel.deleteFolder(folder.id) }) { Text(stringResource(R.string.chat_delete)) }
                        }
                        // 目标插入位指示线：拖拽经过时在对应行下方划线预览落点
                        if (dragFolder != null && previewTarget == index + 1 && previewTarget < sortedFolders.size) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.primary, thickness = 2.dp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onShowFolderManagerChange(false); dragFolder = null }) { Text(stringResource(android.R.string.ok)) } }
        )
    }

    renameFolderId?.let { fid ->
        AlertDialog(
            onDismissRequest = { onRenameFolderIdChange(null); onRenameFolderErrorChange(null)},
            title = { Text(stringResource(R.string.chat_folder_manage)) },
            text = {
                Column {
                    TextField(value = renameFolderName, onValueChange = { onRenameFolderNameChange(it)}, singleLine = true)
                    renameFolderError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.renameFolder(fid, renameFolderName)) { onRenameFolderIdChange(null); onRenameFolderErrorChange(null)}
                    else onRenameFolderErrorChange(folderRenameFailedTip)
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { onRenameFolderIdChange(null)}) { Text(stringResource(android.R.string.cancel)) } }
        )
    }

    folderMoveChat?.let { chat ->
        AlertDialog(
            onDismissRequest = { onFolderMoveChatChange(null)},
            title = { Text(stringResource(R.string.chat_folder_manage)) },
            text = {
                Column {
                    TextButton(onClick = { viewModel.moveChatToFolder(chat.id, null); onFolderMoveChatChange(null)}) { Text(stringResource(R.string.chat_folder_show_all)) }
                    state.folders.forEach { folder ->
                        TextButton(onClick = { viewModel.moveChatToFolder(chat.id, folder.id); onFolderMoveChatChange(null)}) { Text(folder.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onFolderMoveChatChange(null)}) { Text(stringResource(android.R.string.cancel)) } }
        )
    }

    state.ownerTransferRequiredChatId?.let { chatId ->
        AlertDialog(
            onDismissRequest = { viewModel.clearOwnerTransferRequired() },
            // 9.150：正文误用 chat_group（"群聊"），改用专为转让群主提示定义的字符串
            title = { Text(stringResource(R.string.chat_owner_transfer_required_title)) },
            text = { Text(stringResource(R.string.chat_owner_transfer_required_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearOwnerTransferRequired(); onOpenGroupDetail(chatId) }) { Text(stringResource(android.R.string.ok)) }
            }
        )
    }}
