package com.maodouchat.crypto

import kotlinx.serialization.Serializable

enum class SignalExchangeFailure {
    SIGNED_PREKEY_MISSING,
    HTTP,
    EMPTY_RESPONSE,
    TIMEOUT,
    NETWORK,
    INVALID_RESPONSE,
    UNEXPECTED
}

class SignalExchangeException(
    val failure: SignalExchangeFailure,
    val statusCode: Int? = null,
    val serverMessage: String? = null,
    /** Stable machine-readable code from the API (for example DEVICE_ID_CONFLICT). */
    val serverCode: String? = null,
    cause: Throwable? = null
) : Exception(serverMessage, cause)

@Serializable
data class PreKeyBundleResponse(
    val registrationId: Int,
    val deviceId: Int,
    val identityKey: String,
    val signedPreKeyId: Int,
    val signedPreKey: String,
    val signedPreKeySignature: String,
    val preKeyId: Int? = null,
    val preKey: String? = null
)

@Serializable
data class DeviceInfoResponse(
    val userId: String,
    val deviceId: Int,
    val deviceName: String = "",
    val identityKey: String,
    val lastSeenAt: Long? = null,
    val isCurrent: Boolean = false,
    val status: String = "CONFIRMED",
    val confirmedAt: Long? = null,
    val confirmedByDeviceId: Int? = null
)

@Serializable
data class DevicePreKeyBundleResponse(
    val userId: String,
    val deviceId: Int,
    val registrationId: Int,
    val identityKey: String,
    val signedPreKeyId: Int,
    val signedPreKey: String,
    val signedPreKeySignature: String,
    val preKeyId: Int? = null,
    val preKey: String? = null
)
