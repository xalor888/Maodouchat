package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.network.ApiService
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 9.5xx：真实功能门卫与内联投票（自 ChatDetailGroupPlay.kt 拆出保留；其余 ~190 个假群玩法已删除）
internal fun ChatDetailViewModel.votePoll(pollId: String, optionIndex: Int) {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.POLLS)) {
        _uiState.update { it.copy(errorMessage = text(R.string.group_play_poll_disabled)) }
        return
    }
    if (!requireGroupPlay()) return
    if (pollId.isBlank()) return
    viewModelScope.launch {
        val token = tokenManager.getToken().orEmpty()
        if (token.isBlank()) return@launch
        ApiService.voteGroupPoll(token, pollId, listOf(optionIndex)).fold(
            onSuccess = {
                _uiState.update { st -> st.copy(infoMessage = text(R.string.group_play_vote_ok)) }
            },
            onFailure = {
                _uiState.update { st -> st.copy(errorMessage = text(R.string.group_play_poll_failed)) }
            }
        )
    }
}
internal fun ChatDetailViewModel.requireVoiceMessages(): Boolean {
    val ctx = getApplication<Application>()
    if (!RuntimeFlags.isEnabled(ctx, RuntimeFlags.VOICE_MESSAGES)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.voice_messages_disabled)) }
        return false
    }
    if (!RuntimeFlags.isEnabled(ctx, RuntimeFlags.MEDIA_UPLOAD)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_media_upload_disabled)) }
        return false
    }
    return true
}
internal fun ChatDetailViewModel.requireStickers(): Boolean {
    val ctx = getApplication<Application>()
    if (!RuntimeFlags.isEnabled(ctx, RuntimeFlags.STICKERS)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.stickers_disabled)) }
        return false
    }
    return true
}
internal fun ChatDetailViewModel.requireSilentSend(): Boolean {
    val ctx = getApplication<Application>()
    if (!RuntimeFlags.isEnabled(ctx, RuntimeFlags.SILENT_SEND)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.silent_send_disabled)) }
        return false
    }
    return true
}
internal fun ChatDetailViewModel.requireReactions(): Boolean {
    val ctx = getApplication<Application>()
    if (!RuntimeFlags.isEnabled(ctx, RuntimeFlags.REACTIONS)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.reactions_disabled)) }
        return false
    }
    if (_uiState.value.isSecretChat == true && RuntimeFlags.isEnabled(ctx, RuntimeFlags.SECRET_REACTION_BLOCK)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_reaction_blocked)) }
        return false
    }
    return true
}

/** 仅群聊可用的功能门卫（真实功能保留；假玩法已删除）。 */
internal fun ChatDetailViewModel.requireGroupPlay(): Boolean {
    if (_uiState.value.chat?.isGroup != true) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.group_play_group_only)) }
        return false
    }
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.GROUP_PLAY)) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.group_play_disabled)) }
        return false
    }
    return true
}

    internal fun ChatDetailViewModel.refreshSealedSenderCertificate() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.isSecretChat != true) return@launch
            if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SEALED_SENDER)) {
                _uiState.update { it.copy(sealedSenderReady = false, sealedSenderExpiresInSec = 0L) }
                return@launch
            }
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            val ownerUserId = tokenManager.getUserId().orEmpty()
            if (liveToken.isBlank() || ownerUserId.isBlank()) return@launch
            val ownerDeviceId = signalProtocol.getDeviceId()
            val cert = try {
                com.maodouchat.crypto.SealedSenderSupport.fetchCertificate(
                    liveToken,
                    ownerUserId,
                    ownerDeviceId
                ).getOrNull()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val ready = cert?.certificate?.isNotBlank() == true
            val ttl = if (ready) {
                com.maodouchat.crypto.SealedSenderSupport.secondsUntilExpiry(ownerUserId, ownerDeviceId)
            } else {
                0L
            }
            _uiState.update { it.copy(sealedSenderReady = ready, sealedSenderExpiresInSec = ttl) }
        }
    }
internal fun ChatDetailViewModel.sendBotCallback(messageId: String, botUserId: String, callbackData: String) {
    val data = callbackData.trim().take(128)
    if (data.isBlank() || messageId.isBlank() || botUserId.isBlank()) return
    viewModelScope.launch {
        try {
            val tok = tokenManager.getToken().orEmpty()
            if (tok.isBlank()) return@launch
            val chatId = _uiState.value.chat?.id ?: return@launch
            val ok = ApiService.postBotCallback(
                token = tok,
                chatId = chatId,
                messageId = messageId,
                botUserId = botUserId,
                callbackData = data
            ).getOrDefault(false)
            if (!ok) {
                _uiState.update { it.copy(groupEncryptionWarning = "callback failed") }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update {
                it.copy(groupEncryptionWarning = e.message?.take(120) ?: "callback failed")
            }
        }
    }
}
