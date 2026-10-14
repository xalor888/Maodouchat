package com.maodouchat.security.pin

sealed interface VerificationOutcome {
    data class Success(val needsUpgrade: Boolean, val upgradedHash: StoredPinHash? = null) : VerificationOutcome
    object Mismatch : VerificationOutcome
    object Corrupted : VerificationOutcome
}

data class StoredPinHash(
    val formattedString: String,
    val saltHex: String,
    val hashHex: String,
    val iterations: Int,
    val isPbkdf2: Boolean
)
