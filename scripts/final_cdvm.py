from pathlib import Path

kotlin_source = '''package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.maodouchat.MaodouchatApp
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.User
import com.maodouchat.data.repository.MessageRepository
import com.maodouchat.data.repository.UserRepository
import com.maodouchat.network.ApiConfig
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.network.WebSocketClient
import com.maodouchat.network.WebSocketEvent
import com.maodouchat.util.ImagePicker
import com.maodouchat.util.MediaCache
import com.maodouchat.util.VoiceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ChatDetailUiState(
    val contact: User = User("", ""),
    val chat: Chat? = null,
    val chatIsGroup: Boolean = false,
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val currentUserId: String = "",
    val isLoading: Boolean = false,
    val isRecording: Boolean = false,
    val isSending: Boolean = false,
    val identityWarning: String? = null,
    val safetyCode: String? = null,
    val trustStateLabel: String = "未建立安全码",
    val showSafetyCodeDialog: Boolean = false,
    val canVerifyIdentity: Boolean = false,
    val deviceSafetyStates: List<SignalProtocol.DeviceSafetyState> = emptyList(),
    val isLoadingDeviceSafety: Boolean = false,
    val deviceSafetyWarning: String? = null,
    val groupEncryptionWarning: String? = null
)

class ChatDetailViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val chatId: String = savedStateHandle["chatId"] ?: "c1"
    @Volatile private var activeChatId: String = chatId
    private val app = application as MaodouchatApp
    private val messageRepo = MessageRepository(app.database.messageDao())
    private val userRepo = UserRepository(app.database.userDao())
    private val tokenManager = TokenManager.getInstance(application)
    private val voiceRecorder = VoiceRecorder(application)
    private val signalProtocol: SignalProtocol = app.signalProtocol
    private val readMessagesTracker = mutableSetOf<String>()
    private var lastMessagesSeen: List<Pair<String, MessageStatus>>? = null

    private val currentUserId: String get() = tokenManager.getUserId() ?: "me"
    private val token: String get() = tokenManager.getToken() ?: ""

    private val _uiState = MutableStateFlow(ChatDetailUiState())
    val uiState: StateFlow<ChatDetailUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(currentUserId = currentUserId) }
        loadChat()
        connectWebSocket()
        observeWebSocket()
        observeMessageStatus()
    }

    private fun observeMessageStatus() {
        viewModelScope.launch {
            _uiState.collect { state ->
                if (state.chat != null) {
                    val currentIds = state.messages.map { it.id to it.status }
                    if (currentIds != lastMessagesSeen) {
                        lastMessagesSeen = currentIds
                        val unread = state.messages.filter {
                            it.senderId != currentUserId && it.status != MessageStatus.READ && readMessagesTracker.add(it.id)
                        }
                        if (unread.isNotEmpty()) {
                            val unreadIds = unread.map { it.id }.toSet()
                            _uiState.update { st -> st.copy(messages = st.messages.map { m -> if (m.id in unreadIds) m.copy(status = MessageStatus.READ) else m }) }
                            unread.forEach { updateMessageStatus(it.id, MessageStatus.READ) }
                        }
                    }
                }
            }
        }
    }

    private fun updateMessageStatus(messageId: String, status: MessageStatus) {
        _uiState.update { state -> state.copy(messages = state.messages.map { if (it.id == messageId) it.copy(status = status) else it }) }
        viewModelScope.launch { messageRepo.updateMessageStatus(messageId, status) }
    }

    private fun loadChat() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            ApiService.getChats(token).onSuccess { chats ->
                val chatDto = chats.find { it.id == chatId }
                if (chatDto != null) {
                    val chat = Chat(id = chatDto.id, participants = chatDto.participants.map { User(it.id, it.name, it.avatar, it.email, it.isOnline, it.status) }, lastMessage = chatDto.lastMessage, lastMessageTime = chatDto.lastMessageTime, isGroup = chatDto.isGroup, groupName = chatDto.groupName)
                    _uiState.update { it.copy(isLoading = false) }
                    if (chat.isGroup) {
                        val groupContact = User(id = chat.id, name = chat.groupName ?: "群聊", status = "#{chat.participants.size} 位成员")
                        _uiState.update { it.copy(chat = chat, chatIsGroup = true, contact = groupContact) }
                    } else {
                        val contactUser = chat.participants.firstOrNull { it.id != currentUserId }
                        if (contactUser != null) {
                            _uiState.update { it.copy(chat = chat, chatIsGroup = false, contact = contactUser) }
                            refreshIdentitySafetyState()
                        }
                    }
                } else _uiState.update { it.copy(isLoading = false) }
            }
            withContext(Dispatchers.IO) {
                ApiService.getMessages(token, chatId).onSuccess { dtos ->
                    val messages = dtos.sortedWith(compareBy<ApiService.MessageDto> { it.timestamp }.thenBy { if (it.type == "SK_DIST") 0 else 1 })
                        .mapNotNull { dto -> decryptIncomingMessage(dto.senderId, Message(dto.id, dto.chatId, dto.senderId, dto.content, dto.type, dto.timestamp, dto.status)) }
                    _uiState.update { it.copy(messages = mergeMessages(_uiState.value.messages, messages)) }
                }
            }
        }
    }

    private fun connectWebSocket() { if (token.isBlank()) return; WebSocketClient.connect(ApiConfig.WS_URL, token) }

    private fun observeWebSocket() {
        viewModelScope.launch {
            WebSocketClient.events.collect { event ->
                when (event) {
                    is WebSocketEvent.MessageReceived -> {
                        val msg = withContext(Dispatchers.IO) { decryptIncomingMessage(event.message.senderId, event.message) } ?: return@collect
                        if (msg.chatId == activeChatId) {
                            _uiState.update { it.copy(messages = mergeMessages(it.messages, listOf(msg))) }
                            WebSocketClient.sendStatusUpdate(msg.id, MessageStatus.DELIVERED)
                        }
                    }
                    is WebSocketEvent.StatusChanged -> updateMessageStatus(event.messageId, event.status)
                    is WebSocketEvent.Error -> Log.w("ChatDetailViewModel", "WS error: " + event.error)
                    else -> Unit
                }
            }
        }
    }

    fun onInputChange(text: String) { _uiState.update { it.copy(inputText = text) } }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim(); if (text.isBlank()) return
        if (_uiState.value.chat?.isGroup == true) { sendGroupTextMessage(text); return }
        val msgId = "m_${UUID.randomUUID()}"
        viewModelScope.launch {
            val sendResult = withContext(Dispatchers.IO) {
                resolveOutgoingChatId().mapCatching { effectiveChatId ->
                    val wireContent = signalProtocol.encryptTextEnvelopes(token, _uiState.value.contact.id, text).getOrThrow().firstOrNull() ?: throw IllegalStateException("encryption failed")
                    val newMessage = Message(id = msgId, chatId = effectiveChatId, senderId = currentUserId, content = text, type = MessageType.TEXT, timestamp = System.currentTimeMillis(), status = MessageStatus.SENT)
                    val sentByWebSocket = WebSocketClient.isConnected() && WebSocketClient.sendMessage(newMessage.copy(content = wireContent))
                    if (!sentByWebSocket) ApiService.sendMessage(token, effectiveChatId, wireContent, "TEXT", msgId).getOrThrow()
                    newMessage
                }
            }
            sendResult.fold(
                onSuccess = { msg -> _uiState.update { st -> st.copy(messages = mergeMessages(st.messages, listOf(msg))), inputText = "" } },
                onFailure = { -> Log.w("ChatDetailViewModel", "send failed"); val resolvedChatId = _uiState.value.chat?.id ?: activeChatId; _uiState.update { st -> st.copy(messages = mergeMessages(st.messages, listOf(Message(id = msgId, chatId = resolvedChatId, senderId = currentUserId, content = text, type = MessageType.TEXT, timestamp = System.currentTimeMillis(), status = MessageStatus.FAILED))), inputText = "" } }
            )
        }
    }

    fun sendImage(uri: Uri) = sendMedia(uri, MessageType.IMAGE, 800, 70)
    fun sendVideo(uri: Uri) = sendMedia(uri, MessageType.VIDEO, 1280, 60)

    private fun sendMedia(uri: Uri, type: MessageType, maxWidth: Int, quality: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true, groupEncryptionWarning = null) }
            val base64 = withContext(Dispatchers.IO) { when (type) { MessageType.IMAGE -> ImagePicker.uriToBase64(getApplication(), uri, maxWidth, quality); else -> MediaCache.uriToRawBase64(getApplication(), uri) } }
            if (base64 != null) {
                val msgId = "m_${UUID.randomUUID()}"
                val sendResult = withContext(Dispatchers.IO) { resolveOutgoingChatId().mapCatching { effectiveChatId ->
                    val wireContent = if (_uiState.value.chat?.isGroup == true) signalProtocol.encryptGroupTextEnvelope(effectiveChatId, base64).getOrThrow()
                    else signalProtocol.ensureSessions(token, _uiState.value.contact.id).mapCatching { deviceIds ->
                        deviceIds.firstOrNull()?.let { deviceId -> signalProtocol.encryptMessage(_uiState.value.contact.id, base64, deviceId) }
                    }.getOrNull() ?: throw IllegalStateException("encryption failed")
                    val sentByWebSocket = when (type) {
                        MessageType.IMAGE -> WebSocketClient.isConnected() && WebSocketClient.sendImageMessage(effectiveChatId, msgId, wireContent)
                        MessageType.VIDEO -> WebSocketClient.isConnected() && WebSocketClient.sendVideoMessage(effectiveChatId, msgId, wireContent)
                        else -> false
                    }
                    if (!sentByWebSocket) ApiService.sendMessage(token, effectiveChatId, wireContent, type.name, msgId).getOrThrow()
                    effectiveChatId
                } }
                val resolvedChatId = sendResult.getOrNull() ?: activeChatId
                val message = Message(id = msgId, chatId = resolvedChatId, senderId = currentUserId, content = uri.toString(), type = type, timestamp = System.currentTimeMillis(), status = if (sendResult.isSuccess) MessageStatus.SENT : MessageStatus.FAILED)
                _uiState.update { it.copy(messages = mergeMessages(it.messages, listOf(message)), isSending = false) }
                withContext(Dispatchers.IO) { messageRepo.insertMessage(message) }
                if (sendResult.isFailure) _uiState.update { st -> st.copy(groupEncryptionWarning = if (st.chatIsGroup) "群聊端到端加密发送失败，请稍后重试或确认群成员密钥状态。" else st.groupEncryptionWarning) }
            } else _uiState.update { it.copy(isSending = false) }
        }
    }

    fun showSafetyCodeDialog() { _uiState.update { it.copy(showSafetyCodeDialog = true) } }
    fun dismissSafetyCodeDialog() { _uiState.update { it.copy(showSafetyCodeDialog = false) } }
    fun verifyAndTrustIdentity(deviceId: Int? = null) {}

    private val recipientId: String get() = _uiState.value.contact.id

    private suspend fun resolveOutgoingChatId(): Result<String> {
        if (_uiState.value.chat?.isGroup == true) return Result.success(activeChatId)
        if (token.isBlank()) return Result.failure(IllegalStateException("未登录"))
        if (recipientId.isBlank()) return Result.failure(IllegalStateException("聊天对象未就绪"))
        if (recipientId == currentUserId) return Result.failure(IllegalStateException("不能向自己发送"))
        if (activeChatId.isNotBlank() && activeChatId != "c1") return Result.success(activeChatId)
        return ApiService.createChat(token, listOf(recipientId), isGroup = false, groupName = null).map { chatDto -> activeChatId = chatDto.id; chatDto.id }
    }

    private fun sendGroupTextMessage(text: String) {
        val msgId = "m_${UUID.randomUUID()}"
        val newMessage = Message(id = msgId, chatId = chatId, senderId = currentUserId, content = text, type = MessageType.TEXT, timestamp = System.currentTimeMillis(), status = MessageStatus.SENDING)
        _uiState.update { it.copy(messages = mergeMessages(it.messages, listOf(newMessage)), inputText = "", groupEncryptionWarning = null) }
        viewModelScope.launch {
            val sendResult = withContext(Dispatchers.IO) { runCatching {
                val wireContent = signalProtocol.encryptGroupTextEnvelope(chatId, text).getOrThrow()
                val sentByWebSocket = WebSocketClient.isConnected() && WebSocketClient.sendMessage(newMessage.copy(content = wireContent))
                if (!sentByWebSocket) ApiService.sendMessage(token, chatId, wireContent, MessageType.TEXT.name, msgId).getOrThrow()
            } }
            sendResult.fold(
                onSuccess = { _uiState.update { st -> st.copy(messages = st.messages.map { m -> if (m.id == msgId) m.copy(status = MessageStatus.SENT) else m }) } },
                onFailure = { _uiState.update { st -> st.copy(messages = st.messages.map { m -> if (m.id == msgId) m.copy(status = MessageStatus.FAILED) else m }, groupEncryptionWarning = "群聊端到端加密发送失败，请稍后重试或确认群成员密钥状态。") } }
            )
            withContext(Dispatchers.IO) { messageRepo.insertMessage(_uiState.value.messages.find { it.id == msgId } ?: newMessage) }
        }
    }

    private fun decryptIncomingMessage(senderId: String, message: Message): Message? {
        if (message.type == MessageType.NUDGE) return message
        if (_uiState.value.chat?.isGroup == true) {
            if (!message.type.isDecryptable()) return message
            if (message.content.isSenderKeyDistribution()) { signalProtocol.processSenderKeyDistributionEnvelope(senderId, message.content, expectedGroupId = message.chatId); return null }
            if (message.content.isSenderKeyMessage()) {
                if (senderId == currentUserId) return message.copy(content = if (message.type == MessageType.TEXT) "[已加密消息]" else message.mediaDecryptFailedText())
                return when (val result = signalProtocol.decryptGroupContentEnvelope(senderId, message.content)) {
                    is SignalProtocol.DecryptResult.Success -> if (message.type == MessageType.TEXT) message.copy(content = result.plaintext) else { val uri = MediaCache.writeBase64ToCache(getApplication(), result.plaintext, message.id, message.type); message.copy(content = uri ?: message.mediaDecryptFailedText()) }
                    SignalProtocol.DecryptResult.NoSession -> signalProtocol.ensureSession(token, senderId).let { message.copy(content = if (message.type == MessageType.TEXT) "[缺少群聊 Sender Key，请等待成员重新发送]" else message.mediaDecryptFailedText()) }
                    SignalProtocol.DecryptResult.UntrustedIdentity -> { message.copy(content = if (message.type == MessageType.TEXT) "[群成员安全码已变化，请验证身份]" else message.mediaDecryptFailedText()) }
                    else -> message.copy(content = if (message.type == MessageType.TEXT) "[无法解密的群聊消息]" else message.mediaDecryptFailedText())
                }
            }
            return message
        }
        if (message.type.isDecryptable() && signalProtocol.isEncryptedEnvelope(message.content)) {
            if (senderId == currentUserId) return message.copy(content = if (message.type == MessageType.TEXT) "[已加密消息]" else message.mediaDecryptFailedText())
            return when (val result = signalProtocol.decryptContentEnvelope(senderId, message.content)) {
                is SignalProtocol.DecryptResult.Success -> { if (message.type == MessageType.TEXT) message.copy(content = result.plaintext) else { val uri = MediaCache.writeBase64ToCache(getApplication(), result.plaintext, message.id, message.type); message.copy(content = uri ?: message.mediaDecryptFailedText()) } }
                SignalProtocol.DecryptResult.NoSession -> { signalProtocol.ensureSession(token, senderId); message.copy(content = if (message.type == MessageType.TEXT) "[缺少会话密钥，请等待对方重新发送]" else message.mediaDecryptFailedText()) }
                SignalProtocol.DecryptResult.UntrustedIdentity -> { message.copy(content = if (message.type == MessageType.TEXT) "[对方安全码已变化，请验证身份]" else message.mediaDecryptFailedText()) }
                else -> message.copy(content = if (message.type == MessageType.TEXT) "[无法解密的消息]" else message.mediaDecryptFailedText())
            }
        }
        return message
    }

    fun startRecording() { runCatching { voiceRecorder.startRecording() }.onSuccess { _uiState.update { it.copy(isRecording = true) } } }
    fun stopRecordingAndSend() {
        val result = voiceRecorder.stopRecording(); _uiState.update { it.copy(isRecording = false) }
        if (result != null) {
            viewModelScope.launch {
                val (filePath, duration) = result
                val base64 = withContext(Dispatchers.IO) { try { voiceRecorder.fileToBase64(filePath) } finally { File(filePath).delete() } }
                if (base64 != null) {
                    val durationStr = VoiceRecorder.formatDuration(duration)
                    val resolvedChatId = _uiState.value.chat?.id ?: activeChatId
                    val msgId = "m_${UUID.randomUUID()}"
                    val message = Message(id = msgId, chatId = resolvedChatId, senderId = currentUserId, content = durationStr, type = MessageType.VOICE, timestamp = System.currentTimeMillis(), status = MessageStatus.SENT)
                    _uiState.update { it.copy(messages = mergeMessages(it.messages, listOf(message))) }
                }
            }
        }
    }
    fun cancelRecording() { voiceRecorder.cancelRecording(); _uiState.update { it.copy(isRecording = false) } }

    private fun mergeMessages(existing: List<Message>, incoming: List<Message>): List<Message> {
        return (existing + incoming).distinctBy { it.id }.sortedWith(compareBy<Message> { it.timestamp }.thenBy { it.id })
    }

    override fun onCleared() {
        readMessagesTracker.clear()
        super.onCleared()
    }

    private fun String.isSenderKeyDistribution(): Boolean = startsWith('{"')
    private fun String.isSenderKeyMessage(): Boolean = startsWith('{"') && contains("sender_key")
    private fun MessageType.isDecryptable(): Boolean = this in setOf(MessageType.TEXT, MessageType.IMAGE, MessageType.VIDEO, MessageType.VOICE)
    private fun Message.mediaDecryptFailedText(): String = when (type) { MessageType.IMAGE -> "[无法解密的图片]"; MessageType.VIDEO -> "[无法解密的视频]"; MessageType.VOICE -> "[无法解密的语音]"; else -> "[无法解密的消息]" }
}

private fun com.maodouchat.crypto.SignalProtocol.IdentityTrustState.toLabel(): String = when (this) {
    com.maodouchat.crypto.SignalProtocol.IdentityTrustState.UNKNOWN -> "未建立安全码"
    com.maodouchat.crypto.SignalProtocol.IdentityTrustState.TRUSTED -> "首次信任"
    com.maodouchat.crypto.SignalProtocol.IdentityTrustState.VERIFIED -> "已验证"
    com.maodouchat.crypto.SignalProtocol.IdentityTrustState.CHANGED -> "安全码已变化"
}
'''

Path('D:/Maodouchat/app/src/main/java/com/maodouchat/ui/screen/chatdetail/ChatDetailViewModel.kt').write_text(kotlin_source)
print('wrote:', Path('D:/Maodouchat/app/src/main/java/com/maodouchat/ui/screen/chatdetail/ChatDetailViewModel.kt').stat().st_size, 'bytes')
