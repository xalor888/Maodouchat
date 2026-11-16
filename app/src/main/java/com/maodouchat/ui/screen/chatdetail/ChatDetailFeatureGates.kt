package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.maodouchat.data.repository.BotNetworkRepository
import com.maodouchat.data.repository.GroupPollNetworkRepository
import com.maodouchat.R
import com.maodouchat.domain.messaging.ChatFeatureGatePolicy
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// 9.5xx：真实功能门卫与内联投票（自 ChatDetailGroupPlay.kt 拆出保留；其余 ~190 个假群玩法已删除）
internal fun ChatDetailViewModel.votePoll(pollId: String, optionIndex: Int) {
    if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.POLLS)) {
        _uiState.update { it.copy(errorMessage = text(R.string.group_play_poll_disabled)) }
        return
    }
    if (!requireGroupPlay()) return
    if (pollId.isBlank()) return
    viewModelScope.launch {
        val token = com.maodouchat.session.CurrentSession.snapshot().token.orEmpty()
        if (token.isBlank()) return@launch
        GroupPollNetworkRepository().votePoll(token, pollId, listOf(optionIndex)).fold(
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
    return when (
        ChatFeatureGatePolicy.voiceMessagesGate(
            RuntimeFlags.isEnabled(ctx, RuntimeFlags.VOICE_MESSAGES),
            RuntimeFlags.isEnabled(ctx, RuntimeFlags.MEDIA_UPLOAD)
        )
    ) {
        ChatFeatureGatePolicy.VoiceMessagesGate.Allowed -> true
        ChatFeatureGatePolicy.VoiceMessagesGate.VoiceDisabled -> {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.voice_messages_disabled)) }
            false
        }
        ChatFeatureGatePolicy.VoiceMessagesGate.MediaUploadDisabled -> {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.chat_media_upload_disabled)) }
            false
        }
    }
}
internal fun ChatDetailViewModel.requireStickers(): Boolean {
    val ctx = getApplication<Application>()
    if (!ChatFeatureGatePolicy.stickersAllowed(RuntimeFlags.isEnabled(ctx, RuntimeFlags.STICKERS))) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.stickers_disabled)) }
        return false
    }
    return true
}
internal fun ChatDetailViewModel.requireSilentSend(): Boolean {
    val ctx = getApplication<Application>()
    if (!ChatFeatureGatePolicy.silentSendAllowed(RuntimeFlags.isEnabled(ctx, RuntimeFlags.SILENT_SEND))) {
        _uiState.update { it.copy(groupEncryptionWarning = text(R.string.silent_send_disabled)) }
        return false
    }
    return true
}
internal fun ChatDetailViewModel.requireReactions(): Boolean {
    val ctx = getApplication<Application>()
    return when (
        ChatFeatureGatePolicy.reactionsGate(
            RuntimeFlags.isEnabled(ctx, RuntimeFlags.REACTIONS),
            _uiState.value.isSecretChat,
            RuntimeFlags.isEnabled(ctx, RuntimeFlags.SECRET_REACTION_BLOCK)
        )
    ) {
        ChatFeatureGatePolicy.ReactionsGate.Allowed -> true
        ChatFeatureGatePolicy.ReactionsGate.ReactionsDisabled -> {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.reactions_disabled)) }
            false
        }
        ChatFeatureGatePolicy.ReactionsGate.SecretReactionBlocked -> {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.secret_reaction_blocked)) }
            false
        }
    }
}

/** 仅群聊可用的功能门卫（真实功能保留；假玩法已删除）。 */
internal fun ChatDetailViewModel.requireGroupPlay(): Boolean {
    return when (
        ChatFeatureGatePolicy.groupPlayGate(
            _uiState.value.chat?.isGroup,
            RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.GROUP_PLAY)
        )
    ) {
        ChatFeatureGatePolicy.GroupPlayGate.Allowed -> true
        ChatFeatureGatePolicy.GroupPlayGate.NotGroup -> {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.group_play_group_only)) }
            false
        }
        ChatFeatureGatePolicy.GroupPlayGate.GroupPlayDisabled -> {
            _uiState.update { it.copy(groupEncryptionWarning = text(R.string.group_play_disabled)) }
            false
        }
    }
}

    internal fun ChatDetailViewModel.refreshSealedSenderCertificate() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.isSecretChat != true) return@launch
            if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SEALED_SENDER)) {
                _uiState.update { it.copy(sealedSenderReady = false, sealedSenderExpiresInSec = 0L) }
                return@launch
            }
            val liveToken = com.maodouchat.session.CurrentSession.snapshot().token.orEmpty()
            val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
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
            val tok = com.maodouchat.session.CurrentSession.snapshot().token.orEmpty()
            if (tok.isBlank()) return@launch
            val chatId = _uiState.value.chat?.id ?: return@launch
            val ok = BotNetworkRepository().postBotCallback(
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
