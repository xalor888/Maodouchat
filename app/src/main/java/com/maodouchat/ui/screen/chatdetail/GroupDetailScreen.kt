package com.maodouchat.ui.screen.chatdetail

import android.annotation.SuppressLint
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.maodouchat.ui.component.rememberSecretPageWatermarkPayload
import com.maodouchat.ui.component.secretPageBlindWatermark
import com.maodouchat.ui.screen.chatdetail.group.BotPickerDialog
import com.maodouchat.ui.screen.chatdetail.group.CandidateRow
import com.maodouchat.ui.screen.chatdetail.group.CollapsibleGroupSearchField
import com.maodouchat.ui.screen.chatdetail.group.EmptyRow
import com.maodouchat.ui.screen.chatdetail.group.GroupAnnouncementCard
import com.maodouchat.ui.screen.chatdetail.group.GroupAuditRow
import com.maodouchat.ui.screen.chatdetail.group.GroupAvatarPreview
import com.maodouchat.ui.screen.chatdetail.group.GroupDetailFeedbackDialog
import com.maodouchat.ui.screen.chatdetail.group.GroupFeatureAction
import com.maodouchat.ui.screen.chatdetail.group.GroupFeaturesCard
import com.maodouchat.ui.screen.chatdetail.group.GroupHeader
import com.maodouchat.ui.screen.chatdetail.group.GroupSenderKeyStatusSection
import com.maodouchat.ui.screen.chatdetail.group.MemberRow
import com.maodouchat.ui.screen.chatdetail.group.MuteAllConfirmDialog
import com.maodouchat.ui.screen.chatdetail.group.MuteMemberDialog
import com.maodouchat.ui.screen.chatdetail.group.RemoveMemberDialog
import com.maodouchat.ui.screen.chatdetail.group.SearchableSectionHeader
import com.maodouchat.ui.screen.chatdetail.group.SectionTitle
import com.maodouchat.ui.screen.chatdetail.group.SetTitleDialog
import com.maodouchat.ui.screen.chatdetail.group.TransferOwnershipDialog
import com.maodouchat.ui.screen.chatdetail.group.groupAuditActionSearchTokens
import com.maodouchat.ui.screen.chatdetail.group.roleRank
import com.maodouchat.ui.theme.LocalChatPalette

private const val AUDIT_PAGE_SIZE = 80
private const val CANDIDATE_PAGE_SIZE = 32
private const val MEMBER_PAGE_SIZE = 100

private enum class GroupDetailTab(val mediaCategory: MediaCenterCategory? = null) {
    MEMBERS,
    AUDIT,
    MEDIA(MediaCenterCategory.MEDIA),
    FILES(MediaCenterCategory.FILES),
    VOICE(MediaCenterCategory.VOICE),
    LOCATION(MediaCenterCategory.LOCATION),
    LINKS(MediaCenterCategory.LINKS),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("HardwareIds")
fun GroupDetailScreen(
    onBack: () -> Unit,
    viewModel: GroupDetailViewModel = viewModel(),
    mediaCenterViewModel: MediaCenterViewModel = viewModel(),
    onEditGroup: (String) -> Unit = {},
    onOpenGroupInvite: (String) -> Unit = {},
    onOpenProfile: (String) -> Unit = {},
    onOpenGroupPoll: (String) -> Unit = {},
    onOpenGroupCheckin: (String) -> Unit = {},
    onOpenGroupChain: (String) -> Unit = {},
    onOpenGroupPk: (String) -> Unit = {},
    onOpenMessage: (String) -> Unit = {},
    onOpenAgent: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mediaCenterState by mediaCenterViewModel.uiState.collectAsStateWithLifecycle()
    val chatId = viewModel.chatId
    var selectedTab by rememberSaveable { mutableStateOf(GroupDetailTab.MEMBERS) }
    var titleTarget by remember { mutableStateOf<GroupMemberUi?>(null) }
    var removeTarget by remember { mutableStateOf<GroupMemberUi?>(null) }
    var muteTarget by remember { mutableStateOf<GroupMemberUi?>(null) }
    var showMuteAllConfirm by remember { mutableStateOf(false) }
    var ownershipTarget by remember { mutableStateOf<GroupMemberUi?>(null) }
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

    LaunchedEffect(state.members) {
        val ids = state.members.mapTo(hashSetOf()) { it.userId }
        if (titleTarget?.let { it.userId !in ids } == true) titleTarget = null
        if (removeTarget?.let { it.userId !in ids } == true) removeTarget = null
        if (muteTarget?.let { it.userId !in ids } == true) muteTarget = null
        if (ownershipTarget?.let { it.userId !in ids } == true) ownershipTarget = null
    }

    val filteredMembers = remember(state.members, memberSearch) {
        val query = memberSearch.trim()
        val base = if (query.isBlank()) state.members else state.members.filter {
            it.displayName.contains(query, ignoreCase = true) ||
                it.name.contains(query, ignoreCase = true) ||
                it.title.orEmpty().contains(query, ignoreCase = true) ||
                it.userId.contains(query, ignoreCase = true)
        }
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
                add(GroupFeatureAction(stringResource(R.string.chat_group_ai_title), Icons.Outlined.AutoAwesome) { onOpenAgent() })
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
        GroupDetailFeedbackDialog(state, viewModel)

        titleTarget?.let { member ->
            SetTitleDialog(
                member = member,
                onDismiss = { titleTarget = null },
                onSave = { newTitle ->
                    viewModel.updateTitle(member.userId, newTitle)
                    titleTarget = null
                }
            )
        }

        removeTarget?.let { member ->
            RemoveMemberDialog(
                member = member,
                onDismiss = { removeTarget = null },
                onConfirm = {
                    viewModel.removeMember(member.userId)
                    removeTarget = null
                }
            )
        }

        ownershipTarget?.let { member ->
            TransferOwnershipDialog(
                member = member,
                onDismiss = { ownershipTarget = null },
                onConfirm = {
                    viewModel.transferOwnership(member.userId)
                    ownershipTarget = null
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

        if (showMuteAllConfirm) {
            MuteAllConfirmDialog(
                onDismiss = { showMuteAllConfirm = false },
                onClearMuteAll = {
                    showMuteAllConfirm = false
                    viewModel.muteAllMembers(0L)
                },
                onMuteAll24h = {
                    showMuteAllConfirm = false
                    viewModel.muteAllMembers(System.currentTimeMillis() + 24L * 3600_000L)
                }
            )
        }

        if (showAvatarFull && !state.groupAvatar.isNullOrBlank()) {
            GroupAvatarPreview(
                avatarUrl = state.groupAvatar.orEmpty(),
                groupName = state.groupName,
                onDismiss = { showAvatarFull = false }
            )
        }

        if (showBotPicker) {
            BotPickerDialog(
                ownedBots = state.ownedBots,
                isInvitingBot = state.isInvitingBot,
                onDismiss = { showBotPicker = false },
                onInviteBot = { botId ->
                    showBotPicker = false
                    viewModel.inviteOwnedBot(botId)
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
                                        onSetTitle = { titleTarget = member },
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
    }
}

@Composable
fun GroupEditScreen(
    onBack: () -> Unit,
    viewModel: GroupDetailViewModel = viewModel()
) {
    com.maodouchat.ui.screen.chatdetail.group.GroupEditScreen(onBack = onBack, viewModel = viewModel)
}

@Composable
fun GroupInviteQrScreen(
    onBack: () -> Unit,
    viewModel: GroupDetailViewModel = viewModel()
) {
    com.maodouchat.ui.screen.chatdetail.group.GroupInviteQrScreen(onBack = onBack, viewModel = viewModel)
}
