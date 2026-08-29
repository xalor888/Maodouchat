package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.util.RuntimeFlags
import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.local.entity.hasCompletedUpload
import com.maodouchat.data.model.User
import com.maodouchat.network.ApiService
import com.maodouchat.network.GroupMemberDto
import com.maodouchat.network.GroupAuditLogDto
import com.maodouchat.network.SenderKeyDistributionTargetDto
import com.maodouchat.network.SenderKeyDistributionStatusDto
import com.maodouchat.network.TokenManager
import com.maodouchat.network.WebSocketClient
import com.maodouchat.network.WebSocketEvent
import com.maodouchat.messaging.v2.GroupMessagingCoordinator
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.component.rememberSecretPageWatermarkPayload
import com.maodouchat.ui.component.secretPageBlindWatermark
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.UnreadRed
import com.maodouchat.util.QrCodeGenerator
import com.maodouchat.util.ImagePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val AUDIT_PAGE_SIZE = 80
private const val CANDIDATE_PAGE_SIZE = 32
private const val MEMBER_PAGE_SIZE = 100
private const val SENDER_KEY_TARGET_PAGE = 20

private enum class GroupDetailTab(val mediaCategory: MediaCenterCategory? = null) {
    MEMBERS,
    AUDIT,
    MEDIA(MediaCenterCategory.MEDIA),
    FILES(MediaCenterCategory.FILES),
    VOICE(MediaCenterCategory.VOICE),
    LOCATION(MediaCenterCategory.LOCATION),
    LINKS(MediaCenterCategory.LINKS),
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("HardwareIds")
fun GroupDetailScreen(
    onBack: () -> Unit,
    viewModel: GroupDetailViewModel = viewModel(),
    mediaCenterViewModel: MediaCenterViewModel = viewModel(),
    onEditGroup: (String) -> Unit = {},
    onOpenGroupInvite: (String) -> Unit = {},
    // 1.08：点击群成员查看资料（userId）
    onOpenProfile: (String) -> Unit = {},
    onOpenGroupPoll: (String) -> Unit = {},
    onOpenGroupCheckin: (String) -> Unit = {},
    onOpenGroupChain: (String) -> Unit = {},
    onOpenGroupPk: (String) -> Unit = {},
    onOpenMessage: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mediaCenterState by mediaCenterViewModel.uiState.collectAsStateWithLifecycle()
    val chatId = viewModel.chatId
    var selectedTab by rememberSaveable { mutableStateOf(GroupDetailTab.MEMBERS) }
    var titleTarget by remember { mutableStateOf<GroupMemberUi?>(null) }
    var titleDraft by rememberSaveable { mutableStateOf("") }
    var removeTarget by remember { mutableStateOf<GroupMemberUi?>(null) }
    var muteTarget by remember { mutableStateOf<GroupMemberUi?>(null) }
    // 0.99：全员静音确认
    var showMuteAllConfirm by remember { mutableStateOf(false) }
    var ownershipTarget by remember { mutableStateOf<GroupMemberUi?>(null) }
    // 9.150：成员操作弹窗持有的 GroupMemberUi 快照——目标成员被移出/退出（他端或 WS 同步）后自动关闭弹窗，
    // 避免对已不存在成员继续「移除/转让」操作
    LaunchedEffect(state.members) {
        val ids = state.members.mapTo(hashSetOf()) { it.userId }
        if (titleTarget?.let { it.userId !in ids } == true) titleTarget = null
        if (removeTarget?.let { it.userId !in ids } == true) removeTarget = null
        if (muteTarget?.let { it.userId !in ids } == true) muteTarget = null
        if (ownershipTarget?.let { it.userId !in ids } == true) ownershipTarget = null
    }
    var showAvatarFull by remember { mutableStateOf(false) }
    var showBotPicker by rememberSaveable { mutableStateOf(false) }
    var memberSearch by rememberSaveable { mutableStateOf("") }
    var memberSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var membersExpanded by rememberSaveable { mutableStateOf(false) }
    var auditSearch by rememberSaveable { mutableStateOf("") }
    var auditSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var auditExpanded by rememberSaveable { mutableStateOf(false) }
    var candidateSearch by rememberSaveable { mutableStateOf("") }
    var candidateSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var candidatesExpanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val filteredMembers = remember(state.members, memberSearch) {
        val query = memberSearch.trim()
        val base = if (query.isBlank()) state.members else state.members.filter {
            it.displayName.contains(query, ignoreCase = true) ||
                it.name.contains(query, ignoreCase = true) ||
                it.title.orEmpty().contains(query, ignoreCase = true) ||
                it.userId.contains(query, ignoreCase = true)
        }
        // 0.69：群主 → 管理员 → 成员 置顶排序；1.38：同角色内在线成员优先（角色标签已渲染）
        base.sortedWith(
            compareBy<GroupMemberUi> { roleRank(it.role) }
                .thenByDescending { it.isOnline }
                .thenBy { it.displayName.lowercase() }
        )
    }
    val visibleMembers = remember(filteredMembers, membersExpanded) {
        if (membersExpanded || filteredMembers.size <= MEMBER_PAGE_SIZE) {
            filteredMembers
        } else {
            filteredMembers.take(MEMBER_PAGE_SIZE)
        }
    }
    val filteredAuditLogs = remember(state.auditLogs, auditSearch) {
        val query = auditSearch.trim()
        if (query.isBlank()) {
            state.auditLogs
        } else {
            state.auditLogs.filter { audit ->
                audit.actorName.contains(query, ignoreCase = true) ||
                    audit.actorId.contains(query, ignoreCase = true) ||
                    audit.action.contains(query, ignoreCase = true) ||
                    audit.targetUserName.orEmpty().contains(query, ignoreCase = true) ||
                    audit.targetUserId.orEmpty().contains(query, ignoreCase = true) ||
                    groupAuditActionSearchTokens(audit.action).any { token ->
                        token.contains(query, ignoreCase = true)
                    }
            }
        }
    }
    val visibleAuditLogs = remember(filteredAuditLogs, auditExpanded) {
        if (auditExpanded || filteredAuditLogs.size <= AUDIT_PAGE_SIZE) {
            filteredAuditLogs
        } else {
            filteredAuditLogs.take(AUDIT_PAGE_SIZE)
        }
    }
    val filteredCandidates = remember(state.candidates, candidateSearch) {
        val query = candidateSearch.trim()
        if (query.isBlank()) {
            state.candidates
        } else {
            state.candidates.filter {
                it.displayName.contains(query, ignoreCase = true) ||
                    it.name.contains(query, ignoreCase = true) ||
                    it.id.contains(query, ignoreCase = true)
            }
        }
    }
    val visibleCandidates = remember(filteredCandidates, candidatesExpanded) {
        if (candidatesExpanded || filteredCandidates.size <= CANDIDATE_PAGE_SIZE) {
            filteredCandidates
        } else {
            filteredCandidates.take(CANDIDATE_PAGE_SIZE)
        }
    }
    val secretPagePayload = rememberSecretPageWatermarkPayload(
        isSecretChat = state.isSecretChat,
        userId = state.currentUserId,
        chatId = viewModel.chatId,
        deviceHint = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
    )
    val groupOverview: @Composable () -> Unit = {
        GroupHeader(
            groupName = state.groupName,
            groupAvatar = state.groupAvatar,
            memberCount = state.members.size,
            myRole = state.myRole,
            onShowAvatarFull = { showAvatarFull = true }
        )
        GroupAnnouncementCard(state.groupAnnouncement)
        if (state.isChannel) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Icon(
                    Icons.Outlined.Campaign,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (state.canManageGroup) {
                        pluralStringResource(
                            R.plurals.chat_channel_member_count,
                            state.members.size,
                            state.members.size
                        )
                    } else {
                        stringResource(R.string.chat_channel_subscriber_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        SectionTitle(stringResource(R.string.group_detail_play))
        GroupFeaturesCard(
            actions = buildList {
                add(GroupFeatureAction(stringResource(R.string.group_play_poll), Icons.Outlined.Checklist) { onOpenGroupPoll(chatId) })
                add(GroupFeatureAction(stringResource(R.string.group_play_checkin), Icons.Outlined.History) { onOpenGroupCheckin(chatId) })
                add(GroupFeatureAction(stringResource(R.string.group_play_chain_title), Icons.Outlined.Link) { onOpenGroupChain(chatId) })
                add(GroupFeatureAction(stringResource(R.string.group_play_pk_title), Icons.Outlined.SwapHoriz) { onOpenGroupPk(chatId) })
                if (state.canManageGroup && !state.isChannel) {
                    add(GroupFeatureAction(stringResource(R.string.group_play_invite_bot), Icons.Outlined.PersonAdd) { showBotPicker = true })
                }
            }
        )
    }
    val groupTabs: @Composable () -> Unit = {
        ScrollableTabRow(
            selectedTabIndex = selectedTab.ordinal,
            edgePadding = 8.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)) },
        ) {
            GroupDetailTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(stringResource(tab.labelResource()), maxLines = 1) },
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .secretPageBlindWatermark(secretPagePayload)
    ) {
    state.message?.let { body ->
        val feedback = state.feedback
        val isError = feedback != null && feedback.kind != GroupMutationFeedbackKind.SUCCESS
        val showSecondaryDismiss = isError && (feedback.canRetry || feedback.shouldReload)
        AlertDialog(
            onDismissRequest = viewModel::consumeMessage,
            title = {
                Text(
                    stringResource(
                        if (isError) R.string.group_detail_error_title else R.string.group_detail_notice
                    )
                )
            },
            text = { Text(body) },
            confirmButton = {
                when {
                    feedback?.canRetry == true -> {
                        TextButton(
                            onClick = viewModel::retryLastMutation,
                            enabled = !state.isUpdating && !state.isLoading && !state.isUploadingAvatar && !state.isLoadingInvite
                        ) {
                            Text(stringResource(R.string.group_detail_retry_action), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    feedback?.shouldReload == true -> {
                        TextButton(
                            onClick = viewModel::dismissFeedbackAndReload,
                            enabled = !state.isLoading
                        ) {
                            Text(stringResource(R.string.group_detail_reload_action), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    else -> {
                        TextButton(onClick = viewModel::consumeMessage) {
                            Text(stringResource(R.string.chat_acknowledge))
                        }
                    }
                }
            },
            dismissButton = if (showSecondaryDismiss) {
                {
                    TextButton(onClick = viewModel::consumeMessage) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            } else {
                null
            }
        )
    }

    titleTarget?.let { member ->
        AlertDialog(
            onDismissRequest = { titleTarget = null },
            title = { Text(stringResource(R.string.group_detail_set_title)) },
            text = {
                TextField(
                    value = titleDraft,
                    onValueChange = { titleDraft = it.take(50) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.group_detail_member_title)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = groupTextFieldColors()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateTitle(member.userId, titleDraft)
                    titleTarget = null
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { titleTarget = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    removeTarget?.let { member ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.group_detail_remove_member)) },
            text = { Text(stringResource(R.string.group_detail_remove_confirm, member.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeMember(member.userId)
                    removeTarget = null
                }) { Text(stringResource(R.string.chat_remove), color = LocalChatPalette.current.unreadRed) }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    ownershipTarget?.let { member ->
        AlertDialog(
            onDismissRequest = { ownershipTarget = null },
            title = { Text(stringResource(R.string.group_detail_transfer_owner)) },
            text = { Text(stringResource(R.string.group_detail_transfer_confirm, member.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.transferOwnership(member.userId)
                    ownershipTarget = null
                }) { Text(stringResource(R.string.group_detail_transfer_confirm_action), color = LocalChatPalette.current.unreadRed) }
            },
            dismissButton = {
                TextButton(onClick = { ownershipTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    muteTarget?.let { member ->
        MuteMemberDialog(
            member = member,
            onDismiss = { muteTarget = null },
            onMuteUntil = { mutedUntil ->
                viewModel.updateMemberMute(member.userId, mutedUntil)
                muteTarget = null
            }
        )
    }

    // 0.99：全员静音确认（默认 24 小时）；1.19：同一对话框提供「解除全员静音」
    if (showMuteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showMuteAllConfirm = false },
            title = { Text(stringResource(R.string.group_detail_mute_all_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
            text = { Text(stringResource(R.string.group_detail_mute_all_confirm), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        showMuteAllConfirm = false
                        viewModel.muteAllMembers(0L)
                    }) { Text(stringResource(R.string.group_detail_mute_all_clear), color = MaterialTheme.colorScheme.onSurface) }
                    TextButton(onClick = {
                        showMuteAllConfirm = false
                        viewModel.muteAllMembers(System.currentTimeMillis() + 24L * 3600_000L)
                    }) { Text(stringResource(R.string.group_detail_mute_all), color = LocalChatPalette.current.unreadRed) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showMuteAllConfirm = false }) { Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary) }
            }
        )
    }

    if (showAvatarFull && !state.groupAvatar.isNullOrBlank()) {
        // 8.42：群头像大图预览（全屏缩放，点按关闭）
        Dialog(onDismissRequest = { showAvatarFull = false }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f))
                    .clickable { showAvatarFull = false }
            ) {
                com.maodouchat.ui.component.ZoomableAsyncImage(
                    model = state.groupAvatar,
                    contentDescription = state.groupName,
                    modifier = Modifier.fillMaxSize(),
                    onSingleTap = { showAvatarFull = false }
                )
            }
        }
    }

    if (showBotPicker) {
        AlertDialog(
            onDismissRequest = { showBotPicker = false },
            title = { Text(stringResource(R.string.group_play_invite_bot_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.ownedBots.isEmpty()) {
                        Text(stringResource(R.string.group_play_invite_bot_empty))
                    } else {
                        state.ownedBots.forEach { bot ->
                            TextButton(
                                onClick = {
                                    showBotPicker = false
                                    viewModel.inviteOwnedBot(bot.id)
                                },
                                enabled = !state.isInvitingBot,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "${bot.name}  @${bot.username}",
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBotPicker = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    Scaffold(
        containerColor = LocalChatPalette.current.chatBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(if (state.isChannel) R.string.chat_detail_channel_header else R.string.group_detail_title),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { onEditGroup(chatId) },
                        enabled = !state.isLoading
                    ) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.group_detail_edit),
                            tint = if (state.isLoading) LocalChatPalette.current.textHint else MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = { onOpenGroupInvite(chatId) },
                        enabled = state.canManageGroup && !state.isLoadingInvite
                    ) {
                        Icon(
                            Icons.Outlined.QrCode,
                            contentDescription = stringResource(R.string.group_detail_invite_qr),
                            tint = if (state.canManageGroup) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textHint
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.shadow(1.dp)
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                item(key = "group_overview", contentType = "group_overview") { groupOverview() }
                item(key = "group_tabs", contentType = "group_tabs") { groupTabs() }
                when (selectedTab) {
                    GroupDetailTab.MEMBERS -> {
                        item(key = "sender_key_status", contentType = "sender_key_status") {
                            GroupSenderKeyStatusSection(
                                status = state.senderKeyStatus,
                                memberRevision = state.memberRevision,
                                members = state.members,
                                isUpdating = state.isUpdating,
                                hasLocalDistribution = state.localHasSenderKey,
                                onRedistribute = viewModel::redistributeSenderKey
                            )
                        }
                        item(key = "members_header", contentType = "section_header") {
                            SearchableSectionHeader(
                                text = stringResource(R.string.group_detail_members_section, state.members.size),
                                searchExpanded = memberSearchExpanded,
                                onToggleSearch = {
                                    memberSearchExpanded = !memberSearchExpanded
                                    if (!memberSearchExpanded) memberSearch = ""
                                },
                                showSearch = state.members.isNotEmpty(),
                            ) {
                                if (state.canManageGroup) {
                                    IconButton(onClick = { showMuteAllConfirm = true }) {
                                        Icon(
                                            Icons.Outlined.NotificationsOff,
                                            contentDescription = stringResource(R.string.group_detail_mute_all),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            CollapsibleGroupSearchField(
                                visible = memberSearchExpanded,
                                value = memberSearch,
                                onValueChange = {
                                    memberSearch = it
                                    membersExpanded = false
                                },
                                placeholder = stringResource(R.string.group_detail_search_members),
                            )
                        }
                        if (visibleMembers.isEmpty()) {
                            item(key = "members_empty", contentType = "empty") { EmptyRow(stringResource(R.string.group_detail_no_member_results)) }
                        }
                        items(visibleMembers, key = { it.userId }, contentType = { "group_member" }) { member ->
                            Column {
                                MemberRow(
                                    member = member,
                                    isMe = member.userId == state.currentUserId,
                                    canManage = state.canManageGroup,
                                    isOwner = state.isOwner,
                                    isUpdating = state.isUpdating,
                                    onSetTitle = {
                                        titleTarget = member
                                        titleDraft = member.title.orEmpty()
                                    },
                                    onPromote = { viewModel.updateRole(member.userId, "ADMIN") },
                                    onDemote = { viewModel.updateRole(member.userId, "MEMBER") },
                                    onTransferOwnership = { ownershipTarget = member },
                                    onMute = { muteTarget = member },
                                    onRemove = { removeTarget = member },
                                    onOpenProfile = { onOpenProfile(member.userId) },
                                    highlightQuery = memberSearch
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), modifier = Modifier.padding(start = 68.dp))
                            }
                        }
                        if (!membersExpanded && filteredMembers.size > MEMBER_PAGE_SIZE) {
                            item(key = "members_more", contentType = "more") {
                                val remaining = filteredMembers.size - MEMBER_PAGE_SIZE
                                TextButton(
                                    onClick = { membersExpanded = true },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                                ) {
                                    Text(pluralStringResource(R.plurals.group_detail_members_more, remaining, remaining), color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        if (state.canManageGroup) {
                            item(key = "candidates_header", contentType = "section_header") {
                                SearchableSectionHeader(
                                    text = stringResource(R.string.chat_add_member),
                                    searchExpanded = candidateSearchExpanded,
                                    onToggleSearch = {
                                        candidateSearchExpanded = !candidateSearchExpanded
                                        if (!candidateSearchExpanded) candidateSearch = ""
                                    },
                                    showSearch = state.candidates.isNotEmpty(),
                                )
                                CollapsibleGroupSearchField(
                                    visible = candidateSearchExpanded,
                                    value = candidateSearch,
                                    onValueChange = {
                                        candidateSearch = it
                                        candidatesExpanded = false
                                    },
                                    placeholder = stringResource(R.string.group_detail_search_candidates),
                                )
                            }
                            if (state.candidates.isEmpty()) {
                                item(key = "candidates_empty", contentType = "empty") { EmptyRow(stringResource(R.string.group_detail_no_addable_contacts)) }
                            } else if (filteredCandidates.isEmpty()) {
                                item(key = "candidates_no_results", contentType = "empty") {
                                    EmptyRow(stringResource(R.string.group_detail_no_candidate_results))
                                }
                            } else {
                                items(visibleCandidates, key = { it.id }, contentType = { "group_candidate" }) { user ->
                                    CandidateRow(user = user, enabled = !state.isUpdating, onAdd = { viewModel.addMember(user.id) })
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), modifier = Modifier.padding(start = 68.dp))
                                }
                                if (filteredCandidates.size > CANDIDATE_PAGE_SIZE) {
                                    item(key = "candidates_toggle", contentType = "toggle") {
                                        val remaining = filteredCandidates.size - CANDIDATE_PAGE_SIZE
                                        TextButton(
                                            onClick = { candidatesExpanded = !candidatesExpanded },
                                            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                                        ) {
                                            Text(
                                                if (candidatesExpanded) {
                                                    stringResource(R.string.chat_transcript_collapse)
                                                } else {
                                                    pluralStringResource(R.plurals.group_detail_candidates_more, remaining, remaining)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "members_footer", contentType = "footer") { Spacer(modifier = Modifier.height(24.dp)) }
                    }

                    GroupDetailTab.AUDIT -> {
                        item(key = "audit_header", contentType = "section_header") {
                            SearchableSectionHeader(
                                text = stringResource(R.string.group_detail_audit_title),
                                searchExpanded = auditSearchExpanded,
                                onToggleSearch = {
                                    auditSearchExpanded = !auditSearchExpanded
                                    if (!auditSearchExpanded) auditSearch = ""
                                },
                                showSearch = state.auditLogs.isNotEmpty(),
                            )
                            CollapsibleGroupSearchField(
                                visible = auditSearchExpanded,
                                value = auditSearch,
                                onValueChange = {
                                    auditSearch = it
                                    auditExpanded = false
                                },
                                placeholder = stringResource(R.string.group_detail_audit_search),
                            )
                        }
                        if (state.auditLogs.isEmpty()) {
                            item(key = "audit_empty", contentType = "empty") { EmptyRow(stringResource(R.string.group_detail_audit_empty)) }
                        } else if (filteredAuditLogs.isEmpty()) {
                            item(key = "audit_no_results", contentType = "empty") {
                                EmptyRow(stringResource(R.string.group_detail_audit_no_results))
                            }
                        } else {
                            items(visibleAuditLogs, key = { it.id }, contentType = { "audit_log" }) { audit ->
                                GroupAuditRow(audit)
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), modifier = Modifier.padding(start = 52.dp))
                            }
                            if (filteredAuditLogs.size > AUDIT_PAGE_SIZE || state.hasMoreAudit) {
                                item(key = "audit_toggle", contentType = "toggle") {
                                    val remaining = filteredAuditLogs.size - AUDIT_PAGE_SIZE
                                    TextButton(
                                        onClick = {
                                            if (auditExpanded && state.hasMoreAudit && !state.isLoadingMoreAudit) {
                                                viewModel.loadMoreAudit()
                                            } else {
                                                auditExpanded = !auditExpanded
                                            }
                                        },
                                        enabled = !state.isLoadingMoreAudit,
                                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                                    ) {
                                        Text(
                                            when {
                                                state.isLoadingMoreAudit -> stringResource(R.string.group_detail_audit_loading)
                                                auditExpanded && state.hasMoreAudit -> stringResource(R.string.group_detail_audit_load_more)
                                                auditExpanded -> stringResource(R.string.chat_transcript_collapse)
                                                else -> pluralStringResource(R.plurals.group_detail_audit_more, remaining.coerceAtLeast(0), remaining.coerceAtLeast(0))
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        item(key = "audit_footer", contentType = "footer") { Spacer(modifier = Modifier.height(24.dp)) }
                    }

                    else -> {
                        item(key = "media_content", contentType = "media_content") {
                            MediaCenterCategoryContent(
                                category = requireNotNull(selectedTab.mediaCategory),
                                state = mediaCenterState,
                                viewModel = mediaCenterViewModel,
                                onOpenMessage = onOpenMessage,
                                // MediaCenterCategoryContent owns the category list, so give it
                                // a bounded viewport while the outer page remains scrollable.
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 240.dp, max = 560.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    } // secret watermark Box
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("HardwareIds")
fun GroupEditScreen(
    onBack: () -> Unit,
    viewModel: GroupDetailViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val chatId = viewModel.chatId
    var groupNameDraft by rememberSaveable(chatId) { mutableStateOf(state.groupName) }
    var announcementDraft by rememberSaveable(chatId) { mutableStateOf(state.groupAnnouncement) }
    var nicknameDraft by rememberSaveable(chatId) { mutableStateOf(state.myNickname) }
    var lastSyncedGroupName by remember(chatId) { mutableStateOf(state.groupName) }
    var lastSyncedAnnouncement by remember(chatId) { mutableStateOf(state.groupAnnouncement) }
    var lastSyncedNickname by remember(chatId) { mutableStateOf(state.myNickname) }
    var showAvatarFull by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::uploadGroupAvatar)
    }

    LaunchedEffect(chatId, state.groupName, state.groupAnnouncement, state.myNickname, state.isLoading) {
        if (state.isLoading) return@LaunchedEffect
        if (groupNameDraft == lastSyncedGroupName || groupNameDraft.isBlank()) groupNameDraft = state.groupName
        lastSyncedGroupName = state.groupName
        if (announcementDraft == lastSyncedAnnouncement || announcementDraft.isBlank()) {
            announcementDraft = state.groupAnnouncement
        }
        lastSyncedAnnouncement = state.groupAnnouncement
        if (nicknameDraft == lastSyncedNickname || nicknameDraft.isBlank()) nicknameDraft = state.myNickname
        lastSyncedNickname = state.myNickname
    }

    val secretPagePayload = rememberSecretPageWatermarkPayload(
        isSecretChat = state.isSecretChat,
        userId = state.currentUserId,
        chatId = chatId,
        deviceHint = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .secretPageBlindWatermark(secretPagePayload)
    ) {
        GroupDetailFeedbackDialog(state, viewModel)
        if (showAvatarFull && !state.groupAvatar.isNullOrBlank()) {
            GroupAvatarPreview(
                avatarUrl = state.groupAvatar.orEmpty(),
                groupName = state.groupName,
                onDismiss = { showAvatarFull = false }
            )
        }
        Scaffold(
            containerColor = LocalChatPalette.current.chatBackground,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.group_detail_edit), color = MaterialTheme.colorScheme.onSurface) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.common_back),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.shadow(1.dp)
                )
            }
        ) { padding ->
            if (state.isLoading) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    item(key = "edit_avatar", contentType = "edit_avatar") {
                        GroupEditAvatar(
                            groupName = state.groupName,
                            groupAvatar = state.groupAvatar,
                            canChangeAvatar = state.canManageGroup,
                            isUploadingAvatar = state.isUploadingAvatar,
                            onChangeAvatar = {
                                avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            onShowAvatarFull = { showAvatarFull = true }
                        )
                    }
                    item(key = "edit_settings_title", contentType = "section_header") {
                        SectionTitle(stringResource(R.string.group_detail_settings))
                    }
                    if (state.canManageGroup) {
                        item(key = "edit_group_name", contentType = "setting") {
                            SettingTextFieldRow(
                                label = stringResource(R.string.chat_group_name),
                                value = groupNameDraft,
                                onValueChange = { groupNameDraft = it.take(50) },
                                enabled = !state.isUpdating,
                                onSave = { viewModel.renameGroup(groupNameDraft) },
                                saveEnabled = groupNameDraft.trim().isNotBlank() && groupNameDraft.trim() != state.groupName
                            )
                        }
                        item(key = "edit_announcement", contentType = "setting") {
                            AnnouncementRow(
                                value = announcementDraft,
                                onValueChange = { announcementDraft = it.take(1200) },
                                enabled = !state.isUpdating,
                                onSave = { viewModel.updateAnnouncement(announcementDraft) },
                                saveEnabled = announcementDraft.trim() != state.groupAnnouncement,
                                canManage = true
                            )
                        }
                    } else {
                        item(key = "edit_read_only", contentType = "empty") {
                            EmptyRow(stringResource(R.string.group_detail_read_only_hint))
                        }
                    }
                    item(key = "edit_nickname", contentType = "setting") {
                        SettingTextFieldRow(
                            label = stringResource(R.string.group_detail_my_nickname),
                            value = nicknameDraft,
                            onValueChange = { nicknameDraft = it.take(100) },
                            enabled = !state.isUpdating,
                            onSave = { viewModel.setMyNickname(nicknameDraft) },
                            saveEnabled = nicknameDraft.trim() != state.myNickname
                        )
                    }
                    item(key = "edit_footer", contentType = "footer") { Spacer(modifier = Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("HardwareIds")
fun GroupInviteQrScreen(
    onBack: () -> Unit,
    viewModel: GroupDetailViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var inviteExpirySeconds by rememberSaveable { mutableLongStateOf(7L * 24L * 60L * 60L) }
    var inviteMaxUses by rememberSaveable { mutableIntStateOf(100) }
    val context = LocalContext.current
    val chooserTitle = stringResource(R.string.group_detail_share_invite)

    LaunchedEffect(state.isLoading, state.canManageGroup, state.groupInvitePayload) {
        if (!state.isLoading && state.canManageGroup && state.groupInvitePayload.isBlank() && !state.isLoadingInvite) {
            viewModel.loadGroupInvite(expiresInSeconds = inviteExpirySeconds, maxUses = inviteMaxUses)
        }
    }

    val shareInvite: () -> Unit = {
        val payload = state.groupInvitePayload
        if (payload.isNotBlank()) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, payload)
            }
            runCatching { context.startActivity(Intent.createChooser(intent, chooserTitle)) }
        }
    }
    val secretPagePayload = rememberSecretPageWatermarkPayload(
        isSecretChat = state.isSecretChat,
        userId = state.currentUserId,
        chatId = viewModel.chatId,
        deviceHint = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .secretPageBlindWatermark(secretPagePayload)
    ) {
        GroupDetailFeedbackDialog(state, viewModel)
        Scaffold(
            containerColor = LocalChatPalette.current.chatBackground,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.group_detail_invite_qr), color = MaterialTheme.colorScheme.onSurface) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.common_back),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                viewModel.loadGroupInvite(
                                    rotate = true,
                                    expiresInSeconds = inviteExpirySeconds,
                                    maxUses = inviteMaxUses
                                )
                            },
                            enabled = state.canManageGroup && !state.isLoadingInvite
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.common_refresh))
                        }
                        IconButton(
                            onClick = shareInvite,
                            enabled = state.groupInvitePayload.isNotBlank() && !state.isLoadingInvite
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.group_detail_share))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.shadow(1.dp)
                )
            }
        ) { padding ->
            when {
                state.isLoading -> {
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                !state.canManageGroup -> {
                    Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.group_detail_invite_admin_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalChatPalette.current.textSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                else -> {
                    GroupInviteContent(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        payload = state.groupInvitePayload,
                        isLoading = state.isLoadingInvite,
                        expiresAt = state.inviteExpiresAt,
                        maxUses = state.inviteMaxUses,
                        usedCount = state.inviteUsedCount,
                        remainingUses = state.inviteRemainingUses,
                        expiresInSeconds = inviteExpirySeconds,
                        selectedMaxUses = inviteMaxUses,
                        onExpiryChange = { inviteExpirySeconds = it },
                        onMaxUsesChange = { inviteMaxUses = it }
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupInviteContent(
    modifier: Modifier,
    payload: String,
    isLoading: Boolean,
    expiresAt: Long,
    maxUses: Int,
    usedCount: Int,
    remainingUses: Int,
    expiresInSeconds: Long,
    selectedMaxUses: Int,
    onExpiryChange: (Long) -> Unit,
    onMaxUsesChange: (Int) -> Unit
) {
    val context = LocalContext.current
    val inviteCopiedMessage = stringResource(R.string.group_detail_invite_copied)
    val bitmap = remember(payload) {
        payload.takeIf { it.isNotBlank() }?.let { QrCodeGenerator.generateBitmap(it, 720) }
    }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(14.dp)
                .size(248.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                bitmap != null -> Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.group_detail_invite_qr),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                else -> Text(stringResource(R.string.group_detail_qr_failed), color = LocalChatPalette.current.textHint)
            }
        }
        Text(
            stringResource(R.string.group_detail_invite_hint),
            style = MaterialTheme.typography.bodySmall,
            color = LocalChatPalette.current.textSecondary,
            textAlign = TextAlign.Center
        )
        if (payload.isNotBlank()) {
            TextButton(onClick = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("group_invite", payload))
                android.widget.Toast.makeText(
                    context,
                    inviteCopiedMessage,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.group_detail_invite_copy))
            }
        }
        if (expiresAt > 0) {
            Text(
                stringResource(
                    R.string.group_detail_invite_status,
                    java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm",
                        LocalConfiguration.current.locales[0]
                    ).format(java.util.Date(expiresAt)),
                    usedCount,
                    maxUses,
                    remainingUses
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center
            )
        }
        Text(
            stringResource(R.string.group_detail_invite_expiry),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                24L * 60L * 60L to stringResource(R.string.group_detail_invite_one_day),
                3L * 24L * 60L * 60L to stringResource(R.string.group_detail_invite_three_days),
                7L * 24L * 60L * 60L to stringResource(R.string.group_detail_invite_seven_days),
                30L * 24L * 60L * 60L to stringResource(R.string.group_detail_invite_thirty_days)
            ).forEach { (seconds, label) ->
                InviteChoice(selected = expiresInSeconds == seconds, label = label) { onExpiryChange(seconds) }
            }
        }
        Text(
            stringResource(R.string.group_detail_invite_max_uses),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(1, 5, 10, 50, 100, 200, 500, 1000).forEach { uses ->
                InviteChoice(selected = selectedMaxUses == uses, label = uses.toString()) { onMaxUsesChange(uses) }
            }
        }
        Text(
            stringResource(R.string.group_detail_invite_limit_note),
            style = MaterialTheme.typography.labelSmall,
            color = LocalChatPalette.current.textHint,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun InviteChoice(selected: Boolean, label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.background(
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
            RoundedCornerShape(8.dp)
        )
    ) {
        Text(label, color = if (selected) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textSecondary)
    }
}

@Composable
private fun GroupEditAvatar(
    groupName: String,
    groupAvatar: String?,
    canChangeAvatar: Boolean,
    isUploadingAvatar: Boolean,
    onChangeAvatar: () -> Unit,
    onShowAvatarFull: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), CircleShape)
                .clickable(
                    enabled = (canChangeAvatar && !isUploadingAvatar) || (!canChangeAvatar && !groupAvatar.isNullOrBlank())
                ) {
                    if (canChangeAvatar) onChangeAvatar() else onShowAvatarFull()
                },
            contentAlignment = Alignment.Center
        ) {
            Avatar(name = groupName, avatarUrl = groupAvatar, size = AvatarSize.LG)
            when {
                isUploadingAvatar -> CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(30.dp)
                )
                canChangeAvatar -> Icon(
                    Icons.Outlined.CameraAlt,
                    contentDescription = stringResource(R.string.group_detail_change_avatar),
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(26.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                        .padding(5.dp)
                )
            }
        }
        if (canChangeAvatar) {
            Text(
                stringResource(R.string.group_detail_change_avatar),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun GroupAvatarPreview(avatarUrl: String, groupName: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable(onClick = onDismiss)
        ) {
            com.maodouchat.ui.component.ZoomableAsyncImage(
                model = avatarUrl,
                contentDescription = groupName,
                modifier = Modifier.fillMaxSize(),
                onSingleTap = onDismiss
            )
        }
    }
}

@Composable
private fun GroupDetailFeedbackDialog(state: GroupDetailUiState, viewModel: GroupDetailViewModel) {
    val body = state.message ?: return
    val feedback = state.feedback
    val isError = feedback != null && feedback.kind != GroupMutationFeedbackKind.SUCCESS
    val showSecondaryDismiss = isError && (feedback.canRetry || feedback.shouldReload)
    AlertDialog(
        onDismissRequest = viewModel::consumeMessage,
        title = {
            Text(stringResource(if (isError) R.string.group_detail_error_title else R.string.group_detail_notice))
        },
        text = { Text(body) },
        confirmButton = {
            when {
                feedback?.canRetry == true -> TextButton(
                    onClick = viewModel::retryLastMutation,
                    enabled = !state.isUpdating && !state.isLoading && !state.isUploadingAvatar && !state.isLoadingInvite
                ) { Text(stringResource(R.string.group_detail_retry_action)) }
                feedback?.shouldReload == true -> TextButton(
                    onClick = viewModel::dismissFeedbackAndReload,
                    enabled = !state.isLoading
                ) { Text(stringResource(R.string.group_detail_reload_action)) }
                else -> TextButton(onClick = viewModel::consumeMessage) {
                    Text(stringResource(R.string.chat_acknowledge))
                }
            }
        },
        dismissButton = if (showSecondaryDismiss) {
            { TextButton(onClick = viewModel::consumeMessage) { Text(stringResource(R.string.common_cancel)) } }
        } else {
            null
        }
    )
}

@Composable
private fun MuteMemberDialog(
    member: GroupMemberUi,
    onDismiss: () -> Unit,
    onMuteUntil: (Long) -> Unit
) {
    val now = System.currentTimeMillis()
    val label5 = stringResource(R.string.group_detail_mute_5_minutes)
    val label10 = stringResource(R.string.group_detail_mute_10_minutes)
    val label30 = stringResource(R.string.group_detail_mute_30_minutes)
    val label1h = stringResource(R.string.group_detail_mute_1_hour)
    val label2h = stringResource(R.string.group_detail_mute_2_hours)
    val label3h = stringResource(R.string.group_detail_mute_3_hours)
    val label6h = stringResource(R.string.group_detail_mute_6_hours)
    val label8h = stringResource(R.string.group_detail_mute_8_hours)
    val label1d = stringResource(R.string.group_detail_mute_1_day)
    val label7d = stringResource(R.string.group_detail_mute_7_days)
    val label30d = stringResource(R.string.group_detail_mute_30_days)
    val choices = GroupMutePolicy.presets.map { preset ->
        val label = when (preset) {
            GroupMutePolicy.Preset.MINUTES_5 -> label5
            GroupMutePolicy.Preset.MINUTES_10 -> label10
            GroupMutePolicy.Preset.MINUTES_30 -> label30
            GroupMutePolicy.Preset.HOUR_1 -> label1h
            GroupMutePolicy.Preset.HOURS_2 -> label2h
            GroupMutePolicy.Preset.HOURS_3 -> label3h
            GroupMutePolicy.Preset.HOURS_6 -> label6h
            GroupMutePolicy.Preset.HOURS_8 -> label8h
            GroupMutePolicy.Preset.DAY_1 -> label1d
            GroupMutePolicy.Preset.DAYS_7 -> label7d
            GroupMutePolicy.Preset.DAYS_30 -> label30d
        }
        label to preset
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_detail_set_mute)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(member.displayName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                if (GroupMutePolicy.isActiveMute(member.mutedUntil, now)) {
                    Text(stringResource(R.string.group_detail_current_mute_until, formatMuteTime(member.mutedUntil)), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                }
                choices.forEach { (label, preset) ->
                    TextButton(
                        onClick = { onMuteUntil(GroupMutePolicy.mutedUntil(now, preset)) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(label, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (GroupMutePolicy.isActiveMute(member.mutedUntil, now)) {
                    TextButton(
                        onClick = { onMuteUntil(GroupMutePolicy.clearMuteUntil()) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.group_detail_unmute), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
private fun GroupHeader(
    groupName: String,
    groupAvatar: String?,
    memberCount: Int,
    myRole: String,
    onShowAvatarFull: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 24.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Avatar(
                name = groupName,
                avatarUrl = groupAvatar,
                size = AvatarSize.LG,
                modifier = Modifier.clickable(enabled = !groupAvatar.isNullOrBlank(), onClick = onShowAvatarFull)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = groupName,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = pluralStringResource(R.plurals.group_detail_header_summary, memberCount, memberCount, roleLabel(myRole)),
            style = MaterialTheme.typography.bodySmall,
            color = LocalChatPalette.current.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun GroupAnnouncementCard(announcement: String) {
    var expanded by rememberSaveable(announcement) { mutableStateOf(false) }
    var overflowsFourLines by remember(announcement) { mutableStateOf(false) }
    val canToggle = expanded || overflowsFourLines

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(enabled = canToggle) { expanded = !expanded },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Campaign,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.group_detail_announcement),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = announcement.ifBlank { stringResource(R.string.group_detail_no_announcement) },
                style = MaterialTheme.typography.bodyMedium,
                color = if (announcement.isBlank()) LocalChatPalette.current.textHint else MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result ->
                    if (!expanded) overflowsFourLines = result.hasVisualOverflow
                }
            )
            if (canToggle) {
                Text(
                    text = stringResource(if (expanded) R.string.chat_transcript_collapse else R.string.chat_transcript_expand),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

private data class GroupFeatureAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@Composable
private fun GroupFeaturesCard(actions: List<GroupFeatureAction>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        actions.chunked(2).forEachIndexed { rowIndex, rowActions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GroupFeatureButton(rowActions[0], Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(64.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
                )
                if (rowActions.size == 2) {
                    GroupFeatureButton(rowActions[1], Modifier.weight(1f))
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            if (rowIndex < actions.chunked(2).lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
            }
        }
    }
}

@Composable
private fun GroupFeatureButton(action: GroupFeatureAction, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .height(104.dp)
            .clickable(onClick = action.onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun GroupSenderKeyStatusSection(
    status: SenderKeyDistributionStatusDto?,
    memberRevision: Long,
    members: List<GroupMemberUi>,
    isUpdating: Boolean,
    hasLocalDistribution: Boolean?,
    onRedistribute: () -> Unit
) {
    var targetFilter by rememberSaveable { mutableStateOf(SenderKeyTargetFilter.ALL) }
    var targetsExpanded by rememberSaveable { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.group_detail_sender_key))
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val targetStatuses = status?.targets?.map { it.status }.orEmpty()
        val assessment = remember(status, memberRevision, targetStatuses) {
            com.maodouchat.crypto.SenderKeyCoveragePolicy.assess(
                // 8.48 修复 H1：以「本机实际持有」为准——服务端 total>0 不代表本机有 key
                //（重装/换机后 UI 曾误判 COMPLETE，自动重分发永不触发，群消息首次发送失败）
                hasLocalDistribution = hasLocalDistribution ?: (status != null && status.total > 0),
                requestedEpoch = memberRevision,
                statusEpoch = status?.epoch ?: 0L,
                targetStatuses = targetStatuses,
                reportedTotal = status?.total
            )
        }
        if (status == null || status.total == 0) {
            Text(stringResource(R.string.group_detail_no_key_record), style = MaterialTheme.typography.bodyMedium, color = LocalChatPalette.current.textHint)
            Text(stringResource(R.string.group_detail_key_auto_hint), style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textSecondary)
            Text(
                senderKeyReasonLabel(assessment.reason),
                style = MaterialTheme.typography.labelSmall,
                color = LocalChatPalette.current.textSecondary
            )
            Button(onClick = onRedistribute, enabled = !isUpdating) { Text(stringResource(R.string.group_detail_distribute_now)) }
            return@Column
        }
        val needsAction = assessment.requiresDistribution
        val filteredTargets = remember(status.targets, targetFilter) {
            when (targetFilter) {
                SenderKeyTargetFilter.ALL -> status.targets
                SenderKeyTargetFilter.FAILED -> status.targets.filter { it.status.equals("FAILED", ignoreCase = true) }
                SenderKeyTargetFilter.PENDING -> status.targets.filter { it.status.equals("PENDING", ignoreCase = true) }
                SenderKeyTargetFilter.SENT -> status.targets.filter { it.status.equals("SENT", ignoreCase = true) }
            }
        }
        val visibleTargets = remember(filteredTargets, targetsExpanded) {
            if (targetsExpanded || filteredTargets.size <= SENDER_KEY_TARGET_PAGE) {
                filteredTargets
            } else {
                filteredTargets.take(SENDER_KEY_TARGET_PAGE)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.group_detail_epoch, status.epoch), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(pluralStringResource(R.plurals.group_detail_device_stats, status.total, status.total, status.sent, status.failed, status.pending), style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textSecondary)
                Text(
                    senderKeyReasonLabel(assessment.reason),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (needsAction) UnreadRed else LocalChatPalette.current.textSecondary
                )
            }
            Text(
                if (needsAction) stringResource(R.string.group_detail_retry_needed) else stringResource(R.string.group_detail_status_normal),
                style = MaterialTheme.typography.labelLarge,
                color = if (needsAction) UnreadRed else MaterialTheme.colorScheme.primary
            )
        }
        Button(onClick = onRedistribute, enabled = !isUpdating, modifier = Modifier.fillMaxWidth()) {
            Text(
                when (assessment.reason) {
                    com.maodouchat.crypto.SenderKeyCoveragePolicy.Reason.FAILED_TARGETS ->
                        stringResource(R.string.group_detail_retry_failed_devices)
                    com.maodouchat.crypto.SenderKeyCoveragePolicy.Reason.PENDING_TARGETS ->
                        stringResource(R.string.group_detail_retry_pending_devices)
                    else -> stringResource(R.string.group_detail_redistribute_key)
                }
            )
        }
        if (status.targets.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SenderKeyTargetFilter.entries.forEach { filter ->
                    val selected = targetFilter == filter
                    TextButton(
                        onClick = {
                            targetFilter = filter
                            targetsExpanded = false
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            when (filter) {
                                SenderKeyTargetFilter.ALL -> stringResource(R.string.group_detail_key_filter_all)
                                SenderKeyTargetFilter.FAILED -> stringResource(R.string.group_detail_key_filter_failed)
                                SenderKeyTargetFilter.PENDING -> stringResource(R.string.group_detail_key_filter_pending)
                                SenderKeyTargetFilter.SENT -> stringResource(R.string.group_detail_key_filter_sent)
                            },
                            color = if (selected) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        if (filteredTargets.isEmpty()) {
            Text(
                stringResource(R.string.group_detail_key_filter_empty),
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatPalette.current.textHint
            )
        } else {
            visibleTargets.forEach { target ->
                val memberName = members.firstOrNull { it.userId == target.userId }?.displayName ?: target.userId
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.group_detail_device, memberName, target.deviceId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        senderKeyStatusLabel(target.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = senderKeyStatusColor(target.status)
                    )
                }
            }
            if (filteredTargets.size > SENDER_KEY_TARGET_PAGE) {
                TextButton(
                    onClick = { targetsExpanded = !targetsExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (targetsExpanded) {
                            stringResource(R.string.chat_transcript_collapse)
                        } else {
                            stringResource(R.string.group_detail_more_devices, filteredTargets.size - SENDER_KEY_TARGET_PAGE)
                        },
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private enum class SenderKeyTargetFilter { ALL, FAILED, PENDING, SENT }

@Composable
private fun senderKeyReasonLabel(reason: com.maodouchat.crypto.SenderKeyCoveragePolicy.Reason): String =
    stringResource(
        when (reason) {
            com.maodouchat.crypto.SenderKeyCoveragePolicy.Reason.LOCAL_MISSING ->
                R.string.group_detail_key_reason_local_missing
            com.maodouchat.crypto.SenderKeyCoveragePolicy.Reason.EPOCH_MISMATCH ->
                R.string.group_detail_key_reason_epoch_mismatch
            com.maodouchat.crypto.SenderKeyCoveragePolicy.Reason.FAILED_TARGETS ->
                R.string.group_detail_key_reason_failed
            com.maodouchat.crypto.SenderKeyCoveragePolicy.Reason.PENDING_TARGETS ->
                R.string.group_detail_key_reason_pending
            com.maodouchat.crypto.SenderKeyCoveragePolicy.Reason.UNKNOWN_TARGETS ->
                R.string.group_detail_key_reason_unknown
            com.maodouchat.crypto.SenderKeyCoveragePolicy.Reason.NO_SERVER_RECORD ->
                R.string.group_detail_key_reason_no_record
            com.maodouchat.crypto.SenderKeyCoveragePolicy.Reason.COMPLETE ->
                R.string.group_detail_key_reason_complete
        }
    )

@Composable
private fun GroupAuditRow(audit: GroupAuditLogDto) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    R.string.group_detail_audit_event,
                    audit.actorName.ifBlank { audit.actorId },
                    groupAuditActionLabel(audit.action),
                    audit.targetUserName?.takeIf(String::isNotBlank) ?: audit.targetUserId.orEmpty()
                ),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                java.text.SimpleDateFormat(
                    "MM-dd HH:mm",
                    LocalConfiguration.current.locales[0]
                ).format(java.util.Date(audit.createdAt)),
                color = LocalChatPalette.current.textHint,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun groupAuditActionLabel(action: String): String = stringResource(when (action) {
    "MEMBER_ADDED" -> R.string.group_audit_member_added
    "MEMBER_JOINED" -> R.string.group_audit_member_joined
    "MEMBER_REMOVED" -> R.string.group_audit_member_removed
    "MEMBER_PROMOTED" -> R.string.group_audit_member_promoted
    "MEMBER_DEMOTED" -> R.string.group_audit_member_demoted
    "MEMBER_MUTED" -> R.string.group_audit_member_muted
    "MEMBER_UNMUTED" -> R.string.group_audit_member_unmuted
    "GROUP_RENAMED" -> R.string.group_audit_group_renamed
    "ANNOUNCEMENT_UPDATED" -> R.string.group_audit_announcement
    "AVATAR_UPDATED" -> R.string.group_audit_avatar
    "INVITE_ROTATED", "INVITE_CONFIGURED" -> R.string.group_audit_invite
    "TITLE_UPDATED" -> R.string.group_audit_title
    "NICKNAME_UPDATED" -> R.string.group_audit_nickname
    "MEMBER_LEFT" -> R.string.group_audit_member_left
    "OWNERSHIP_TRANSFERRED" -> R.string.group_audit_ownership_transferred
    else -> R.string.group_audit_other
})

/** Non-composable tokens so audit search matches common zh/en action labels without Context. */
private fun groupAuditActionSearchTokens(action: String): List<String> = when (action) {
    "MEMBER_ADDED" -> listOf("添加", "成员", "added", "member", "add")
    "MEMBER_JOINED" -> listOf("加入", "邀请", "joined", "invite", "join")
    "MEMBER_REMOVED" -> listOf("移除", "踢出", "removed", "remove", "kick")
    "MEMBER_PROMOTED" -> listOf("管理员", "提升", "admin", "promoted", "promote")
    "MEMBER_DEMOTED" -> listOf("取消管理员", "降级", "demoted", "demote")
    "MEMBER_MUTED" -> listOf("禁言", "muted", "mute")
    "MEMBER_UNMUTED" -> listOf("解禁", "解除禁言", "unmuted", "unmute")
    "GROUP_RENAMED" -> listOf("群名", "改名", "rename", "renamed")
    "ANNOUNCEMENT_UPDATED" -> listOf("公告", "announcement")
    "AVATAR_UPDATED" -> listOf("头像", "avatar")
    "INVITE_ROTATED", "INVITE_CONFIGURED" -> listOf("邀请", "链接", "invite", "link")
    "TITLE_UPDATED" -> listOf("头衔", "title")
    "NICKNAME_UPDATED" -> listOf("昵称", "nickname")
    "MEMBER_LEFT" -> listOf("退群", "退出", "left", "leave")
    "OWNERSHIP_TRANSFERRED" -> listOf("转让", "群主", "owner", "transfer")
    else -> listOf("操作", "activity", "group")
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

private fun GroupDetailTab.labelResource(): Int = when (this) {
    GroupDetailTab.MEMBERS -> R.string.chat_group_member
    GroupDetailTab.AUDIT -> R.string.group_detail_audit_title
    GroupDetailTab.MEDIA -> R.string.media_center_media
    GroupDetailTab.FILES -> R.string.media_center_files
    GroupDetailTab.VOICE -> R.string.media_center_voice
    GroupDetailTab.LOCATION -> R.string.media_center_location
    GroupDetailTab.LINKS -> R.string.media_center_links
}

@Composable
private fun SearchableSectionHeader(
    text: String,
    searchExpanded: Boolean,
    onToggleSearch: () -> Unit,
    showSearch: Boolean = true,
    trailingContent: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f),
        )
        if (showSearch) {
            IconButton(onClick = onToggleSearch) {
                Icon(
                    imageVector = if (searchExpanded) Icons.Outlined.Close else Icons.Outlined.Search,
                    contentDescription = stringResource(
                        if (searchExpanded) R.string.chat_search_close else R.string.chat_search_action
                    ),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        trailingContent()
    }
}

@Composable
private fun CollapsibleGroupSearchField(
    visible: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        TextField(
            value = value,
            onValueChange = { onValueChange(it.take(120)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            placeholder = { Text(placeholder) },
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 8.dp),
            colors = groupTextFieldColors(),
        )
    }
}

@Composable
private fun SettingTextFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSave: () -> Unit,
    saveEnabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            modifier = Modifier.weight(1f),
            colors = groupTextFieldColors()
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = onSave, enabled = saveEnabled) { Text(stringResource(R.string.common_save)) }
    }
}

@Composable
private fun AnnouncementRow(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSave: () -> Unit,
    saveEnabled: Boolean,
    canManage: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp, 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.group_detail_announcement), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        TextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            minLines = 3,
            maxLines = 6,
            placeholder = { Text(if (canManage) stringResource(R.string.group_detail_announcement_input) else stringResource(R.string.group_detail_no_announcement)) },
            modifier = Modifier.fillMaxWidth(),
            colors = groupTextFieldColors()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${value.length}/1200", style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint, modifier = Modifier.weight(1f))
            if (canManage) {
                TextButton(onClick = { onValueChange("") }, enabled = enabled && value.isNotBlank()) { Text(stringResource(R.string.common_clear)) }
                Spacer(modifier = Modifier.width(6.dp))
                Button(onClick = onSave, enabled = saveEnabled) { Text(stringResource(R.string.group_detail_save_announcement)) }
            }
        }
    }
}

@Composable
private fun MemberRow(
    member: GroupMemberUi,
    isMe: Boolean,
    canManage: Boolean,
    isOwner: Boolean,
    isUpdating: Boolean,
    onSetTitle: () -> Unit,
    onPromote: () -> Unit,
    onDemote: () -> Unit,
    onTransferOwnership: () -> Unit,
    onMute: () -> Unit,
    onRemove: () -> Unit,
    onOpenProfile: () -> Unit = {},
    // 1.295：成员搜索关键词高亮
    highlightQuery: String = ""
) {
    var actionsExpanded by remember(member.userId) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!isMe) {
            // 1.08：点击成员头像/名字查看资料
            Avatar(
                name = member.name,
                avatarUrl = member.avatar,
                size = AvatarSize.SM,
                isOnline = member.isOnline,
                modifier = Modifier.clickable { onOpenProfile() }
            )
        } else {
            Avatar(name = member.name, avatarUrl = member.avatar, size = AvatarSize.SM, isOnline = member.isOnline)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f).then(if (!isMe) Modifier.clickable { onOpenProfile() } else Modifier)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // 1.295：成员搜索时高亮匹配关键词
                    if (highlightQuery.isBlank()) androidx.compose.ui.text.AnnotatedString(member.displayName)
                    else highlightedText(member.displayName, highlightQuery),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isMe) Text(stringResource(R.string.group_detail_me_marker), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                listOfNotNull(
                    roleLabel(member.role),
                    member.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.group_detail_no_title),
                    // 8.63：OWNER 豁免禁言——被禁言成员晋升 OWNER 后不再显示禁言徽标（服务端同样豁免发言拦截）
                    if (member.isMuted && member.role != "OWNER") stringResource(R.string.group_detail_muted_until, formatMuteTime(member.mutedUntil)) else null
                )
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (member.isMuted && member.role != "OWNER") UnreadRed else LocalChatPalette.current.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (canManage) {
            Box {
                IconButton(onClick = { actionsExpanded = true }, enabled = !isUpdating) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.group_detail_member_actions, member.displayName),
                        tint = LocalChatPalette.current.textSecondary
                    )
                }
                DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.group_detail_set_title)) },
                        onClick = { actionsExpanded = false; onSetTitle() },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) }
                    )
                    if (isOwner && !isMe && member.role != "OWNER") {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (member.role == "ADMIN") stringResource(R.string.group_detail_demote)
                                    else stringResource(R.string.group_detail_promote_admin)
                                )
                            },
                            onClick = {
                                actionsExpanded = false
                                if (member.role == "ADMIN") onDemote() else onPromote()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.group_detail_transfer_owner), color = LocalChatPalette.current.unreadRed) },
                            onClick = { actionsExpanded = false; onTransferOwnership() },
                            leadingIcon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null, tint = LocalChatPalette.current.unreadRed) }
                        )
                    }
                    if (!isMe && member.role != "OWNER" && (isOwner || member.role != "ADMIN")) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (member.isMuted) stringResource(R.string.group_detail_unmute)
                                    else stringResource(R.string.group_detail_mute)
                                )
                            },
                            onClick = { actionsExpanded = false; onMute() }
                        )
                    }
                    if (!isMe && member.role != "OWNER" && (isOwner || member.role != "ADMIN")) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_remove), color = LocalChatPalette.current.unreadRed) },
                            onClick = { actionsExpanded = false; onRemove() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(user: User, enabled: Boolean, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(name = user.name, avatarUrl = user.avatar, size = AvatarSize.SM, isOnline = user.isOnline)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(user.displayName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (user.status.isNotBlank()) Text(user.status, style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onAdd, enabled = enabled) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.chat_add))
        }
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = LocalChatPalette.current.textHint,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp)
    )
}

@Composable
private fun groupTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = LocalChatPalette.current.chatInputBackground,
    unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
    disabledContainerColor = LocalChatPalette.current.chatInputBackground,
    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
    disabledIndicatorColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    disabledTextColor = LocalChatPalette.current.textHint
)


@Composable
private fun roleLabel(role: String): String = when (role) {
    "OWNER" -> stringResource(R.string.group_detail_role_owner)
    "ADMIN" -> stringResource(R.string.group_detail_role_admin)
    else -> stringResource(R.string.group_detail_role_member)
}

@Composable
private fun formatMuteTime(timestamp: Long): String {
    if (timestamp <= 0) return stringResource(R.string.group_detail_not_muted)
    val formatter = java.text.SimpleDateFormat(
        "MM-dd HH:mm",
        LocalConfiguration.current.locales[0]
    )
    return formatter.format(java.util.Date(timestamp))
}

@Composable
private fun senderKeyStatusLabel(status: String): String = when (status.uppercase()) {
    "SENT" -> stringResource(R.string.group_detail_key_sent)
    "FAILED" -> stringResource(R.string.group_detail_key_failed)
    "PENDING" -> stringResource(R.string.group_detail_key_pending)
    else -> status
}

@Composable
private fun senderKeyStatusColor(status: String) = when (status.uppercase()) {
    "SENT" -> MaterialTheme.colorScheme.primary
    "FAILED" -> UnreadRed
    else -> LocalChatPalette.current.textHint
}

/** 0.69：群成员角色置顶排序权重（OWNER > ADMIN > MEMBER）。 */
private fun roleRank(role: String): Int = when (role) {
    "OWNER" -> 0
    "ADMIN" -> 1
    else -> 2
}

/** 1.295：群成员搜索关键词高亮（复用 GlobalSearchTextHighlight，与 Explore/收藏/通知中心/联系人一致）。 */
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
            pushStyle(androidx.compose.ui.text.SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, background = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)))
            append(snippet.text.substring(span.start, span.end))
            pop()
            cursor = span.end
        }
        if (cursor < snippet.text.length) append(snippet.text.substring(cursor))
    }
}
