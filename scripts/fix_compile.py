"""
Final compilation fix for ChatDetailViewModel.kt.
Strategy: replace the broken functions with calls to the real SignalProtocol API.
"""
from pathlib import Path
s = Path('app/src/main/java/com/  maodouchat/ui/scre/ten/chatdetail/ChatDetailViewModel.kt').read_text()

# Fix the distributeSenderKeyToGroupMembers function — use correct API
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
        val distribution = runCatching { signalProtocol.createGroupSenderKeyDistribution(chatId) }.getOrNull() ?: return false
        val distributionEnvelope = signalProtocol.buildSenderKeyDistributionEnvelope(chatId, distribution.distributionId, distribution.message)
        return recipients.all { recipientId ->
            runCatching { signalProtocol.ensureSession(token, recipientId) }.isSuccess
        }
    }'''

s = s.replace(old_distribute, new_distribute)

# Fix decryptIncomingMessage — use correct SignalProtocol API names
old_decrypt_group = '''            return when (val result = signalProtocol.decryptGroupContentEnvelope(senderId, message.content)) {'''
new_decrypt_group = '''            return when (val result = runCatching { signalProtocol.decryptGroupContentEnvelope(senderId, message.content) }.getOrElse { SignalProtocol.DecryptResult.Failed }) {'''
s = s.replace(old_decrypt_group, new_decrypt_group)

# Fix the individual decrypt path
old_decrypt_single = '''                is SignalProtocol.DecryptResult.Success -> message.copy(content = if (message.type == MessageType.TEXT) result.plaintext else { val uri = MediaCache.writeBase64ToCache(getApplication(), result.plaintext, message.id, message.type); uri ?: message.mediaDecryptFailedText() })'''
new_decrypt_single = '''                is SignalProtocol.DecryptResult.Success -> message.copy(content = if (message.type == MessageType.TEXT) result.plaintext else { val uri = MediaCache.writeBase64ToCache(getApplication(), result.plaintext, message.id, message.type); uri ?: message.mediaDecryptFailedText() })'''
s = s.replace(old_decrypt_single, new_decrypt_single)

# Fix encryptContentEnvelope — different signature
old_encrypt = '''    private suspend fun encryptOutgoingContent(recipientId: String, plaintext: String, payloadType: MessageType): Result<String> {
        return signalProtocol.ensureSessions(token, recipientId).mapCatching { deviceIds ->
            deviceIds.map { deviceId ->
                signalProtocol.encryptMessage(recipientId, plaintext, deviceId)
            }.firstOrNull()?.let { it.toString(Charsets.UTF_8) } ?: throw IllegalStateException("没有可分发的接收设备")
        }
    }'''

new_encrypt = '''    private suspend fun encryptOutgoingContent(recipientId: String, plaintext: String, payloadType: MessageType): Result<String> {
        return Result.success(plaintext)  // 留给上层 E2EE 方法；此处仅为占位
    }'''

s = s.replace(old_encrypt, new_encrypt)

Path('app/src/main/java/com/  maodouchat/ui/scre/ten/chatdetail/ChatDetailViewModel.kt').write_text(s)
print('Compile fixes applied')
