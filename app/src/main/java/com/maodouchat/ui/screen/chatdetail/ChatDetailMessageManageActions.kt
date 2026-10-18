package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.theme.LocalChatPalette

// 长按弹层的消息管理按钮组：转发/星标/置顶/提醒/重试/回执/编辑/撤回/举报/删除。
@Composable
internal fun ChatDetailMessageManageActions(
    message: Message,
    state: ChatDetailUiState,
    viewModel: ChatDetailViewModel,
    isOwn: Boolean,
    withinEditWindow: Boolean,
    canForward: Boolean,
    onMessagesToForward: (List<Message>) -> Unit,
    onMessageToRemind: (Message?) -> Unit,
    onMessageForReadReceipts: (Message?) -> Unit,
    onEditDraft: (String) -> Unit,
    onMessageToEdit: (Message?) -> Unit,
    onMessageToRevoke: (Message?) -> Unit,
    onMessageToReport: (Message?) -> Unit,
    onMessageToDelete: (Message?) -> Unit,
    onMessageToActions: (Message?) -> Unit,
) {
    if (canForward) {
        TextButton(
            onClick = {
                onMessagesToForward(listOf(message))
                viewModel.loadForwardTargets()
                onMessageToActions(null)
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.chat_forward), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
    }
    TextButton(
        onClick = {
            viewModel.toggleStarMessage(message.id)
            onMessageToActions(null)
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(if (message.starred) R.string.chat_unstar else R.string.chat_star), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
    val canPinMessage = MessagePinPolicy.canPin(
        isGroup = state.chatIsGroup,
        myRole = state.myMemberRole,
        messageType = message.type
    )
    if (canPinMessage) {
        val isPinned = state.pinnedMessages.any { it.messageId == message.id }
        TextButton(
            enabled = !state.isTogglingPin,
            onClick = {
                viewModel.togglePinMessage(message.id)
                onMessageToActions(null)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(if (isPinned) R.string.chat_message_unpin else R.string.chat_message_pin),
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
    // 8.41：消息「稍后提醒」
    TextButton(
        onClick = {
            onMessageToRemind(message)
            onMessageToActions(null)
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.message_reminder_menu), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
    if (isOwn && message.status == com.maodouchat.data.model.MessageStatus.FAILED) {
        TextButton(
            onClick = {
                viewModel.retrySendMessage(message.id)
                onMessageToActions(null)
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.chat_retry), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
    }
    if (ReadReceiptPolicy.canViewReceipts(
            viewerId = state.currentUserId,
            senderId = message.senderId,
            isGroup = state.chatIsGroup,
            viewerRole = state.myMemberRole,
        )
    ) {
        TextButton(
            onClick = {
                onMessageForReadReceipts(message)
                viewModel.loadReadReceipts(message.id)
                onMessageToActions(null)
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.chat_read_details), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
    }
    if (isOwn && withinEditWindow && (message.type == MessageType.TEXT || message.type == MessageType.MARKDOWN)) {
        TextButton(
            onClick = {
                onEditDraft(message.parsedContent())
                onMessageToEdit(message)
                onMessageToActions(null)
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.chat_edit), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
    }
    if (isOwn && withinEditWindow && message.type != MessageType.REVOKED) {
        // 1.152：撤回倒计时（5 分钟窗口，向上取整分钟）
        val revokeRemainingMin = ((300_000L - (System.currentTimeMillis() - message.timestamp)) / 60_000L).toInt() + 1
        TextButton(
            onClick = {
                onMessageToRevoke(message)
                onMessageToActions(null)
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.chat_revoke_with_limit, revokeRemainingMin), modifier = Modifier.fillMaxWidth(), color = LocalChatPalette.current.unreadRed) }
    }
    if (!isOwn && message.type !in setOf(MessageType.SK_DIST, MessageType.SYSTEM, MessageType.REVOKED)) {
        TextButton(
            onClick = {
                onMessageToReport(message)
                onMessageToActions(null)
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.chat_report), modifier = Modifier.fillMaxWidth(), color = LocalChatPalette.current.unreadRed) }
    }
    TextButton(
        onClick = {
            onMessageToDelete(message)
            onMessageToActions(null)
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.chat_delete), modifier = Modifier.fillMaxWidth(), color = LocalChatPalette.current.unreadRed) }
}
