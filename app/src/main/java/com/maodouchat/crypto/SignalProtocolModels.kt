package com.maodouchat.crypto

import org.signal.libsignal.protocol.InvalidMessageException

enum class SenderKeyDistOutcome {
    Installed,
    Skipped,
    Failed
}

internal object SenderKeyDistributionFailurePolicy {
    fun outcomeFor(error: Throwable): SenderKeyDistOutcome = when (error) {
        is InvalidMessageException,
        is IllegalArgumentException -> SenderKeyDistOutcome.Skipped
        else -> SenderKeyDistOutcome.Failed
    }
}

class NoRecipientDevicesException : IllegalStateException()

class SignalStorePersistenceException(cause: Throwable) :
    java.io.IOException("signal_store_persistence_failed", cause)
