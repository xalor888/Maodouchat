package com.maodouchat.crypto

import android.util.Base64
import android.util.Log
import com.maodouchat.core.crypto.CryptoAccountBootstrapper
import com.maodouchat.core.crypto.DecryptResult
import com.maodouchat.core.crypto.DeviceIdMigrationCoordinator
import com.maodouchat.core.crypto.DirectMessageCipher
import com.maodouchat.core.crypto.DirectSessionManager
import com.maodouchat.core.crypto.EnvelopeCodec
import com.maodouchat.core.crypto.GroupEncryptionHealth
import com.maodouchat.core.crypto.GroupEncryptionHealthService
import com.maodouchat.core.crypto.GroupMessageCipher
import com.maodouchat.core.crypto.GroupSenderKeyManager
import com.maodouchat.core.crypto.IdentityTrustService
import com.maodouchat.core.crypto.PreKeyInventory
import com.maodouchat.core.crypto.PreKeyPublisher
import com.maodouchat.core.model.ConversationId
import com.maodouchat.data.local.dao.IdentityTrustDao
import com.maodouchat.data.local.dao.SignalKeyDao
import com.maodouchat.data.local.entity.SignalKeyEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.UUID
import kotlin.concurrent.withLock

/**
 * Signal 端到端加密协议封装（外观模式 Facade）。
 *
 * 在 M03/M04 阶段已解耦为独立领域服务：
 * - [SignalProtocolContext]: 密钥对、内存状态、Room 持久化 store 与锁
 * - [SignalEnvelopeCodec]: 信封编解码器
 * - [SignalIdentityTrustService]: 身份公钥与指纹管理、信任状态
 * - [SignalDeviceIdCoordinator]: 多设备 ID 分配与迁移协调器
 * - [SignalPreKeyManager]: 预密钥与签名预密钥生命周期管理
 * - [SignalSessionManager]: 1:1 双向棘轮会话管理
 * - [SignalDirectCipher]: 1:1 及多设备信封加解密器
 * - [SignalAccountBootstrapper]: 账户初始化与恢复引导器
 * - [SignalGroupSenderKeyManager]: 群 Sender Key 管理与生命周期
 * - [SignalGroupCipher]: 群消息信封加密、解密与分发
 * - [SignalGroupEncryptionHealthService]: 群加密健康服务
 */
class SignalProtocol(
    private val signalKeyDao: SignalKeyDao,
    private val identityTrustDao: IdentityTrustDao
) : DirectSessionManager,
    EnvelopeCodec,
    DirectMessageCipher,
    CryptoAccountBootstrapper,
    PreKeyInventory,
    PreKeyPublisher,
    IdentityTrustService,
    DeviceIdMigrationCoordinator,
    GroupSenderKeyManager,
    GroupMessageCipher,
    GroupEncryptionHealthService,
    // G328c：解密状态判定的窄端口（把 SignalProtocol 交给 ChatDetailDecryptStatus 时
    // 只需要这 4 个方法，不必暴露整个协议对象）。
    com.maodouchat.ui.screen.chatdetail.DecryptEnvelopeGate {

    internal val context = SignalProtocolContext(signalKeyDao, identityTrustDao)
    internal val envelopeCodec = SignalEnvelopeCodec()
    internal val identityTrustService = SignalIdentityTrustService(context)
    internal val deviceIdCoordinator = SignalDeviceIdCoordinator(context)
    internal val preKeyManager = SignalPreKeyManager(context, deviceIdCoordinator)
    internal val sessionManager = SignalSessionManager(context, deviceIdCoordinator)
    internal val directCipher = SignalDirectCipher(context, sessionManager, deviceIdCoordinator, envelopeCodec)
    internal val accountBootstrapper = SignalAccountBootstrapper(context, deviceIdCoordinator, preKeyManager)
    internal val groupSenderKeyManager = SignalGroupSenderKeyManager(context)
    internal val groupCipher = SignalGroupCipher(context, groupSenderKeyManager, directCipher)
    internal val groupEncryptionHealthService = SignalGroupEncryptionHealthService(groupSenderKeyManager, context)

    // ==========================================
    // CryptoAccountBootstrapper
    // ==========================================

    override suspend fun initialize(token: String?, userId: String?): Boolean =
        accountBootstrapper.initialize(token, userId)

    override suspend fun ensureLocalCryptoReady(token: String?, userId: String): Boolean =
        accountBootstrapper.ensureLocalCryptoReady(token, userId)

    suspend fun clearLocalState() = accountBootstrapper.clearLocalState()

    /** G39 诊断：上一次初始化失败的原因（成功时为空）。 */
    fun lastInitializationFailure(): Throwable? = context.lastInitializationFailure

    suspend fun invalidateInMemoryAccountState() = accountBootstrapper.invalidateInMemoryAccountState()

    // ==========================================
    // PreKeyInventory & PreKeyPublisher
    // ==========================================

    override suspend fun replenishPreKeysIfNeeded(token: String?, expectedUserId: String): Boolean =
        preKeyManager.replenishPreKeysIfNeeded(token, expectedUserId)

    override suspend fun rotateSignedPreKeyIfNeeded(token: String?, expectedUserId: String): Boolean =
        preKeyManager.rotateSignedPreKeyIfNeeded(token, expectedUserId)

    fun getPreKeys(): List<PreKeyRecord> = context.preKeys

    fun getSignedPreKey(): SignedPreKeyRecord? = context.signedPreKey

    // ==========================================
    // DirectSessionManager
    // ==========================================

    override suspend fun ensureSession(token: String, recipientId: String, deviceId: Int): Result<Unit> =
        sessionManager.ensureSession(token, recipientId, deviceId)

    suspend fun ensureSessions(token: String, recipientId: String): Result<List<Int>> =
        sessionManager.ensureSessions(token, recipientId)

    internal suspend fun ensureSessionsDetailed(token: String, recipientId: String): Result<SessionCoverage> =
        sessionManager.ensureSessionsDetailed(token, recipientId)

    override fun cleanupStaleSessions(activeContactIds: Set<String>) =
        sessionManager.cleanupStaleSessions(activeContactIds)

    fun hasSession(recipientId: String, deviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID): Boolean =
        sessionManager.hasSession(recipientId, deviceId)

    fun getKnownSessionDeviceIds(recipientId: String): List<Int> =
        sessionManager.getKnownSessionDeviceIds(recipientId)

    fun establishSession(recipientId: String, deviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID, preKeyBundle: PreKeyBundle) =
        sessionManager.establishSession(recipientId, deviceId, preKeyBundle)

    // ==========================================
    // DirectMessageCipher
    // ==========================================

    override suspend fun encryptTextEnvelope(token: String, recipientId: String, plaintext: String): Result<String> =
        directCipher.encryptTextEnvelope(token, recipientId, plaintext)

    suspend fun encryptTextEnvelopes(token: String, recipientId: String, plaintext: String): Result<List<String>> =
        directCipher.encryptTextEnvelopes(token, recipientId, plaintext)

    suspend fun encryptMultiDeviceTextEnvelope(token: String, recipientId: String, plaintext: String): Result<String> =
        directCipher.encryptMultiDeviceTextEnvelope(token, recipientId, plaintext)

    suspend fun encryptSyncedTextEnvelope(token: String, recipientId: String, plaintext: String): Result<String> =
        directCipher.encryptSyncedTextEnvelope(token, recipientId, plaintext)

    suspend fun encryptSyncedContentEnvelope(
        token: String,
        recipientId: String,
        plaintext: String,
        payloadType: String
    ): Result<String> = directCipher.encryptSyncedContentEnvelope(token, recipientId, plaintext, payloadType)

    suspend fun encryptMultiDeviceContentEnvelope(
        token: String,
        recipientId: String,
        plaintext: String,
        payloadType: String
    ): Result<String> = directCipher.encryptMultiDeviceContentEnvelope(token, recipientId, plaintext, payloadType)

    suspend fun encryptMultiRecipientContentEnvelope(
        token: String,
        recipientIds: List<String>,
        plaintext: String,
        payloadType: String,
        includeCurrentUserDevices: Boolean = false,
        requiredRecipientIds: Set<String> = emptySet()
    ): Result<String> = directCipher.encryptMultiRecipientContentEnvelope(
        token, recipientIds, plaintext, payloadType, includeCurrentUserDevices, requiredRecipientIds
    )

    suspend fun encryptMultiRecipientContentEnvelopeWithTargets(
        token: String,
        recipientIds: List<String>,
        plaintext: String,
        payloadType: String,
        includeCurrentUserDevices: Boolean = false,
        requiredRecipientIds: Set<String> = emptySet()
    ): Result<MultiRecipientEnvelopePayload> = directCipher.encryptMultiRecipientContentEnvelopeWithTargets(
        token, recipientIds, plaintext, payloadType, includeCurrentUserDevices, requiredRecipientIds
    )

    suspend fun encryptContentEnvelopes(
        token: String,
        recipientId: String,
        plaintext: String,
        payloadType: String
    ): Result<List<String>> = directCipher.encryptContentEnvelopes(token, recipientId, plaintext, payloadType)

    suspend fun encryptContentEnvelope(
        token: String,
        recipientId: String,
        plaintext: String,
        payloadType: String,
        deviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID
    ): Result<String> = directCipher.encryptContentEnvelope(token, recipientId, plaintext, payloadType, deviceId)

    fun encryptMessage(recipientId: String, plaintext: String, deviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID): EncryptedPayload =
        directCipher.encryptMessage(recipientId, plaintext, deviceId)

    override fun decryptTextEnvelope(senderId: String, content: String): DecryptResult =
        directCipher.decryptTextEnvelope(senderId, content)

    fun decryptContentEnvelope(senderId: String, content: String): DecryptResult =
        directCipher.decryptContentEnvelope(senderId, content)

    fun decryptMessage(
        senderId: String,
        ciphertext: ByteArray,
        deviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID,
        ciphertextType: String? = null
    ): String = directCipher.decryptMessage(senderId, ciphertext, deviceId, ciphertextType)

    fun decryptDeviceCiphertext(
        senderId: String,
        senderDeviceId: Int,
        ciphertextType: String,
        ciphertext: String,
    ): DecryptResult = directCipher.decryptDeviceCiphertext(senderId, senderDeviceId, ciphertextType, ciphertext)

    fun shouldAcknowledgeDecrypt(envelopeId: String, result: DecryptResult): Boolean =
        directCipher.shouldAcknowledgeDecrypt(envelopeId, result)

    fun clearDecryptRetryState(senderId: String, content: String) =
        directCipher.clearDecryptRetryState(senderId, content)

    fun clearDecryptFailure(senderId: String, content: String) =
        directCipher.clearDecryptFailure(senderId, content)

    fun clearDecryptRetryStateForSender(senderId: String) =
        directCipher.clearDecryptRetryStateForSender(senderId)

    override fun isDecryptTerminalFailure(senderId: String, content: String): Boolean =
        directCipher.isDecryptTerminalFailure(senderId, content)

    override fun isDecryptRetryExhausted(senderId: String, content: String): Boolean =
        directCipher.isDecryptRetryExhausted(senderId, content)

    // ==========================================
    // EnvelopeCodec
    // ==========================================

    override fun envelopePayloadType(content: String): String? =
        envelopeCodec.envelopePayloadType(content)

    override fun isEncryptedEnvelope(content: String): Boolean =
        envelopeCodec.isEncryptedEnvelope(content)

    override fun isSenderKeyEnvelope(content: String): Boolean =
        envelopeCodec.isSenderKeyEnvelope(content)

    fun isSenderKeyDistributionEnvelope(content: String): Boolean =
        envelopeCodec.isSenderKeyDistributionEnvelope(content)

    fun buildSenderKeyDistributionEnvelope(
        groupId: String,
        distributionId: String,
        message: SenderKeyDistributionMessage,
        epoch: Long = 0
    ): String = envelopeCodec.buildSenderKeyDistributionEnvelope(
        groupId, distributionId, message, epoch, getDeviceId()
    )

    fun parseSenderKeyEnvelopeEpoch(content: String): Long? =
        envelopeCodec.parseSenderKeyEnvelopeEpoch(content)

    fun buildUnsupportedSenderKeyEnvelope(
        groupId: String,
        distributionId: String,
        payloadType: String,
        ciphertext: String,
        epoch: Long = 0,
        senderDeviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID
    ): String = envelopeCodec.buildUnsupportedSenderKeyEnvelope(
        groupId, distributionId, payloadType, ciphertext, epoch, senderDeviceId
    )

    // ==========================================
    // IdentityTrustService
    // ==========================================

    override fun stateFor(peerAccountId: String, deviceId: Int): com.maodouchat.core.crypto.IdentityTrustState =
        identityTrustService.stateFor(peerAccountId, deviceId)

    override fun markVerified(peerAccountId: String, deviceId: Int): com.maodouchat.core.crypto.IdentityTrustState =
        identityTrustService.markVerified(peerAccountId, deviceId)

    override fun onIdentityKeyChanged(peerAccountId: String, deviceId: Int): com.maodouchat.core.crypto.IdentityTrustState =
        identityTrustService.onIdentityKeyChanged(peerAccountId, deviceId)

    override fun reset(peerAccountId: String, deviceId: Int): com.maodouchat.core.crypto.IdentityTrustState =
        identityTrustService.reset(peerAccountId, deviceId)

    fun getIdentityPublicKey(): IdentityKey =
        identityTrustService.getIdentityPublicKey()

    fun getLocalIdentityFingerprint(): String =
        identityTrustService.getLocalIdentityFingerprint()

    fun getRemoteIdentityFingerprint(remoteUserId: String, deviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID): String? =
        identityTrustService.getRemoteIdentityFingerprint(remoteUserId, deviceId)

    fun identityFingerprintFromBase64(identityKeyBase64: String): String? =
        identityTrustService.identityFingerprintFromBase64(identityKeyBase64)

    fun getIdentityTrustState(remoteUserId: String, deviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID): IdentityTrustState =
        identityTrustService.getIdentityTrustState(remoteUserId, deviceId)

    fun getSafetyCode(remoteUserId: String, deviceId: Int = SignalProtocolConstants.DEFAULT_DEVICE_ID): String? =
        identityTrustService.getSafetyCode(remoteUserId, deviceId)

    fun markIdentityVerified(remoteUserId: String, deviceId: Int): Boolean =
        identityTrustService.markIdentityVerified(remoteUserId, deviceId)

    suspend fun getRemoteDeviceSafetyStates(token: String, remoteUserId: String): Result<List<DeviceSafetyState>> =
        identityTrustService.getRemoteDeviceSafetyStates(token, remoteUserId)

    fun signDeviceConfirmation(targetDeviceId: Int, targetIdentityKeyBase64: String): String? =
        identityTrustService.signDeviceConfirmation(targetDeviceId, targetIdentityKeyBase64)

    // ==========================================
    // DeviceIdMigrationCoordinator & Device State
    // ==========================================

    override suspend fun recoverFromConflict(token: String?): Boolean =
        deviceIdCoordinator.recoverFromConflict(token)

    override fun isMigrationPending(): Boolean =
        deviceIdCoordinator.isMigrationPending()

    fun getDeviceId(): Int = context.localDeviceId

    fun getRegistrationId(): Int = context.registrationId

    fun isInitializedFor(userId: String): Boolean =
        context.isInitializedFor(userId)

    fun isLocalStoreReadyFor(userId: String): Boolean =
        context.isLocalStoreReadyFor(userId)

    fun isLocalCryptoReadyFor(userId: String): Boolean =
        context.isLocalCryptoReadyFor(userId)

    fun isDevicePendingApproval(): Boolean =
        context.devicePendingApproval

    fun wasIdentityRestoredFromStore(): Boolean =
        context.identityRestoredFromStore

    // ==========================================
    // GroupSenderKeyManager, GroupMessageCipher & GroupEncryptionHealthService
    // ==========================================

    override suspend fun createDistribution(conversationId: ConversationId, epoch: Long): Result<Unit> =
        groupSenderKeyManager.createDistribution(conversationId, epoch)

    override fun distributionUsable(conversationId: ConversationId, epoch: Long): Boolean =
        groupSenderKeyManager.distributionUsable(conversationId, epoch)

    override suspend fun invalidate(conversationId: ConversationId): Result<Unit> =
        groupSenderKeyManager.invalidate(conversationId)

    override suspend fun encrypt(conversationId: ConversationId, plaintext: String, epoch: Long): Result<String> =
        groupCipher.encrypt(conversationId, plaintext, epoch)

    override suspend fun decrypt(senderAccountId: String, ciphertext: String): Result<String> =
        groupCipher.decrypt(senderAccountId, ciphertext)

    override fun healthFor(conversationId: ConversationId, currentEpoch: Long?): GroupEncryptionHealth =
        groupEncryptionHealthService.healthFor(conversationId, currentEpoch)

    override suspend fun repair(conversationId: ConversationId, currentEpoch: Long?): Result<GroupEncryptionHealth> =
        groupEncryptionHealthService.repair(conversationId, currentEpoch)

    fun processSenderKeyDistributionEnvelope(
        senderId: String,
        content: String,
        expectedGroupId: String? = null,
        currentEpoch: Long? = null
    ): SenderKeyDistOutcome =
        groupCipher.processSenderKeyDistributionEnvelope(senderId, content, expectedGroupId, currentEpoch)

    fun createGroupSenderKeyDistribution(groupId: String, epoch: Long = 0): SenderKeyDistributionPayload =
        groupSenderKeyManager.createGroupSenderKeyDistribution(groupId, epoch)

    fun hasGroupDistributionId(groupId: String, epoch: Long = 0): Boolean =
        groupSenderKeyManager.hasGroupDistributionId(groupId, epoch)

    fun groupDistributionUsable(groupId: String, epoch: Long = 0): Boolean =
        groupSenderKeyManager.groupDistributionUsable(groupId, epoch)

    fun shouldRotateGroupSenderKey(groupId: String, epoch: Long = 0): Boolean =
        groupSenderKeyManager.shouldRotateGroupSenderKey(groupId, epoch)

    fun markGroupSenderKeyMessageSent(groupId: String, epoch: Long = 0, messageId: String? = null) =
        groupSenderKeyManager.markGroupSenderKeyMessageSent(groupId, epoch, messageId)

    fun invalidateGroupSenderKey(groupId: String): Boolean =
        groupSenderKeyManager.invalidateGroupSenderKey(groupId)

    fun encryptGroupTextEnvelope(groupId: String, plaintext: String, payloadType: String, epoch: Long = 0): Result<String> =
        groupCipher.encryptGroupTextEnvelope(groupId, plaintext, payloadType, epoch)

    fun encryptGroupContentEnvelope(groupId: String, plaintext: String, payloadType: String, epoch: Long = 0): Result<String> =
        groupCipher.encryptGroupContentEnvelope(groupId, plaintext, payloadType, epoch)

    fun decryptGroupContentEnvelope(
        senderId: String,
        content: String,
        expectedGroupId: String? = null,
        currentEpoch: Long? = null
    ): DecryptResult =
        groupCipher.decryptGroupContentEnvelope(senderId, content, expectedGroupId, currentEpoch)

    // ==========================================
    // Nested Data Types and Backward Compatibility
    // ==========================================

    enum class IdentityTrustState {
        UNKNOWN,
        TRUSTED,
        VERIFIED,
        CHANGED
    }

    data class DeviceSafetyState(
        val deviceId: Int,
        val identityKey: String,
        val identityFingerprint: String,
        val trustState: IdentityTrustState,
        val safetyCode: String?,
        val isCurrent: Boolean = false
    )

    data class SenderKeyDistributionPayload(
        val distributionId: String,
        val message: SenderKeyDistributionMessage,
        val epoch: Long
    )
}

enum class SenderKeyDistOutcome {
    Installed,
    Skipped,
    Failed
}

internal object SenderKeyDistributionFailurePolicy {
    fun outcomeFor(error: Throwable): SenderKeyDistOutcome = when (error) {
        is InvalidMessageException,
        is IllegalArgumentException -> SenderKeyDistOutcome.Skipped
        else -> SenderKeyDistOutcome.Failed
    }
}

class NoRecipientDevicesException : IllegalStateException()

class SignalStorePersistenceException(cause: Throwable) :
    java.io.IOException("signal_store_persistence_failed", cause)
