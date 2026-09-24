package com.maodouchat.ui.screen.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.maodouchat.navigation.MainTab
import com.maodouchat.util.ChatFolderPolicy
import androidx.compose.runtime.Composable

/**
 * 会话列表的 Scaffold 外观（G140 从 `ChatListScreen.kt` 拆出，原 193 行）。
 *
 * 两个 Composable：`ChatListTopBar`（顶栏：多选态计数/退出、文件夹切换、归档开关、搜索、
 * 通知中心、扫码、新建菜单、第三方服务器告警）与 `ChatListFab`（悬浮新建按钮：
 * 发起群聊 / 扫一扫 / 建频道）。
 *
 * 三个可变状态以「值 + setter」成对传入，不抓全局单例、不读数据库。
 * 纯搬移，不改判断。
 */

/** 顶栏（原 `Scaffold(topBar = { ... })` 的内容）。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ChatListTopBar(
    state: ChatListUiState,
    viewModel: ChatListViewModel,
    liquidGlass: Boolean,
    notifUnread: Int,
    showCreateMenu: Boolean,
    showBatchDeleteConfirm: Boolean,
    showThirdPartyServerDialog: Boolean,
    onShowCreateMenuChange: (Boolean) -> Unit,
    onShowBatchDeleteConfirmChange: (Boolean) -> Unit,
    onShowThirdPartyServerDialogChange: (Boolean) -> Unit,
    onNavigateToTab: (Int) -> Unit,
    onOpenGlobalSearch: () -> Unit,
    onOpenNotificationCenter: () -> Unit,
    onOpenScan: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

        TopAppBar(
            title = {
                if (state.selectionMode) {
                    Text(
                        pluralStringResource(R.plurals.chat_list_selected_count, state.selectedChatIds.size, state.selectedChatIds.size),
                        style = MaterialTheme.typography.titleLarge
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (state.showArchived) stringResource(R.string.chat_archived_title)
                            else stringResource(R.string.nav_chats)
                        )
                        // 9.286：第三方服务器提醒——平时不显示服务器名；第三方且未确认时
                        // 仅一个小感叹号，点开提示后「我知道了」不再显示（按地址隔离）
                        if (com.maodouchat.network.ServerIdentity.isThirdPartyServer &&
                            !com.maodouchat.network.ServerIdentity.isWarningAcknowledged(context, com.maodouchat.network.ApiConfig.BASE_URL)
                        ) {
                            IconButton(
                                onClick = { onShowThirdPartyServerDialogChange(true)},
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.WarningAmber,
                                    contentDescription = stringResource(R.string.home_third_server_title),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            },
            navigationIcon = {
                if (state.selectionMode) {
                    IconButton(onClick = viewModel::exitSelectionMode) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
                    }
                }
            },
            actions = {
                if (state.selectionMode) {
                    // 1.368：多选模式操作条（置顶 / 已读 / 删除）
                    val hasSelection = state.selectedChatIds.isNotEmpty()
                    IconButton(
                        onClick = viewModel::batchTogglePinSelected,
                        enabled = hasSelection
                    ) {
                        Icon(Icons.Outlined.PushPin, contentDescription = stringResource(R.string.chat_pin), tint = if (hasSelection) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(
                        onClick = viewModel::batchMarkReadSelected,
                        enabled = hasSelection
                    ) {
                        Icon(Icons.Outlined.DoneAll, contentDescription = stringResource(R.string.chat_mark_read), tint = if (hasSelection) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(
                        // 1.373：批量删除先确认（防止误触批量清空）
                        onClick = { onShowBatchDeleteConfirmChange(true)},
                        enabled = hasSelection
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.chat_delete), tint = if (hasSelection) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    if (state.selectedFolderId == com.maodouchat.util.ChatFolderPolicy.SYSTEM_UNREAD_ID) {
                        IconButton(
                            onClick = viewModel::markAllUnreadChatsRead,
                            enabled = state.unreadInFolder(com.maodouchat.util.ChatFolderPolicy.SYSTEM_UNREAD_ID) > 0
                        ) {
                            Icon(
                                Icons.Outlined.DoneAll,
                                contentDescription = stringResource(R.string.notif_center_mark_all_read)
                            )
                        }
                    }
                    IconButton(onClick = onOpenGlobalSearch) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.global_search_title))
                    }
                    IconButton(onClick = onOpenNotificationCenter) {
                        Box {
                            Icon(Icons.Outlined.Notifications, contentDescription = stringResource(R.string.notif_center_title))
                            if (notifUnread > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.error)
                                )
                            }
                        }
                    }
                    IconButton(onClick = { viewModel.setShowArchived(!state.showArchived) }) {
                        Icon(if (state.showArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, contentDescription = stringResource(R.string.chat_archived_title))
                    }
                    if (liquidGlass) {
                        Box {
                            IconButton(onClick = { onShowCreateMenuChange(true)}) {
                                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.chat_empty_action_add))
                            }
                            DropdownMenu(
                                expanded = showCreateMenu,
                                onDismissRequest = { onShowCreateMenuChange(false)}
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.contacts_add_contact)) },
                                    onClick = {
                                        onShowCreateMenuChange(false)
                                        onNavigateToTab(MainTab.CONTACTS)
                                    },
                                    leadingIcon = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.contacts_start_group)) },
                                    onClick = {
                                        onShowCreateMenuChange(false)
                                        onNavigateToTab(MainTab.CONTACTS)
                                    },
                                    leadingIcon = { Icon(Icons.Filled.Group, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_create_channel)) },
                                    onClick = {
                                        onShowCreateMenuChange(false)
                                        onNavigateToTab(MainTab.CONTACTS)
                                    },
                                    leadingIcon = { Icon(Icons.Outlined.Campaign, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.contacts_scan)) },
                                    onClick = {
                                        onShowCreateMenuChange(false)
                                        onOpenScan()
                                    },
                                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
                                )
                            }
                        }
                    }
                }
            },
            colors = com.maodouchat.ui.theme.liquidGlassTopAppBarColors()
        )}

/** 悬浮新建按钮（原 `Scaffold(floatingActionButton = { ... })` 的内容）。 */
@Composable
internal fun ChatListFab(
    state: ChatListUiState,
    viewModel: ChatListViewModel,
    liquidGlass: Boolean,
    notifUnread: Int,
    showCreateMenu: Boolean,
    showBatchDeleteConfirm: Boolean,
    showThirdPartyServerDialog: Boolean,
    onShowCreateMenuChange: (Boolean) -> Unit,
    onShowBatchDeleteConfirmChange: (Boolean) -> Unit,
    onShowThirdPartyServerDialogChange: (Boolean) -> Unit,
    onNavigateToTab: (Int) -> Unit,
    onOpenGlobalSearch: () -> Unit,
    onOpenNotificationCenter: () -> Unit,
    onOpenScan: () -> Unit,
) {
        if (!liquidGlass && !state.selectionMode) {
            // 主界面在 MainContainerRoute 里整屏悬浮 BottomNavBar（无 Scaffold bottomBar），
            // FAB 若不手动让位会被导航栏遮住大半、只剩一个蓝色残边（真机可见）。
            Box(Modifier.navigationBarsPadding().padding(bottom = com.maodouchat.ui.component.PinnedBottomNavMetrics.BarHeight)) {
                FloatingActionButton(
                    onClick = { onShowCreateMenuChange(true)},
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.chat_empty_action_add))
                }
                DropdownMenu(
                    expanded = showCreateMenu,
                    onDismissRequest = { onShowCreateMenuChange(false)}
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.contacts_add_contact)) },
                        onClick = {
                            onShowCreateMenuChange(false)
                            onNavigateToTab(MainTab.CONTACTS)
                        },
                        leadingIcon = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.contacts_start_group)) },
                        onClick = {
                            onShowCreateMenuChange(false)
                            onNavigateToTab(MainTab.CONTACTS)
                        },
                        leadingIcon = { Icon(Icons.Filled.Group, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_create_channel)) },
                        onClick = {
                            onShowCreateMenuChange(false)
                            onNavigateToTab(MainTab.CONTACTS)
                        },
                        leadingIcon = { Icon(Icons.Outlined.Campaign, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.contacts_scan)) },
                        onClick = {
                            onShowCreateMenuChange(false)
                            onOpenScan()
                        },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
                    )
                }
            }
        }}
