package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.util.RuntimeFlags

/**
 * 长按消息的「操作」底部弹层（G328c 从 `ChatDetailRoute.kt` 的
 * `messageToActions?.let { ... }` 整块搬出，**纯搬移不改判断**）。
 *
 * 为什么值得先搬它：它是该文件里最大的自包含块（450 行），而 `ChatDetailRoute` 是
 * 3432 行的单体 composable——热点棘轮真正想压的就是这个。搬它的代价是 14 个
 * 「要写回 Route 的状态」变成回调（[onMessageToActions] 等），换来的是一处能独立读、
 * 独立审的 UI 单元。块内的局部状态（`cursor`/`busy`/`copied` 这类瞬时态）随块留在这里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatDetailMessageActionsSheet(
    /** 由 Route 传入的文案与可见性开关（它们是 Route 的 `stringResource` 结果与 AI 入口判定）。 */
    onMessageToActionsDismiss: () -> Unit,
    chatCopiedMsg: String,
    chatTranslationCopiedMsg: String,
    chatTranscriptCopiedMsg: String,
    chatClipboardMessageLabel: String,
    chatClipboardTranslationLabel: String,
    chatClipboardTranscriptLabel: String,
    chatAiSurfacesVisible: Boolean,
    message: Message,
    state: ChatDetailUiState,
    viewModel: ChatDetailViewModel,
    context: android.content.Context,
    /** 发送者显示名；由 Route 侧的 `resolveSenderName` 算好传入（它依赖 Route 的参与者映射）。 */
    senderName: String?,
    onMessageToActions: (Message?) -> Unit,
    onMessageToDelete: (Message?) -> Unit,
    onMessageToRevoke: (Message?) -> Unit,
    onMessagesToForward: (List<Message>) -> Unit,
    onMessageToEdit: (Message?) -> Unit,
    onMessageToRemind: (Message?) -> Unit,
    onMessageToTranslate: (Message?) -> Unit,
    onMessageToReport: (Message?) -> Unit,
    onMessageForReadReceipts: (Message?) -> Unit,
    onMessageToAnalyzeImage: (Message?) -> Unit,
    onMessageToAnalyzeFile: (Message?) -> Unit,
    onEditDraft: (String) -> Unit,
    onReplyTarget: (Message?) -> Unit,
    onSelectedMessageIds: (Set<String>) -> Unit,
) {
        val isOwn = message.senderId == state.currentUserId
        val withinEditWindow = System.currentTimeMillis() - message.timestamp < 300_000
        val canForward = isMessageForwardable(message.type, isSecretChat = state.isSecretChat == true, forwardBlockEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_FORWARD_BLOCK))
        val meta = message.parsedMeta()
        val voiceTranscript = meta.voiceTranscript
        val displayedTranslation = meta.displayedTranslation()
        ModalBottomSheet(onDismissRequest = onMessageToActionsDismiss) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                    Text(
                        stringResource(R.string.chat_message_actions),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    ReactionPickerRow(
                        onPick = { emoji ->
                            viewModel.setMessageReaction(message.id, emoji)
                            onMessageToActions(null)
                        }
                    )
                    TextButton(
                        onClick = {
                            onSelectedMessageIds(setOf(message.id))
                            onMessageToActions(null)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.chat_select_message), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                    if (isMessageReplyable(message.type)) {
                        TextButton(
                            onClick = {
                                onReplyTarget(message)
                                onMessageToActions(null)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.message_reply), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                    }
                    ChatDetailMessageCopyActions(
                        message = message,
                        state = state,
                        context = context,
                        senderName = senderName,
                        voiceTranscript = voiceTranscript,
                        displayedTranslation = displayedTranslation,
                        chatCopiedMsg = chatCopiedMsg,
                        chatTranslationCopiedMsg = chatTranslationCopiedMsg,
                        chatTranscriptCopiedMsg = chatTranscriptCopiedMsg,
                        chatClipboardMessageLabel = chatClipboardMessageLabel,
                        chatClipboardTranslationLabel = chatClipboardTranslationLabel,
                        chatClipboardTranscriptLabel = chatClipboardTranscriptLabel,
                        onMessageToActions = onMessageToActions,
                    )
                    ChatDetailMessageCopyActions(
                        message = message,
                        state = state,
                        context = context,
                        senderName = senderName,
                        voiceTranscript = voiceTranscript,
                        displayedTranslation = displayedTranslation,
                        chatCopiedMsg = chatCopiedMsg,
                        chatTranslationCopiedMsg = chatTranslationCopiedMsg,
                        chatTranscriptCopiedMsg = chatTranscriptCopiedMsg,
                        chatClipboardMessageLabel = chatClipboardMessageLabel,
                        chatClipboardTranslationLabel = chatClipboardTranslationLabel,
                        chatClipboardTranscriptLabel = chatClipboardTranscriptLabel,
                        onMessageToActions = onMessageToActions,
                    )
                    ChatDetailMessageAiActions(
                        message = message,
                        state = state,
                        viewModel = viewModel,
                        context = context,
                        voiceTranscript = voiceTranscript,
                        chatAiSurfacesVisible = chatAiSurfacesVisible,
                        onMessageToTranslate = onMessageToTranslate,
                        onMessageToAnalyzeImage = onMessageToAnalyzeImage,
                        onMessageToAnalyzeFile = onMessageToAnalyzeFile,
                        onMessageToActions = onMessageToActions,
                    )
                    ChatDetailMessageManageActions(
                        message = message,
                        state = state,
                        viewModel = viewModel,
                        isOwn = isOwn,
                        withinEditWindow = withinEditWindow,
                        canForward = canForward,
                        onMessagesToForward = onMessagesToForward,
                        onMessageToRemind = onMessageToRemind,
                        onMessageForReadReceipts = onMessageForReadReceipts,
                        onEditDraft = onEditDraft,
                        onMessageToEdit = onMessageToEdit,
                        onMessageToRevoke = onMessageToRevoke,
                        onMessageToReport = onMessageToReport,
                        onMessageToDelete = onMessageToDelete,
                        onMessageToActions = onMessageToActions,
                    )
                    TextButton(onClick = onMessageToActionsDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.common_cancel), modifier = Modifier.fillMaxWidth(), color = LocalChatPalette.current.textSecondary)
                    }
                    Spacer(modifier = Modifier.navigationBarsPadding())
            }
        }
}
