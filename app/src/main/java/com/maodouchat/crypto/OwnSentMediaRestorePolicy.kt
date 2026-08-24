package com.maodouchat.crypto

import com.maodouchat.data.model.MessageType

/**
 * After same-account logout the encrypted DB is kept, but decrypted media files may be
 * gone (cache wipe / process death). Own-sent rows still carry AES-GCM attachment meta
 * in Room; re-login must re-download those files instead of showing a broken local URI.
 *
 * Secret chats stay excluded: their plaintext media is wiped on purpose.
 */
object OwnSentMediaRestorePolicy {
    private val RESTORABLE_TYPES = setOf(
        MessageType.FILE,
        MessageType.IMAGE,
        MessageType.GIF,
        MessageType.VIDEO,
        MessageType.VOICE
    )

    fun shouldRestore(
        isSecretChat: Boolean,
        type: MessageType,
        attachmentId: String?,
        localUriReadable: Boolean
    ): Boolean {
        if (isSecretChat) return false
        if (type !in RESTORABLE_TYPES) return false
        if (attachmentId.isNullOrBlank()) return false
        return !localUriReadable
    }

    /**
     * Same-account keep-store logout leaves Room attachment AES-GCM keys.
     * [hydrateMissingLocalAttachments] re-downloads when the local URI is gone
     * and either the type auto-downloads or the row is own-sent.
     */
    fun shouldHydrateMissingLocalAttachment(
        isSecretChat: Boolean,
        type: MessageType,
        attachmentId: String?,
        localUriReadable: Boolean,
        senderIsCurrentUser: Boolean,
        autoDownload: Boolean
    ): Boolean {
        if (!shouldRestore(isSecretChat, type, attachmentId, localUriReadable)) return false
        return autoDownload || senderIsCurrentUser
    }

    /** Soft logout must not delete ordinary (non-secret) decrypted media. */
    fun wipeOrdinaryMediaOnLogout(destroyEncryptedDatabase: Boolean): Boolean = destroyEncryptedDatabase

    /** Room attachment meta (id/key/iv) survives keep-store; destroy-store wipes the DB. */
    fun keepAttachmentMetaOnLogout(destroyEncryptedDatabase: Boolean): Boolean =
        !destroyEncryptedDatabase
}
