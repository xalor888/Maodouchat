package com.maodouchat.domain.messaging

/**
 * NoSession 且为 Sender Key 密文时，解析需补发分发的 epoch：信封自带 groupRevision
 * 优先，其次取调用方提供的当前 revision，仍缺失或不大于 0 时不补发——拿不到 epoch
 * 就不该发起修复，否则会用错误的 epoch 请求 sender key。
 * 非 Sender Key 密文不补发，此时不调用 [providedRevision]。
 */
suspend fun resolveSenderKeyMissingEpoch(
    ciphertextType: String,
    envelopeGroupRevision: Long?,
    providedRevision: suspend () -> Long?,
): Long? {
    if (ciphertextType != SENDER_KEY_CIPHERTEXT_TYPE) return null
    val epoch = envelopeGroupRevision ?: providedRevision() ?: 0L
    return epoch.takeIf { it > 0L }
}
