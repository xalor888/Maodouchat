package com.maodouchat.crypto

import android.util.Base64
import com.maodouchat.core.crypto.EncryptedMessageEnvelope
import com.maodouchat.core.crypto.EnvelopeCodec
import com.maodouchat.core.crypto.MultiDeviceMessageEntry
import com.maodouchat.core.crypto.SenderKeyDistributionEnvelope
import com.maodouchat.core.crypto.SenderKeyMessageEnvelope
import com.maodouchat.core.crypto.SignalProtocolConstants
import com.maodouchat.domain.messaging.SignalEnvelopeJson
import kotlinx.serialization.encodeToString
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage

/**
 * Signal 信封编解码器（M03 解耦）。
 * 负责 1:1 单设备、1:1 多设备、群 SenderKey 及分发信封的 JSON 结构编码与识别。
 * 纯 JSON 层在 `:domain:messaging` 的 SignalEnvelopeJson，本类只留
 * libsignal/Base64 绑定的分发信封构造，其余方法薄委托。
 */
class SignalEnvelopeCodec : EnvelopeCodec {

    override fun envelopePayloadType(content: String): String? =
        SignalEnvelopeJson.envelopePayloadType(content)

    override fun isEncryptedEnvelope(content: String): Boolean =
        SignalEnvelopeJson.isEncryptedEnvelope(content)

    override fun isSenderKeyEnvelope(content: String): Boolean =
        SignalEnvelopeJson.isSenderKeyEnvelope(content)

    fun isSenderKeyDistributionEnvelope(content: String): Boolean =
        SignalEnvelopeJson.isSenderKeyDistributionEnvelope(content)

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

    fun parseSenderKeyEnvelopeEpoch(content: String): Long? =
        SignalEnvelopeJson.parseSenderKeyEnvelopeEpoch(content)

    fun buildUnsupportedSenderKeyEnvelope(
        groupId: String,
        distributionId: String,
        payloadType: String,
        ciphertext: String,
        epoch: Long = 0,
        senderDeviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID
    ): String = SignalEnvelopeJson.buildUnsupportedSenderKeyEnvelope(
        groupId, distributionId, payloadType, ciphertext, epoch, senderDeviceId
    )

    fun buildEncryptedMessageEnvelope(
        senderDeviceId: Int,
        recipientDeviceId: Int?,
        ciphertextType: String?,
        payloadType: String?,
        ciphertext: String
    ): String = SignalEnvelopeJson.buildEncryptedMessageEnvelope(
        senderDeviceId, recipientDeviceId, ciphertextType, payloadType, ciphertext
    )

    fun buildMultiDeviceMessageEnvelope(
        senderDeviceId: Int,
        payloadType: String?,
        entries: List<MultiDeviceMessageEntry>
    ): String = SignalEnvelopeJson.buildMultiDeviceMessageEnvelope(
        senderDeviceId, payloadType, entries
    )

    internal fun parseEncryptedMessageEnvelope(content: String): EncryptedMessageEnvelope? =
        SignalEnvelopeJson.parseEncryptedMessageEnvelope(content)

    internal fun parseSenderKeyMessageEnvelope(content: String): SenderKeyMessageEnvelope? =
        SignalEnvelopeJson.parseSenderKeyMessageEnvelope(content)

    internal fun parseSenderKeyDistributionEnvelope(content: String): SenderKeyDistributionEnvelope? =
        SignalEnvelopeJson.parseSenderKeyDistributionEnvelope(content)
}
