package com.maodouchat.ui.screen.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.maodouchat.data.model.User
import com.maodouchat.ui.component.EmptyState
import com.maodouchat.ui.component.EmptyStateType
import com.maodouchat.ui.component.FloatingBottomBarContentPadding
import com.maodouchat.ui.component.SearchBar
import com.maodouchat.ui.component.ShimmerContactRow
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.MaodouchatTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 通讯录页面（接入 Room 数据库）
 *
 * @param onChatCreated 创建或打开聊天后导航到聊天详情
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onChatCreated: (String) -> Unit = {},
    onOpenScan: () -> Unit = {},
    viewModel: ContactsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val motion = LocalMotionSettings.current
    var showGroupDialog by remember { mutableStateOf(false) }
    var showChannelDialog by remember { mutableStateOf(false) }
    var removeFriendTarget by remember { mutableStateOf<User?>(null) }
    var nicknameTarget by remember { mutableStateOf<User?>(null) }
    var contactActionTarget by remember { mutableStateOf<User?>(null) }
    // 1.291：拉黑确认目标
    var blockContactTarget by remember { mutableStateOf<User?>(null) }
    var friendRequestTarget by remember { mutableStateOf<User?>(null) }
    val searchFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    fun focusAddContactSearch() {
        scope.launch {
            delay(50)
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(state.createdChatId) {
        state.createdChatId?.let { chatId ->
            onChatCreated(chatId)
            viewModel.clearCreatedChat()
        }
    }

    val grouped = state.grouped
    val letters = remember(grouped) { grouped.keys.toList() }
    val listState = rememberLazyListState()

    // 字母索引栏点击滚动
    var scrollToLetter by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scrollToLetter) {
        scrollToLetter?.let { letter ->
            val index = findLetterIndex(
                grouped,
                state.incomingRequests.size,
                state.outgoingRequests.size,
                state.groupInvites.size,
                letter
            )
            if (index >= 0) {
                if (motion.animationsEnabled) listState.animateScrollToItem(index)
                else listState.scrollToItem(index)
            }
            scrollToLetter = null
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_contacts), modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
                actions = {
                    // 标题栏搜索图标：点击清空当前搜索框输入（已输入时），否则聚焦体验由下方 SearchBar 承担
                    IconButton(onClick = onOpenScan) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = stringResource(R.string.contacts_scan), tint = LocalChatPalette.current.textSecondary)
                    }
                    IconButton(onClick = { focusAddContactSearch() }) {
                        Icon(Icons.Outlined.PersonAdd, contentDescription = stringResource(R.string.contacts_add_contact), tint = LocalChatPalette.current.textSecondary)
                    }
                },
                colors = com.maodouchat.ui.theme.liquidGlassTopAppBarColors()
            )

            SearchBar(
                value = state.searchQuery,
                onValueChange = { viewModel.onSearchQueryChange(it) },
                placeholder = stringResource(R.string.contacts_search_placeholder),
                focusRequester = searchFocusRequester,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (state.searchQuery.trim().length < 2 && state.contacts.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilterChip(
                            selected = state.onlineOnly,
                            onClick = { viewModel.setOnlineOnly(!state.onlineOnly) },
                            label = { Text(stringResource(R.string.contacts_filter_online_only)) }
                        )
                        if (state.onlineCount > 0) {
                            Text(
                                stringResource(R.string.contacts_online_count, state.onlineCount),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (state.onlineOnly) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textHint
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.contacts_presence_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint,
                        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                    )
                }
            }

            if (state.isLoading) {
                Column(modifier = Modifier.fillMaxSize()) {
                    repeat(10) { ShimmerContactRow() }
                }
            } else if (state.searchQuery.trim().length >= 2) {
                // 搜索态：隐藏字母索引与字母分组，仅展示服务端 / 本地缓存的搜索结果
                SearchResultList(
                    results = state.searchResults,
                    isSearching = state.isSearching,
                    searchFailed = state.searchFailed,
                    query = state.searchQuery.trim(),
                    friendIds = remember(state.contacts) { state.contacts.map { it.id }.toSet() },
                    pendingOutgoingIds = remember(state.outgoingRequests) { state.outgoingRequests.map { it.user.id }.toSet() },
                    onOpenChat = { viewModel.createDirectChat(it) },
                    onAddFriend = { friendRequestTarget = it }
                )
            } else {
                val requestQuery = state.searchQuery.trim()
                val visibleIncoming = remember(state.incomingRequests, requestQuery) {
                    if (requestQuery.isBlank()) state.incomingRequests
                    else state.incomingRequests.filter { req ->
                        req.user.displayName.contains(requestQuery, ignoreCase = true) ||
                            req.user.name.contains(requestQuery, ignoreCase = true) ||
                            req.user.id.contains(requestQuery, ignoreCase = true) ||
                            req.message.contains(requestQuery, ignoreCase = true)
                    }
                }
                val visibleOutgoing = remember(state.outgoingRequests, requestQuery) {
                    if (requestQuery.isBlank()) state.outgoingRequests
                    else state.outgoingRequests.filter { req ->
                        req.user.displayName.contains(requestQuery, ignoreCase = true) ||
                            req.user.name.contains(requestQuery, ignoreCase = true) ||
                            req.user.id.contains(requestQuery, ignoreCase = true) ||
                            req.message.contains(requestQuery, ignoreCase = true)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = FloatingBottomBarContentPadding)
                ) {
                    if (visibleIncoming.isNotEmpty()) {
                        item(key = "incoming_header", contentType = "section_header") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    stringResource(R.string.contacts_friend_requests_incoming),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.weight(1f)
                                )
                                // 8.49：好友申请批量操作（非搜索态显示）
                                if (requestQuery.isBlank() && visibleIncoming.size > 1) {
                                    TextButton(onClick = { viewModel.acceptAllFriendRequests() }) {
                                        Text(stringResource(R.string.contacts_friend_accept_all), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(onClick = { viewModel.rejectAllFriendRequests() }) {
                                        Text(stringResource(R.string.contacts_friend_reject_all), color = LocalChatPalette.current.unreadRed, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        items(visibleIncoming, key = { "in_${it.id}" }, contentType = { "friend_request" }) { req ->
                            FriendRequestRow(
                                request = req,
                                onAccept = { viewModel.acceptFriendRequest(req.id) },
                                onReject = { viewModel.rejectFriendRequest(req.id) },
                                // 1.297：长按拉黑请求者
                                onBlock = { blockContactTarget = req.user }
                            )
                        }
                    }
                    if (visibleOutgoing.isNotEmpty()) {
                        item(key = "outgoing_header", contentType = "section_header") {
                            Text(
                                stringResource(R.string.contacts_friend_requests_outgoing),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                        items(visibleOutgoing, key = { "out_${it.id}" }, contentType = { "friend_request_out" }) { req ->
                            FriendRequestRow(
                                request = req,
                                onAccept = null,
                                onReject = null,
                                onCancel = { viewModel.cancelFriendRequest(req.id) }
                            )
                        }
                    }
                    // 9.3xx：群邀请同意流程——本人接受后才入群
                    if (state.groupInvites.isNotEmpty()) {
                        item(key = "group_invites_header", contentType = "section_header") {
                            Text(
                                stringResource(R.string.contacts_group_invites_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                        items(state.groupInvites, key = { "gi_${it.id}" }, contentType = { "group_invite" }) { invite ->
                            GroupInviteRow(
                                invite = invite,
                                busy = state.isGroupInviteBusy,
                                onAccept = { viewModel.acceptGroupInvite(invite.id) },
                                onDecline = { viewModel.declineGroupInvite(invite.id) }
                            )
                        }
                    }
                    item(key = "add_contact", contentType = "add_contact") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable { focusAddContactSearch() }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape)) {
                                Icon(Icons.Outlined.PersonAdd, contentDescription = stringResource(R.string.contacts_add_contact), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(stringResource(R.string.contacts_add_contact), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    // 新群聊
                    item(key = "new_group", contentType = "new_group") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable { showGroupDialog = true }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary, CircleShape)) {
                                Icon(Icons.Filled.GroupAdd, contentDescription = stringResource(R.string.contacts_new_group), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(stringResource(R.string.contacts_new_group), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    // 新建广播频道
                    item(key = "new_channel", contentType = "new_channel") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable { showChannelDialog = true }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape)) {
                                Icon(Icons.Outlined.Campaign, contentDescription = stringResource(R.string.chat_create_channel), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(stringResource(R.string.chat_create_channel), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    if (state.filteredContacts.isEmpty()) {
                        item(key = "empty", contentType = "empty") {
                            if (state.onlineOnly && state.contacts.isNotEmpty()) {
                                EmptyState(
                                    title = stringResource(R.string.contacts_online_empty),
                                    subtitle = stringResource(R.string.contacts_filter_online_only),
                                    type = EmptyStateType.CONTACTS,
                                    actionText = stringResource(R.string.contacts_show_all),
                                    onAction = { viewModel.setOnlineOnly(false) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                EmptyState(
                                    title = stringResource(R.string.contacts_empty_title),
                                    subtitle = stringResource(R.string.contacts_empty_subtitle),
                                    type = EmptyStateType.CONTACTS,
                                    actionText = stringResource(R.string.contacts_empty_action_search),
                                    onAction = { focusAddContactSearch() },
                                    secondaryActionText = stringResource(R.string.contacts_empty_action_scan),
                                    onSecondaryAction = onOpenScan,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    // 字母分组
                    grouped.forEach { (letter, users) ->
                        item(key = "header_$letter", contentType = "letter_header") {
                            Box(
                                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text(letter, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, letterSpacing = 0.5.sp), color = MaterialTheme.colorScheme.outline)
                            }
                        }

                        users.forEachIndexed { index, user ->
                            item(key = "contact_${user.id}", contentType = "contact") {
                                val rowShape = when {
                                    users.size == 1 -> RoundedCornerShape(12.dp)
                                    index == 0 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                                    index == users.lastIndex -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                                    else -> RoundedCornerShape(0.dp)
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                        .clip(rowShape)
                                        .background(MaterialTheme.colorScheme.surface)
                                ) {
                                    ContactItem(
                                        user = user,
                                        onClick = { viewModel.createDirectChat(user) },
                                        onLongClick = { contactActionTarget = user }
                                    )
                                    if (index < users.lastIndex) {
                                        HorizontalDivider(
                                            thickness = 0.5.dp,
                                            color = LocalChatPalette.current.divider,
                                            modifier = Modifier.padding(start = 64.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item(key = "contacts_footer", contentType = "footer") { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }

        // 右侧字母索引栏
        if (!state.isLoading && state.searchQuery.trim().length < 2 && letters.isNotEmpty()) {
            AlphabetScroller(
                letters = letters,
                onLetterClick = { letter -> scrollToLetter = letter },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }

        if (state.isCreatingChat) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.primary)
            }
        }

        ContactsDialogs(
            state = state,
            viewModel = viewModel,
            showGroupDialog = showGroupDialog,
            onShowGroupDialogChange = { showGroupDialog = it },
            showChannelDialog = showChannelDialog,
            onShowChannelDialogChange = { showChannelDialog = it },
            contactActionTarget = contactActionTarget,
            onContactActionTargetChange = { contactActionTarget = it },
            blockContactTarget = blockContactTarget,
            onBlockContactTargetChange = { blockContactTarget = it },
            removeFriendTarget = removeFriendTarget,
            onRemoveFriendTargetChange = { removeFriendTarget = it },
            nicknameTarget = nicknameTarget,
            onNicknameTargetChange = { nicknameTarget = it },
            friendRequestTarget = friendRequestTarget,
            onFriendRequestTargetChange = { friendRequestTarget = it },
        )
    }
}


@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun ContactsScreenPreview() { MaodouchatTheme { ContactsScreen() } }

