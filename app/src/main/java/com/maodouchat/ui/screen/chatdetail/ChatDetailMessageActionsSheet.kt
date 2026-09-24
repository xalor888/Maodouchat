package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.util.RuntimeFlags
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme

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
                    if (isMessageCopyable(message.type, isSecretChat = state.isSecretChat == true, copyBlockEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_COPY_BLOCK))) {
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                                    chatClipboardMessageLabel,
                                    com.maodouchat.data.repository.ChatListPreviewPolicy.redactedIfWire(
                                        message.parsedContent(),
                                        context.getString(R.string.chat_decrypt_failed)
                                    )
                                ))
                                Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
                                onMessageToActions(null)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.chat_copy), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                        // 1.73：复制带发送者（引用/记录用，格式「发送者: 内容」）
                        TextButton(
                            onClick = {
                                val sender = senderName ?: ""
                                val copied = com.maodouchat.data.repository.ChatListPreviewPolicy.redactedIfWire(
                                    message.parsedContent(),
                                    context.getString(R.string.chat_decrypt_failed)
                                )
                                val label = if (sender.isBlank()) copied else "$sender: $copied"
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chatClipboardMessageLabel, label))
                                Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
                                onMessageToActions(null)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.chat_copy_with_sender), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                        // 1.160：复制带发送者与时间（格式「MM-dd HH:mm 发送者: 内容」）
                        TextButton(
                            onClick = {
                                val sender = senderName ?: ""
                                val time = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(message.timestamp))
                                val body = com.maodouchat.data.repository.ChatListPreviewPolicy.redactedIfWire(
                                    message.parsedContent(),
                                    context.getString(R.string.chat_decrypt_failed)
                                )
                                val label = when {
                                    body.isBlank() -> time
                                    sender.isBlank() -> "$time $body"
                                    else -> "$time $sender: $body"
                                }
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chatClipboardMessageLabel, label))
                                Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
                                onMessageToActions(null)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.chat_copy_with_sender_time), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                        // 1.169：分享消息到系统其他应用（密聊与复制同款门控，防外泄）
                        if (isMessageCopyable(message.type, isSecretChat = state.isSecretChat == true, copyBlockEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_COPY_BLOCK))) {
                        val chatShareMessageTitle = stringResource(R.string.chat_share_message_title)
                        val previewImageLabel = stringResource(R.string.message_preview_image)
                        val previewGifLabel = stringResource(R.string.message_preview_gif)
                        val previewStickerLabel = stringResource(R.string.message_preview_sticker)
                        val previewLocationLabel = stringResource(R.string.message_preview_location)
                        val previewFileLabel = stringResource(R.string.message_preview_file)
                        TextButton(
                            onClick = {
                                // 1.197：图片/GIF 且本地可读时直接分享原图；1.198：扩展到视频/文件
                                val contentUri = message.parsedContent()
                                val fileMime = when (message.type) {
                                    MessageType.IMAGE, MessageType.GIF -> "image/*"
                                    MessageType.VIDEO -> "video/*"
                                    MessageType.FILE -> "application/octet-stream"
                                    else -> null
                                }
                                val shareFile = fileMime != null &&
                                    runCatching { com.maodouchat.util.MediaCache.isReadableLocalUri(context, contentUri) }.getOrDefault(false)
                                if (shareFile) {
                                    val fileIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = fileMime
                                        putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.parse(contentUri))
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    val fileChooser = android.content.Intent.createChooser(fileIntent, chatShareMessageTitle)
                                    if (context !is android.app.Activity) fileChooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    runCatching { context.startActivity(fileChooser) }
                                } else {
                                    val shareText = com.maodouchat.messaging.ChatMarkdown.toPlainText(contentUri).ifBlank {
                                        when (message.type) {
                                            MessageType.IMAGE -> previewImageLabel
                                            MessageType.GIF -> previewGifLabel
                                            MessageType.STICKER -> previewStickerLabel
                                            MessageType.LOCATION -> previewLocationLabel
                                            else -> previewFileLabel
                                        }
                                    }
                                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                                    }
                                    val chooser = android.content.Intent.createChooser(shareIntent, chatShareMessageTitle)
                                    if (context !is android.app.Activity) chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(chooser)
                                }
                                onMessageToActions(null)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.chat_share_message), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                        }
                        // 0.71：Markdown 消息提供「复制为纯文本」（剥离 **、# 等标记）
                        if (message.type == MessageType.MARKDOWN) {
                            TextButton(
                                onClick = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(
                                        android.content.ClipData.newPlainText(
                                            chatClipboardMessageLabel,
                                            com.maodouchat.messaging.ChatMarkdown.toPlainText(message.parsedContent())
                                        )
                                    )
                                    Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
                                    onMessageToActions(null)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(stringResource(R.string.chat_copy_plain), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                        }
                        // 1.84：名片消息复制干净文本（不含 [contactUser:...] 标记）
                        if (message.parsedContent().contains("[contactUser:")) {
                            TextButton(
                                onClick = {
                                    val clean = com.maodouchat.messaging.ChatMarkdown.stripContactCardMarker(message.parsedContent()).trim()
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chatClipboardMessageLabel, clean))
                                    Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
                                    onMessageToActions(null)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(stringResource(R.string.chat_copy_contact_card), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                        }
                        // 0.97：消息分享到系统（ACTION_SEND 文本分享）
                        TextButton(
                            onClick = {
                                val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, message.parsedContent())
                                }
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent.createChooser(sendIntent, context.getString(R.string.chat_share))
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                                onMessageToActions(null)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.chat_share), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                        if (!displayedTranslation.isNullOrBlank()) {
                            TextButton(
                                onClick = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chatClipboardTranslationLabel, displayedTranslation))
                                    Toast.makeText(context, chatTranslationCopiedMsg, Toast.LENGTH_SHORT).show()
                                    onMessageToActions(null)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(stringResource(R.string.chat_copy_translation), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                    if (message.type == MessageType.VOICE && !voiceTranscript.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chatClipboardTranscriptLabel, voiceTranscript))
                                Toast.makeText(context, chatTranscriptCopiedMsg, Toast.LENGTH_SHORT).show()
                                onMessageToActions(null)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.chat_copy_transcript), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                    }
                    // 1.299：复制消息 ID（反查排障；ID 本身不涉密，密聊也可用）
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.chat_copy_message_id), message.id))
                            Toast.makeText(context, chatCopiedMsg, Toast.LENGTH_SHORT).show()
                            onMessageToActions(null)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.chat_copy_message_id), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                    // AI 场景入口（与输入栏主入口分区视觉统一）；密聊会话不提供（防解密明文送 AI）
                    val contextAiActions = com.maodouchat.ai.AiEntryPolicy.contextActionsFor(
                        messageType = message.type.name,
                        hasTranscript = !voiceTranscript.isNullOrBlank()
                    )
                    if (
                        contextAiActions.isNotEmpty() &&
                        state.isSecretChat != true &&
                        chatAiSurfacesVisible
                    ) {
                        Text(
                            stringResource(R.string.chat_ai_section_context),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                        contextAiActions.forEach { action ->
                            when (action) {
                                com.maodouchat.ai.AiEntryPolicy.MessageAiAction.TRANSLATE -> {
                                    val busy = message.id in state.translatingMessageIds
                                    TextButton(
                                        enabled = com.maodouchat.ai.AiEntryPolicy.canRunContextAction(context, state.aiEnabled, busy),
                                        onClick = {
                                            if (!state.aiEnabled) {
                                                Toast.makeText(context, context.getString(R.string.chat_ai_disabled_short), Toast.LENGTH_SHORT).show()
                                                return@TextButton
                                            }
                                            onMessageToTranslate(message)
                                            onMessageToActions(null)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            stringResource(if (busy) R.string.chat_translating else R.string.chat_translate),
                                            modifier = Modifier.fillMaxWidth(),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                com.maodouchat.ai.AiEntryPolicy.MessageAiAction.TRANSCRIBE -> {
                                    val busy = message.id in state.transcribingVoiceMessageIds
                                    TextButton(
                                        enabled = com.maodouchat.ai.AiEntryPolicy.canRunContextAction(context, state.aiEnabled, busy),
                                        onClick = {
                                            if (!state.aiEnabled) {
                                                Toast.makeText(context, context.getString(R.string.chat_ai_disabled_short), Toast.LENGTH_SHORT).show()
                                                return@TextButton
                                            }
                                            viewModel.requestVoiceTranscription(message.id)
                                            onMessageToActions(null)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            stringResource(if (busy) R.string.chat_transcribing else R.string.chat_transcribe),
                                            modifier = Modifier.fillMaxWidth(),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                com.maodouchat.ai.AiEntryPolicy.MessageAiAction.ANALYZE_IMAGE -> {
                                    val busy = message.id in state.analyzingImageMessageIds
                                    TextButton(
                                        enabled = com.maodouchat.ai.AiEntryPolicy.canRunContextAction(context, state.aiEnabled, busy),
                                        onClick = {
                                            if (!state.aiEnabled) {
                                                Toast.makeText(context, context.getString(R.string.chat_ai_disabled_short), Toast.LENGTH_SHORT).show()
                                                return@TextButton
                                            }
                                            onMessageToAnalyzeImage(message)
                                            onMessageToActions(null)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            stringResource(if (busy) R.string.chat_ai_image_analyzing else R.string.chat_ai_image_action),
                                            modifier = Modifier.fillMaxWidth(),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                com.maodouchat.ai.AiEntryPolicy.MessageAiAction.ANALYZE_FILE -> {
                                    val busy = message.id in state.analyzingFileMessageIds
                                    TextButton(
                                        enabled = com.maodouchat.ai.AiEntryPolicy.canRunContextAction(context, state.aiEnabled, busy),
                                        onClick = {
                                            if (!state.aiEnabled) {
                                                Toast.makeText(context, context.getString(R.string.chat_ai_disabled_short), Toast.LENGTH_SHORT).show()
                                                return@TextButton
                                            }
                                            onMessageToAnalyzeFile(message)
                                            onMessageToActions(null)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            stringResource(if (busy) R.string.chat_ai_file_analyzing else R.string.chat_ai_file_action),
                                            modifier = Modifier.fillMaxWidth(),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
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
                    TextButton(onClick = onMessageToActionsDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.common_cancel), modifier = Modifier.fillMaxWidth(), color = LocalChatPalette.current.textSecondary)
                    }
                    Spacer(modifier = Modifier.navigationBarsPadding())
            }
        }
}
