package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.util.RuntimeFlags
import android.net.Uri
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.util.MediaCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


fun ChatDetailViewModel.revealSpoilerMedia(messageId: String) {
    if (messageId.isBlank()) return
    val current = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return
    val currentMeta = current.parsedMeta()
    if (!currentMeta.spoilerMedia || currentMeta.spoilerRevealed) return
    val revealed = current.withEncodedMeta(currentMeta.copy(spoilerRevealed = true))
    _uiState.update { state ->
        state.copy(
            messages = state.messages.map { msg ->
                if (msg.id != messageId) msg
                else {
                    val meta = msg.parsedMeta()
                    if (!meta.spoilerMedia || meta.spoilerRevealed) msg
                    else msg.withEncodedMeta(meta.copy(spoilerRevealed = true))
                }
            }
        )
    }
    persistLocalMediaMeta(revealed)
}

fun ChatDetailViewModel.markViewOnceOpened(messageId: String) {
    if (messageId.isBlank()) return
    val current = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return
    val opened = com.maodouchat.util.ViewOncePolicy.markOpened(current)
    if (opened == current) return
    _uiState.update { state ->
        val updated = state.messages.map { msg ->
            if (msg.id != messageId) msg
            else com.maodouchat.util.ViewOncePolicy.markOpened(msg)
        }
        state.copy(messages = updated)
    }
    viewModelScope.launch(Dispatchers.IO) {
        val persisted = try {
            messageRepo.persistLocalMediaMeta(opened)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!persisted) {
            Log.w("ChatDetailViewModel", "Failed to persist view-once opened state for $messageId")
        }
        // 9.147：删除媒体须与 ensureLocalAttachment 同锁串行——并发自动下载会把
        // 刚被擦除的阅后即焚媒体重新写回缓存（隐私失效），或在下载中途删出残缺文件
        attachmentDownloadCoordinator.withAttachmentLock(messageId) {
            runCatching {
                com.maodouchat.util.MediaCache.deleteCachedMediaForMessage(getApplication(), messageId)
            }
        }
    }
}

internal fun ChatDetailViewModel.persistLocalMediaMeta(message: Message) {
    viewModelScope.launch(Dispatchers.IO) {
        val ok = try {
            messageRepo.persistLocalMediaMeta(message)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            Log.w("ChatDetailViewModel", "Failed to persist local media metadata for ${message.id}")
        }
    }
}

fun ChatDetailViewModel.sendImage(uri: Uri) {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.IMAGE_SEND)) {
        _uiState.update { it.copy(errorMessage = text(R.string.image_send_disabled)) }
        return
    }
    sendEncryptedAttachment(uri, MessageType.IMAGE)
}
fun ChatDetailViewModel.sendViewOnceImage(uri: Uri) {
    if (_uiState.value.chatIsGroup) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.view_once_direct_only)) }
        return
    }
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.VIEW_ONCE)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.view_once_disabled)) }
        return
    }
    sendEncryptedAttachment(uri, MessageType.IMAGE, viewOnce = true)
}
fun ChatDetailViewModel.sendViewOnceVideo(uri: Uri) {
    if (_uiState.value.chatIsGroup) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.view_once_direct_only)) }
        return
    }
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.VIEW_ONCE)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.view_once_disabled)) }
        return
    }
    sendEncryptedAttachment(uri, MessageType.VIDEO, viewOnce = true)
}
fun ChatDetailViewModel.sendSpoilerImage(uri: Uri) {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SPOILER_MEDIA)) {
        _uiState.update { it.copy(errorMessage = text(R.string.spoiler_media_disabled)) }
        return
    }
    sendEncryptedAttachment(uri, MessageType.IMAGE, spoilerMedia = true)
}
fun ChatDetailViewModel.sendSpoilerVideo(uri: Uri) {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SPOILER_MEDIA)) {
        _uiState.update { it.copy(errorMessage = text(R.string.spoiler_media_disabled)) }
        return
    }
    sendEncryptedAttachment(uri, MessageType.VIDEO, spoilerMedia = true)
}
fun ChatDetailViewModel.sendGif(uri: Uri) {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.GIF_SEND)) {
        _uiState.update { it.copy(errorMessage = text(R.string.gif_send_disabled)) }
        return
    }
    sendEncryptedAttachment(uri, MessageType.GIF)
}
fun ChatDetailViewModel.sendVideo(uri: Uri) {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.VIDEO_SEND)) {
        _uiState.update { it.copy(errorMessage = text(R.string.video_send_disabled)) }
        return
    }
    sendEncryptedAttachment(uri, MessageType.VIDEO)
}
fun ChatDetailViewModel.sendFile(uri: Uri) {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.FILE_SHARE)) {
        _uiState.update { it.copy(errorMessage = text(R.string.file_share_disabled)) }
        return
    }
    sendEncryptedAttachment(uri, MessageType.FILE)
}

fun ChatDetailViewModel.sendSticker(sticker: String) {
    if (!requireStickers()) return
    val content = sticker.trim().take(32)
    if (content.isBlank()) return
    // 最近使用按账号本地记录，与发送解耦
    runCatching {
        com.maodouchat.util.StickerPreferences.recordRecent(getApplication(), content)
    }
    sendInlineContent(content, MessageType.STICKER, text(R.string.message_preview_sticker))
}
