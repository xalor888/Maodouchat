package com.maodouchat.ui.screen.contacts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.data.model.User
import com.maodouchat.R
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.component.EmptyState
import com.maodouchat.ui.component.EmptyStateType
import com.maodouchat.ui.component.SearchBar
import com.maodouchat.ui.component.ShimmerContactRow
import com.maodouchat.ui.theme.Background
import com.maodouchat.ui.theme.Divider
import com.maodouchat.ui.theme.MaodouchatTheme
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.SurfaceContainerLow
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.UnreadRed
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.MotionTokens

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
    // rememberSaveable 保证旋转屏幕后不再重复播放入场动画
    var animPlayed by rememberSaveable { mutableStateOf(false) }
    var showGroupDialog by remember { mutableStateOf(false) }
    var showChannelDialog by remember { mutableStateOf(false) }
    var removeFriendTarget by remember { mutableStateOf<User?>(null) }
    var nicknameTarget by remember { mutableStateOf<User?>(null) }
    var contactActionTarget by remember { mutableStateOf<User?>(null) }
    // 1.291：拉黑确认目标
    var blockContactTarget by remember { mutableStateOf<User?>(null) }
    var friendRequestTarget by remember { mutableStateOf<User?>(null) }
    LaunchedEffect(Unit) { animPlayed = true }

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
            val index = findLetterIndex(grouped, state.incomingRequests.size, state.outgoingRequests.size, letter)
            if (index >= 0) {
                if (motion.animationsEnabled) listState.animateScrollToItem(index)
                else listState.scrollToItem(index)
            }
            scrollToLetter = null
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_contacts), style = MaterialTheme.typography.headlineMedium, color = OnSurface) },
                actions = {
                    // 标题栏搜索图标：点击清空当前搜索框输入（已输入时），否则聚焦体验由下方 SearchBar 承担
                    IconButton(onClick = { if (state.searchQuery.isNotBlank()) viewModel.onSearchQueryChange("") }) { Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.contacts_search), tint = TextSecondary) }
                    IconButton(onClick = { showGroupDialog = true }) { Icon(Icons.Outlined.PersonAdd, contentDescription = stringResource(R.string.contacts_start_group), tint = TextSecondary) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            )

            SearchBar(
                value = state.searchQuery,
                onValueChange = { viewModel.onSearchQueryChange(it) },
                placeholder = stringResource(R.string.contacts_search_placeholder),
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
                                color = if (state.onlineOnly) Primary else TextHint
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.contacts_presence_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextHint,
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
                    query = state.searchQuery.trim(),
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
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
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
                                    color = Outline,
                                    modifier = Modifier.weight(1f)
                                )
                                // 8.49：好友申请批量操作（非搜索态显示）
                                if (requestQuery.isBlank() && visibleIncoming.size > 1) {
                                    TextButton(onClick = { viewModel.acceptAllFriendRequests() }) {
                                        Text(stringResource(R.string.contacts_friend_accept_all), color = Primary, style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(onClick = { viewModel.rejectAllFriendRequests() }) {
                                        Text(stringResource(R.string.contacts_friend_reject_all), color = UnreadRed, style = MaterialTheme.typography.labelSmall)
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
                                color = Outline,
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
                    // 新群聊
                    item(key = "new_group", contentType = "new_group") {
                        AnimatedVisibility(
                            visible = animPlayed,
                            enter = fadeIn(tween(motion.duration(MotionTokens.Emphasized))) + slideInVertically(tween(motion.duration(MotionTokens.Emphasized)) { it / 4 })
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .clickable { showGroupDialog = true }
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).background(Primary, CircleShape)) {
                                    Icon(Icons.Filled.GroupAdd, contentDescription = stringResource(R.string.contacts_new_group), tint = Color.White, modifier = Modifier.size(22.dp))
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(stringResource(R.string.contacts_new_group), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = OnSurface)
                            }
                        }
                    }

                    // 新建广播频道
                    item(key = "new_channel", contentType = "new_channel") {
                        AnimatedVisibility(
                            visible = animPlayed,
                            enter = fadeIn(tween(motion.duration(MotionTokens.Emphasized))) + slideInVertically(tween(motion.duration(MotionTokens.Emphasized)) { it / 4 })
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .clickable { showChannelDialog = true }
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).background(Primary.copy(alpha = 0.12f), CircleShape)) {
                                    Icon(Icons.Outlined.Campaign, contentDescription = stringResource(R.string.chat_create_channel), tint = Primary, modifier = Modifier.size(22.dp))
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(stringResource(R.string.chat_create_channel), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = OnSurface)
                            }
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
                                    onAction = {
                                        // 清空搜索以保持 SearchBar 可见；用户可直接在上方输入用户名
                                        if (state.searchQuery.isNotBlank()) {
                                            viewModel.onSearchQueryChange("")
                                        }
                                    },
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
                                Text(letter, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, letterSpacing = 0.5.sp), color = Outline)
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
                                            color = Divider,
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
                CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp, color = Primary)
            }
        }

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
                onDismiss = { showGroupDialog = false },
                onConfirm = { groupName, members ->
                    viewModel.createGroupChat(groupName, members)
                    if (groupName.isNotBlank() && members.isNotEmpty()) showGroupDialog = false
                }
            )
        }

        if (showChannelDialog) {
            NewChannelDialog(
                contacts = state.contacts,
                onDismiss = { showChannelDialog = false },
                onConfirm = { channelName, members ->
                    viewModel.createChannelChat(channelName, members)
                    if (channelName.isNotBlank() && members.isNotEmpty()) showChannelDialog = false
                }
            )
        }

        contactActionTarget?.let { user ->
            AlertDialog(
                onDismissRequest = { contactActionTarget = null },
                title = { Text(user.displayName) },
                text = {
                    Column {
                        Text(stringResource(R.string.contacts_long_press_actions))
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(
                            onClick = { contactActionTarget = null; nicknameTarget = user },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.contacts_action_set_nickname), modifier = Modifier.fillMaxWidth()) }
                        TextButton(
                            onClick = { contactActionTarget = null; removeFriendTarget = user },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.contacts_action_remove_friend), modifier = Modifier.fillMaxWidth(), color = TextSecondary) }
                        // 1.291：拉黑（隐私入口，与设置页黑名单管理配套）
                        TextButton(
                            onClick = { contactActionTarget = null; blockContactTarget = user },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.contacts_action_block), modifier = Modifier.fillMaxWidth(), color = UnreadRed) }
                    }
                },
                confirmButton = { TextButton(onClick = { contactActionTarget = null }) { Text(stringResource(R.string.common_cancel)) } }
            )
        }

        // 1.291：拉黑确认
        blockContactTarget?.let { user ->
            AlertDialog(
                onDismissRequest = { blockContactTarget = null },
                title = { Text(stringResource(R.string.contacts_block_title)) },
                text = { Text(stringResource(R.string.contacts_block_message, user.displayName)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.blockUser(user)
                        blockContactTarget = null
                    }) { Text(stringResource(R.string.contacts_action_block), color = UnreadRed) }
                },
                dismissButton = {
                    TextButton(onClick = { blockContactTarget = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }

        removeFriendTarget?.let { user ->
            AlertDialog(
                onDismissRequest = { removeFriendTarget = null },
                title = { Text(stringResource(R.string.contacts_remove_friend_title)) },
                text = { Text(stringResource(R.string.contacts_remove_friend_message, user.displayName)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.removeFriend(user)
                        removeFriendTarget = null
                    }) { Text(stringResource(R.string.contacts_remove_friend_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { removeFriendTarget = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }

        nicknameTarget?.let { user ->
            var draft by remember(user.id) { mutableStateOf(user.nickname.orEmpty()) }
            AlertDialog(
                onDismissRequest = { nicknameTarget = null },
                title = { Text(stringResource(R.string.contacts_nickname_title)) },
                text = {
                    Column {
                        Text(user.name, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
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
                        nicknameTarget = null
                    }) { Text(stringResource(R.string.contacts_nickname_save)) }
                },
                dismissButton = {
                    Row {
                        if (!user.nickname.isNullOrBlank() || draft.isNotBlank()) {
                            TextButton(onClick = {
                                viewModel.setContactNickname(user, "")
                                nicknameTarget = null
                            }) { Text(stringResource(R.string.contacts_nickname_clear), color = TextSecondary) }
                        }
                        TextButton(onClick = { nicknameTarget = null }) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                }
            )
        }

        friendRequestTarget?.let { user ->
            var message by remember(user.id) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { friendRequestTarget = null },
                title = { Text(stringResource(R.string.contacts_friend_request_message_title)) },
                text = {
                    Column {
                        Text(user.displayName, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
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
                        friendRequestTarget = null
                    }) { Text(stringResource(R.string.contacts_friend_request_send)) }
                },
                dismissButton = {
                    TextButton(onClick = { friendRequestTarget = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun SearchResultList(
    results: List<User>,
    isSearching: Boolean,
    query: String,
    onOpenChat: (User) -> Unit,
    onAddFriend: (User) -> Unit
) {
    val motion = LocalMotionSettings.current
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = if (isSearching) stringResource(R.string.contacts_searching, query) else stringResource(R.string.contacts_search_results, query, results.size),
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (isSearching && results.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp, color = Primary)
            }
            return
        }
        if (results.isEmpty()) {
            // 8.52 UX：搜索无结果空态（图标 + 文案，与全局搜索一致）
            EmptyState(
                type = EmptyStateType.SEARCH,
                title = stringResource(R.string.contacts_no_matching_users),
                modifier = Modifier.fillMaxSize().padding(32.dp)
            )
            return
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(results, key = { it.id }, contentType = { "contact_result" }) { user ->
                Column(modifier = Modifier.animateItem(
                    fadeInSpec = motion.listItemFadeInSpec(),
                    fadeOutSpec = motion.listItemFadeOutSpec(),
                    placementSpec = motion.listItemPlacementSpec()
                )) {
                SearchUserRow(
                    user = user,
                    // 1.286：搜索关键词高亮（与 Explore/收藏/通知中心/会话列表一致）
                    highlightQuery = query,
                    onOpenChat = { onOpenChat(user) },
                    onAddFriend = { onAddFriend(user) }
                )
                HorizontalDivider(thickness = 0.5.dp, color = Divider, modifier = Modifier.padding(start = 80.dp))
                }
            }
            item(key = "search_footer", contentType = "footer") { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SearchUserRow(
    user: User,
    onOpenChat: () -> Unit,
    onAddFriend: () -> Unit,
    // 1.286：搜索关键词高亮
    highlightQuery: String = ""
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenChat)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Avatar(name = user.displayName, avatarUrl = user.avatar, size = AvatarSize.MD, isOnline = user.isOnline)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (highlightQuery.isBlank()) androidx.compose.ui.text.AnnotatedString(user.displayName)
                else highlightedText(user.displayName, highlightQuery),
                style = MaterialTheme.typography.bodyLarge,
                color = OnSurface,
                maxLines = 1
            )
            if (user.status.isNotBlank()) {
                Text(user.status, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1)
            }
        }
        TextButton(onClick = onAddFriend) {
            Text(stringResource(R.string.contacts_add_friend), color = Primary)
        }
    }
}

@Composable
private fun FriendRequestRow(
    request: FriendRequestItem,
    onAccept: (() -> Unit)?,
    onReject: (() -> Unit)?,
    onCancel: (() -> Unit)? = null,
    // 1.297：长按拉黑请求者（拦截骚扰）
    onBlock: (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (onBlock != null) Modifier.combinedClickable(onClick = {}, onLongClick = onBlock)
                else Modifier.clickable(enabled = false) {}
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Avatar(name = request.user.name, avatarUrl = request.user.avatar, size = AvatarSize.MD, isOnline = request.user.isOnline)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(request.user.name, style = MaterialTheme.typography.bodyLarge, color = OnSurface, maxLines = 1)
            if (request.message.isNotBlank()) {
                Text(request.message, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 2)
            } else if (request.outgoing) {
                Text(stringResource(R.string.contacts_friend_request_pending), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
        if (onAccept != null && onReject != null) {
            TextButton(onClick = onReject) {
                Text(stringResource(R.string.contacts_friend_reject), color = TextSecondary)
            }
            Button(onClick = onAccept, modifier = Modifier.padding(start = 4.dp)) {
                Text(stringResource(R.string.contacts_friend_accept))
            }
        } else if (onCancel != null) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.contacts_friend_cancel), color = TextSecondary)
            }
        }
    }
}

@Composable
private fun NewGroupDialog(
    contacts: List<User>,
    onDismiss: () -> Unit,
    onConfirm: (groupName: String, members: List<User>) -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    var memberQuery by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val selectedMembers = remember(selectedIds, contacts) { contacts.filter { it.id in selectedIds } }
    val filteredContacts = remember(contacts, memberQuery) {
        val q = memberQuery.trim()
        val base = if (q.isEmpty()) contacts
        else contacts.filter { user ->
            user.displayName.contains(q, ignoreCase = true) ||
                user.name.contains(q, ignoreCase = true) ||
                user.id.contains(q, ignoreCase = true) ||
                user.email.contains(q, ignoreCase = true) ||
                user.nickname?.contains(q, ignoreCase = true) == true
        }
        base.sortedWith(compareByDescending<User> { it.isOnline }.thenBy { it.displayName.lowercase() })
    }
    val canCreate = groupName.trim().isNotEmpty() && selectedMembers.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contacts_new_group)) },
        text = {
            Column {
                TextField(
                    value = groupName,
                    onValueChange = { groupName = it.take(50) },
                    placeholder = { Text(stringResource(R.string.contacts_group_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = memberQuery,
                    onValueChange = { memberQuery = it.take(120) },
                    placeholder = { Text(stringResource(R.string.contacts_search_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.contacts_select_members, selectedMembers.size), style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                // 群成员选择列表：可滚动，最大 50% 屏幕高度
                if (filteredContacts.isEmpty()) {
                    Text(
                        stringResource(
                            if (memberQuery.isNotBlank()) R.string.contacts_new_group_search_empty
                            else R.string.contacts_empty
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextHint,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxHeight(0.5f).heightIn(min = 160.dp, max = 360.dp)) {
                        items(filteredContacts, key = { it.id }, contentType = { "member_candidate" }) { user ->
                            val selected = user.id in selectedIds
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedIds = if (selected) selectedIds - user.id else selectedIds + user.id
                                }.padding(vertical = 6.dp)
                            ) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { checked ->
                                        selectedIds = if (checked) selectedIds + user.id else selectedIds - user.id
                                    }
                                )
                                Avatar(name = user.displayName, avatarUrl = user.avatar, size = AvatarSize.SM, isOnline = user.isOnline)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(user.displayName, style = MaterialTheme.typography.bodyMedium, color = OnSurface, maxLines = 1)
                                    val subtitle = when {
                                        user.isOnline -> stringResource(R.string.contacts_online)
                                        user.email.isNotBlank() -> user.email
                                        else -> stringResource(R.string.contacts_offline)
                                    }
                                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = if (user.isOnline) Primary else TextSecondary, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(groupName.trim(), selectedMembers) },
                enabled = canCreate
            ) { Text(stringResource(R.string.contacts_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/**
 * 新建广播频道：创建者单向广播，订阅者只读。创建时可选初始订阅者（可留空，仅自己）。
 */
@Composable
private fun NewChannelDialog(
    contacts: List<User>,
    onDismiss: () -> Unit,
    onConfirm: (channelName: String, members: List<User>) -> Unit
) {
    var channelName by remember { mutableStateOf("") }
    var memberQuery by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val selectedMembers = remember(selectedIds, contacts) { contacts.filter { it.id in selectedIds } }
    val filteredContacts = remember(contacts, memberQuery) {
        val q = memberQuery.trim()
        val base = if (q.isEmpty()) contacts
        else contacts.filter { user ->
            user.displayName.contains(q, ignoreCase = true) ||
                user.name.contains(q, ignoreCase = true) ||
                user.id.contains(q, ignoreCase = true) ||
                user.email.contains(q, ignoreCase = true) ||
                user.nickname?.contains(q, ignoreCase = true) == true
        }
        base.sortedWith(compareByDescending<User> { it.isOnline }.thenBy { it.displayName.lowercase() })
    }
    val canCreate = channelName.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_channel_create_title)) },
        text = {
            Column {
                Text(stringResource(R.string.chat_channel_create_subtitle), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Spacer(modifier = Modifier.height(10.dp))
                TextField(
                    value = channelName,
                    onValueChange = { channelName = it.take(50) },
                    placeholder = { Text(stringResource(R.string.chat_channel_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = memberQuery,
                    onValueChange = { memberQuery = it.take(120) },
                    placeholder = { Text(stringResource(R.string.contacts_search_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.contacts_select_members, selectedMembers.size), style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                if (filteredContacts.isEmpty()) {
                    Text(
                        stringResource(
                            if (memberQuery.isNotBlank()) R.string.contacts_new_group_search_empty
                            else R.string.contacts_empty
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextHint,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxHeight(0.5f).heightIn(min = 160.dp, max = 360.dp)) {
                        items(filteredContacts, key = { it.id }, contentType = { "channel_candidate" }) { user ->
                            val selected = user.id in selectedIds
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedIds = if (selected) selectedIds - user.id else selectedIds + user.id
                                }.padding(vertical = 6.dp)
                            ) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { checked ->
                                        selectedIds = if (checked) selectedIds + user.id else selectedIds - user.id
                                    }
                                )
                                Avatar(name = user.displayName, avatarUrl = user.avatar, size = AvatarSize.SM, isOnline = user.isOnline)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(user.displayName, style = MaterialTheme.typography.bodyMedium, color = OnSurface, maxLines = 1)
                                    val subtitle = if (user.isOnline) stringResource(R.string.contacts_online) else stringResource(R.string.contacts_offline)
                                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = if (user.isOnline) Primary else TextSecondary, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(channelName.trim(), selectedMembers) },
                enabled = canCreate
            ) { Text(stringResource(R.string.chat_channel_create_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactItem(user: User, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 520f),
        label = "contactPressScale"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(),
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Avatar(name = user.displayName, avatarUrl = user.avatar, size = AvatarSize.MD, isOnline = user.isOnline)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(user.displayName, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = OnSurface, maxLines = 1)
            val subtitle = when {
                !user.nickname.isNullOrBlank() && user.nickname != user.name -> user.name
                user.isOnline -> stringResource(R.string.contacts_online)
                user.status.isNotBlank() -> user.status
                else -> stringResource(R.string.contacts_offline)
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = if (user.isOnline && user.nickname.isNullOrBlank()) Primary else TextSecondary,
                maxLines = 1
            )
        }
    }
}

/** 1.286：联系人搜索结果关键词高亮（复用 GlobalSearchTextHighlight，与 Explore/收藏/通知中心一致）。 */
@Composable
private fun highlightedText(text: String, query: String): androidx.compose.ui.text.AnnotatedString {
    val snippet = remember(text, query) {
        com.maodouchat.ui.screen.chatlist.GlobalSearchTextHighlight.buildSnippet(text, query)
    }
    return androidx.compose.ui.text.buildAnnotatedString {
        if (snippet.highlights.isEmpty()) {
            append(snippet.text)
            return@buildAnnotatedString
        }
        var cursor = 0
        snippet.highlights.forEach { span ->
            if (span.start > cursor) append(snippet.text.substring(cursor, span.start))
            pushStyle(androidx.compose.ui.text.SpanStyle(color = Primary, fontWeight = FontWeight.SemiBold, background = Primary.copy(alpha = 0.12f)))
            append(snippet.text.substring(span.start, span.end))
            pop()
            cursor = span.end
        }
        if (cursor < snippet.text.length) append(snippet.text.substring(cursor))
    }
}

@Composable
private fun AlphabetScroller(
    letters: List<String>,
    onLetterClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val allLetters = remember { ('A'..'Z').map { it.toString() } + "#" }
    var activeLetter by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    val hapticContext = LocalContext.current

    Box(modifier = modifier.fillMaxHeight().padding(end = 4.dp)) {
        // Active letter bubble indicator
        val bubbleScale by animateFloatAsState(
            targetValue = if (activeLetter != null) 1f else 0f,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
            label = "letterBubbleScale"
        )
        if (bubbleScale > 0.01f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (-36).dp)
                    .size(36.dp)
                    .graphicsLayer {
                        scaleX = bubbleScale
                        scaleY = bubbleScale
                        alpha = bubbleScale
                    }
                    .background(Primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = activeLetter ?: "",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val itemHeight = size.height / allLetters.size
                            val index = (offset.y / itemHeight).toInt().coerceIn(0, allLetters.lastIndex)
                            val letter = allLetters[index]
                            if (letter in letters) {
                                activeLetter = letter
                                com.maodouchat.util.HapticGate.perform(hapticContext, haptic, HapticFeedbackType.TextHandleMove)
                                onLetterClick(letter)
                            }
                        },
                        onDragEnd = { activeLetter = null },
                        onDragCancel = { activeLetter = null }
                    ) { change, _ ->
                        change.consume()
                        val y = change.position.y
                        val itemHeight = size.height / allLetters.size
                        val index = (y / itemHeight).toInt().coerceIn(0, allLetters.lastIndex)
                        val letter = allLetters[index]
                        if (letter in letters && letter != activeLetter) {
                            activeLetter = letter
                            com.maodouchat.util.HapticGate.perform(hapticContext, haptic, HapticFeedbackType.TextHandleMove)
                            onLetterClick(letter)
                        }
                    }
                }
        ) {
            allLetters.forEach { letter ->
                val isActive = letter == activeLetter
                val textColor = if (letter in letters) Primary else Outline.copy(alpha = 0.4f)
                Text(
                    text = letter,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = if (isActive) 13.sp else 10.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
                    ),
                    color = textColor,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
    }
}

internal fun findLetterIndex(
    grouped: Map<String, List<User>>,
    incomingCount: Int,
    outgoingCount: Int,
    targetLetter: String
): Int {
    // 字母分组前实际固定的前置项数：
    // 新群聊(1) + 若有收到请求则 [请求头(1) + N] + 若有发出请求则 [请求头(1) + M]
    val leadingFixedItems = 1 +
        (if (incomingCount > 0) incomingCount + 1 else 0) +
        (if (outgoingCount > 0) outgoingCount + 1 else 0)
    val ordered = grouped.keys.toList()
    val sizes = grouped.mapValues { it.value.size }
    return com.maodouchat.contacts.ContactsIndexPolicy.letterListIndex(
        orderedLetters = ordered,
        sizes = sizes,
        targetLetter = targetLetter,
        leadingFixedItems = leadingFixedItems
    )
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun ContactsScreenPreview() { MaodouchatTheme { ContactsScreen() } }
