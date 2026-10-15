package com.maodouchat.crypto

import android.util.Base64
import kotlinx.serialization.encodeToString
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.CiphertextMessage
import kotlin.concurrent.withLock

// 内容信封加密簇：单设备/多设备/多接收者信封组装与底层会话加密。
// 从 SignalDirectCipher 纯搬移，零行为改动。
internal class SignalContentEnvelopeEncryptor(
    private val context: SignalProtocolContext,
    private val sessionManager: SignalSessionManager
) {
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
            val failuresByTarget = mutableMapOf<Target, Throwable>()
            val requiredRecipientSet = requiredRecipientIds
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSet()
            val requiredTargets = mutableSetOf<Target>()
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
                            Target(recipientId, it)
                        }
                    }
                    coverage.failuresByDevice.forEach { (deviceId, error) ->
                        val target = Target(recipientId, deviceId)
                        failuresByTarget[target] = error
                        sessionFailures.putIfAbsent(recipientId, error)
                    }
                    establishedDeviceIds
                        .mapNotNull { deviceId ->
                            val target = Target(recipientId, deviceId)
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
            val coveredTargets = targets.map { Target(it.userId, it.deviceId) }
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
}
