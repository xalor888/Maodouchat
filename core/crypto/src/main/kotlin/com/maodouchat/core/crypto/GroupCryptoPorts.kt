package com.maodouchat.core.crypto

import com.maodouchat.core.model.ConversationId

/** 群 Sender Key 管理器（M04）：分发密钥的创建/轮换/失效，与 UI/Room/HTTP 解耦。 */
interface GroupSenderKeyManager {
    /** 为 [conversationId] 在 [epoch] 创建并持久化分发。 */
    suspend fun createDistribution(conversationId: ConversationId, epoch: Long): Result<Unit>

    /** 当前分发密钥在 [epoch] 是否可用。 */
    fun distributionUsable(conversationId: ConversationId, epoch: Long): Boolean

    /** 成员 revision 变化后失效旧分发，保证旧 prepared ciphertext 不跨 revision 发送。 */
    suspend fun invalidate(conversationId: ConversationId): Result<Unit>
}

/** 群消息加解密（M04）。 */
interface GroupMessageCipher {
    suspend fun encrypt(conversationId: ConversationId, plaintext: String, epoch: Long): Result<String>
    suspend fun decrypt(senderAccountId: String, ciphertext: String): Result<String>
}

/** 群加密健康服务（M04）：唯一管理 coverage、epoch、repair 与错误状态。 */
interface GroupEncryptionHealthService {
    fun healthFor(conversationId: ConversationId, currentEpoch: Long?): GroupEncryptionHealth
    suspend fun repair(conversationId: ConversationId, currentEpoch: Long?): Result<GroupEncryptionHealth>
}
