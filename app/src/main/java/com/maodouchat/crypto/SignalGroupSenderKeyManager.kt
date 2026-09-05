package com.maodouchat.crypto

import android.util.Log
import com.maodouchat.core.crypto.GroupSenderKeyManager
import com.maodouchat.core.model.ConversationId
import com.maodouchat.data.local.entity.SignalKeyEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.withLock

/**
 * Signal 群 Sender Key 管理器（M04 解耦）。
 * 遵循 [GroupSenderKeyManager] 契约。
 * 负责群 Sender Key 分发标识的创建、可用性检查、轮换与失效。
 */
class SignalGroupSenderKeyManager internal constructor(
    private val context: SignalProtocolContext
) : GroupSenderKeyManager {

    private val countedGroupMessageIds = ConcurrentHashMap.newKeySet<String>()
    private val countedGroupMessageOrder = ArrayDeque<String>()

    override suspend fun createDistribution(conversationId: ConversationId, epoch: Long): Result<Unit> = runCatching {
        createGroupSenderKeyDistribution(conversationId.value, epoch)
        Unit
    }

    override fun distributionUsable(conversationId: ConversationId, epoch: Long): Boolean =
        groupDistributionUsable(conversationId.value, epoch)

    override suspend fun invalidate(conversationId: ConversationId): Result<Unit> = runCatching {
        if (!invalidateGroupSenderKey(conversationId.value)) {
            error("failed_to_invalidate_sender_key")
        }
    }

    fun createGroupSenderKeyDistribution(groupId: String, epoch: Long = 0): SignalProtocol.SenderKeyDistributionPayload {
        val owner = context.currentUserId
        check(!owner.isNullOrBlank() && context.isLocalCryptoReadyFor(owner)) { "signal_not_initialized" }
        return context.cryptoLock.withLock {
            val distributionId = getOrCreateGroupDistributionId(groupId, epoch)
            val senderAddress = SignalProtocolAddress(
                context.currentUserId ?: SignalProtocolConstants.ANONYMOUS_ACCOUNT_ID,
                context.localDeviceId
            )
            val message = GroupSessionBuilder(context.protocolStore).create(senderAddress, UUID.fromString(distributionId))
            context.throwIfSignalStorePersistenceFailed()
            SignalProtocol.SenderKeyDistributionPayload(distributionId, message, epoch)
        }
    }

    fun hasGroupDistributionId(groupId: String, epoch: Long = 0): Boolean {
        val key = context.scopedKey("${SignalProtocolConstants.KEY_GROUP_DISTRIBUTION_PREFIX}$groupId")
        val epochKey = context.scopedKey("${SignalProtocolConstants.KEY_GROUP_DISTRIBUTION_EPOCH_PREFIX}$groupId")
        val savedEpoch = context.signalKeyDao.getKeyBlocking(epochKey)?.keyData?.toLongOrNull() ?: 0L
        return context.signalKeyDao.getKeyBlocking(key)?.keyData
            ?.let { runCatching { UUID.fromString(it) }.isSuccess && savedEpoch == epoch } == true
    }

    fun groupDistributionUsable(groupId: String, epoch: Long = 0): Boolean {
        if (!hasGroupDistributionId(groupId, epoch)) return false
        return context.cryptoLock.withLock {
            runCatching {
                val key = context.scopedKey("${SignalProtocolConstants.KEY_GROUP_DISTRIBUTION_PREFIX}$groupId")
                val distributionId = context.signalKeyDao.getKeyBlocking(key)?.keyData ?: return@withLock false
                val senderAddress = SignalProtocolAddress(
                    context.currentUserId ?: SignalProtocolConstants.ANONYMOUS_ACCOUNT_ID,
                    context.localDeviceId
                )
                context.protocolStore.loadSenderKey(senderAddress, UUID.fromString(distributionId)) != null
            }.getOrDefault(false)
        }
    }

    fun shouldRotateGroupSenderKey(groupId: String, epoch: Long = 0): Boolean {
        if (!hasGroupDistributionId(groupId, epoch)) return false
        val metadata = loadGroupDistributionMetadata(groupId)
        val ageMs = System.currentTimeMillis() - metadata.createdAt
        return ageMs >= SignalProtocolConstants.GROUP_SENDER_KEY_MAX_AGE_MS ||
            metadata.messageCount >= SignalProtocolConstants.GROUP_SENDER_KEY_MAX_MESSAGES
    }

    fun markGroupSenderKeyMessageSent(groupId: String, epoch: Long = 0, messageId: String? = null) {
        if (groupId.isBlank()) return
        if (!messageId.isNullOrBlank()) {
            val dedupeKey = "$groupId:$epoch:$messageId"
            synchronized(countedGroupMessageIds) {
                if (!countedGroupMessageIds.add(dedupeKey)) return
                countedGroupMessageOrder.addLast(dedupeKey)
                while (countedGroupMessageOrder.size > SignalProtocolConstants.MAX_COUNTED_GROUP_MESSAGE_IDS) {
                    countedGroupMessageIds.remove(countedGroupMessageOrder.removeFirst())
                }
            }
        }
        context.cryptoLock.withLock {
            val metadata = loadGroupDistributionMetadata(groupId)
            saveGroupDistributionMetadata(groupId, metadata.copy(epoch = epoch, messageCount = metadata.messageCount + 1))
        }
    }

    fun invalidateGroupSenderKey(groupId: String): Boolean {
        if (groupId.isBlank()) return false
        return context.cryptoLock.withLock {
            val key = context.scopedKey("${SignalProtocolConstants.KEY_GROUP_DISTRIBUTION_PREFIX}$groupId")
            val epochKey = context.scopedKey("${SignalProtocolConstants.KEY_GROUP_DISTRIBUTION_EPOCH_PREFIX}$groupId")
            runCatching {
                context.signalKeyDao.deleteKeyBlocking(key)
                context.signalKeyDao.deleteKeyBlocking(epochKey)
                context.signalKeyDao.deleteKeyBlocking(context.scopedKey("${SignalProtocolConstants.KEY_GROUP_DISTRIBUTION_METADATA_PREFIX}$groupId"))
                true
            }.onFailure {
                Log.w(SignalProtocolConstants.TAG, "Failed to invalidate group SenderKey for $groupId", it)
            }.getOrDefault(false)
        }
    }

    internal fun getOrCreateGroupDistributionId(groupId: String, epoch: Long): String {
        val key = context.scopedKey("${SignalProtocolConstants.KEY_GROUP_DISTRIBUTION_PREFIX}$groupId")
        val epochKey = context.scopedKey("${SignalProtocolConstants.KEY_GROUP_DISTRIBUTION_EPOCH_PREFIX}$groupId")
        val saved = context.signalKeyDao.getKeyBlocking(key)?.keyData
        val savedEpoch = context.signalKeyDao.getKeyBlocking(epochKey)?.keyData?.toLongOrNull() ?: 0L
        return saved?.takeIf { runCatching { UUID.fromString(it) }.isSuccess && savedEpoch == epoch }
            ?: UUID.randomUUID().toString().also {
                context.signalKeyDao.insertKeyBlocking(SignalKeyEntity(key, it))
                context.signalKeyDao.insertKeyBlocking(SignalKeyEntity(epochKey, epoch.toString()))
                saveGroupDistributionMetadata(
                    groupId,
                    GroupDistributionMetadata(epoch = epoch, createdAt = System.currentTimeMillis(), messageCount = 0)
                )
            }
    }

    internal fun requireExistingGroupDistributionId(groupId: String, epoch: Long): String {
        val key = context.scopedKey("${SignalProtocolConstants.KEY_GROUP_DISTRIBUTION_PREFIX}$groupId")
        val epochKey = context.scopedKey("${SignalProtocolConstants.KEY_GROUP_DISTRIBUTION_EPOCH_PREFIX}$groupId")
        val saved = context.signalKeyDao.getKeyBlocking(key)?.keyData
        val savedEpoch = context.signalKeyDao.getKeyBlocking(epochKey)?.keyData?.toLongOrNull() ?: 0L
        val id = saved?.takeIf { runCatching { UUID.fromString(it) }.isSuccess && savedEpoch == epoch }
        return id ?: error("group_sender_key_not_distributed:epoch=$epoch")
    }

    private fun loadGroupDistributionMetadata(groupId: String): GroupDistributionMetadata {
        val key = context.scopedKey("${SignalProtocolConstants.KEY_GROUP_DISTRIBUTION_METADATA_PREFIX}$groupId")
        val raw = context.signalKeyDao.getKeyBlocking(key)?.keyData
        return raw?.let {
            runCatching { SignalProtocolConstants.json.decodeFromString(GroupDistributionMetadata.serializer(), it) }.getOrNull()
        } ?: GroupDistributionMetadata()
    }

    private fun saveGroupDistributionMetadata(groupId: String, metadata: GroupDistributionMetadata) {
        val key = context.scopedKey("${SignalProtocolConstants.KEY_GROUP_DISTRIBUTION_METADATA_PREFIX}$groupId")
        context.signalKeyDao.insertKeyBlocking(
            SignalKeyEntity(
                key,
                SignalProtocolConstants.json.encodeToString(GroupDistributionMetadata.serializer(), metadata)
            )
        )
    }

    @Serializable
    private data class GroupDistributionMetadata(
        val epoch: Long = 0,
        val createdAt: Long = System.currentTimeMillis(),
        val messageCount: Int = 0
    )
}
