package com.maodouchat.ui.screen.chatdetail

import android.widget.Toast
import androidx.compose.runtime.Composable
import com.maodouchat.data.model.Message
import com.maodouchat.util.RuntimeFlags

/** 下半区弹窗簇：撤回/删除确认、复制操作弹窗、编辑与重发弹窗，原在 ChatDetailLowerDialogHost 内联，纯搬移。 */
@Composable
internal fun ChatDetailMutationConfirmDialogs(
    messageActions: ChatDetailMessageActionState,
    state: ChatDetailUiState,
    viewModel: ChatDetailViewModel,
    drafts: ChatDetailDraftState,
    context: android.content.Context,
    onStartParticleEffect: (Message, ParticleAction) -> Unit,
    chatClipboardMessageLabel: String,
    chatCopiedMsg: String,
) {
    // 长按撤回消息确认弹窗（带粒子动效）
    RevokeMessageConfirmDialog(
        visible = messageActions.messageToRevoke != null,
        sentAtMillis = messageActions.messageToRevoke?.timestamp ?: 0L,
        onRevoke = {
            messageActions.messageToRevoke?.let { onStartParticleEffect(it, ParticleAction.REVOKE) }
            messageActions.messageToRevoke = null
        },
        onDismiss = { messageActions.messageToRevoke = null },
    )

    // 长按删除消息确认弹窗
    // messageActions.messageToDelete 是 by remember 委托属性，不能智能转换——先取局部值（G159b）
    val pendingDelete = messageActions.messageToDelete
    DeleteMessageConfirmDialog(
        visible = pendingDelete != null,
        isOwn = pendingDelete?.senderId == state.currentUserId,
        isForwardable = pendingDelete != null && isMessageForwardable(
            pendingDelete.type,
            isSecretChat = state.isSecretChat == true,
            forwardBlockEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_FORWARD_BLOCK)
        ),
        onDelete = {
            pendingDelete?.let { onStartParticleEffect(it, ParticleAction.DELETE) }
            messageActions.messageToDelete = null
        },
        onForward = {
            pendingDelete?.let {
                messageActions.messagesToForward = listOf(it)
                viewModel.loadForwardTargets()
            }
            messageActions.messageToDelete = null
        },
        onDismiss = { messageActions.messageToDelete = null },
    )

    // G80：消息操作弹窗（58 行）抽到 ChatDetailMessageActionsDialog.kt，纯搬移不改判断。
    messageActions.messageToCopy?.let { msg ->
        ChatDetailMessageActionsDialog(
            msg = msg,
            currentUserId = state.currentUserId,
            isSecretChat = state.isSecretChat == true,
            onDismiss = { messageActions.messageToCopy = null },
            onCopy = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chatClipboardMessageLabel, msg.parsedContent()))
                Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
            },
            onForward = {
                messageActions.messagesToForward = listOf(msg)
                viewModel.loadForwardTargets()
            },
            onEdit = {
                drafts.editDraft = msg.parsedContent()
                messageActions.messageToEdit = msg
            },
            onRevoke = { messageActions.messageToRevoke = msg },
            onDelete = { onStartParticleEffect(msg, ParticleAction.DELETE) },
        )
    }

    EditMessageDialog(
        visible = messageActions.messageToEdit != null,
        draft = drafts.editDraft,
        onDraftChange = { drafts.editDraft = it.take(2000) },
        onSave = {
            messageActions.messageToEdit?.let { viewModel.editTextMessage(it.id, drafts.editDraft) }
            messageActions.messageToEdit = null
        },
        onDismiss = { messageActions.messageToEdit = null },
    )

    // 重发失败消息弹窗
    RetryMessageDialog(
        visible = messageActions.messageToRetry != null,
        onRetry = {
            messageActions.messageToRetry?.let { viewModel.retrySendMessage(it.id) }
            messageActions.messageToRetry = null
        },
        onDelete = {
            messageActions.messageToRetry?.let { onStartParticleEffect(it, ParticleAction.DELETE) }
            messageActions.messageToRetry = null
        },
        onDismiss = { messageActions.messageToRetry = null },
    )
}
