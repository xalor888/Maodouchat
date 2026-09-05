package com.maodouchat.crypto

import android.util.Base64
import android.util.Log
import com.maodouchat.core.crypto.DecryptResult
import com.maodouchat.core.crypto.DirectMessageCipher
import kotlinx.serialization.encodeToString
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import kotlin.concurrent.withLock

data class EncryptedPayload(val type: String, val payload: ByteArray)

data class MultiRecipientDeviceTarget(
    val userId: String,
    val deviceId: Int
)

data class DeviceCiphertext(
    val userId: String,
    val deviceId: Int,
    val ciphertextType: String,
    val ciphertext: String,
)

data class MultiRecipientEnvelopePayload(
    val envelope: String,
    val targets: List<MultiRecipientDeviceTarget>,
    val ciphertexts: List<DeviceCiphertext> = emptyList(),
)

/**
 * Signal 直接点对点消息与多设备信封加解密器（M03 解耦）。
 * 遵循 [DirectMessageCipher] 端口。
 * 负责 1:1 双向棘轮会话的报文加解密、多接收者扇出、会话恢复追踪及失败重试控制。
 */
class SignalDirectCipher internal constructor(
    private val context: SignalProtocolContext,
    private val sessionManager: SignalSessionManager,
    private val deviceIdCoordinator: SignalDeviceIdCoordinator,
    private val envelopeCodec: SignalEnvelopeCodec
) : DirectMessageCipher {

    override suspend fun encryptTextEnvelope(token: String, recipientId: String, plaintext: String): Result<String> {
        return encryptContentEnvelope(token, recipientId, plaintext, SignalProtocolConstants.PAYLOAD_TEXT)
    }

    suspend fun encryptTextEnvelopes(token: String, recipientId: String, plaintext: String): Result<List<String>> {
        return encryptContentEnvelopes(token, recipientId, plaintext, SignalProtocolConstants.PAYLOAD_TEXT)
    }

    suspend fun encryptMultiDeviceTextEnvelope(token: String, recipientId: String, plaintext: String): Result<String> {
        return encryptMultiDeviceContentEnvelope(token, recipientId, plaintext, SignalProtocolConstants.PAYLOAD_TEXT)
    }

    suspend fun encryptSyncedTextEnvelope(token: String, recipientId: String, plaintext: String): Result<String> {
        return encryptSyncedContentEnvelope(token, recipientId, plaintext, SignalProtocolConstants.PAYLOAD_TEXT)
    }

    suspend fun encryptSyncedContentEnvelope(
        token: String,
        recipientId: String,
        plaintext: String,
        payloadType: String
    ): Result<String> {
        val peerId = MultiRecipientCoveragePolicy.normalizeRequiredRecipientId(recipientId)
            ?: return Result.failure(NoRecipientDevicesException())
        val recipients = listOfNotNull(
            peerId,
            context.currentUserId?.takeIf { it.isNotBlank() }
        ).distinct()
        return encryptMultiRecipientContentEnvelope(
            token = token,
            recipientIds = recipients,
            plaintext = plaintext,
            payloadType = payloadType,
            includeCurrentUserDevices = true,
            requiredRecipientIds = setOf(peerId)
        )
    }

    suspend fun encryptMultiDeviceContentEnvelope(
        token: String,
        recipientId: String,
        plaintext: String,
        payloadType: String
    ): Result<String> {
        return try {
            val deviceIds = sessionManager.ensureSessions(token, recipientId).getOrThrow()
            val entries = deviceIds.map { deviceId ->
                val cipherResult = encryptMessage(recipientId, plaintext, deviceId)
                MultiDeviceMessageEntry(
                    recipientDeviceId = deviceId,
                    ciphertextType = cipherResult.type,
                    ciphertext = Base64.encodeToString(cipherResult.payload, Base64.NO_WRAP)
                )
            }
            Result.success(
                SignalProtocolConstants.json.encodeToString(
                    MultiDeviceMessageEnvelope(
                        version = SignalProtocolConstants.MULTI_DEVICE_ENVELOPE_VERSION,
                        algorithm = SignalProtocolConstants.ALGORITHM_SIGNAL_MULTI_DEVICE,
                        senderDeviceId = context.localDeviceId,
                        payloadType = payloadType,
                        entries = entries
                    )
                )
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun encryptMultiRecipientContentEnvelope(
        token: String,
        recipientIds: List<String>,
        plaintext: String,
        payloadType: String,
        includeCurrentUserDevices: Boolean = false,
        requiredRecipientIds: Set<String> = emptySet()
    ): Result<String> {
        return encryptMultiRecipientContentEnvelopeWithTargets(
            token = token,
            recipientIds = recipientIds,
            plaintext = plaintext,
            payloadType = payloadType,
            includeCurrentUserDevices = includeCurrentUserDevices,
            requiredRecipientIds = requiredRecipientIds
        ).map { it.envelope }
    }

    suspend fun encryptMultiRecipientContentEnvelopeWithTargets(
        token: String,
        recipientIds: List<String>,
        plaintext: String,
        payloadType: String,
        includeCurrentUserDevices: Boolean = false,
        requiredRecipientIds: Set<String> = emptySet()
    ): Result<MultiRecipientEnvelopePayload> {
        return try {
            val targets = mutableListOf<MultiRecipientDeviceTarget>()
            val sessionFailures = mutableMapOf<String, Throwable>()
            val failuresByTarget = mutableMapOf<MultiRecipientCoveragePolicy.Target, Throwable>()
            val requiredRecipientSet = requiredRecipientIds
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSet()
            val requiredTargets = mutableSetOf<MultiRecipientCoveragePolicy.Target>()
            val entries = recipientIds
                .filter {
                    it.isNotBlank() &&
                        (includeCurrentUserDevices || it != context.currentUserId) &&
                        !com.maodouchat.bot.BotCommandPolicy.isBotUserId(it)
                }
                .distinct()
                .flatMap { recipientId ->
                    val coverage = sessionManager.ensureSessionsDetailed(token, recipientId).getOrElse { error ->
                        sessionFailures.putIfAbsent(recipientId, error)
                        null
                    }
                    if (coverage == null) return@flatMap emptyList()

                    val candidateDeviceIds = coverage.candidateDeviceIds
                    val establishedDeviceIds = coverage.establishedDeviceIds
                    if (recipientId in requiredRecipientSet) {
                        requiredTargets += candidateDeviceIds.map {
                            MultiRecipientCoveragePolicy.Target(recipientId, it)
                        }
                    }
                    coverage.failuresByDevice.forEach { (deviceId, error) ->
                        val target = MultiRecipientCoveragePolicy.Target(recipientId, deviceId)
                        failuresByTarget[target] = error
                        sessionFailures.putIfAbsent(recipientId, error)
                    }
                    establishedDeviceIds
                        .mapNotNull { deviceId ->
                            val target = MultiRecipientCoveragePolicy.Target(recipientId, deviceId)
                            try {
                                val cipherResult = encryptMessage(recipientId, plaintext, deviceId)
                                targets += MultiRecipientDeviceTarget(recipientId, deviceId)
                                MultiDeviceMessageEntry(
                                    recipientUserId = recipientId,
                                    recipientDeviceId = deviceId,
                                    ciphertextType = cipherResult.type,
                                    ciphertext = Base64.encodeToString(cipherResult.payload, Base64.NO_WRAP)
                                )
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                failuresByTarget[target] = error
                                sessionFailures.putIfAbsent(recipientId, error)
                                null
                            }
                        }
                }
            val coveredTargets = targets.map { MultiRecipientCoveragePolicy.Target(it.userId, it.deviceId) }
            if (!MultiRecipientCoveragePolicy.requiredTargetsCovered(requiredTargets, coveredTargets) ||
                !MultiRecipientCoveragePolicy.requiredRecipientsCovered(requiredRecipientIds, coveredTargets)
            ) {
                MultiRecipientCoveragePolicy.transientFailureForMissingTargets(
                    requiredTargets = requiredTargets,
                    targets = coveredTargets,
                    failuresByTarget = failuresByTarget,
                )?.let { throw it }
                MultiRecipientCoveragePolicy.transientFailureForMissingRecipients(
                    requiredRecipientIds = requiredRecipientIds,
                    targets = coveredTargets,
                    failuresByRecipient = sessionFailures,
                )?.let { throw it }
                throw NoRecipientDevicesException()
            }
            if (entries.isEmpty()) {
                val transientFailure = sequenceOf(
                    failuresByTarget.values.asSequence(),
                    sessionFailures.values.asSequence(),
                ).flatten().firstOrNull(MultiRecipientCoveragePolicy::isTransient)
                if (transientFailure != null) {
                    throw TransientCoverageException(
                        message = "all recipient session targets are temporarily unavailable",
                        cause = transientFailure,
                    )
                }
                throw NoRecipientDevicesException()
            }
            val envelope = SignalProtocolConstants.json.encodeToString(
                MultiDeviceMessageEnvelope(
                    version = SignalProtocolConstants.MULTI_DEVICE_ENVELOPE_VERSION,
                    algorithm = SignalProtocolConstants.ALGORITHM_SIGNAL_MULTI_DEVICE,
                    senderDeviceId = context.localDeviceId,
                    payloadType = payloadType,
                    entries = entries
                )
            )
            Result.success(
                MultiRecipientEnvelopePayload(
                    envelope = envelope,
                    targets = targets,
                    ciphertexts = entries.map { entry ->
                        DeviceCiphertext(
                            userId = requireNotNull(entry.recipientUserId),
                            deviceId = entry.recipientDeviceId,
                            ciphertextType = requireNotNull(entry.ciphertextType),
                            ciphertext = entry.ciphertext,
                        )
                    },
                ),
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun encryptContentEnvelopes(
        token: String,
        recipientId: String,
        plaintext: String,
        payloadType: String
    ): Result<List<String>> {
        return try {
            val deviceIds = sessionManager.ensureSessions(token, recipientId).getOrThrow()
            Result.success(
                deviceIds.map { deviceId ->
                    encryptContentEnvelope(token, recipientId, plaintext, payloadType, deviceId).getOrThrow()
                }
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun encryptContentEnvelope(
        token: String,
        recipientId: String,
        plaintext: String,
        payloadType: String,
        deviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID
    ): Result<String> {
        return try {
            val resolvedDeviceId = sessionManager.resolveSessionDeviceId(token, recipientId, deviceId)
                ?: return Result.failure(NoRecipientDevicesException())
            sessionManager.ensureSession(token, recipientId, resolvedDeviceId).getOrThrow()
            val cipherResult = encryptMessage(recipientId, plaintext, resolvedDeviceId)
            Result.success(
                SignalProtocolConstants.json.encodeToString(
                    EncryptedMessageEnvelope(
                        version = SignalProtocolConstants.ENVELOPE_VERSION,
                        algorithm = SignalProtocolConstants.ALGORITHM_SIGNAL,
                        senderDeviceId = context.localDeviceId,
                        recipientDeviceId = resolvedDeviceId,
                        ciphertextType = cipherResult.type,
                        payloadType = payloadType,
                        ciphertext = Base64.encodeToString(cipherResult.payload, Base64.NO_WRAP)
                    )
                )
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    fun encryptMessage(recipientId: String, plaintext: String, deviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID): EncryptedPayload {
        val owner = context.currentUserId
        if (owner.isNullOrBlank() || !context.isLocalCryptoReadyFor(owner)) {
            throw IllegalStateException("signal_not_initialized")
        }
        context.cryptoLock.withLock {
            val address = SignalProtocolAddress(recipientId, deviceId)
            val cipher = SessionCipher(context.protocolStore, address)
            val ciphertext = cipher.encrypt(plaintext.toByteArray(Charsets.UTF_8))
            context.throwIfSignalStorePersistenceFailed()
            val type = when (ciphertext.type) {
                CiphertextMessage.PREKEY_TYPE -> SignalProtocolConstants.CIPHERTEXT_TYPE_PREKEY
                CiphertextMessage.WHISPER_TYPE -> SignalProtocolConstants.CIPHERTEXT_TYPE_SIGNAL
                else -> SignalProtocolConstants.CIPHERTEXT_TYPE_UNKNOWN
            }
            return EncryptedPayload(type, ciphertext.serialize())
        }
    }

    override fun decryptTextEnvelope(senderId: String, content: String): DecryptResult {
        return decryptContentEnvelope(senderId, content)
    }

    fun decryptContentEnvelope(senderId: String, content: String): DecryptResult {
        val owner = context.currentUserId
        if (owner.isNullOrBlank() || !context.isLocalCryptoReadyFor(owner)) return DecryptResult.Failed
        val fingerprint = DecryptFailurePolicy.envelopeFingerprint(senderId, content)
        if (context.decryptRetryTracker.isTerminal(fingerprint)) {
            return DecryptResult.UnsupportedEnvelope
        }
        if (DecryptFailurePolicy.shouldSkipCryptoAttempt(context.decryptRetryTracker.failureCount(fingerprint))) {
            return DecryptResult.Failed
        }
        val result = try {
            val parsedMulti = MultiDeviceEnvelopePolicy.parse(content)
            if (parsedMulti != null) {
                decryptParsedMultiDeviceEnvelope(senderId, parsedMulti)
            } else {
                val envelope = SignalProtocolConstants.json.decodeFromString(EncryptedMessageEnvelope.serializer(), content)
                when (envelope.version) {
                    1 -> DecryptResult.Success(
                        decryptMessage(senderId, Base64.decode(envelope.ciphertext, Base64.NO_WRAP), envelope.senderDeviceId)
                    )
                    SignalProtocolConstants.ENVELOPE_VERSION -> {
                        if (envelope.algorithm != SignalProtocolConstants.ALGORITHM_SIGNAL) {
                            DecryptResult.UnsupportedEnvelope
                        } else {
                            val ciphertext = Base64.decode(envelope.ciphertext, Base64.NO_WRAP)
                            DecryptResult.Success(
                                decryptMessage(
                                    senderId = senderId,
                                    ciphertext = ciphertext,
                                    deviceId = envelope.senderDeviceId,
                                    ciphertextType = envelope.ciphertextType
                                )
                            )
                        }
                    }
                    else -> DecryptResult.UnsupportedEnvelope
                }
            }
        } catch (e: NoSessionException) {
            DecryptResult.NoSession
        } catch (e: org.signal.libsignal.protocol.UntrustedIdentityException) {
            DecryptResult.UntrustedIdentity
        } catch (e: org.signal.libsignal.protocol.DuplicateMessageException) {
            DecryptResult.Duplicate
        } catch (e: InvalidMessageException) {
            Log.w(SignalProtocolConstants.TAG, "decryptContentEnvelope invalid message", e)
            DecryptResult.Failed
        } catch (e: kotlinx.serialization.SerializationException) {
            Log.w(SignalProtocolConstants.TAG, "decryptContentEnvelope malformed envelope", e)
            DecryptResult.UnsupportedEnvelope
        } catch (e: IllegalArgumentException) {
            Log.w(SignalProtocolConstants.TAG, "decryptContentEnvelope malformed encoding", e)
            DecryptResult.UnsupportedEnvelope
        } catch (e: Exception) {
            Log.w(SignalProtocolConstants.TAG, "decryptContentEnvelope unexpected failure", e)
            DecryptResult.Failed
        }
        rememberDecryptOutcome(senderId, fingerprint, result)
        return result
    }

    fun decryptParsedMultiDeviceEnvelope(
        senderId: String,
        envelope: MultiDeviceEnvelopePolicy.ParsedEnvelope
    ): DecryptResult {
        val entry = MultiDeviceEnvelopePolicy.selectEntry(envelope, context.currentUserId, context.localDeviceId)
            ?: return DecryptResult.NotForThisDevice
        val ciphertext = Base64.decode(entry.ciphertext, Base64.NO_WRAP)
        return DecryptResult.Success(
            decryptMessage(
                senderId = senderId,
                ciphertext = ciphertext,
                deviceId = envelope.senderDeviceId,
                ciphertextType = entry.ciphertextType
            )
        )
    }

    fun decryptMessage(
        senderId: String,
        ciphertext: ByteArray,
        deviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID,
        ciphertextType: String? = null
    ): String {
        val owner = context.currentUserId
        if (owner.isNullOrBlank() || !context.isLocalCryptoReadyFor(owner)) {
            throw IllegalStateException("signal_not_initialized")
        }
        context.cryptoLock.withLock {
            val address = SignalProtocolAddress(senderId, deviceId)
            val cipher = SessionCipher(context.protocolStore, address)

            val plaintext = when (ciphertextType) {
                SignalProtocolConstants.CIPHERTEXT_TYPE_PREKEY -> String(cipher.decrypt(PreKeySignalMessage(ciphertext)), Charsets.UTF_8)
                SignalProtocolConstants.CIPHERTEXT_TYPE_SIGNAL -> String(cipher.decrypt(SignalMessage(ciphertext)), Charsets.UTF_8)
                else -> try {
                    val preKeyMsg = PreKeySignalMessage(ciphertext)
                    String(cipher.decrypt(preKeyMsg), Charsets.UTF_8)
                } catch (e: InvalidMessageException) {
                    try {
                        val signalMsg = SignalMessage(ciphertext)
                        String(cipher.decrypt(signalMsg), Charsets.UTF_8)
                    } catch (e2: NoSessionException) {
                        throw e2
                    }
                }
            }
            context.throwIfSignalStorePersistenceFailed()
            return plaintext
        }
    }

    fun decryptDeviceCiphertext(
        senderId: String,
        senderDeviceId: Int,
        ciphertextType: String,
        ciphertext: String,
    ): DecryptResult = try {
        DecryptResult.Success(
            decryptMessage(
                senderId = senderId,
                ciphertext = Base64.decode(ciphertext, Base64.NO_WRAP),
                deviceId = senderDeviceId,
                ciphertextType = ciphertextType,
            ),
        )
    } catch (error: NoSessionException) {
        DecryptResult.NoSession
    } catch (error: org.signal.libsignal.protocol.UntrustedIdentityException) {
        DecryptResult.UntrustedIdentity
    } catch (error: org.signal.libsignal.protocol.DuplicateMessageException) {
        DecryptResult.Duplicate
    } catch (error: InvalidMessageException) {
        DecryptResult.Failed
    } catch (error: IllegalArgumentException) {
        DecryptResult.UnsupportedEnvelope
    } catch (error: Exception) {
        Log.w(SignalProtocolConstants.TAG, "decryptDeviceCiphertext unexpected failure", error)
        DecryptResult.Failed
    }

    fun shouldAcknowledgeDecrypt(envelopeId: String, result: DecryptResult): Boolean =
        context.decryptRetryTracker.shouldAcknowledge(envelopeId, result)

    fun clearDecryptRetryState(senderId: String, content: String) {
        context.decryptRetryTracker.clear(DecryptFailurePolicy.envelopeFingerprint(senderId, content))
    }

    fun clearDecryptFailure(senderId: String, content: String) {
        clearDecryptRetryState(senderId, content)
    }

    fun clearDecryptRetryStateForSender(senderId: String) {
        context.decryptRetryTracker.clearForSender(senderId)
    }

    fun isDecryptTerminalFailure(senderId: String, content: String): Boolean {
        val fingerprint = DecryptFailurePolicy.envelopeFingerprint(senderId, content)
        return context.decryptRetryTracker.isTerminal(fingerprint)
    }

    fun isDecryptRetryExhausted(senderId: String, content: String): Boolean {
        val fingerprint = DecryptFailurePolicy.envelopeFingerprint(senderId, content)
        return DecryptFailurePolicy.shouldSkipCryptoAttempt(context.decryptRetryTracker.failureCount(fingerprint))
    }

    internal fun rememberDecryptOutcome(senderId: String, fingerprint: String, result: DecryptResult) {
        when (DecryptFailurePolicy.trackingAction(result)) {
            DecryptFailurePolicy.TrackingAction.CLEAR -> context.decryptRetryTracker.clear(fingerprint)
            DecryptFailurePolicy.TrackingAction.RECORD_FAILURE ->
                context.decryptRetryTracker.recordCryptoFailure(senderId, fingerprint)
            DecryptFailurePolicy.TrackingAction.MARK_TERMINAL -> context.decryptRetryTracker.markTerminal(fingerprint)
        }
    }
}
