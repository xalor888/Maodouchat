package com.maodouchat.ui.screen.chatdetail.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.maodouchat.R
import com.maodouchat.ui.component.ZoomableAsyncImage
import com.maodouchat.ui.screen.chatdetail.GroupDetailViewModel
import com.maodouchat.group.GroupMemberUi
import com.maodouchat.ui.screen.chatdetail.GroupMutePolicy
import com.maodouchat.group.GroupMutationFeedbackKind
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.group.GroupDetailUiState
import com.maodouchat.group.OwnedBotUi

@Composable
fun GroupAvatarPreview(avatarUrl: String, groupName: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(onClick = onDismiss)
        ) {
            ZoomableAsyncImage(
                model = avatarUrl,
                contentDescription = groupName,
                modifier = Modifier.fillMaxSize(),
                onSingleTap = onDismiss
            )
        }
    }
}

@Composable
fun GroupDetailFeedbackDialog(state: GroupDetailUiState, viewModel: GroupDetailViewModel) {
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
fun MuteMemberDialog(
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
                    Text(
                        stringResource(R.string.group_detail_current_mute_until, formatMuteTime(member.mutedUntil)),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textSecondary
                    )
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
fun SetTitleDialog(
    member: GroupMemberUi,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var titleDraft by rememberSaveable { mutableStateOf(member.title.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
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
            TextButton(onClick = { onSave(titleDraft) }) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
fun RemoveMemberDialog(
    member: GroupMemberUi,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_detail_remove_member)) },
        text = { Text(stringResource(R.string.group_detail_remove_confirm, member.displayName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.chat_remove), color = LocalChatPalette.current.unreadRed)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
fun TransferOwnershipDialog(
    member: GroupMemberUi,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_detail_transfer_owner)) },
        text = { Text(stringResource(R.string.group_detail_transfer_confirm, member.displayName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.group_detail_transfer_confirm_action), color = LocalChatPalette.current.unreadRed)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
fun MuteAllConfirmDialog(
    onDismiss: () -> Unit,
    onClearMuteAll: () -> Unit,
    onMuteAll24h: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_detail_mute_all_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
        text = { Text(stringResource(R.string.group_detail_mute_all_confirm), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) },
        confirmButton = {
            Row {
                TextButton(onClick = onClearMuteAll) {
                    Text(stringResource(R.string.group_detail_mute_all_clear), color = MaterialTheme.colorScheme.onSurface)
                }
                TextButton(onClick = onMuteAll24h) {
                    Text(stringResource(R.string.group_detail_mute_all), color = LocalChatPalette.current.unreadRed)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary)
            }
        }
    )
}

@Composable
fun BotPickerDialog(
    ownedBots: List<OwnedBotUi>,
    isInvitingBot: Boolean,
    onDismiss: () -> Unit,
    onInviteBot: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_play_invite_bot_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ownedBots.isEmpty()) {
                    Text(stringResource(R.string.group_play_invite_bot_empty))
                } else {
                    ownedBots.forEach { bot ->
                        TextButton(
                            onClick = { onInviteBot(bot.id) },
                            enabled = !isInvitingBot,
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
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
