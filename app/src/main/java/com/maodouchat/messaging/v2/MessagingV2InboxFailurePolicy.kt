package com.maodouchat.messaging.v2

/** Decides when a poison envelope must stop blocking mailbox convergence. */
internal object MessagingV2InboxFailurePolicy {
    const val MAX_RECOVERABLE_ATTEMPTS = 8

    fun shouldDeadLetter(errorCode: String, attemptsAfterFailure: Int): Boolean =
        errorCode in PERMANENT_ERRORS || attemptsAfterFailure >= MAX_RECOVERABLE_ATTEMPTS

    private val PERMANENT_ERRORS = setOf(
        "messaging_v2_wrong_device",
        "messaging_v2_unsupported_ciphertext",
        "messaging_v2_sender_key_install_failed",
    )
}
