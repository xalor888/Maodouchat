package com.maodouchat.core.crypto

import kotlinx.serialization.Serializable

@Serializable
data class EncryptedMessageEnvelope(
    val version: Int = SignalProtocolConstants.ENVELOPE_VERSION,
    val algorithm: String = SignalProtocolConstants.ALGORITHM_SIGNAL,
    val senderDeviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID,
    val recipientDeviceId: Int? = null,
    val ciphertextType: String? = null,
    val payloadType: String? = null,
    val ciphertext: String
)

@Serializable
data class MultiDeviceMessageEnvelope(
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
data class SenderKeyDistributionEnvelope(
    val version: Int = SignalProtocolConstants.SENDER_KEY_DISTRIBUTION_VERSION,
    val algorithm: String = SignalProtocolConstants.ALGORITHM_SENDER_KEY_DISTRIBUTION,
    val groupId: String,
    val epoch: Long = 0,
    val senderDeviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID,
    val distributionId: String,
    val distributionMessage: String
)

@Serializable
data class SenderKeyMessageEnvelope(
    val version: Int = SignalProtocolConstants.SENDER_KEY_ENVELOPE_VERSION,
    val algorithm: String = SignalProtocolConstants.ALGORITHM_SENDER_KEY,
    val groupId: String,
    val epoch: Long = 0,
    val senderDeviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID,
    val distributionId: String,
    val payloadType: String,
    val ciphertext: String
)
