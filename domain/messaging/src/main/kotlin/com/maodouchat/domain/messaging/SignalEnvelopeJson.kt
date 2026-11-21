package com.maodouchat.domain.messaging

import com.maodouchat.core.crypto.EncryptedMessageEnvelope
import com.maodouchat.core.crypto.MultiDeviceEnvelopePolicy
import com.maodouchat.core.crypto.MultiDeviceMessageEntry
import com.maodouchat.core.crypto.MultiDeviceMessageEnvelope
import com.maodouchat.core.crypto.SenderKeyDistributionEnvelope
import com.maodouchat.core.crypto.SenderKeyMessageEnvelope
import com.maodouchat.core.crypto.SignalProtocolConstants
import kotlinx.serialization.encodeToString

/**
 * Signal 信封 JSON 编解码（原 app 侧 SignalEnvelopeCodec 的纯函数部分）。
 * 负责 1:1 单设备、1:1 多设备、群 SenderKey 及分发信封的 JSON 结构编码与识别，不含加密。
 */
object SignalEnvelopeJson {

    fun envelopePayloadType(content: String): String? {
        return MultiDeviceEnvelopePolicy.parse(content)?.payloadType
            ?: runCatching {
                SignalProtocolConstants.json.decodeFromString(EncryptedMessageEnvelope.serializer(), content).payloadType
            }.getOrNull()
    }

    fun isEncryptedEnvelope(content: String): Boolean {
        if (MultiDeviceEnvelopePolicy.parse(content) != null) return true
        return runCatching {
            SignalProtocolConstants.json.decodeFromString(EncryptedMessageEnvelope.serializer(), content)
        }.getOrNull()?.version?.let { it >= 1 } == true
    }

    fun isSenderKeyEnvelope(content: String): Boolean {
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

    fun parseEncryptedMessageEnvelope(content: String): EncryptedMessageEnvelope? {
        return runCatching {
            SignalProtocolConstants.json.decodeFromString(EncryptedMessageEnvelope.serializer(), content)
        }.getOrNull()
    }

    fun parseSenderKeyMessageEnvelope(content: String): SenderKeyMessageEnvelope? {
        return runCatching {
            SignalProtocolConstants.json.decodeFromString(SenderKeyMessageEnvelope.serializer(), content)
        }.getOrNull()
    }

    fun parseSenderKeyDistributionEnvelope(content: String): SenderKeyDistributionEnvelope? {
        return runCatching {
            SignalProtocolConstants.json.decodeFromString(SenderKeyDistributionEnvelope.serializer(), content)
        }.getOrNull()
    }
}
