package com.maodouchat.ui.screen.chatdetail

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.util.RuntimeFlags

// 长按弹层的复制/分享按钮组：复制、带发送者/时间复制、系统分享、纯文本/名片/翻译/转写/ID 复制。
// SimpleDateFormat 非线程安全，ThreadLocal 每线程复用一个；只调 format，不残留状态。
private val copyWithSenderTimeFormat: ThreadLocal<java.text.SimpleDateFormat> =
    ThreadLocal.withInitial { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()) }

@Composable
internal fun ChatDetailMessageCopyActions(
    message: Message,
    state: ChatDetailUiState,
    context: android.content.Context,
    senderName: String?,
    voiceTranscript: String?,
    displayedTranslation: String?,
    chatCopiedMsg: String,
    chatTranslationCopiedMsg: String,
    chatTranscriptCopiedMsg: String,
    chatClipboardMessageLabel: String,
    chatClipboardTranslationLabel: String,
    chatClipboardTranscriptLabel: String,
    onMessageToActions: (Message?) -> Unit,
) {
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
                val time = copyWithSenderTimeFormat.get().format(java.util.Date(message.timestamp))
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
                        putExtra(android.content.Intent.EXTRA_STREAM, contentUri.toUri())
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
}
