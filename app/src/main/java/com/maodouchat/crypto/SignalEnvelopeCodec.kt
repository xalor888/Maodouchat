package com.maodouchat.crypto

import android.util.Base64
import com.maodouchat.core.crypto.EnvelopeCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage

/**
 * Signal 信封编解码器（M03 解耦）。
 * 负责 1:1 单设备、1:1 多设备、群 SenderKey 及分发信封的 JSON 结构编码与识别。
 */
class SignalEnvelopeCodec : EnvelopeCodec {

    override fun envelopePayloadType(content: String): String? {
        return MultiDeviceEnvelopePolicy.parse(content)?.payloadType
            ?: runCatching {
                SignalProtocolConstants.json.decodeFromString(EncryptedMessageEnvelope.serializer(), content).payloadType
            }.getOrNull()
    }

    override fun isEncryptedEnvelope(content: String): Boolean {
        if (MultiDeviceEnvelopePolicy.parse(content) != null) return true
        return runCatching {
            SignalProtocolConstants.json.decodeFromString(EncryptedMessageEnvelope.serializer(), content)
        }.getOrNull()?.version?.let { it >= 1 } == true
    }

    override fun isSenderKeyEnvelope(content: String): Boolean {
        return runCatching {
            SignalProtocolConstants.json.decodeFromString(SenderKeyMessageEnvelope.serializer(), content)
        }.getOrNull()?.let { envelope ->
            envelope.version == SignalProtocolConstants.SENDER_KEY_ENVELOPE_VERSION &&
                envelope.algorithm == SignalProtocolConstants.ALGORITHM_SENDER_KEY
        } == true
    }

    fun isSenderKeyDistributionEnvelope(content: String): Boolean {
        return runCatching {
            SignalProtocolConstants.json.decodeFromString(SenderKeyDistributionEnvelope.serializer(), content)
        }.getOrNull()?.let { envelope ->
            envelope.version == SignalProtocolConstants.SENDER_KEY_DISTRIBUTION_VERSION &&
                envelope.algorithm == SignalProtocolConstants.ALGORITHM_SENDER_KEY_DISTRIBUTION
        } == true
    }

    fun buildSenderKeyDistributionEnvelope(
        groupId: String,
        distributionId: String,
        message: SenderKeyDistributionMessage,
        epoch: Long = 0,
        senderDeviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID
    ): String {
        return SignalProtocolConstants.json.encodeToString(
            SenderKeyDistributionEnvelope(
                groupId = groupId,
                epoch = epoch,
                senderDeviceId = senderDeviceId,
                distributionId = distributionId,
                distributionMessage = Base64.encodeToString(message.serialize(), Base64.NO_WRAP)
            )
        )
    }

    fun parseSenderKeyEnvelopeEpoch(content: String): Long? {
        return runCatching {
            val envelope = SignalProtocolConstants.json.decodeFromString(SenderKeyMessageEnvelope.serializer(), content)
            envelope.epoch.takeIf { it > 0L }
        }.getOrNull()
    }

    fun buildUnsupportedSenderKeyEnvelope(
        groupId: String,
        distributionId: String,
        payloadType: String,
        ciphertext: String,
        epoch: Long = 0,
        senderDeviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID
    ): String {
        return SignalProtocolConstants.json.encodeToString(
            SenderKeyMessageEnvelope(
                groupId = groupId,
                epoch = epoch,
                senderDeviceId = senderDeviceId,
                distributionId = distributionId,
                payloadType = payloadType,
                ciphertext = ciphertext
            )
        )
    }

    fun buildEncryptedMessageEnvelope(
        senderDeviceId: Int,
        recipientDeviceId: Int?,
        ciphertextType: String?,
        payloadType: String?,
        ciphertext: String
    ): String {
        return SignalProtocolConstants.json.encodeToString(
            EncryptedMessageEnvelope(
                version = SignalProtocolConstants.ENVELOPE_VERSION,
                algorithm = SignalProtocolConstants.ALGORITHM_SIGNAL,
                senderDeviceId = senderDeviceId,
                recipientDeviceId = recipientDeviceId,
                ciphertextType = ciphertextType,
                payloadType = payloadType,
                ciphertext = ciphertext
            )
        )
    }

    fun buildMultiDeviceMessageEnvelope(
        senderDeviceId: Int,
        payloadType: String?,
        entries: List<MultiDeviceMessageEntry>
    ): String {
        return SignalProtocolConstants.json.encodeToString(
            MultiDeviceMessageEnvelope(
                version = SignalProtocolConstants.MULTI_DEVICE_ENVELOPE_VERSION,
                algorithm = SignalProtocolConstants.ALGORITHM_SIGNAL_MULTI_DEVICE,
                senderDeviceId = senderDeviceId,
                payloadType = payloadType,
                entries = entries
            )
        )
    }

    internal fun parseEncryptedMessageEnvelope(content: String): EncryptedMessageEnvelope? {
        return runCatching {
            SignalProtocolConstants.json.decodeFromString(EncryptedMessageEnvelope.serializer(), content)
        }.getOrNull()
    }

    internal fun parseSenderKeyMessageEnvelope(content: String): SenderKeyMessageEnvelope? {
        return runCatching {
            SignalProtocolConstants.json.decodeFromString(SenderKeyMessageEnvelope.serializer(), content)
        }.getOrNull()
    }

    internal fun parseSenderKeyDistributionEnvelope(content: String): SenderKeyDistributionEnvelope? {
        return runCatching {
            SignalProtocolConstants.json.decodeFromString(SenderKeyDistributionEnvelope.serializer(), content)
        }.getOrNull()
    }
}

@Serializable
internal data class EncryptedMessageEnvelope(
    val version: Int = SignalProtocolConstants.ENVELOPE_VERSION,
    val algorithm: String = SignalProtocolConstants.ALGORITHM_SIGNAL,
    val senderDeviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID,
    val recipientDeviceId: Int? = null,
    val ciphertextType: String? = null,
    val payloadType: String? = null,
    val ciphertext: String
)

@Serializable
internal data class MultiDeviceMessageEnvelope(
    val version: Int = SignalProtocolConstants.MULTI_DEVICE_ENVELOPE_VERSION,
    val algorithm: String = SignalProtocolConstants.ALGORITHM_SIGNAL_MULTI_DEVICE,
    val senderDeviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID,
    val payloadType: String? = null,
    val entries: List<MultiDeviceMessageEntry>
)

@Serializable
data class MultiDeviceMessageEntry(
    val recipientUserId: String? = null,
    val recipientDeviceId: Int,
    val ciphertextType: String? = null,
    val ciphertext: String
)

@Serializable
internal data class SenderKeyDistributionEnvelope(
    val version: Int = SignalProtocolConstants.SENDER_KEY_DISTRIBUTION_VERSION,
    val algorithm: String = SignalProtocolConstants.ALGORITHM_SENDER_KEY_DISTRIBUTION,
    val groupId: String,
    val epoch: Long = 0,
    val senderDeviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID,
    val distributionId: String,
    val distributionMessage: String
)

@Serializable
internal data class SenderKeyMessageEnvelope(
    val version: Int = SignalProtocolConstants.SENDER_KEY_ENVELOPE_VERSION,
    val algorithm: String = SignalProtocolConstants.ALGORITHM_SENDER_KEY,
    val groupId: String,
    val epoch: Long = 0,
    val senderDeviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID,
    val distributionId: String,
    val payloadType: String,
    val ciphertext: String
)
