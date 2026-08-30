package com.maodouchat.core.crypto

/** 群加密健康状态（M04）：Sender Key 覆盖/epoch 是否可用。 */
enum class GroupEncryptionHealth {
    /** 持有当前 epoch 的分发密钥，可正常加密群消息。 */
    HEALTHY,

    /** 没有分发密钥，须发起缺钥修复。 */
    MISSING_KEY,

    /** 有密钥但 epoch 落后，须重新分发。 */
    STALE_EPOCH,

    /** 本地不知道当前 epoch，不能判断是否可用。 */
    UNKNOWN_EPOCH,
}

/**
 * 群加密健康判定（纯逻辑，无 libsignal/UI 依赖）。
 * 对应旧 `groupDistributionUsable` / epoch 对比语义，抽为可测试纯函数。
 */
object GroupEncryptionHealthPolicy {
    fun evaluate(
        hasDistributionKey: Boolean,
        localEpoch: Long?,
        currentEpoch: Long?,
    ): GroupEncryptionHealth = when {
        currentEpoch == null || currentEpoch <= 0L -> GroupEncryptionHealth.UNKNOWN_EPOCH
        !hasDistributionKey || localEpoch == null -> GroupEncryptionHealth.MISSING_KEY
        localEpoch < currentEpoch -> GroupEncryptionHealth.STALE_EPOCH
        else -> GroupEncryptionHealth.HEALTHY
    }
}
