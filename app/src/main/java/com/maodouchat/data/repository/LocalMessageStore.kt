package com.maodouchat.data.repository

import androidx.room.withTransaction
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.dao.MessageDao
import com.maodouchat.data.local.entity.MessageEntity
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.local.entity.toEntity
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageReaction
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * SQLCipher-backed local timeline store.
 *
 * @param database 可选的 AppDatabase 引用。传入后，读-改-写操作会包裹在 withTransaction 中，
 *                 避免 v2 Inbox 与本地乐观状态并发覆盖 reactions/status。
 *                 传 null 时退化为非事务模式，仍能工作但存在竞态。
 */
class LocalMessageStore(
    private val messageDao: MessageDao,
    private val database: AppDatabase? = null,
) {

    /** Observe the device-local decrypted timeline for one conversation. */
    fun getMessagesByChatId(chatId: String): Flow<List<Message>> =
        messageDao.getMessagesByChatId(chatId).map { list -> list.map { it.toDomain() } }

    /** SQLCipher-backed media center view; no decrypted content is copied to another store. */
    fun observeMediaCenterMessages(chatId: String): Flow<List<Message>> =
        messageDao.observeMediaCenterMessages(chatId).map { list -> list.map { it.toDomain() } }

    /** 获取指定会话的最近 N 条消息（一次性，用于快速预加载） */
    suspend fun getRecentMessages(chatId: String, limit: Int = 50): List<Message> =
        messageDao.getRecentMessages(chatId, limit).map { it.toDomain() }

    suspend fun getMessageIdsByChatId(chatId: String): List<String> =
        messageDao.getMessageIdsByChatId(chatId)

    /** 按 ID 查找单条消息 */
    suspend fun getMessageById(messageId: String): Message? =
        messageDao.getMessageById(messageId)?.toDomain()

    suspend fun getMessagesByIds(messageIds: List<String>): List<Message> {
        if (messageIds.isEmpty()) return emptyList()
        return messageIds.chunked(500).flatMap { ids ->
            messageDao.getMessagesByIds(ids).map { it.toDomain() }
        }
    }

    suspend fun getLatestIncomingMessage(chatId: String, ownerUserId: String): Message? =
        messageDao.getLatestIncomingMessage(chatId, ownerUserId)?.toDomain()

    /** 目标时间点（含）之后的第一条消息，用于日历/日期跳转精确定位。 */
    suspend fun getFirstMessageAtOrAfter(chatId: String, fromTimestamp: Long): Message? =
        messageDao.getFirstMessageAtOrAfter(chatId, fromTimestamp)?.toDomain()

    /** 会话内最早一条消息的时间，用于日历可选范围上限。 */
    suspend fun getEarliestMessageTimestamp(chatId: String): Long? =
        messageDao.getEarliestMessageTimestamp(chatId)

    /**
     * 插入/更新消息。若本地已有同 id 行，合并后写入，避免 REPLACE 把 READ 回退成 DELIVERED/SENT。
     * 若 [database] 已注入，读-改-写包裹在单一事务中，避免并发覆盖。
     */
    suspend fun insertMessage(message: Message) {
        if (database == null) {
            persistMerged(message)
            return
        }
        database.withTransaction {
            persistMerged(message)
        }
    }

    /** 批量插入消息（逐条合并，保持投递状态单调）。整体包裹在单一事务中（若 [database] 已注入）。 */
    suspend fun insertMessages(messages: List<Message>) {
        if (messages.isEmpty()) return
        if (database == null) {
            messages.forEach { persistMerged(it) }
            return
        }
        database.withTransaction {
            // 9.213：批量预查消除 N+1（断线收敛热路径每页可达 100 条，原先逐条 SELECT）；
            // 分批 500 条规避 SQLite 绑定变量上限。
            val existingById = HashMap<String, MessageEntity>(messages.size)
            messages.map { it.id }.chunked(500).forEach { chunk ->
                messageDao.getMessagesByIds(chunk).forEach { existingById[it.id] = it }
            }
            messages.forEach { msg ->
                persistMerged(msg, existingById)
            }
        }
    }

    private suspend fun persistMerged(
        message: Message,
        existingById: MutableMap<String, MessageEntity>? = null,
    ) {
        val existingByPrimary = existingById?.get(message.id)?.toDomain()
            ?: messageDao.getMessageById(message.id)?.toDomain()
        val existing = existingByPrimary ?: findSameDelivery(message)
        val incoming = when {
            existing == null || existing.id == message.id -> message
            else -> MessageDuplicatePolicy.pickCanonical(existing, message)
        }
        val merged = if (existing == null) incoming else mergeMessageForPersistence(existing, incoming)
        if (existing != null && MessageDuplicatePolicy.isRedundantWrite(existing, merged)) return
        val entity = merged.toEntity()
        messageDao.insertMessage(entity)
        existingById?.put(entity.id, entity)
    }

    suspend fun findMessagesByDeliveryHint(
        chatId: String,
        senderId: String,
        timestamp: Long,
    ): List<Message> {
        if (chatId.isBlank() || senderId.isBlank() || timestamp <= 0L) return emptyList()
        return messageDao.getMessagesByDeliveryHint(chatId, senderId, timestamp).map { it.toDomain() }
    }

    private suspend fun findSameDelivery(message: Message): Message? {
        if (message.chatId.isBlank() || message.senderId.isBlank() || message.timestamp <= 0L) return null
        return findMessagesByDeliveryHint(message.chatId, message.senderId, message.timestamp)
            .firstOrNull { it.id != message.id && MessageDuplicatePolicy.isSameDelivery(it, message) }
    }

    /** 9.213：批量查重——返回已存在于库中的消息 id 集合（断线收敛新消息判定，消除逐条 SELECT）。 */
    suspend fun getExistingMessageIds(ids: List<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        if (database != null) {
            return database.withTransaction {
                val found = HashSet<String>(ids.size)
                ids.chunked(500).forEach { chunk ->
                    messageDao.getMessagesByIds(chunk).forEach { found.add(it.id) }
                }
                found
            }
        }
        return ids.filterTo(HashSet(ids.size)) { messageDao.getMessageById(it) != null }
    }

    /**
     * Apply a full reaction snapshot from the server/WS without touching body/status.
     * Empty list is valid (all reactions cleared) — must not keep the previous set.
     */
    suspend fun updateMessageReactions(messageId: String, reactions: List<MessageReaction>): Boolean {
        if (database == null) {
            val existing = messageDao.getMessageById(messageId)?.toDomain() ?: return false
            if (existing.type == MessageType.REVOKED) return false
            if (existing.reactions == reactions) return true
            messageDao.insertMessage(existing.copy(reactions = reactions).toEntity())
            return true
        }
        return database.withTransaction {
            val existing = messageDao.getMessageById(messageId)?.toDomain() ?: return@withTransaction false
            if (existing.type == MessageType.REVOKED) return@withTransaction false
            if (existing.reactions == reactions) return@withTransaction true
            messageDao.insertMessage(existing.copy(reactions = reactions).toEntity())
            true
        }
    }

    /** Applies an edit without allowing a stale local post-commit write to cross revoke/delete. */
    suspend fun applyEditedMessage(candidate: Message): Message? = mutateExisting(candidate.id) { existing ->
        applyEditedMessageVersion(existing, candidate)
    }

    /** Revoke is terminal and never recreates a message already deleted by another device. */
    suspend fun applyRevokedMessage(candidate: Message): Message? = mutateExisting(candidate.id) { existing ->
        applyRevokedMessageVersion(existing, candidate)
    }

    /** Reads and writes under one SQLCipher transaction so concurrent actors cannot lose reactions. */
    suspend fun mutateMessageReactions(
        messageId: String,
        transform: (List<MessageReaction>) -> List<MessageReaction>,
    ): Message? = mutateExisting(messageId) { existing ->
        if (existing.type == MessageType.REVOKED) null
        else existing.copy(reactions = transform(existing.reactions))
    }

    private suspend fun mutateExisting(
        messageId: String,
        transform: (Message) -> Message?,
    ): Message? {
        suspend fun apply(): Message? {
            val existing = messageDao.getMessageById(messageId)?.toDomain() ?: return null
            val updated = transform(existing) ?: return null
            if (updated != existing) messageDao.insertMessage(updated.toEntity())
            return updated
        }
        return if (database == null) apply() else database.withTransaction { apply() }
    }

    /** 更新消息状态（单调升级，防止 READ 被迟到的 DELIVERED 覆盖） */
    suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {
        messageDao.updateMessageStatusIfAdvanced(messageId, status.name)
    }

    suspend fun updateMessageSealedSender(messageId: String, sealedSender: Boolean): Boolean =
        messageDao.updateMessageSealedSender(messageId, sealedSender) > 0

    suspend fun persistLocalMediaMeta(localSnapshot: Message): Boolean {
        repeat(3) {
            val currentEntity = messageDao.getMessageById(localSnapshot.id)
            if (currentEntity == null) {
                if (messageDao.insertMessageIfAbsent(localSnapshot.toEntity()) != -1L) return true
                return@repeat
            }
            val current = currentEntity.toDomain()
            val merged = mergeLocalMediaMetaForPersistence(current, localSnapshot)
            if (merged.content == current.content) return true
            if (messageDao.updateMessageContentIfUnchanged(
                    messageId = current.id,
                    expectedContent = current.content,
                    content = merged.content
                ) == 1
            ) {
                return true
            }
        }
        return false
    }

    /** 删除单条消息 */
    suspend fun deleteMessage(messageId: String) =
        messageDao.deleteMessageById(messageId)

    /** 删除所有已过期的阅后即焚消息，返回被删除的消息 id 列表（用于清理媒体缓存） */
    suspend fun deleteExpiredMessages(now: Long = System.currentTimeMillis()): List<String> {
        val ids = messageDao.getExpiredMessageIds(now)
        if (ids.isEmpty()) return emptyList()
        // 8.47 修复：分批删除（SQLite 变量数上限 ~999）——大批自毁消息同时到期时
        // 全量 IN(...) 会抛 "too many SQL variables" 崩溃
        ids.chunked(900).forEach { chunk -> messageDao.deleteMessagesByIds(chunk) }
        return ids
    }

    /** 删除指定聊天的所有消息 */
    suspend fun deleteMessagesByChatId(chatId: String) =
        messageDao.deleteMessagesByChatId(chatId)
}
