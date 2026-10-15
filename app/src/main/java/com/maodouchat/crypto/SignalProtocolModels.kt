package com.maodouchat.crypto

import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage

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

enum class IdentityTrustState {
    UNKNOWN,
    TRUSTED,
    VERIFIED,
    CHANGED
}

data class DeviceSafetyState(
    val deviceId: Int,
    val identityKey: String,
    val identityFingerprint: String,
    val trustState: IdentityTrustState,
    val safetyCode: String?,
    val isCurrent: Boolean = false
)

data class SenderKeyDistributionPayload(
    val distributionId: String,
    val message: SenderKeyDistributionMessage,
    val epoch: Long
)
