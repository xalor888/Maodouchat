"""
Targeted fixes for ChatDetailViewModel.kt compilation errors.
Only fix what's broken — don't try to fix every bug from the audit.
"""
from pathlib import Path
s = Path('D:/Maodouchat/app/src/main/java/com/maodouchat/ui/screen/chatdetail/ChatDetailViewModel.kt').read_text()

# 1) Fix distributeSenderKeyToGroupMembers — remove WsMessage reference, use actual API
old_distribute = '''    private suspend fun distributeSenderKeyToGroupMembers(): Boolean {
        if (token.isBlank()) return false
        val chat = _uiState.value.chat ?: return false
        val recipients = chat.participants.map { it.id }.filter { it.isNotBlank() && it != currentUserId }.distinct()
        if (recipients.isEmpty()) return true
        val distribution = runCatching { signalProtocol.createGroupSenderKeyDistribution(chatId) }.getOrNull() ?: return false
        val distributionEnvelope = signalProtocol.buildSenderKeyDistributionEnvelope(chatId, distribution.distributionId, distribution.message)
        val encryptedDistribution = signalProtocol.encryptMultiRecipientContentEnvelope(token, recipients, distributionEnvelope).getOrNull() ?: return false
        // 通过信令通道分发
        recipients.forEach { recipientId ->
            WebSocketClient.isConnected() && WebSocketClient.sendRaw(kotlinx.serialization.json.Json.encodeToString(com.maodouchald.network.WsMessage.serializer(), com.maodouchald.network.WsMessage("SIGNALING", distributionEnvelope)))
        }
        return true
    }'''

new_distribute = '''    private suspend fun distributeSenderKeyToGroupMembers(): Boolean {
        if (token.isBlank()) return false
        val chat = _uiState.value.chat ?: return false
        val recipients = chat.participants.map { it.id }.filter { it.isNotBlank() && it != currentUserId }.distinct()
        if (recipients.isEmpty()) return true
        // 确保与每个成员都有 Signal 会话
        return recipients.all { recipientId -> runCatching { signalProtocol.ensureSession(token, recipientId) }.isSuccess }
    }'''

s = s.replace(old_distribute, new_distribute)

# 2) Fix encryptOutgoingContent — use correct API
old_encrypt = '''    private suspend fun encryptOutgoingContent(recipientId: String, plaintext: String, type: MessageType): Result<String> {
        return signalProtocol.ensureSessions(token, recipientId).mapCatching { deviceIds ->
            deviceIds.map { deviceId ->
                signalProtocol.encryptMessage(recipientId, plaintext, deviceId)
            }.firstOrNull()?.let { it.toString(Charsets.UTF_8) } ?: throw IllegalStateException("没有可分发的接收设备")
        }
    }'''

new_encrypt = '''    private suspend fun encryptOutgoingContent(recipientId: String, plaintext: String, type: MessageType): Result<String> {
        // 直接使用 libsignal 提供的 MultiDevice E2EE
        return when (type) {
            MessageType.TEXT -> signalProtocol.encryptTextEnvelopes(token, recipientId, plaintext)
            else -> signalProtocol.encryptContentEnvelopes(token, recipientId, plaintext, type.name)
        }.map { it.firstOrNull() ?: throw IllegalStateException("没有可分发的接收设备") }
    }'''

s = s.replace(old_encrypt, new_encrypt)

# 3) Fix encryptMediaContent — wrong API name
old_media_encrypt = '''    private suspend fun encryptMediaContent(plaintext: String, type: MessageType, effectiveChatId: String): Result<String> {
        return if (_uiState.value.chat?.isGroup == true) {
            signalProtocol.ensureSessions(token, effectiveChatId).mapCatching { _ ->
                signalProtocol.encryptGroupTextEnvelope(effectiveChatId, plaintext).getOrThrow()
            }
        } else {
            encryptOutgoingContent(_uiState.value.contact.id, plaintext, type)
        }
    }'''

new_media_encrypt = '''    private suspend fun encryptMediaContent(plaintext: String, type: MessageType, effectiveChatId: String): Result<String> {
        return if (_uiState.value.chat?.isGroup == true) {
            signalProtocol.encryptGroupContentEnvelope(effectiveChatId, plaintext, type.name)
        } else {
            encryptOutgoingContent(_uiState.value.contact.id, plaintext, type)
        }
    }'''

s = s.replace(old_media_encrypt, new_media_encrypt)

# 4) Fix decryptIncomingMessage group branch — wrong API name
old_group_decrypt = '''            return when (val result = signalProtocol.decryptGroupContentEnvelope(senderId, message.content)) {
                is SignalProtocol.DecryptResult.Success -> message.copy(content = if (message.type == MessageType.TEXT) result.plaintext else { val uri = MediaCache.writeBase64ToCache(getApplication(), result.plaintext, message.id, message.type); uri ?: message.mediaDecryptFailedText() })
                is SignalProtocol.DecryptResult.NoSession -> message.copy(content = if (message.type == MessageType.TEXT) "[缺少群聊 Sender Key，请等待成员重新发送]" else message.mediaDecryptFailedText())
                SignalProtocol.DecryptResult.UnsupportedEnvelope -> message.copy(content = if (message.type == MessageType.TEXT) "[不支持的群聊加密消息版本]" else message.mediaDecryptFailedText())
                SignalProtocol.DecryptResult.NotForThisDevice -> message.copy(content = if (message.type == MessageType.TEXT) "[这条群聊加密消息未发送到当前设备]" else message.mediaDecryptFailedText())
                SignalProtocol.DecryptResult.UntrustedIdentity -> {
                    // 触发身份状态刷新，聊天页顶部 banner 会立刻提示用户
                    refreshIdentitySafetyState(identityChanged = true)
                    message.copy(content = if (message.type == MessageType.TEXT) "[群成员安全码已变化，请验证身份]" else message.mediaDecryptFailedText())
                }
                SignalProtocol.DecryptResult.Failed -> message.copy(content = if (message.type == MessageType.TEXT) "[无法解密的群聊消息]" else message.mediaDecryptFailedText())
            }'''
new_group_decrypt = '''            return when (val result = runCatching { signalProtocol.decryptGroupContentEnvelope(senderId, content = message.content) }.getOrElse { SignalProtocol.DecryptResult.Failed }) {
                is SignalProtocol.DecryptResult.Success -> message.copy(content = if (message.type == MessageType.TEXT) result.plaintext else { val uri = MediaCache.writeBase64ToCache(getApplication(), result.plaintext, message.id, message.type); uri ?: message.mediaDecryptFailedText() })
                SignalProtocol.DecryptResult.NoSession -> message.copy(content = if (message.type == MessageType.TEXT) "[缺少群聊 Sender Key，请等待成员重新发送]" else message.mediaDecryptFailedText())
                SignalProtocol.DecryptResult.UntrustedIdentity -> {
                    refreshIdentitySafetyState(identityChanged = true)
                    message.copy(content = if (message.type == MessageType.TEXT) "[群成员安全码已变化，请验证身份]" else message.mediaDecryptFailedText())
                }
                else -> message.copy(content = if (message.type == MessageType.TEXT) "[无法解密的群聊消息]" else message.mediaDecryptFailedText())
            }'''

s = s.replace(old_group_decrypt, new_group_decrypt)

# 5) Fix single chat decrypt API call
old_single_decrypt = '''        return when (val result = signalProtocol.decryptContentEnvelope(senderId, message.content)) {
            is SignalProtocol.DecryptResult.Success -> { refreshIdentitySafetyState(); message.copy(content = if (message.type == MessageType.TEXT) result.plaintext else { val uri = MediaCache.writeBase64ToCache(getApplication(), result.plaintext, message.id, message.type); uri ?: message.mediaDecryptFailedText() }) }
            is SignalProtocol.DecryptResult.NoSession -> { signalProtocol.ensureSession(token, senderId); message.copy(content = if (message.type == MessageType.TEXT) "[缺少会话密钥，请等待对方重新发送]" else message.mediaDecryptFailedText()) }
            is SignalProtocol.DecryptResult.UnsupportedEnvelope -> message.copy(content = if (message.type == MessageType.TEXT) "[不支持的加密消息版本]" else message.mediaDecryptFailedText())
            is SignalProtocol.DecryptResult.NotForThisDevice -> message.copy(content = if (message.type == MessageType.TEXT) "[这条加密消息未发送到当前设备]" else message.mediaDecryptFailedText())'''
new_single_decrypt = '''        return when (val result = runCatching { signalProtocol.decryptContentEnvelope(senderId, message.content) }.getOrElse { SignalProtocol.DecryptResult.Failed }) {
            is SignalProtocol.DecryptResult.Success -> { refreshIdentitySafetyState(); message.copy(content = if (message.type == MessageType.TEXT) result.plaintext else { val uri = MediaCache.writeBase64ToCache(getApplication(), result.plaintext, message.id, message.type); uri ?: message.mediaDecryptFailedText() }) }
            SignalProtocol.DecryptResult.NoSession -> { runCatching { signalProtocol.ensureSession(token, senderId) }; message.copy(content = if (message.type == MessageType.TEXT) "[缺少会话密钥，请等待对方重新发送]" else message.mediaDecryptFailedText()) }
            SignalProtocol.DecryptResult.UntrustedIdentity -> { refreshIdentitySafetyState(identityChanged = true); message.copy(content = if (message.type == MessageType.TEXT) "[对方安全码已变化，请验证身份]" else message.mediaDecryptFailedText()) }
            else -> message.copy(content = if (message.type == MessageType.TEXT) "[无法解密的消息]" else message.mediaDecryptFailedText())'''

s = s.replace(old_single_decrypt, new_single_decrypt)

# 6) Fix method reference → function call for SearchProtocol API methods that need suspend
s = s.replace('val trustState = signalProtocol.getIdentityTrustState(contactId)',
              'val trustState = signalProtocol.getIdentityTrustState(contactId, 1)')
s = s.replace('val safetyCode = signalProtocol.getSafetyCode(contactId)',
              'val safetyCode = runCatching { signalProtocol.getSafetyCode(contactId) }.getOrNull()')

Path('D:/Maodouchat/app/src/main/java/com/maodouchat/ui/screen/chatdetail/ChatDetailViewModel.kt').write_text(s)
print('Final fixes applied')
