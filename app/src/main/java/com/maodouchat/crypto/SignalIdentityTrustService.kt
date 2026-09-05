package com.maodouchat.crypto

import android.util.Base64
import com.maodouchat.core.crypto.IdentityTrustService
import com.maodouchat.core.crypto.IdentityTrustState as CoreIdentityTrustState
import com.maodouchat.core.crypto.IdentityTrustStateMachine
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.Curve
import java.security.MessageDigest
import kotlin.concurrent.withLock

/**
 * Signal 身份信任服务与安全码计算（M03 解耦）。
 * 遵循 [IdentityTrustService] 端口与 [IdentityTrustStateMachine] 状态机契约。
 */
internal class SignalIdentityTrustService(
    private val context: SignalProtocolContext
) : IdentityTrustService {

    fun getIdentityPublicKey(): IdentityKey = context.identityKeyPair.publicKey

    fun getLocalIdentityFingerprint(): String =
        identityFingerprint(context.identityKeyPair.publicKey.serialize())

    override fun stateFor(peerAccountId: String, deviceId: Int): CoreIdentityTrustState {
        val legacy = getIdentityTrustState(peerAccountId, deviceId)
        return when (legacy) {
            SignalProtocol.IdentityTrustState.VERIFIED -> CoreIdentityTrustState.VERIFIED
            SignalProtocol.IdentityTrustState.CHANGED -> CoreIdentityTrustState.CHANGED
            else -> CoreIdentityTrustState.UNKNOWN
        }
    }

    override fun markVerified(peerAccountId: String, deviceId: Int): CoreIdentityTrustState {
        val ok = markIdentityVerified(peerAccountId, deviceId)
        return if (ok) CoreIdentityTrustState.VERIFIED else stateFor(peerAccountId, deviceId)
    }

    override fun onIdentityKeyChanged(peerAccountId: String, deviceId: Int): CoreIdentityTrustState {
        val accountId = context.currentUserId ?: SignalProtocolConstants.ANONYMOUS_ACCOUNT_ID
        val existing = (context.protocolStore as? PersistentSignalProtocolStore)?.getIdentityTrust(peerAccountId, deviceId)
        if (existing != null) {
            context.identityTrustDao.upsertTrustBlocking(
                existing.copy(
                    trustState = SignalProtocolConstants.TRUST_CHANGED,
                    lastSeenAt = System.currentTimeMillis(),
                    verifiedAt = null
                )
            )
            context.protocolStore.deleteSession(SignalProtocolAddress(peerAccountId, deviceId))
        }
        return CoreIdentityTrustState.CHANGED
    }

    override fun reset(peerAccountId: String, deviceId: Int): CoreIdentityTrustState {
        val accountId = context.currentUserId ?: SignalProtocolConstants.ANONYMOUS_ACCOUNT_ID
        context.identityTrustDao.deleteTrustBlocking(accountId, peerAccountId, deviceId)
        context.protocolStore.deleteSession(SignalProtocolAddress(peerAccountId, deviceId))
        return IdentityTrustStateMachine.onReset()
    }

    fun getIdentityTrustState(remoteUserId: String, deviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID): SignalProtocol.IdentityTrustState {
        val trust = (context.protocolStore as? PersistentSignalProtocolStore)?.getIdentityTrust(remoteUserId, deviceId)
            ?: return SignalProtocol.IdentityTrustState.UNKNOWN
        return when (trust.trustState) {
            SignalProtocolConstants.TRUST_VERIFIED -> SignalProtocol.IdentityTrustState.VERIFIED
            SignalProtocolConstants.TRUST_CHANGED -> SignalProtocol.IdentityTrustState.CHANGED
            else -> SignalProtocol.IdentityTrustState.TRUSTED
        }
    }

    fun markIdentityVerified(remoteUserId: String, deviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID): Boolean {
        return (context.protocolStore as? PersistentSignalProtocolStore)?.markIdentityVerified(remoteUserId, deviceId) == true
    }

    fun getSafetyCode(remoteUserId: String, deviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID): String? {
        val localUserId = context.currentUserId?.takeIf { it.isNotBlank() } ?: return null
        val remoteIdentity = (context.protocolStore as? PersistentSignalProtocolStore)
            ?.getIdentity(SignalProtocolAddress(remoteUserId, deviceId))
            ?: return null
        val localStableId = "$localUserId:${context.localDeviceId}".toByteArray(Charsets.UTF_8)
        val remoteStableId = "$remoteUserId:$deviceId".toByteArray(Charsets.UTF_8)
        return runCatching {
            context.numericFingerprintGenerator.createFor(
                /* version = */ 2,
                localStableId,
                context.identityKeyPair.publicKey,
                remoteStableId,
                remoteIdentity
            ).displayableFingerprint.displayText
        }.getOrNull()
    }

    fun getRemoteIdentityFingerprint(remoteUserId: String, deviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID): String? {
        val remoteIdentity = (context.protocolStore as? PersistentSignalProtocolStore)
            ?.getIdentity(SignalProtocolAddress(remoteUserId, deviceId))
            ?: return null
        return identityFingerprint(remoteIdentity.serialize())
    }

    fun identityFingerprintFromBase64(identityKeyBase64: String): String? {
        return runCatching { identityFingerprint(Base64.decode(identityKeyBase64, Base64.NO_WRAP)) }.getOrNull()
    }

    suspend fun getRemoteDeviceSafetyStates(token: String, remoteUserId: String): Result<List<SignalProtocol.DeviceSafetyState>> {
        return try {
            val devices = SignalKeyExchange.fetchDevices(token, remoteUserId).getOrElse { return Result.failure(it) }
            Result.success(
                devices.map { device ->
                    SignalProtocol.DeviceSafetyState(
                        deviceId = device.deviceId,
                        identityKey = device.identityKey,
                        identityFingerprint = getRemoteIdentityFingerprint(remoteUserId, device.deviceId)
                            ?: identityFingerprintFromBase64(device.identityKey).orEmpty(),
                        trustState = getIdentityTrustState(remoteUserId, device.deviceId),
                        safetyCode = getSafetyCode(remoteUserId, device.deviceId),
                        isCurrent = device.isCurrent
                    )
                }
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    fun signDeviceConfirmation(targetDeviceId: Int, targetIdentityKeyBase64: String): String? = context.cryptoLock.withLock {
        val userId = context.currentUserId?.takeIf { it.isNotBlank() } ?: return@withLock null
        if (!context.initializationSucceeded || context.localDeviceId !in 1..255 || targetDeviceId !in 1..255) return@withLock null
        val targetIdentity = targetIdentityKeyBase64.trim().takeIf { it.isNotBlank() } ?: return@withLock null
        val payload = buildDeviceConfirmationPayload(userId, context.localDeviceId, targetDeviceId, targetIdentity)
        val signature = Curve.calculateSignature(context.identityKeyPair.privateKey, payload)
        Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    private fun identityFingerprint(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    private fun buildDeviceConfirmationPayload(
        userId: String,
        approverDeviceId: Int,
        targetDeviceId: Int,
        targetIdentityKeyBase64: String
    ): ByteArray = buildString {
        append("maodouchat-device-confirm:v1\n")
        append(userId)
        append('\n')
        append(approverDeviceId)
        append('\n')
        append(targetDeviceId)
        append('\n')
        append(targetIdentityKeyBase64)
    }.toByteArray(Charsets.UTF_8)
}
