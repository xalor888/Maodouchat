package com.maodouchat.crypto

/**
 * When a group ciphertext cannot be decrypted yet, keep the wire row, ask the
 * sender to redistribute, and do not block the rest of history on that row.
 * Placeholders must not replace ciphertext — SK arrival cannot re-decrypt them.
 */
object GroupSenderKeyRequestPolicy {
    const val MIN_REQUEST_INTERVAL_MS = 8_000L

    fun shouldRequestRedistribution(isGroup: Boolean, decryptHadNoSession: Boolean): Boolean =
        isGroup && decryptHadNoSession

    fun shouldRequestRedistribution(isGroup: Boolean, result: SignalProtocol.DecryptResult): Boolean =
        isGroup && when (result) {
            SignalProtocol.DecryptResult.NoSession,
            SignalProtocol.DecryptResult.FutureEpoch -> true
            else -> false
        }

    fun shouldAdvanceSyncPastMissingKey(isGroup: Boolean, decryptHadNoSession: Boolean): Boolean =
        isGroup && decryptHadNoSession

    /** Recoverable group failures: persist original envelope, never a UI placeholder. */
    fun shouldKeepGroupWire(result: SignalProtocol.DecryptResult): Boolean =
        DecryptHistoryPolicy.shouldKeepWire(result)

    /**
     * REQUEST_SENDER_KEY is fan-out to *other* members. Asking our own user id is a no-op
     * (server already skips the requester), so own-sent group history must rely on the
     * local Sender Key restored from SQLCipher.
     */
    fun shouldRequestFromSender(senderId: String, currentUserId: String): Boolean {
        val sender = senderId.trim()
        return sender.isNotEmpty() && sender != currentUserId.trim()
    }

    /** Per-sender throttle so opening a group after re-login can ask every missing peer. */
    fun shouldSendNow(
        senderId: String,
        nowMs: Long,
        lastRequestAtBySender: Map<String, Long>,
        minIntervalMs: Long = MIN_REQUEST_INTERVAL_MS
    ): Boolean {
        val sender = senderId.trim()
        if (sender.isEmpty()) return false
        val last = lastRequestAtBySender[sender] ?: lastRequestAtBySender[senderId] ?: return true
        return nowMs - last >= minIntervalMs
    }

    fun sendersNeedingRedistribution(
        senderIds: Iterable<String>,
        currentUserId: String
    ): List<String> = senderIds
        .map { it.trim() }
        .filter { shouldRequestFromSender(it, currentUserId) }
        .distinct()
}
