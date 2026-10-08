package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import com.maodouchat.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.bannerEnter
import com.maodouchat.ui.theme.composerBarEnter
import com.maodouchat.util.RuntimeFlags

// 聊天详情页顶部横幅栈组装点（置顶/群公告/未读摘要横幅已拆出同包簇文件，纯搬移）。

// 横幅栈组装：顺序、出现条件与开关（G344 从 ChatDetailRoute.kt 抽出，纯搬移）。
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调内读取，非组合作用域；lint 无法区分（同 ChatDetailRoute）
@Composable
internal fun ChatDetailBannerStack(
    state: ChatDetailUiState,
    viewModel: ChatDetailViewModel,
    flows: ChatDetailConversationFlowState,
    schedule: ChatDetailScheduleState,
    participantNamesById: Map<String, String>,
    onOpenProfile: ((userId: String) -> Unit)?,
    chatAiSurfacesVisible: Boolean,
) {
    val context = LocalContext.current
if (state.pinnedMessages.isNotEmpty()) {
    PinnedMessagesBanner(
        pins = state.pinnedMessages,
        messages = state.messages,
        canManage = MessagePinPolicy.canPin(
            isGroup = state.chatIsGroup,
            myRole = state.myMemberRole,
            messageType = MessageType.TEXT
        ),
        onOpen = { viewModel.jumpToPinnedMessage(it) },
        onUnpin = { viewModel.togglePinMessage(it) },
        // 1.49：置顶者显示名
        resolvePinnerName = { uid -> participantNamesById[uid] ?: uid },
        // 1.53：点击置顶者打开其资料
        onPinnerClick = { uid ->
            if (onOpenProfile != null) {
                onOpenProfile(uid)
            } else {
                Toast.makeText(context, context.getString(R.string.chat_contact_card_tap_hint), Toast.LENGTH_SHORT).show()
            }
        }
    )
}
// 8.57：群公告会话顶部横幅（可折叠；点开看全文）
if (flows.showAnnouncementBanner && state.chatIsGroup) {
    val announcement = state.chat?.groupAnnouncement?.trim()
    if (!announcement.isNullOrBlank()) {
        GroupAnnouncementBanner(
            announcement = announcement,
            onOpen = { flows.showAnnouncementDialog = true },
            onDismiss = { flows.showAnnouncementBanner = false }
        )
    }
}
if (state.scheduledMessages.isNotEmpty()) {
    ScheduledMessagesBanner(
        items = state.scheduledMessages,
        onCancel = { viewModel.cancelScheduledMessage(it) },
        onReschedule = { id -> schedule.beginReschedule(id, state.scheduledMessages.firstOrNull { it.id == id }?.text.orEmpty()) },
        onViewAll = { schedule.showScheduledList = true }
    )
}
if (!state.chatIsGroup && state.disappearingMessageSeconds > 0) {
    DisappearingMessagesBanner(
        seconds = state.disappearingMessageSeconds,
        onChange = {
            if (state.isSecretChat != true) schedule.showDisappearDialog = true
        }
    )
}
AnimatedVisibility(
    visible = state.isSecretChat == true && com.maodouchat.util.SecretSessionNoticePrefs.isEnabled(context),
    enter = LocalMotionSettings.current.bannerEnter(),
    exit = fadeOut()
) {
    SecretChatBanner(
        sealedSenderReady = state.sealedSenderReady,
        sealedSenderExpiresInSec = state.sealedSenderExpiresInSec,
    )
}
if (state.activeLiveLocationSessionId != null) {
    LiveLocationSharingBanner(
        untilMs = state.activeLiveLocationUntil,
        onStop = { viewModel.stopLiveLocationSharing() }
    )
}
state.groupEncryptionWarning?.let { warning ->
    GroupEncryptionWarningBanner(warning = warning)
}
state.identityWarning?.let { warning ->
    SecurityWarningBanner(
        warning = warning,
        sticky = com.maodouchat.crypto.SafetyCodePolicy.isStickyIdentityWarning(state.trustState),
        onClick = {
            if (RuntimeFlags.isEnabled(context, RuntimeFlags.SAFETY_CODE)) viewModel.showSafetyCodeDialog()
        }
    )
}
AnimatedVisibility(
    visible = chatAiSurfacesVisible && (state.isUnreadSummaryLoading || state.unreadAiSummary != null),
    enter = expandVertically() + LocalMotionSettings.current.composerBarEnter(),
    exit = shrinkVertically() + fadeOut()
) {
    UnreadSummaryBanner(
        summary = state.unreadAiSummary,
        messageCount = state.unreadAiSummaryCount,
        isLoading = state.isUnreadSummaryLoading,
        onOpen = { viewModel.openUnreadAiSummary() },
        onDismiss = { viewModel.clearUnreadAiSummary() },
        // 1.194：复制未读摘要
        onCopy = {
            val textToCopy = state.unreadAiSummary
            if (!textToCopy.isNullOrBlank()) {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.chat_unread_summary), textToCopy))
                Toast.makeText(context, context.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
            }
        }
    )
}
}
