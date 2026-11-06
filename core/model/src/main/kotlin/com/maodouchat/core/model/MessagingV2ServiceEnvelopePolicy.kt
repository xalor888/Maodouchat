package com.maodouchat.core.model

object MessagingV2ServiceEnvelopePolicy {
    fun accepts(senderUserId: String, senderDeviceId: Int, ciphertextType: String): Boolean =
        senderDeviceId == SERVICE_DEVICE_ID &&
            ciphertextType == CIPHERTEXT_SERVICE &&
            (senderUserId.startsWith("bot_") || senderUserId == SYSTEM_SENDER_ID)

    private const val CIPHERTEXT_SERVICE = "SERVICE_PLAINTEXT"
    private const val SERVICE_DEVICE_ID = 0
    private const val SYSTEM_SENDER_ID = "system"
}
