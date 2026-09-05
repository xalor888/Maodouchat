package com.maodouchat.crypto

import android.util.Base64
import android.util.Log
import com.maodouchat.core.crypto.DecryptResult
import com.maodouchat.core.crypto.GroupMessageCipher
import com.maodouchat.core.model.ConversationId
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import java.util.UUID
import kotlin.concurrent.withLock

/**
 * Signal 群消息密码学组件（M04 解耦）。
 * 遵循 [GroupMessageCipher] 契约。
 * 负责群 Sender Key 消息信封的加密、解密与分发信封的安装处理。
 */
class SignalGroupCipher internal constructor(
    private val context: SignalProtocolContext,
    private val groupSenderKeyManager: SignalGroupSenderKeyManager,
    private val directCipher: SignalDirectCipher
) : GroupMessageCipher {

    override suspend fun encrypt(conversationId: ConversationId, plaintext: String, epoch: Long): Result<String> =
        encryptGroupContentEnvelope(conversationId.value, plaintext, "text", epoch)

    override suspend fun decrypt(senderAccountId: String, ciphertext: String): Result<String> {
        return when (val outcome = decryptGroupContentEnvelope(senderAccountId, ciphertext)) {
            is DecryptResult.Success -> Result.success(outcome.plaintext)
            else -> Result.failure(IllegalStateException("Group decryption failed: $outcome"))
        }
    }

    fun processSenderKeyDistributionEnvelope(
        senderId: String,
        content: String,
        expectedGroupId: String? = null,
        currentEpoch: Long? = null
    ): SenderKeyDistOutcome {
        val fingerprint = DecryptFailurePolicy.envelopeFingerprint(senderId, content)
        if (context.decryptRetryTracker.isTerminal(fingerprint) ||
            DecryptFailurePolicy.shouldSkipCryptoAttempt(context.decryptRetryTracker.failureCount(fingerprint))
        ) {
            return SenderKeyDistOutcome.Skipped
        }
        val owner = context.currentUserId
        if (owner.isNullOrBlank() || !context.isLocalCryptoReadyFor(owner)) {
            return SenderKeyDistOutcome.Failed
        }
        val outcome = context.cryptoLock.withLock {
            runCatching {
                val envelope = runCatching {
                    SignalProtocolConstants.json.decodeFromString(SenderKeyDistributionEnvelope.serializer(), content)
                }.getOrElse { return@runCatching SenderKeyDistOutcome.Skipped }
                if (envelope.version != SignalProtocolConstants.SENDER_KEY_DISTRIBUTION_VERSION ||
                    envelope.algorithm != SignalProtocolConstants.ALGORITHM_SENDER_KEY_DISTRIBUTION
                ) {
                    return@runCatching SenderKeyDistOutcome.Skipped
                }
                if (expectedGroupId != null && envelope.groupId != expectedGroupId) {
                    return@runCatching SenderKeyDistOutcome.Skipped
                }
                if (currentEpoch != null && currentEpoch > 0L && envelope.epoch > 0L && envelope.epoch < currentEpoch) {
                    return@runCatching SenderKeyDistOutcome.Skipped
                }
                val senderAddress = SignalProtocolAddress(senderId, envelope.senderDeviceId)
                val distributionMessage = SenderKeyDistributionMessage(Base64.decode(envelope.distributionMessage, Base64.NO_WRAP))
                GroupSessionBuilder(context.protocolStore).process(senderAddress, distributionMessage)
                context.throwIfSignalStorePersistenceFailed()
                SenderKeyDistOutcome.Installed
            }.getOrElse(SenderKeyDistributionFailurePolicy::outcomeFor)
        }
        return when (outcome) {
            SenderKeyDistOutcome.Installed -> {
                directCipher.clearDecryptRetryStateForSender(senderId)
                outcome
            }
            SenderKeyDistOutcome.Skipped -> {
                context.decryptRetryTracker.markTerminal(fingerprint)
                outcome
            }
            SenderKeyDistOutcome.Failed -> {
                context.decryptRetryTracker.recordCryptoFailure(senderId, fingerprint)
                if (DecryptFailurePolicy.shouldSkipCryptoAttempt(context.decryptRetryTracker.failureCount(fingerprint))) {
                    SenderKeyDistOutcome.Skipped
                } else {
                    outcome
                }
            }
        }
    }

    fun encryptGroupTextEnvelope(groupId: String, plaintext: String, payloadType: String, epoch: Long = 0): Result<String> {
        return encryptGroupContentEnvelope(groupId, plaintext, payloadType, epoch)
    }

    fun encryptGroupContentEnvelope(groupId: String, plaintext: String, payloadType: String, epoch: Long = 0): Result<String> {
        val owner = context.currentUserId
        if (owner.isNullOrBlank() || !context.isLocalCryptoReadyFor(owner)) {
            return Result.failure(IllegalStateException("signal_not_initialized"))
        }
        return context.cryptoLock.withLock {
            runCatching {
                require(epoch > 0L) { "group_epoch_unknown" }
                val distributionId = groupSenderKeyManager.requireExistingGroupDistributionId(groupId, epoch)
                val senderAddress = SignalProtocolAddress(
                    context.currentUserId ?: SignalProtocolConstants.ANONYMOUS_ACCOUNT_ID,
                    context.localDeviceId
                )
                val cipher = GroupCipher(context.protocolStore, senderAddress)
                val ciphertext = cipher.encrypt(UUID.fromString(distributionId), plaintext.toByteArray(Charsets.UTF_8))
                context.throwIfSignalStorePersistenceFailed()
                SignalProtocolConstants.json.encodeToString(
                    SenderKeyMessageEnvelope.serializer(),
                    SenderKeyMessageEnvelope(
                        groupId = groupId,
                        epoch = epoch,
                        senderDeviceId = context.localDeviceId,
                        distributionId = distributionId,
                        payloadType = payloadType,
                        ciphertext = Base64.encodeToString(ciphertext.serialize(), Base64.NO_WRAP)
                    )
                )
            }
        }
    }

    fun decryptGroupContentEnvelope(
        senderId: String,
        content: String,
        expectedGroupId: String? = null,
        currentEpoch: Long? = null
    ): DecryptResult {
        val owner = context.currentUserId
        if (owner.isNullOrBlank() || !context.isLocalCryptoReadyFor(owner)) return DecryptResult.Failed
        val fingerprint = DecryptFailurePolicy.envelopeFingerprint(senderId, content)
        if (context.decryptRetryTracker.isTerminal(fingerprint)) {
            return DecryptResult.UnsupportedEnvelope
        }
        if (DecryptFailurePolicy.shouldSkipCryptoAttempt(context.decryptRetryTracker.failureCount(fingerprint))) {
            return DecryptResult.Failed
        }
        val result = context.cryptoLock.withLock {
            try {
                val envelope = SignalProtocolConstants.json.decodeFromString(SenderKeyMessageEnvelope.serializer(), content)
                if (envelope.version != SignalProtocolConstants.SENDER_KEY_ENVELOPE_VERSION ||
                    envelope.algorithm != SignalProtocolConstants.ALGORITHM_SENDER_KEY
                ) {
                    return@withLock DecryptResult.UnsupportedEnvelope
                }
                if (expectedGroupId != null && envelope.groupId != expectedGroupId) {
                    return@withLock DecryptResult.UnsupportedEnvelope
                }
                if (currentEpoch != null && currentEpoch > 0L && envelope.epoch > currentEpoch) {
                    return@withLock DecryptResult.FutureEpoch
                }
                val senderAddress = SignalProtocolAddress(senderId, envelope.senderDeviceId)
                val plaintext = GroupCipher(context.protocolStore, senderAddress)
                    .decrypt(Base64.decode(envelope.ciphertext, Base64.NO_WRAP))
                context.throwIfSignalStorePersistenceFailed()
                DecryptResult.Success(String(plaintext, Charsets.UTF_8))
            } catch (e: NoSessionException) {
                DecryptResult.NoSession
            } catch (e: org.signal.libsignal.protocol.UntrustedIdentityException) {
                DecryptResult.UntrustedIdentity
            } catch (e: org.signal.libsignal.protocol.DuplicateMessageException) {
                DecryptResult.Duplicate
            } catch (e: InvalidMessageException) {
                Log.w(SignalProtocolConstants.TAG, "decryptGroupContentEnvelope invalid message", e)
                DecryptResult.Failed
            } catch (e: kotlinx.serialization.SerializationException) {
                Log.w(SignalProtocolConstants.TAG, "decryptGroupContentEnvelope malformed envelope", e)
                DecryptResult.UnsupportedEnvelope
            } catch (e: IllegalArgumentException) {
                Log.w(SignalProtocolConstants.TAG, "decryptGroupContentEnvelope malformed encoding", e)
                DecryptResult.UnsupportedEnvelope
            } catch (e: Exception) {
                Log.w(SignalProtocolConstants.TAG, "decryptGroupContentEnvelope unexpected failure", e)
                DecryptResult.Failed
            }
        }
        directCipher.rememberDecryptOutcome(senderId, fingerprint, result)
        return result
    }
}
