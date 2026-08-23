package com.maodouchat.crypto

/**
 * When a group ciphertext cannot be decrypted yet, keep the wire row, ask the
 * sender to redistribute, and do not block the rest of history on that row.
 * Placeholders must not replace ciphertext — SK arrival cannot re-decrypt them.
 */
object GroupSenderKeyRequestPolicy {
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
    fun shouldKeepGroupWire(result: SignalProtocol.DecryptResult): Boolean = when (result) {
        SignalProtocol.DecryptResult.NoSession,
        SignalProtocol.DecryptResult.FutureEpoch,
        SignalProtocol.DecryptResult.UntrustedIdentity,
        SignalProtocol.DecryptResult.Failed,
        SignalProtocol.DecryptResult.Duplicate -> true
        else -> false
    }
}
