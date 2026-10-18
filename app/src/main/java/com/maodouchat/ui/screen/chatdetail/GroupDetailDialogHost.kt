package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.maodouchat.group.GroupDetailUiState
import com.maodouchat.ui.screen.chatdetail.group.BotPickerDialog
import com.maodouchat.ui.screen.chatdetail.group.GroupAvatarPreview
import com.maodouchat.ui.screen.chatdetail.group.MuteAllConfirmDialog
import com.maodouchat.ui.screen.chatdetail.group.MuteMemberDialog
import com.maodouchat.ui.screen.chatdetail.group.RemoveMemberDialog
import com.maodouchat.ui.screen.chatdetail.group.SetTitleDialog
import com.maodouchat.ui.screen.chatdetail.group.TransferOwnershipDialog

// 群详情页 7 个对话框的接线宿主：原在 GroupDetailScreen 内联声明，纯搬移不改判断。
@Composable
internal fun GroupDetailDialogHost(
    interaction: GroupDetailInteractionState,
    state: GroupDetailUiState,
    viewModel: GroupDetailViewModel,
) {
    var titleTarget by interaction.titleTarget
    var removeTarget by interaction.removeTarget
    var muteTarget by interaction.muteTarget
    var showMuteAllConfirm by interaction.showMuteAllConfirm
    var ownershipTarget by interaction.ownershipTarget
    var showAvatarFull by interaction.showAvatarFull
    var showBotPicker by interaction.showBotPicker

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
}
