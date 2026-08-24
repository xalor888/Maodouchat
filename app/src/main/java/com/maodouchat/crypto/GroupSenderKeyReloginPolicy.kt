package com.maodouchat.crypto

/**
 * Same-account logout keeps SQLCipher, including persisted SenderKeyRecord rows.
 * After re-login, initialize() reloads those records; group history decrypts
 * without minting a new identity. Only still-wire peer rows should REQUEST_SENDER_KEY.
 * Decrypt failure must keep the original envelope — placeholders cannot be re-decrypted.
 */
object GroupSenderKeyReloginPolicy {

    fun keepSenderKeysOnLogout(destroyEncryptedDatabase: Boolean): Boolean =
        !destroyEncryptedDatabase

    fun shouldRequestMissingPeerKey(
        isGroup: Boolean,
        stillWire: Boolean,
        senderId: String,
        currentUserId: String
    ): Boolean {
        if (!isGroup || !stillWire) return false
        return GroupSenderKeyRequestPolicy.shouldRequestFromSender(senderId, currentUserId)
    }

    fun stillWireForRequest(isSenderKeyEnvelope: Boolean, isSenderKeyMessage: Boolean): Boolean =
        isSenderKeyEnvelope || isSenderKeyMessage
}
