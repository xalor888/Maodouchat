package com.maodouchat.ui.screen.chatdetail

import android.annotation.SuppressLint
import com.maodouchat.ui.component.ParticleDeleteEffect
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.data.model.Message

/**
 * G352：会话详情「下半区」弹窗簇从 `ChatDetailRoute.kt` 抽出（纯搬移不改判断）——
 * 群公告全文、批量删除确认、消息长按操作面板、AI 图像/文件分析、稍后提醒、图片/视频发送预览、
 * 翻译/举报/复制/编辑/重试/撤回/删除确认、跳转日期、已读回执、转发选择、删除粒子动效。
 *
 * 依赖全经参数注入；组合期内不新增状态所有权，开关读写语义逐字一致。
 */
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串在回调内读取，非组合作用域；lint 无法区分（同 ChatDetailRoute）
@Composable
internal fun ChatDetailLowerDialogHost(
    state: ChatDetailUiState,
    viewModel: ChatDetailViewModel,
    flows: ChatDetailConversationFlowState,
    dialogs: ChatDetailDialogState,
    drafts: ChatDetailDraftState,
    messageActions: ChatDetailMessageActionState,
    sendPending: ChatDetailSendPendingState,
    particles: ChatDetailParticleState,
    targets: ChatDetailMessageTargetState,
    media: ChatDetailFullscreenMediaState,
    pickers: ChatDetailPickers,
    listScrollScope: kotlinx.coroutines.CoroutineScope,
    selectedMessages: List<Message>,
    resolveSenderName: (Message) -> String?,
    chatAiSurfacesVisible: Boolean,
    chatCopiedMsg: String,
    chatTranslationCopiedMsg: String,
    chatTranscriptCopiedMsg: String,
    onStartParticleEffect: (Message, ParticleAction) -> Unit,
    chatClipboardMessageLabel: String,
    chatClipboardTranslationLabel: String,
    chatClipboardTranscriptLabel: String,
    onOpenProfile: ((String) -> Unit)?,
) {
    val context = LocalContext.current
    val secretActive = state.isSecretChat == true
    // 8.57：群公告全文弹窗
    val groupAnnouncementText = state.chat?.groupAnnouncement?.trim().orEmpty()
    GroupAnnouncementDialog(
        visible = flows.showAnnouncementDialog, announcement = groupAnnouncementText,
        onCopy = {
            if (groupAnnouncementText.isNotBlank()) {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.group_announcement_copy), groupAnnouncementText))
                Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
            }
            flows.showAnnouncementDialog = false
        },
        onDismiss = { flows.showAnnouncementDialog = false },
    )

    // G86：批量删除确认对话框（50 行）抽到 ChatDetailBatchDeleteDialog.kt，纯搬移不改判断。
    if (dialogs.showBatchDeleteConfirm) {
        ChatDetailBatchDeleteDialog(
            selectedMessages = selectedMessages,
            currentUserId = state.currentUserId,
            onDelete = { ids -> viewModel.deleteMessagesBatch(ids) },
            onDismiss = { dialogs.showBatchDeleteConfirm = false },
            onSelectionCleared = { drafts.selectedMessageIds = emptySet() },
        )
    }

    messageActions.messageToActions?.let { message ->
        ChatDetailMessageActionsSheet(
            onMessageToActionsDismiss = { messageActions.messageToActions = null },
            chatCopiedMsg = chatCopiedMsg,
            chatTranslationCopiedMsg = chatTranslationCopiedMsg,
            chatTranscriptCopiedMsg = chatTranscriptCopiedMsg,
            chatClipboardMessageLabel = chatClipboardMessageLabel,
            chatClipboardTranslationLabel = chatClipboardTranslationLabel,
            chatClipboardTranscriptLabel = chatClipboardTranscriptLabel,
            chatAiSurfacesVisible = chatAiSurfacesVisible,
            message = message,
            state = state,
            viewModel = viewModel,
            context = context,
            senderName = resolveSenderName(message),
            onMessageToActions = { messageActions.messageToActions = it },
            onMessageToDelete = { messageActions.messageToDelete = it },
            onMessageToRevoke = { messageActions.messageToRevoke = it },
            onMessagesToForward = { messageActions.messagesToForward = it },
            onMessageToEdit = { messageActions.messageToEdit = it },
            onMessageToRemind = { messageActions.messageToRemind = it },
            onMessageToTranslate = { messageActions.messageToTranslate = it },
            onMessageToReport = { messageActions.messageToReport = it },
            onMessageForReadReceipts = { messageActions.messageForReadReceipts = it },
            onMessageToAnalyzeImage = { messageActions.messageToAnalyzeImage = it },
            onMessageToAnalyzeFile = { messageActions.messageToAnalyzeFile = it },
            onEditDraft = { drafts.editDraft = it },
            onReplyTarget = { targets.replyTarget = it },
            onSelectedMessageIds = { drafts.selectedMessageIds = it },
        )
    }

    messageActions.messageToAnalyzeImage?.let { message ->
        AiImageAnalysisModeDialog(
            onSelect = { mode ->
                messageActions.messageToAnalyzeImage = null
                viewModel.requestAiImageAnalysis(message.id, mode)
            },
            onDismiss = { messageActions.messageToAnalyzeImage = null }
        )
    }

    // 8.41：消息「稍后提醒」时间选择
    messageActions.messageToRemind?.let { message ->
        MessageReminderTimeDialog(
            onPick = { delayMs ->
                messageActions.messageToRemind = null
                viewModel.scheduleMessageReminder(message, System.currentTimeMillis() + delayMs)
            },
            onDismiss = { messageActions.messageToRemind = null }
        )
    }

    // G79：图片发送前预览（47 行）抽到 ChatDetailSendPreviews.kt，纯搬移不改判断。
    sendPending.imageConfirm?.let { pending ->
        ImageSendPreviewDialog(
            pending = pending,
            onDismiss = { sendPending.imageConfirm = null },
            onRechoose = { viewOnce, spoiler ->
                sendPending.viewOnce = viewOnce
                sendPending.spoiler = spoiler
                listScrollScope.launch {
                    kotlinx.coroutines.yield()
                    runCatching { pickers.image.launch("image/*") }
                        .onFailure {
                            Toast.makeText(
                                context,
                                context.getString(R.string.chat_image_picker_unavailable),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                }
            },
            onSendImage = { p -> viewModel.sendImage(p.uri) },
            onSendSpoilerImage = { p -> viewModel.sendSpoilerImage(p.uri) },
            onSendViewOnceImage = { p -> viewModel.sendViewOnceImage(p.uri) },
        )
    }

    // G79：视频发送前预览（37 行）抽到 ChatDetailSendPreviews.kt，纯搬移不改判断。
    sendPending.videoConfirm?.let { pending ->
        VideoSendPreviewDialog(
            pending = pending,
            onDismiss = { sendPending.videoConfirm = null },
            onSendVideo = { p -> viewModel.sendVideo(p.uri) },
            onSendSpoilerVideo = { p -> viewModel.sendSpoilerVideo(p.uri) },
            onSendViewOnceVideo = { p -> viewModel.sendViewOnceVideo(p.uri) },
        )
    }

    messageActions.messageToAnalyzeFile?.let { message ->
        AiFileAnalysisModeDialog(
            fileName = message.parsedMeta().fileName.orEmpty(),
            onSelect = { mode ->
                messageActions.messageToAnalyzeFile = null
                if (mode == AiFileAnalysisMode.SUMMARIZE) {
                    viewModel.requestAiFileAnalysis(message.id, mode)
                } else {
                    drafts.fileQuestionDraft = ""
                    messageActions.fileQuestionMessage = message
                }
            },
            onDismiss = { messageActions.messageToAnalyzeFile = null }
        )
    }

    messageActions.fileQuestionMessage?.let { message ->
        AiFileQuestionDialog(
            fileName = message.parsedMeta().fileName.orEmpty(),
            question = drafts.fileQuestionDraft,
            onQuestionChange = { drafts.fileQuestionDraft = it.take(500) },
            onSubmit = {
                viewModel.requestAiFileAnalysis(message.id, AiFileAnalysisMode.QUESTION, drafts.fileQuestionDraft)
                messageActions.fileQuestionMessage = null
                drafts.fileQuestionDraft = ""
            },
            onDismiss = {
                messageActions.fileQuestionMessage = null
                drafts.fileQuestionDraft = ""
            }
        )
    }

    messageActions.messageToTranslate?.let { message ->
        TranslationLanguageDialog(
            translatedLanguages = message.parsedMeta().translations.keys,
            onDismiss = { messageActions.messageToTranslate = null },
            onSelect = { language ->
                viewModel.requestMessageTranslation(message.id, language)
                messageActions.messageToTranslate = null
            }
        )
    }

    messageActions.messageToReport?.let { msg ->
        ReportDialog(
            title = stringResource(R.string.chat_report_message),
            onDismiss = { messageActions.messageToReport = null },
            onReport = { reason, description ->
                viewModel.reportMessage(msg.id, reason, description)
                messageActions.messageToReport = null
            }
        )
    }

    if (drafts.showDateJumpDialog) {
        DateJumpDialog(
            onDismiss = { drafts.showDateJumpDialog = false },
            onJump = { dayStartMillis ->
                drafts.showDateJumpDialog = false
                viewModel.jumpToDate(dayStartMillis)
            }
        )
    }

    // G332：已读回执面板 176 行搬进 `ChatDetailReadReceiptsSheet.kt`。
    messageActions.messageForReadReceipts?.let { receiptMessage ->
        ChatDetailReadReceiptsSheet(
            messageId = receiptMessage.id,
            receipts = state.readReceipts,
            isLoading = state.isLoadingReadReceipts,
            onOpenProfile = onOpenProfile,
            onDismiss = {
                messageActions.messageForReadReceipts = null
                viewModel.clearReadReceipts()
            },
        )
    }

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

    // 转发目标选择弹窗
    // G75：转发目标选择弹窗（235 行）抽到 ChatDetailForwardPicker.kt，纯搬移不改判断。
    if (messageActions.messagesToForward.isNotEmpty()) {
        ChatDetailForwardPicker(
            messages = messageActions.messagesToForward,
            forwardTargets = state.forwardTargets,
            currentUserId = state.currentUserId,
            onCancel = { messageActions.messagesToForward = emptyList() },
            onForwardBatch = { msgs, targets, note ->
                viewModel.forwardMessagesBatch(msgs, targets, note)
            },
            onSendTextToChat = { chatId, body -> viewModel.sendTextToChat(chatId, body) },
            onSelectionCleared = { drafts.selectedMessageIds = emptySet() },
            onLoadForwardTargets = { viewModel.loadForwardTargets() },
            secretSource = secretActive,
        )
    }

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

    // G76：全屏图片/视频查看器（207 行）抽到 ChatDetailFullscreenMedia.kt，纯搬移不改判断。
    media.fullScreenImage?.let { msg ->
        FullscreenImageDialog(
            msg = msg,
            isSecretChat = state.isSecretChat == true,
            onDismiss = { media.fullScreenImage = null },
        )
    }

    media.fullScreenVideo?.let { msg ->
        FullscreenVideoDialog(
            msg = msg,
            isSecretChat = state.isSecretChat == true,
            onDismiss = { media.fullScreenVideo = null },
        )
    }

    // 粒子删除动效：消息泡碎裂为彩色粒子消散（Telegram 风格）
    if (particles.animatingMessageId != null && particles.states.isNotEmpty()) {
        ParticleDeleteEffect(
            particleStates = particles.states,
            onFinished = {
                val targetId = particles.animatingMessageId
                when (particles.action) {
                    ParticleAction.DELETE -> targetId?.let { viewModel.deleteMessage(it) }
                    ParticleAction.REVOKE -> targetId?.let { viewModel.revokeMessage(it) }
                    null -> Unit
                }
                particles.clear()
            }
        )
    }
}
