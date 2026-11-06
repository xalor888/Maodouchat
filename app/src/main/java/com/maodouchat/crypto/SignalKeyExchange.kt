package com.maodouchat.crypto

import android.os.Build
import com.maodouchat.core.crypto.DeviceInfoResponse
import com.maodouchat.core.crypto.DevicePreKeyBundleResponse
import com.maodouchat.core.crypto.PreKeyBundleResponse
import com.maodouchat.core.crypto.SignalExchangeException
import com.maodouchat.core.crypto.SignalExchangeFailure
import com.maodouchat.core.crypto.shouldUseLegacyBundleEndpoint
import com.maodouchat.core.crypto.toExchangeException
import com.maodouchat.network.ApiService
import com.maodouchat.core.model.PreKeyDataDto
import com.maodouchat.core.model.UploadKeysRequest
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * Signal 密钥交换服务
 *
 * 负责上传本机密钥到服务器，以及获取目标用户的 PreKeyBundle。
 * HTTP 统一走 [ApiService] 的 executeWithRefresh：401 时 refresh、写回 TokenManager、
 * 触发 tokenExpired / WS 重连，与消息/附件等 REST 路径一致。
 */
object SignalKeyExchange {


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

    /** Transport failure mapping now lives in :core:crypto ([toExchangeException]). */
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
    suspend fun uploadKeys(
        token: String,
        identityKey: IdentityKey,
        signedPreKey: SignedPreKeyRecord?,
        preKeys: List<PreKeyRecord>,
        registrationId: Int,
        deviceId: Int
    ): Result<Unit> {
        return try {
            if (signedPreKey == null) {
                return Result.failure(SignalExchangeException(SignalExchangeFailure.SIGNED_PREKEY_MISSING))
            }

            val request = UploadKeysRequest(
                registrationId = registrationId,
                deviceId = deviceId,
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

    suspend fun uploadKeys(token: String, signalProtocol: SignalProtocol): Result<Unit> =
        uploadKeys(
            token = token,
            identityKey = signalProtocol.getIdentityPublicKey(),
            signedPreKey = signalProtocol.getSignedPreKey(),
            preKeys = signalProtocol.getPreKeys(),
            registrationId = signalProtocol.getRegistrationId(),
            deviceId = signalProtocol.getDeviceId()
        )

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

    /** Only an absent multi-device endpoint may fall back to the legacy single-bundle API. */
    internal fun shouldUseLegacyBundleEndpoint(error: Throwable): Boolean =
        error.shouldUseLegacyBundleEndpoint()

    /**
     * Discover confirmed devices on current servers while retaining interoperability with a
     * pre-multi-device server. Transport/auth failures remain fatal; treating them as an empty
     * device set would incorrectly turn a retryable outage into a permanent no-device failure.
     */
    suspend fun fetchCompatibleDevicePreKeyBundles(
        token: String,
        targetUserId: String,
    ): Result<List<DevicePreKeyBundleResponse>> {
        val current = fetchDevicePreKeyBundles(token, targetUserId)
        val currentBundles = current.getOrNull()
        // A successful empty response is authoritative: the peer currently has no confirmed
        // devices. Only an explicit endpoint-not-found response identifies a legacy server.
        if (currentBundles != null) return Result.success(currentBundles)
        val currentError = current.exceptionOrNull()
        if (currentError != null && !shouldUseLegacyBundleEndpoint(currentError)) {
            return Result.failure(currentError)
        }
        return fetchPreKeyBundle(token, targetUserId).map { legacy ->
            listOf(
                DevicePreKeyBundleResponse(
                    userId = targetUserId,
                    deviceId = legacy.deviceId,
                    registrationId = legacy.registrationId,
                    identityKey = legacy.identityKey,
                    signedPreKeyId = legacy.signedPreKeyId,
                    signedPreKey = legacy.signedPreKey,
                    signedPreKeySignature = legacy.signedPreKeySignature,
                    preKeyId = legacy.preKeyId,
                    preKey = legacy.preKey,
                )
            )
        }
    }

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
