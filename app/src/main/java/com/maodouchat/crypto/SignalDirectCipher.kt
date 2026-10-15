package com.maodouchat.crypto

import com.maodouchat.core.crypto.DecryptResult
import com.maodouchat.core.crypto.DirectMessageCipher

/**
 * Signal 直接点对点消息与多设备信封加解密器（M03 解耦）。
 * 遵循 [DirectMessageCipher] 端口。
 *
 * 加解密簇分别在 [SignalContentEnvelopeEncryptor]/[SignalContentEnvelopeDecryptor]，
 * 数据模型在 [SignalDirectCipherModels.kt]；这里只留装配与一行委托，对外 API 不变。
 */
class SignalDirectCipher internal constructor(
    private val context: SignalProtocolContext,
    private val sessionManager: SignalSessionManager,
    private val deviceIdCoordinator: SignalDeviceIdCoordinator,
    private val envelopeCodec: SignalEnvelopeCodec
) : DirectMessageCipher {

    private val encryptor = SignalContentEnvelopeEncryptor(context, sessionManager)
    private val decryptor = SignalContentEnvelopeDecryptor(context)

    override suspend fun encryptTextEnvelope(token: String, recipientId: String, plaintext: String): Result<String> {
        return encryptContentEnvelope(token, recipientId, plaintext, SignalProtocolConstants.PAYLOAD_TEXT)
    }

    suspend fun encryptTextEnvelopes(token: String, recipientId: String, plaintext: String): Result<List<String>> =
        encryptor.encryptTextEnvelopes(token, recipientId, plaintext)

    suspend fun encryptMultiDeviceTextEnvelope(token: String, recipientId: String, plaintext: String): Result<String> =
        encryptor.encryptMultiDeviceTextEnvelope(token, recipientId, plaintext)

    suspend fun encryptSyncedTextEnvelope(token: String, recipientId: String, plaintext: String): Result<String> =
        encryptor.encryptSyncedTextEnvelope(token, recipientId, plaintext)

    suspend fun encryptSyncedContentEnvelope(
        token: String,
        recipientId: String,
        plaintext: String,
        payloadType: String
    ): Result<String> = encryptor.encryptSyncedContentEnvelope(token, recipientId, plaintext, payloadType)

    suspend fun encryptMultiDeviceContentEnvelope(
        token: String,
        recipientId: String,
        plaintext: String,
        payloadType: String
    ): Result<String> = encryptor.encryptMultiDeviceContentEnvelope(token, recipientId, plaintext, payloadType)

    suspend fun encryptMultiRecipientContentEnvelope(
        token: String,
        recipientIds: List<String>,
        plaintext: String,
        payloadType: String,
        includeCurrentUserDevices: Boolean = false,
        requiredRecipientIds: Set<String> = emptySet()
    ): Result<String> = encryptor.encryptMultiRecipientContentEnvelope(
        token, recipientIds, plaintext, payloadType, includeCurrentUserDevices, requiredRecipientIds
    )

    suspend fun encryptMultiRecipientContentEnvelopeWithTargets(
        token: String,
        recipientIds: List<String>,
        plaintext: String,
        payloadType: String,
        includeCurrentUserDevices: Boolean = false,
        requiredRecipientIds: Set<String> = emptySet()
    ): Result<MultiRecipientEnvelopePayload> = encryptor.encryptMultiRecipientContentEnvelopeWithTargets(
        token, recipientIds, plaintext, payloadType, includeCurrentUserDevices, requiredRecipientIds
    )

    suspend fun encryptContentEnvelopes(
        token: String,
        recipientId: String,
        plaintext: String,
        payloadType: String
    ): Result<List<String>> = encryptor.encryptContentEnvelopes(token, recipientId, plaintext, payloadType)

    suspend fun encryptContentEnvelope(
        token: String,
        recipientId: String,
        plaintext: String,
        payloadType: String,
        deviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID
    ): Result<String> = encryptor.encryptContentEnvelope(token, recipientId, plaintext, payloadType, deviceId)

    fun encryptMessage(recipientId: String, plaintext: String, deviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID): EncryptedPayload =
        encryptor.encryptMessage(recipientId, plaintext, deviceId)

    override fun decryptTextEnvelope(senderId: String, content: String): DecryptResult {
        return decryptContentEnvelope(senderId, content)
    }

    fun decryptContentEnvelope(senderId: String, content: String): DecryptResult =
        decryptor.decryptContentEnvelope(senderId, content)

    fun decryptParsedMultiDeviceEnvelope(
        senderId: String,
        envelope: ParsedEnvelope
    ): DecryptResult = decryptor.decryptParsedMultiDeviceEnvelope(senderId, envelope)

    fun decryptMessage(
        senderId: String,
        ciphertext: ByteArray,
        deviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID,
        ciphertextType: String? = null
    ): String = decryptor.decryptMessage(senderId, ciphertext, deviceId, ciphertextType)

    fun decryptDeviceCiphertext(
        senderId: String,
        senderDeviceId: Int,
        ciphertextType: String,
        ciphertext: String,
    ): DecryptResult = decryptor.decryptDeviceCiphertext(senderId, senderDeviceId, ciphertextType, ciphertext)

    fun shouldAcknowledgeDecrypt(envelopeId: String, result: DecryptResult): Boolean =
        decryptor.shouldAcknowledgeDecrypt(envelopeId, result)

    fun clearDecryptRetryState(senderId: String, content: String) {
        decryptor.clearDecryptRetryState(senderId, content)
    }

    fun clearDecryptFailure(senderId: String, content: String) {
        decryptor.clearDecryptFailure(senderId, content)
    }

    fun clearDecryptRetryStateForSender(senderId: String) {
        decryptor.clearDecryptRetryStateForSender(senderId)
    }

    fun isDecryptTerminalFailure(senderId: String, content: String): Boolean =
        decryptor.isDecryptTerminalFailure(senderId, content)

    fun isDecryptRetryExhausted(senderId: String, content: String): Boolean =
        decryptor.isDecryptRetryExhausted(senderId, content)

    internal fun rememberDecryptOutcome(senderId: String, fingerprint: String, result: DecryptResult) {
        decryptor.rememberDecryptOutcome(senderId, fingerprint, result)
    }
}
