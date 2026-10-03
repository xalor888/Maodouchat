package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.R
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.component.FloatingGlassTopBar
import com.maodouchat.ui.component.InlineTypingDots
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.UnreadRed
import com.maodouchat.util.RuntimeFlags

@Composable
internal fun ChatDetailTopBar(
    state: ChatDetailUiState,
    headerStatus: ChatHeaderStatus,
    participantNamesById: Map<String, String>,
    viewModel: ChatDetailViewModel,
    dialogs: ChatDetailDialogState,
    schedule: ChatDetailScheduleState,
    chatLock: ChatDetailChatLockState,
    flows: ChatDetailConversationFlowState,
    search: ChatDetailSearchState,
    drafts: ChatDetailDraftState,
    contactSheets: ChatDetailContactSheetState,
    groupCall: ChatDetailGroupCallState,
    pickers: ChatDetailPickers,
    onBack: () -> Unit,
    onOpenGroupDetail: (chatId: String) -> Unit,
    onOpenProfile: ((userId: String) -> Unit)?,
    onOpenStarredMessages: (chatId: String) -> Unit,
    onOpenMediaCenter: (chatId: String) -> Unit,
    onOpenCallHistory: (() -> Unit)?,
    onVoiceCall: (contactId: String, contactName: String) -> Unit,
    onVideoCall: (contactId: String, contactName: String) -> Unit,
) {
    val context = LocalContext.current
FloatingGlassTopBar(
    consumeStatusBars = true,
    title = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .clickable {
                    if (state.chatIsGroup) {
                        state.chat?.id?.let(onOpenGroupDetail)
                    } else {
                        val peerId = state.contact.id
                        if (onOpenProfile != null && peerId.isNotBlank()) {
                            onOpenProfile(peerId)
                        }
                    }
                }
        ) {
            Avatar(
                name = state.contact.displayName,
                avatarUrl = if (state.isSecretChat == true) null else state.contact.avatar,
                size = AvatarSize.SM,
                isOnline = state.isSecretChat != true && state.contact.isOnline
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                // maxLines + overflow 防止长昵称/状态把 action 按钮挤出屏幕
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.isSecretChat == true) {
                            stringResource(R.string.secret_chat_indicator)
                        } else {
                            state.contact.displayName
                        },
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = 17.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // TalkBack 标题导航：会话名是这一屏的标题。
                        modifier = Modifier.weight(1f, fill = false).semantics { heading() }
                    )
                    if (state.isSecretChat == true) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Outlined.VisibilityOff,
                            contentDescription = stringResource(R.string.secret_chat_indicator),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        if (state.sealedSenderReady) {
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                Icons.Outlined.Security,
                                contentDescription = stringResource(
                                    R.string.secret_chat_sealed_ready_ttl,
                                    (state.sealedSenderExpiresInSec / 3600L).coerceAtLeast(0L)
                                ),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    // 1.153：会话已静音 → 顶栏标题旁显示静音图标
                    if (state.chat?.notificationsMuted == true) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Outlined.NotificationsOff,
                            contentDescription = stringResource(R.string.chat_mute_notifications),
                            tint = LocalChatPalette.current.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                                            when (val status = headerStatus) {
                    is ChatHeaderStatus.Typing -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        InlineTypingDots()
                        Text(
                            text = participantNamesById[status.userId]
                                ?.takeIf { state.chatIsGroup }
                                ?.let { stringResource(R.string.chat_typing_user, it) }
                                ?: stringResource(R.string.chat_typing),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    ChatHeaderStatus.Online -> Text(stringResource(R.string.chat_online), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    is ChatHeaderStatus.LastSeen -> Text(stringResource(R.string.user_last_seen_prefix) + " " + android.text.format.DateUtils.getRelativeTimeSpanString(status.timestamp, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS), style = MaterialTheme.typography.labelMedium, color = LocalChatPalette.current.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ChatHeaderStatus.Offline -> Text(stringResource(R.string.chat_offline), style = MaterialTheme.typography.labelMedium, color = LocalChatPalette.current.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    is ChatHeaderStatus.Custom -> Text(
                        // 预设状态 wire 值为中文原文，展示前先本地化（自定义文本原样透传）
                        com.maodouchat.ui.component.localizedCustomStatusLabel(status.text),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    ChatHeaderStatus.None -> Unit
                }
            }
        }
    },
    navigationIcon = {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        }
    },
    actions = {
        if (state.chatIsGroup) {
            IconButton(onClick = { groupCall.showGroupCallTypeDialog = true }) {
                Icon(Icons.Outlined.Videocam, contentDescription = stringResource(R.string.chat_group_call), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            }
        } else {
            if (RuntimeFlags.isEnabled(context, RuntimeFlags.SAFETY_CODE)) {
                IconButton(onClick = { viewModel.showSafetyCodeDialog() }) { Icon(Icons.Outlined.Security, contentDescription = stringResource(R.string.chat_safety_code), tint = if (state.identityWarning == null) Primary else UnreadRed, modifier = Modifier.size(26.dp)) }
            }
            if (state.isSecretChat != true) {
                IconButton(onClick = { requestVoiceCallPermission(context, pickers.voiceCallPermission::launch, state.contact.id, state.contact.name, onVoiceCall) }) { Icon(Icons.Outlined.Call, contentDescription = stringResource(R.string.chat_voice_call), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp)) }
                IconButton(onClick = { requestVideoCallPermissions(context, pickers.videoCallPermission::launch, state.contact.id, state.contact.name, onVideoCall) }) { Icon(Icons.Outlined.Videocam, contentDescription = stringResource(R.string.chat_video_call), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) }
            }
        }
        Box {
            IconButton(onClick = { dialogs.showChatOverflow = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.chat_more), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            }
            DropdownMenu(expanded = dialogs.showChatOverflow, onDismissRequest = { dialogs.showChatOverflow = false }) {
                // 1.155：会话内置顶/取消置顶
                DropdownMenuItem(
                    text = { Text(stringResource(if ((state.chat?.pinnedAt ?: 0L) > 0L) R.string.chat_unpin else R.string.chat_pin)) },
                    onClick = { dialogs.showChatOverflow = false; viewModel.toggleChatPinned() }
                )
                // 1.156：会话内标记未读/已读
                DropdownMenuItem(
                    text = { Text(stringResource(if (state.chat?.markedUnread == true) R.string.chat_mark_read else R.string.chat_mark_unread)) },
                    onClick = { dialogs.showChatOverflow = false; viewModel.toggleChatMarkedUnread() }
                )
                if (!state.chatIsGroup) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_contact_actions)) },
                        onClick = { dialogs.showChatOverflow = false; contactSheets.showContactActions = true }
                    )
                    if (state.isSecretChat != true) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.disappear_menu)) },
                            onClick = { dialogs.showChatOverflow = false; schedule.showDisappearDialog = true }
                        )
                    }
                }
                // 8.46：会话免打扰时段（本地 per-chat 静音窗，单聊/群聊均可用）
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_quiet_hours_menu)) },
                    onClick = { dialogs.showChatOverflow = false; schedule.showQuietHoursDialog = true }
                )
                // 1.02：临时静音至（1/8/24 小时，本地）
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_silent_until_menu)) },
                    onClick = { dialogs.showChatOverflow = false; schedule.showSilentUntilDialog = true }
                )
                // 8.48：稍后提醒列表（查看/取消）
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.message_reminder_list_menu)) },
                    onClick = {
                        dialogs.showChatOverflow = false
                        // G22：打开瞬间同步加载列表，首帧即有数据（原 remember 初始化的语义）。
                        val chatId = state.chat?.id
                        if (chatId.isNullOrBlank()) schedule.showReminderList = true
                        else schedule.openReminderList(viewModel.listRemindersForChat(chatId))
                    }
                )
                // 1.29：通话记录（本地 CallLogStore 历史）
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.call_log_title)) },
                    onClick = {
                        dialogs.showChatOverflow = false
                        onOpenCallHistory?.invoke()
                    }
                )
                if (RuntimeFlags.isEnabled(context, RuntimeFlags.NUDGE) && !state.chatIsGroup && state.chat?.isChannel != true && state.isSecretChat != true) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_nudge)) },
                        onClick = { dialogs.showChatOverflow = false; viewModel.sendNudge() }
                    )
                }
                // Local device PIN gate — works for 1:1 and groups (Room chatId key).
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (state.isChatLocked == true) R.string.chat_lock_menu_disable
                                else R.string.chat_lock_menu_enable
                            )
                        )
                    },
                    onClick = {
                        dialogs.showChatOverflow = false
                        if (state.isChatLocked == true) {
                            chatLock.disableLockPinDraft = ""
                            chatLock.showDisableChatLock = true
                        } else {
                            chatLock.setLockPinDraft = ""
                            chatLock.setLockPinConfirm = ""
                            chatLock.setLockError = null
                            chatLock.showSetChatLock = true
                        }
                    }
                )
                // 钉钉式：从普通单聊发起一场独立密聊；群没有密聊。
                if (
                    state.isSecretChat != true &&
                    com.maodouchat.security.SecretChatPolicy.canStartFromDirect(
                        isGroup = state.chatIsGroup,
                        chatType = state.chat?.chatType
                    )
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.secret_chat_menu_start)) },
                        onClick = {
                            dialogs.showChatOverflow = false
                            flows.showSecretChatConfirm = true
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_starred_messages)) },
                    onClick = { dialogs.showChatOverflow = false; state.chat?.id?.let(onOpenStarredMessages) }
                )
                if (!state.chatIsGroup) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.media_center_title)) },
                        onClick = { dialogs.showChatOverflow = false; state.chat?.id?.let(onOpenMediaCenter) }
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_search_action)) },
                    onClick = { dialogs.showChatOverflow = false; search.showSearchBar = !search.showSearchBar }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_jump_date)) },
                    onClick = { dialogs.showChatOverflow = false; drafts.showDateJumpDialog = true }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_clear_local_history), color = LocalChatPalette.current.unreadRed) },
                    onClick = {
                        dialogs.showChatOverflow = false
                        dialogs.showClearHistoryConfirm = true
                    }
                )
            }
        }
    },
)
}
