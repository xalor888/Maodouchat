package com.maodouchat.crypto

// SignalDirectCipher 的信封数据模型：从母文件纯搬移，零行为改动。
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
