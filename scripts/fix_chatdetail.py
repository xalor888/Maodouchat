"""
Append missing public functions to ChatDetailViewModel.kt.
These functions were accidentally truncated by an earlier edit.
"""
from pathlib import Path

missing_functions = '''
    fun onInputChange(text: String) { _uiState.update { it.copy(inputText = text) } }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank()) return

        if (_uiState.value.chat?.isGroup == true) {
            sendGroupTextMessage(text)
            return
        }

        val msgId = "m_${UUID.randomUUID()}"

        viewModelScope.launch {
            val recipientId = _uiState.value.contact.id
            val sendResult = withContext(Dispatchers.IO) {
                resolveDirectServerChatId(recipientId).mapCatching { effectiveChatId ->
                    val newMessage = Message(
                        id = msgId, chatId = effectiveChatId, senderId = currentUserId,
                        content = text, type = MessageType.TEXT,
                        timestamp = System.currentTimeMillis(), status = MessageStatus.SENDING
                    )
                    val wireContent = encryptOutgoingText(recipientId, text).getOrThrow()
                    val wireMessage = newMessage.copy(content = wireContent)
                    val sentByWebSocket = WebSocketClient.isConnected() && WebSocketClient.sendMessage(wireMessage)
                    if (!sentByWebSocket) {
                        ApiService.sendMessage(token, effectiveChatId, wireContent, "TEXT", msgId).getOrThrow()
                    }
                    newMessage
                }
            }
            sendResult.fold(
                onSuccess = { sentMessage ->
                    upsertUiMessage(sentMessage) { it.copy(inputText = "") }
                    withContext(Dispatchers.IO) { messageRepo.insertMessage(sentMessage) }
                    updateMessageStatus(msgId, MessageStatus.SENT)
                },
                onFailure = { error ->
                    android.util.Log.w(TAG, "Direct text send failed: ${error.javaClass.simpleName}: ${error.message}")
                    val failedMessage = Message(
                        id = msgId, chatId = chatId, senderId = currentUserId,
                        content = text, type = MessageType.TEXT,
                        timestamp = System.currentTimeMillis(), status = MessageStatus.FAILED
                    )
                    upsertUiMessage(failedMessage) { it.copy(inputText = "") }
                    withContext(Dispatchers.IO) { messageRepo.insertMessage(failedMessage) }
                }
            )
        }
    }

    fun sendImage(uri: Uri) = sendMedia(uri, MessageType.IMAGE, 800, 70)
    fun sendVideo(uri: Uri) = sendMedia(uri, MessageType.VIDEO, 1280, 60)

    private fun sendMedia(uri: Uri, type: MessageType, maxWidth: Int, quality: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true, groupEncryptionWarning = null) }
            val base64 = withContext(Dispatchers.IO) {
                when (type) {
                    MessageType.IMAGE -> ImagePicker.uriToBase64(getApplication(), uri, maxWidth, quality)
                    MessageType.VOICE -> null
                    MessageType.VIDEO -> MediaCache.uriToRawBase64(getApplication(), uri)
                    else -> null
                }
            }
            if (base64 != null) {
                val msgId = "m_${UUID.randomUUID()}"
                val sendResult = withContext(Dispatchers.IO) {
                    resolveOutgoingChatId().mapCatching { effectiveChatId ->
                        val wireContent = encryptMediaContent(base64, type, effectiveChatId).getOrThrow()
                        val sentByWebSocket = when (type) {
                            MessageType.IMAGE -> WebSocketClient.isConnected() && WebSocketClient.sendImageMessage(effectiveChatId, msgId, wireContent)
                            MessageType.VIDEO -> WebSocketClient.isConnected() && WebSocketClient.sendVideoMessage(effectiveChatId, msgId, wireContent)
                            else -> false
                        }
                        if (!sentByWebSocket) {
                            ApiService.sendMessage(token, effectiveChatId, wireContent, type.name, msgId).getOrThrow()
                        }
                        effectiveChatId
                    }
                }

                val effectiveChatId = sendResult.getOrNull() ?: activeChatId
                val message = Message(
                    id = msgId, chatId = effectiveChatId, senderId = currentUserId,
                    content = uri.toString(), type = type,
                    timestamp = System.currentTimeMillis(), status = if (sendResult.isSuccess) MessageStatus.SENT else MessageStatus.FAILED
                )
                upsertUiMessage(message) { it.copy(isSending = false) }
                withContext(Dispatchers.IO) { messageRepo.insertMessage(message) }

                if (sendResult.isFailure) {
                    _uiState.update { state ->
                        state.copy(groupEncryptionWarning = if (state.chatIsGroup) GROUP_E2EE_FAILURE_WARNING else state.groupEncryptionWarning)
                    }
                }
            } else {
                _uiState.update { it.copy(isSending = false) }
            }
        }
    }

    fun startRecording() {
        runCatching { voiceRecorder.startRecording() }
            .onSuccess { _uiState.update { it.copy(isRecording = true) } }
            .onFailure {
                _uiState.update { state ->
                    state.copy(groupEncryptionWarning = "无法开始录音，请确认麦克风权限已开启。")
                }
            }
    }

    fun stopRecordingAndSend() {
        val result = voiceRecorder.stopRecording()
        _uiState.update { it.copy(isRecording = false) }
        if (result != null) {
            val (filePath, duration) = result
            viewModelScope.launch {
                val base64 = withContext(Dispatchers.IO) {
                    try { voiceRecorder.fileToBase64(filePath) } finally { java.io.File(filePath).delete() }
                }
                if (base64 != null) {
                    val msgId = "m_${UUID.randomUUID()}"
                    val durationStr = VoiceRecorder.formatDuration(duration)
                    val sendResult = withContext(Dispatchers.IO) {
                        resolveOutgoingChatId().mapCatching { effectiveChatId ->
                            val wireContent = encryptMediaContent(base64, MessageType.VOICE, effectiveChatId).getOrThrow()
                            val sentByWebSocket = WebSocketClient.isConnected() && WebSocketClient.sendVoiceMessage(effectiveChatId, msgId, wireContent)
                            if (!sentByWebSocket) {
                                ApiService.sendMessage(token, effectiveChatId, wireContent, "VOICE", msgId).getOrThrow()
                            }
                            effectiveChatId
                        }
                    }
                    val effectiveChatId = sendResult.getOrNull() ?: activeChatId
                    val message = Message(
                        id = msgId, chatId = effectiveChatId, senderId = currentUserId,
                        content = durationStr, type = MessageType.VOICE,
                        timestamp = System.currentTimeMillis(), status = if (sendResult.isSuccess) MessageStatus.SENT else MessageStatus.FAILED
                    )
                    upsertUiMessage(message)
                    withContext(Dispatchers.IO) { messageRepo.insertMessage(message) }
                }
            }
        }
    }

    fun cancelRecording() {
        voiceRecorder.cancelRecording()
        _uiState.update { it.copy(isRecording = false) }
    }

    fun showSafetyCodeDialog() {
        _uiState.update { it.copy(showSafetyCodeDialog = true, deviceSafetyStates = emptyList(), isLoadingDeviceSafety = true) }
        refreshIdentitySafetyState(showDialog = true)
    }

    fun dismissSafetyCodeDialog() {
        _uiState.update { it.copy(showSafetyCodeDialog = false) }
    }

    fun verifyAndTrustIdentity(deviceId: Int? = null) {
        viewModelScope.launch {
            val targetDeviceId = if (_uiState.value.deviceSafetyStates.isNotEmpty()) {
                _uiState.value.deviceSafetyStates.firstOrNull { it.safetyCode != null }?.deviceId
            } else null
            val idToVerify = deviceId ?: targetDeviceId ?: return
            val success = runCatching { signalProtocol.markIdentityVerified(_uiState.value.contact.id, idToVerify) }.getOrDefault(false)
            if (success) { refreshIdentitySafetyState() }
        }
    }
'''

p = Path('D:/Maodouchat/app/src/main/java/com/maodouchat/ui/screen/chatdetail/ChatDetailViewModel.kt')
s = p.read_text()

# Find insertion point: last } before companion object
marker = '    private companion object {'
idx = s.find(marker)
if idx > 0:
    new_s = s[:idx] + missing_functions + s[idx:]
    p.write_text(new_s)
    print(f"Inserted at offset {idx}")
else:
    print("marker not found")
    print(repr(s[-200:]))
