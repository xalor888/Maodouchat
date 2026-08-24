package com.maodouchat.crypto

/**
 * History restore / decrypt-failure rules that must stay off ChatDetailViewModel.
 *
 * Server stores ciphertext only. A UI placeholder can never be re-decrypted, so
 * recoverable failures keep the original wire row. Session repair is deferred and
 * de-duplicated so opening a long chat does not storm PreKey fetches.
 */
object DecryptHistoryPolicy {

    /** Never drop a chat row because this device cannot read it yet. */
    fun shouldKeepWire(result: SignalProtocol.DecryptResult): Boolean = when (result) {
        is SignalProtocol.DecryptResult.Success -> false
        SignalProtocol.DecryptResult.NotForThisDevice,
        SignalProtocol.DecryptResult.NoSession,
        SignalProtocol.DecryptResult.UntrustedIdentity,
        SignalProtocol.DecryptResult.FutureEpoch,
        SignalProtocol.DecryptResult.Failed,
        SignalProtocol.DecryptResult.Duplicate,
        SignalProtocol.DecryptResult.UnsupportedEnvelope -> true
    }

    /** 1:1 NoSession / identity change can be repaired later; not per-row during history. */
    fun shouldDeferSessionRepair(result: SignalProtocol.DecryptResult): Boolean = when (result) {
        SignalProtocol.DecryptResult.NoSession,
        SignalProtocol.DecryptResult.UntrustedIdentity -> true
        else -> false
    }

    fun shouldAttemptSessionRepair(senderId: String, alreadyAttempted: Set<String>): Boolean {
        val id = senderId.trim()
        if (id.isEmpty()) return false
        return id !in alreadyAttempted
    }

    /**
     * True wipe / new device: this device was not a recipient of historical
     * multi-device envelopes (send path skips the sending device). Cloud history
     * stays ciphertext until peers (or other own devices) re-session.
     */
    fun newDeviceHistoryCannotDecrypt(identityRestoredFromStore: Boolean): Boolean =
        !identityRestoredFromStore
}
