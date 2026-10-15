package com.maodouchat.crypto

data class ParsedEnvelope(
    val version: Int,
    val algorithm: String,
    val senderDeviceId: Int,
    val payloadType: String?,
    val entries: List<ParsedEntry>
)

data class ParsedEntry(
    val recipientUserId: String?,
    val recipientDeviceId: Int,
    val recipientDeviceIdOmitted: Boolean,
    val ciphertextType: String?,
    val ciphertext: String
)
