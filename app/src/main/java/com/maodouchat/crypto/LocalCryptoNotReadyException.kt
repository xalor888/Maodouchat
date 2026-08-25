package com.maodouchat.crypto

/** Local Signal state could not be restored yet; callers may retry without user action. */
class LocalCryptoNotReadyException(
    reason: String = "signal_local_crypto_not_ready",
) : IllegalStateException(reason)
