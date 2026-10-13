package com.maodouchat.crypto

import android.util.Base64
import android.util.Log
import com.maodouchat.core.crypto.DecryptResult
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import kotlin.concurrent.withLock

// 内容信封解密簇：信封分派、底层会话解密、失败分类与解密重试追踪。
// 从 SignalDirectCipher 纯搬移，零行为改动。
internal class SignalContentEnvelopeDecryptor(
    private val context: SignalProtocolContext
) {
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
        } catch (e: Exception) {
            classifyDecryptFailure("decryptContentEnvelope", e)
        } catch (e: AssertionError) {
            // libsignal 会把意外的 checked exception 包成 AssertionError（extends Error，
            // 上面那条 catch (e: Exception) 抓不到它）；群入口上已实测复现过，这里保留对齐。
            classifyDecryptFailure("decryptContentEnvelope", e)
        }
        rememberDecryptOutcome(senderId, fingerprint, result)
        return result
    }

    // 三个解密入口共用失败分类：各写一份 catch 链会漂移，这里收一处。
    // 不用 catch(Throwable) 一把梭，是不想把 OutOfMemoryError 之类也吞成 Failed。
    private fun classifyDecryptFailure(operation: String, error: Throwable): DecryptResult = when (error) {
        is NoSessionException -> DecryptResult.NoSession
        is org.signal.libsignal.protocol.UntrustedIdentityException -> DecryptResult.UntrustedIdentity
        is org.signal.libsignal.protocol.DuplicateMessageException -> DecryptResult.Duplicate
        is InvalidMessageException -> {
            Log.w(SignalProtocolConstants.TAG, "$operation invalid message", error)
            DecryptResult.Failed
        }
        is kotlinx.serialization.SerializationException -> {
            Log.w(SignalProtocolConstants.TAG, "$operation malformed envelope", error)
            DecryptResult.UnsupportedEnvelope
        }
        is IllegalArgumentException -> {
            Log.w(SignalProtocolConstants.TAG, "$operation malformed encoding", error)
            DecryptResult.UnsupportedEnvelope
        }
        is AssertionError -> {
            Log.w(SignalProtocolConstants.TAG, "$operation native assertion", error)
            DecryptResult.Failed
        }
        else -> {
            Log.w(SignalProtocolConstants.TAG, "$operation unexpected failure", error)
            DecryptResult.Failed
        }
    }

    fun decryptParsedMultiDeviceEnvelope(
        senderId: String,
        envelope: MultiDeviceEnvelopePolicy.ParsedEnvelope
    ): DecryptResult {
        val entry = MultiDeviceEnvelopePolicy.selectEntry(envelope, context.currentUserId, context.localDeviceId)
            ?: return DecryptResult.NotForThisDevice
        return try {
            val ciphertext = Base64.decode(entry.ciphertext, Base64.NO_WRAP)
            DecryptResult.Success(
                decryptMessage(
                    senderId = senderId,
                    ciphertext = ciphertext,
                    deviceId = envelope.senderDeviceId,
                    ciphertextType = entry.ciphertextType
                )
            )
        } catch (e: Exception) {
            classifyDecryptFailure("decryptParsedMultiDeviceEnvelope", e)
        } catch (e: AssertionError) {
            // libsignal 的 FilterExceptions 会把意外的 checked exception 包成 AssertionError，
            // 而它 extends Error，所以上面那条 catch (e: Exception) 抓不到。
            classifyDecryptFailure("decryptParsedMultiDeviceEnvelope", e)
        }
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
    } catch (error: Exception) {
        classifyDecryptFailure("decryptDeviceCiphertext", error)
    } catch (error: AssertionError) {
        classifyDecryptFailure("decryptDeviceCiphertext", error)
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
