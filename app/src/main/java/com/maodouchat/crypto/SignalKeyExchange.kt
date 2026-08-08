package com.maodouchat.crypto

import android.os.Build
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.network.ApiService
import com.maodouchat.network.PreKeyDataDto
import com.maodouchat.network.UploadKeysRequest
import kotlinx.serialization.Serializable
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.state.PreKeyBundle

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
    cause: Throwable? = null
) : Exception(serverMessage, cause)

/**
 * Signal 密钥交换服务
 *
 * 负责上传本机密钥到服务器，以及获取目标用户的 PreKeyBundle。
 * HTTP 统一走 [ApiService] 的 executeWithRefresh：401 时 refresh、写回 TokenManager、
 * 触发 tokenExpired / WS 重连，与消息/附件等 REST 路径一致。
 */
object SignalKeyExchange {

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

    fun PreKeyBundleResponse.toSignalPreKeyBundle(): PreKeyBundle {
        val preKeyIdValue = preKeyId?.takeIf { it > 0 && !preKey.isNullOrBlank() }
        val preKeyPublic = preKeyIdValue?.let { Curve.decodePoint(android.util.Base64.decode(preKey, android.util.Base64.NO_WRAP), 0) }
        return PreKeyBundle(
            registrationId,
            deviceId,
            preKeyIdValue ?: 0,
            preKeyPublic,
            signedPreKeyId,
            Curve.decodePoint(android.util.Base64.decode(signedPreKey, android.util.Base64.NO_WRAP), 0),
            android.util.Base64.decode(signedPreKeySignature, android.util.Base64.NO_WRAP),
            IdentityKey(android.util.Base64.decode(identityKey, android.util.Base64.NO_WRAP), 0)
        )
    }

    fun DevicePreKeyBundleResponse.toSignalPreKeyBundle(): PreKeyBundle {
        val preKeyIdValue = preKeyId?.takeIf { it > 0 && !preKey.isNullOrBlank() }
        val preKeyPublic = preKeyIdValue?.let { Curve.decodePoint(android.util.Base64.decode(preKey, android.util.Base64.NO_WRAP), 0) }
        return PreKeyBundle(
            registrationId,
            deviceId,
            preKeyIdValue ?: 0,
            preKeyPublic,
            signedPreKeyId,
            Curve.decodePoint(android.util.Base64.decode(signedPreKey, android.util.Base64.NO_WRAP), 0),
            android.util.Base64.decode(signedPreKeySignature, android.util.Base64.NO_WRAP),
            IdentityKey(android.util.Base64.decode(identityKey, android.util.Base64.NO_WRAP), 0)
        )
    }

    private fun Throwable.toExchangeException(): SignalExchangeException = when (this) {
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
            cause = this
        )
        is java.net.SocketTimeoutException -> SignalExchangeException(SignalExchangeFailure.TIMEOUT, cause = this)
        is java.io.IOException -> SignalExchangeException(SignalExchangeFailure.NETWORK, cause = this)
        is kotlinx.serialization.SerializationException,
        is IllegalArgumentException -> SignalExchangeException(SignalExchangeFailure.INVALID_RESPONSE, cause = this)
        else -> SignalExchangeException(SignalExchangeFailure.UNEXPECTED, cause = this)
    }

    private fun <T> Result<T>.mapApiFailure(): Result<T> =
        fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it.toExchangeException()) }
        )

    /**
     * 上传密钥包到服务器（经 ApiService.executeWithRefresh）
     *
     * PQXDH 保守路径：本方法仅上传 ECC 密钥（identityKey / signedPreKey / oneTimePreKeys），
     * **不**上传 Kyber PreKey。完整 PQXDH 需要服务端 [SignalKeyRepository] 加 Kyber 列存储、
     * [UploadKeysRequest] 与 [PreKeyBundleResponse] / [DevicePreKeyBundleResponse] 加 Kyber 字段，
     * 风险大且会破坏旧客户端兼容。当前走 X3DH 路径，详见
     * [com.maodouchat.crypto.SignalProtocol.ensurePreKeysAvailable] 的注释。
     */
    suspend fun uploadKeys(token: String, signalProtocol: SignalProtocol): Result<Unit> {
        return try {
            val identityKey = signalProtocol.getIdentityPublicKey()
            val signedPreKey = signalProtocol.getSignedPreKey()
            val preKeys = signalProtocol.getPreKeys()

            if (signedPreKey == null) {
                return Result.failure(SignalExchangeException(SignalExchangeFailure.SIGNED_PREKEY_MISSING))
            }

            val request = UploadKeysRequest(
                registrationId = signalProtocol.getRegistrationId(),
                deviceId = signalProtocol.getDeviceId(),
                deviceName = defaultDeviceName(),
                identityKey = android.util.Base64.encodeToString(identityKey.serialize(), android.util.Base64.NO_WRAP),
                signedPreKeyId = signedPreKey.id,
                signedPreKey = android.util.Base64.encodeToString(signedPreKey.keyPair.publicKey.serialize(), android.util.Base64.NO_WRAP),
                signedPreKeySignature = android.util.Base64.encodeToString(signedPreKey.signature, android.util.Base64.NO_WRAP),
                preKeys = preKeys.map {
                    PreKeyDataDto(
                        it.id,
                        android.util.Base64.encodeToString(it.keyPair.publicKey.serialize(), android.util.Base64.NO_WRAP)
                    )
                }
            )
            ApiService.uploadKeys(token, request).mapApiFailure()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e.toExchangeException())
        }
    }

    suspend fun fetchPreKeyBundle(token: String, targetUserId: String): Result<PreKeyBundleResponse> =
        ApiService.getPreKeyBundle(token, targetUserId)
            .map { dto ->
                PreKeyBundleResponse(
                    registrationId = dto.registrationId,
                    deviceId = dto.deviceId,
                    identityKey = dto.identityKey,
                    signedPreKeyId = dto.signedPreKeyId,
                    signedPreKey = dto.signedPreKey,
                    signedPreKeySignature = dto.signedPreKeySignature,
                    preKeyId = dto.preKeyId,
                    preKey = dto.preKey
                )
            }
            .mapApiFailure()

    suspend fun fetchDevices(token: String, targetUserId: String, currentDeviceId: Int? = null): Result<List<DeviceInfoResponse>> =
        ApiService.getDevices(token, targetUserId, currentDeviceId)
            .map { list ->
                list.map { d ->
                    DeviceInfoResponse(
                        userId = d.userId,
                        deviceId = d.deviceId,
                        deviceName = d.deviceName,
                        identityKey = d.identityKey,
                        lastSeenAt = d.lastSeenAt,
                        isCurrent = d.isCurrent,
                        status = d.status,
                        confirmedAt = d.confirmedAt,
                        confirmedByDeviceId = d.confirmedByDeviceId
                    )
                }
            }
            .mapApiFailure()

    suspend fun fetchDevicePreKeyBundle(token: String, targetUserId: String, deviceId: Int): Result<DevicePreKeyBundleResponse> =
        ApiService.getDevicePreKeyBundle(token, targetUserId, deviceId)
            .map { dto ->
                DevicePreKeyBundleResponse(
                    userId = dto.userId,
                    deviceId = dto.deviceId,
                    registrationId = dto.registrationId,
                    identityKey = dto.identityKey,
                    signedPreKeyId = dto.signedPreKeyId,
                    signedPreKey = dto.signedPreKey,
                    signedPreKeySignature = dto.signedPreKeySignature,
                    preKeyId = dto.preKeyId,
                    preKey = dto.preKey
                )
            }
            .mapApiFailure()

    suspend fun fetchDevicePreKeyBundles(token: String, targetUserId: String): Result<List<DevicePreKeyBundleResponse>> =
        ApiService.getDevicePreKeyBundles(token, targetUserId)
            .map { list ->
                list.map { dto ->
                    DevicePreKeyBundleResponse(
                        userId = dto.userId,
                        deviceId = dto.deviceId,
                        registrationId = dto.registrationId,
                        identityKey = dto.identityKey,
                        signedPreKeyId = dto.signedPreKeyId,
                        signedPreKey = dto.signedPreKey,
                        signedPreKeySignature = dto.signedPreKeySignature,
                        preKeyId = dto.preKeyId,
                        preKey = dto.preKey
                    )
                }
            }
            .mapApiFailure()

    private fun defaultDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val model = Build.MODEL.orEmpty()
        return listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android" }
            .take(50)
    }
}
