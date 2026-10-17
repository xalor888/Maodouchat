package com.maodouchat.ui.screen.chatlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.navigation.MainTab

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
