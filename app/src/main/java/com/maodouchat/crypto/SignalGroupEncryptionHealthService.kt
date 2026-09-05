package com.maodouchat.crypto

import com.maodouchat.core.crypto.GroupEncryptionHealth
import com.maodouchat.core.crypto.GroupEncryptionHealthPolicy
import com.maodouchat.core.crypto.GroupEncryptionHealthService
import com.maodouchat.core.model.ConversationId

/**
 * 群加密健康管理服务（M04 解耦）。
 * 遵循 [GroupEncryptionHealthService] 契约。
 * 负责集中判定群聊的 Sender Key 覆盖状态 (HEALTHY, MISSING_KEY, STALE_EPOCH, UNKNOWN_EPOCH) 并支持修复。
 */
class SignalGroupEncryptionHealthService internal constructor(
    private val groupSenderKeyManager: SignalGroupSenderKeyManager,
    private val context: SignalProtocolContext
) : GroupEncryptionHealthService {

    override fun healthFor(conversationId: ConversationId, currentEpoch: Long?): GroupEncryptionHealth {
        val epochKey = context.scopedKey("${SignalProtocolConstants.KEY_GROUP_DISTRIBUTION_EPOCH_PREFIX}${conversationId.value}")
        val localEpoch = context.signalKeyDao.getKeyBlocking(epochKey)?.keyData?.toLongOrNull()
        val hasKey = groupSenderKeyManager.hasGroupDistributionId(conversationId.value, localEpoch ?: 0L)
        return GroupEncryptionHealthPolicy.evaluate(
            hasDistributionKey = hasKey,
            localEpoch = localEpoch,
            currentEpoch = currentEpoch
        )
    }

    override suspend fun repair(conversationId: ConversationId, currentEpoch: Long?): Result<GroupEncryptionHealth> = runCatching {
        val targetEpoch = currentEpoch ?: 1L
        groupSenderKeyManager.createGroupSenderKeyDistribution(conversationId.value, targetEpoch)
        healthFor(conversationId, targetEpoch)
    }
}
