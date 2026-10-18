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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import com.maodouchat.ui.screen.chatdetail.group.GroupAuditRow
import com.maodouchat.ui.screen.chatdetail.group.GroupAvatarPreview
import com.maodouchat.ui.screen.chatdetail.group.GroupDetailFeedbackDialog
import com.maodouchat.ui.screen.chatdetail.group.GroupSenderKeyStatusSection
import com.maodouchat.ui.screen.chatdetail.group.MemberRow
import com.maodouchat.ui.screen.chatdetail.group.MuteAllConfirmDialog
import com.maodouchat.ui.screen.chatdetail.group.MuteMemberDialog
import com.maodouchat.ui.screen.chatdetail.group.RemoveMemberDialog
import com.maodouchat.ui.screen.chatdetail.group.SearchableSectionHeader
import com.maodouchat.ui.screen.chatdetail.group.SetTitleDialog
import com.maodouchat.ui.screen.chatdetail.group.TransferOwnershipDialog
import com.maodouchat.ui.theme.LocalChatPalette

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
    // 对话框/搜索/展开交互状态簇收进持有类（见 GroupDetailInteractionState.kt），纯搬移不改判断。
    val interaction = rememberGroupDetailInteractionState(state.members)
    var titleTarget by interaction.titleTarget
    var removeTarget by interaction.removeTarget
    var muteTarget by interaction.muteTarget
    var showMuteAllConfirm by interaction.showMuteAllConfirm
    var ownershipTarget by interaction.ownershipTarget
    var showAvatarFull by interaction.showAvatarFull
    var showBotPicker by interaction.showBotPicker
    var memberSearch by interaction.memberSearch
    var memberSearchExpanded by interaction.memberSearchExpanded
    var membersExpanded by interaction.membersExpanded
    var auditSearch by interaction.auditSearch
    var auditSearchExpanded by interaction.auditSearchExpanded
    var auditExpanded by interaction.auditExpanded
    var candidateSearch by interaction.candidateSearch
    var candidateSearchExpanded by interaction.candidateSearchExpanded
    var candidatesExpanded by interaction.candidatesExpanded
    val context = LocalContext.current

    // 成员 / 审计 / 可添加联系人三条「过滤→分页」派生链收进持有类（见 GroupDetailSearchDerived.kt），纯搬移不改判断。
    val searchDerived = rememberGroupDetailSearchDerived(
        members = state.members,
        memberSearch = memberSearch,
        membersExpanded = membersExpanded,
        auditLogs = state.auditLogs,
        auditSearch = auditSearch,
        auditExpanded = auditExpanded,
        candidates = state.candidates,
        candidateSearch = candidateSearch,
        candidatesExpanded = candidatesExpanded,
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .secretPageBlindWatermark(
                rememberSecretPageWatermarkPayload(
                    isSecretChat = state.isSecretChat,
                    userId = state.currentUserId,
                    chatId = viewModel.chatId,
                    deviceHint = android.provider.Settings.Secure.getString(
                        context.contentResolver,
                        android.provider.Settings.Secure.ANDROID_ID
                    )
                )
            )
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
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.semantics { heading() }
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
                    item(key = "group_overview", contentType = "group_overview") {
                        GroupDetailOverview(
                            state = state,
                            chatId = chatId,
                            onShowAvatarFull = { showAvatarFull = true },
                            onShowBotPicker = { showBotPicker = true },
                            onOpenAgent = onOpenAgent,
                            onOpenGroupPoll = onOpenGroupPoll,
                            onOpenGroupCheckin = onOpenGroupCheckin,
                            onOpenGroupChain = onOpenGroupChain,
                            onOpenGroupPk = onOpenGroupPk,
                        )
                    }
                    item(key = "group_tabs", contentType = "group_tabs") {
                        GroupDetailTabs(
                            selectedTab = selectedTab,
                            onSelectTab = { selectedTab = it },
                        )
                    }
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
                            if (searchDerived.visibleMembers.isEmpty()) {
                                item(key = "members_empty", contentType = "empty") { EmptyRow(stringResource(R.string.group_detail_no_member_results)) }
                            }
                            items(searchDerived.visibleMembers, key = { it.userId }, contentType = { "group_member" }) { member ->
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
                            if (!membersExpanded && searchDerived.filteredMembers.size > MEMBER_PAGE_SIZE) {
                                item(key = "members_more", contentType = "more") {
                                    val remaining = searchDerived.filteredMembers.size - MEMBER_PAGE_SIZE
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
                                } else if (searchDerived.filteredCandidates.isEmpty()) {
                                    item(key = "candidates_no_results", contentType = "empty") {
                                        EmptyRow(stringResource(R.string.group_detail_no_candidate_results))
                                    }
                                } else {
                                    items(searchDerived.visibleCandidates, key = { it.id }, contentType = { "group_candidate" }) { user ->
                                        CandidateRow(user = user, enabled = !state.isUpdating, onAdd = { viewModel.addMember(user.id) })
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), modifier = Modifier.padding(start = 68.dp))
                                    }
                                    if (searchDerived.filteredCandidates.size > CANDIDATE_PAGE_SIZE) {
                                        item(key = "candidates_toggle", contentType = "toggle") {
                                            val remaining = searchDerived.filteredCandidates.size - CANDIDATE_PAGE_SIZE
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
                            } else if (searchDerived.filteredAuditLogs.isEmpty()) {
                                item(key = "audit_no_results", contentType = "empty") {
                                    EmptyRow(stringResource(R.string.group_detail_audit_no_results))
                                }
                            } else {
                                items(searchDerived.visibleAuditLogs, key = { it.id }, contentType = { "audit_log" }) { audit ->
                                    GroupAuditRow(audit)
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), modifier = Modifier.padding(start = 52.dp))
                                }
                                if (searchDerived.filteredAuditLogs.size > AUDIT_PAGE_SIZE || state.hasMoreAudit) {
                                    item(key = "audit_toggle", contentType = "toggle") {
                                        val remaining = searchDerived.filteredAuditLogs.size - AUDIT_PAGE_SIZE
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
