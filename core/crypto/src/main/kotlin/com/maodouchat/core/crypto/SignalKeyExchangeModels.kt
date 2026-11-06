package com.maodouchat.core.crypto

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import kotlinx.serialization.Serializable

class SignalExchangeException(
    val failure: SignalExchangeFailure,
    val statusCode: Int? = null,
    val serverMessage: String? = null,
    /** Stable machine-readable code from the API (for example DEVICE_ID_CONFLICT). */
    val serverCode: String? = null,
    cause: Throwable? = null
) : Exception(serverMessage, cause)

/**
 * Maps any transport failure to the exchange exception the crypto policies inspect.
 * Pure mapping over core types; moved here from the app's SignalKeyExchange so the
 * :core:crypto policy tests can build realistic wrapped errors without the app module.
 */
fun Throwable.toExchangeException(): SignalExchangeException = when (this) {
    is SignalExchangeException -> this
    is ApiException -> SignalExchangeException(
        failure = when (kind) {
            ApiFailureKind.HTTP -> SignalExchangeFailure.HTTP
            ApiFailureKind.TIMEOUT -> SignalExchangeFailure.TIMEOUT
            ApiFailureKind.NETWORK -> SignalExchangeFailure.NETWORK
            ApiFailureKind.INVALID_RESPONSE -> SignalExchangeFailure.INVALID_RESPONSE
            ApiFailureKind.UNEXPECTED -> SignalExchangeFailure.UNEXPECTED
        },
        statusCode = statusCode,
        serverMessage = serverMessage,
        serverCode = serverCode,
        cause = this
    )
    is java.net.SocketTimeoutException -> SignalExchangeException(SignalExchangeFailure.TIMEOUT, cause = this)
    is java.io.IOException -> SignalExchangeException(SignalExchangeFailure.NETWORK, cause = this)
    is kotlinx.serialization.SerializationException,
    is IllegalArgumentException -> SignalExchangeException(SignalExchangeFailure.INVALID_RESPONSE, cause = this)
    else -> SignalExchangeException(SignalExchangeFailure.UNEXPECTED, cause = this)
}

/** Only an absent multi-device endpoint may fall back to the legacy single-bundle API. */
fun Throwable.shouldUseLegacyBundleEndpoint(): Boolean {
    val exchange = toExchangeException()
    return exchange.failure == SignalExchangeFailure.HTTP && exchange.statusCode == 404
}

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
