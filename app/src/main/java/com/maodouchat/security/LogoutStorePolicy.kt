package com.maodouchat.security

/**
 * Whether logout / session-end should destroy SQLCipher + Signal identity.
 *
 * Same-account logout and token expiry must keep the encrypted store so re-login
 * can decrypt history. Account switch and account deletion still wipe.
 */
object LogoutStorePolicy {

    enum class Reason {
        LOGOUT,
        TOKEN_EXPIRED,
        ACCOUNT_SWITCH,
        DELETE_ACCOUNT,
        TRUST_DOMAIN_CHANGE
    }

    fun destroyEncryptedDatabase(reason: Reason): Boolean = when (reason) {
        Reason.LOGOUT, Reason.TOKEN_EXPIRED -> false
        Reason.ACCOUNT_SWITCH, Reason.DELETE_ACCOUNT, Reason.TRUST_DOMAIN_CHANGE -> true
    }

    /** Ordinary (non-secret) decrypted media follows the encrypted-store rule. */
    fun wipeOrdinaryMediaCache(reason: Reason): Boolean = destroyEncryptedDatabase(reason)

    /**
     * Coil disk holds decoded chat images. Same-account re-login must keep it so
     * IMAGE/VIDEO bubbles still render from cache without a full re-download.
     * Memory cache is process-local and may always be dropped.
     */
    fun wipeCoilDiskCache(reason: Reason): Boolean = destroyEncryptedDatabase(reason)

    /**
     * [com.maodouchat.attachment.AttachmentTransferCoordinator.deleteAll] also
     * deletes the in-flight message rows. Keep-store logout must not do that.
     */
    fun wipeInFlightAttachmentTransfers(reason: Reason): Boolean = destroyEncryptedDatabase(reason)

    /**
     * Sender-key records live in SQLCipher `signal_keys`. Keep-store logout must
     * not cancel the retry WorkManager queue so coverage resumes after re-login
     * without waiting for a new enqueue.
     */
    fun cancelSenderKeyRetryWork(reason: Reason): Boolean = destroyEncryptedDatabase(reason)
}
