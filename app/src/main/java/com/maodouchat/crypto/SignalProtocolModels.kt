package com.maodouchat.crypto

import com.maodouchat.core.crypto.SenderKeyDistOutcome
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage

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

data class SenderKeyDistributionPayload(
    val distributionId: String,
    val message: SenderKeyDistributionMessage,
    val epoch: Long
)
