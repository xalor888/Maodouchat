package com.maodouchat.ui.screen.chatlist

import com.maodouchat.data.model.Chat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.maodouchat.ui.component.FloatingBottomBarContentPadding
import com.maodouchat.ui.component.PullToRefreshLayout
import com.maodouchat.ui.component.SearchBar
import com.maodouchat.ui.component.SwipeableChatItem
import com.maodouchat.ui.navigation.MainTab
import com.maodouchat.ui.theme.LocalChatPalette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 会话列表的主体内容（G141 从 `ChatListScreen.kt` 拆出，原 206 行）。
 *
 * 即原 `Scaffold(...) { padding -> ... }` 的 content lambda：置顶公告条、文件夹条、
 * 归档建议、未读优先提示、聊天列表（`SwipeableChatItem`）、空态、加载态、下拉刷新。
 *
 * 五个可变状态以「值 + setter」成对传入，不抓全局单例、不读数据库。
 * 纯搬移，不改判断。
 */
@Composable
internal fun ChatListContent(
    paddingValues: androidx.compose.foundation.layout.PaddingValues,
    state: ChatListUiState,
    viewModel: ChatListViewModel,
    floatingDock: Boolean,
    motion: com.maodouchat.ui.theme.MotionSettings,
    menuChat: Chat?,
    publicBanner: String?,
    showMissedCallsSheet: Boolean,
    showFolderManager: Boolean,
    showCreateFolder: Boolean,
    onMenuChatChange: (Chat?) -> Unit,
    onPublicBannerChange: (String?) -> Unit,
    onShowMissedCallsSheetChange: (Boolean) -> Unit,
    onShowFolderManagerChange: (Boolean) -> Unit,
    onShowCreateFolderChange: (Boolean) -> Unit,
    onChatClick: (String) -> Unit,
    onNavigateToTab: (Int) -> Unit,
    onOpenGlobalSearch: () -> Unit,
    onOpenGroupDetail: (String) -> Unit,
    onOpenMediaCenter: (String) -> Unit,
    onOpenNotificationCenter: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenScan: () -> Unit,
    onOpenStarredMessages: (String) -> Unit,
    onVideoCall: (String, String) -> Unit,
    onVoiceCall: (String, String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
        val bannerText = publicBanner ?: state.realtimeBanner
        AnimatedVisibility(visible = !bannerText.isNullOrBlank(), enter = fadeIn(), exit = fadeOut()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    bannerText.orEmpty(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = { onPublicBannerChange(null); viewModel.clearRealtimeBanner() }) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                }
            }
        }

        SearchBar(
            value = state.searchQuery,
            onValueChange = viewModel::onSearchQueryChange,
            placeholder = stringResource(R.string.global_search_chats_placeholder),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )

        ChatFolderStrip(
            folders = state.folders,
            selectedFolderId = state.selectedFolderId,
            secretChatCount = state.secretChatIds.size,
            lockedChatCount = state.lockedChatIds.size,
            unreadInFolder = { state.unreadInFolder(it) },
            onSelectFolder = viewModel::selectFolder,
            onManage = { onShowFolderManagerChange(true)},
            onCreate = { onShowCreateFolderChange(true)}
        )

        // 8.45：未读优先轻提示条（恢复被重写丢失的抛光项）——未读会话较多且未读优先开启时提示
        if (state.showUnreadPriorityHint) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setUnreadPriorityEnabled(false) }
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Outlined.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.chat_unread_priority_hint, state.unreadChatCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalChatPalette.current.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { viewModel.setUnreadPriorityEnabled(false) }) {
                    Text(stringResource(R.string.chat_unread_priority_hint_dismiss), color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        if (state.missedCalls.isNotEmpty()) {
            MissedCallsCard(calls = state.missedCalls, onOpen = {
                onShowMissedCallsSheetChange(true)
                viewModel.markMissedCallsRead()
            })
        }

        // 8.47：智能归档建议卡片（纯本地启发式；采纳走现有归档流程）
        if (state.archiveSuggestions.isNotEmpty()) {
            ArchiveSuggestionsCard(
                suggestions = state.archiveSuggestions.take(3),
                chatsById = state.chats.associateBy { it.id },
                onArchive = { chatId ->
                    state.chats.firstOrNull { it.id == chatId }?.let { viewModel.archiveChatFromSuggestion(it) }
                },
                onDismissOne = viewModel::dismissArchiveSuggestion,
                onDismissAll = viewModel::dismissAllArchiveSuggestions
            )
        }

        when {
            state.isLoading && state.chats.isEmpty() -> ShimmerChatList()
            // 8.52 UX：会话列表支持下拉刷新（此前仅靠 ON_RESUME 触发，无手动刷新入口）
            else -> PullToRefreshLayout(
                isRefreshing = state.isLoading,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize()
            ) {
                Column {
                    // 8.45：列表已有数据时的刷新指示（重写后丢失的抛光项）
                    if (state.isLoading) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (state.filteredChats.isEmpty()) {
                        EmptyChatState(
                            hasSearchQuery = state.searchQuery.isNotBlank(),
                            showArchived = state.showArchived,
                            selectedFolderId = state.selectedFolderId,
                            // 8.52 UX：加载失败且列表为空时显示错误态 + 重试
                            loadError = if (state.errorMessage != null && state.chats.isEmpty()) state.errorMessage else null,
                            onRetry = { viewModel.refresh() },
                            onAddContact = { onNavigateToTab(MainTab.CONTACTS) },
                            onScan = onOpenScan
                        )
                    } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            bottom = if (floatingDock) FloatingBottomBarContentPadding else 80.dp
                        )
                    ) {
                        val archivedCount = state.chats.count { it.archived }
                        if (!state.showArchived && archivedCount > 0 && state.searchQuery.isBlank()) {
                            item(key = "archive-row") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.setShowArchived(true) }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Outlined.Archive,
                                        contentDescription = null,
                                        tint = LocalChatPalette.current.textSecondary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Text(
                                        stringResource(R.string.chat_archived_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        archivedCount.toString(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = LocalChatPalette.current.textHint
                                    )
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 72.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                )
                            }
                        }
                        items(state.filteredChats, key = { it.id }) { chat ->
                            // 0.73：会话左滑操作（置顶/静音/归档 + 全滑删除）——组件早已存在未接入
                            SwipeableChatItem(
                                isPinned = chat.pinnedAt > 0,
                                isMuted = chat.notificationsMuted,
                                isArchived = chat.archived,
                                onPin = { viewModel.togglePinned(chat.id) },
                                onMute = { viewModel.toggleNotificationsMuted(chat.id) },
                                onArchive = { viewModel.toggleArchived(chat.id) },
                                onDelete = { viewModel.deleteChat(chat.id) },
                                modifier = Modifier.animateItem(
                                    fadeInSpec = motion.listItemFadeInSpec(),
                                    fadeOutSpec = motion.listItemFadeOutSpec(),
                                    placementSpec = motion.listItemPlacementSpec()
                                )
                            ) {
                                ChatListItem(
                                    chat = chat,
                                    draft = state.drafts[chat.id],
                                    typingUserId = state.typingByChat[chat.id],
                                    scheduledCount = state.scheduledByChat[chat.id] ?: 0,
                                    searchQuery = state.searchQuery,
                                    isLocked = chat.id in state.lockedChatIds,
                                    isSecret = chat.id in state.secretChatIds,
                                    identityChanged = !chat.isGroup && chat.participants.firstOrNull()?.id in state.identityChangedUserIds,
                                    isDeleting = chat.id in state.deletingChatIds,
                                    // 1.368：多选模式下点按勾选，长按保持原单条菜单
                                    isSelecting = state.selectionMode,
                                    isSelected = chat.id in state.selectedChatIds,
                                    receipt = state.receiptsByChat[chat.id],
                                    onClick = {
                                        if (state.selectionMode) viewModel.toggleSelectChat(chat.id)
                                        else onChatClick(chat.id)
                                    },
                                    onLongClick = {
                                        if (state.selectionMode) viewModel.toggleSelectChat(chat.id)
                                        else onMenuChatChange(chat)
                                    },
                                    // 1.182：点未读角标标记已读
                                    onBadgeClick = { viewModel.toggleMarkedUnread(chat.id) }
                                )
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 72.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                            )
                        }
                    }
                }
            }
        }
    }
}}
