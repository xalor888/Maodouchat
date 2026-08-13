package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.Messages
import com.maodouchat.server.db.MessageMutations
import com.maodouchat.server.db.EncryptedAttachments
import com.maodouchat.server.db.MessageReactions
import com.maodouchat.server.db.PinnedMessages
import com.maodouchat.server.db.ReadReceipts
import com.maodouchat.server.db.StarMessages
import com.maodouchat.server.model.MessageMutationResponse
import com.maodouchat.server.model.MessageReactionResponse
import com.maodouchat.server.model.MessageResponse
import com.maodouchat.server.service.SealedSenderDelivery
import com.maodouchat.server.model.ReadReceiptResponse
import com.maodouchat.server.model.UnreadWindowResponse
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInSubQuery
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inSubQuery
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import java.util.UUID

class DuplicateMessageIdException(messageId: String) : IllegalArgumentException("Duplicate message id: $messageId")

/** Attachment cipher object is missing or not yet UPLOADED when the media message is sent. */
class AttachmentNotReadyException(messageId: String) : IllegalStateException("Attachment not ready for message: $messageId")

/** Sender is no longer a chat participant at insert time (leave/kick race). */
class NotParticipantException(chatId: String, userId: String) :
    IllegalStateException("User $userId is not a participant of chat $chatId")

/** Sender is muted in this group at insert time (mute race after pre-check). */
class MutedException(chatId: String, userId: String) :
    IllegalStateException("User $userId is muted in chat $chatId")

/** Peer has blocked the sender at insert time (block race after pre-check). */
class BlockedException(chatId: String, userId: String) :
    IllegalStateException("User $userId is blocked from chat $chatId")

/** Client-facing send API attempted to create a server-controlled message type. */
class InvalidMessageTypeException(type: String) :
    IllegalArgumentException("Message type is not client-sendable: $type")

/** 发送结果：消息体 + 同事务内权威成员列表（供 fanout，避免路由层快照过期）。 */
data class SentMessage(
    val message: MessageResponse,
    val participantIds: List<String>,
    /** true when the message id already existed (idempotent re-send after a lost ACK); the caller must NOT re-fan-out. */
    val wasExisting: Boolean = false
)

class MessageRepository {

    private data class LockedMessage(val chat: ResultRow, val message: ResultRow)

    /** Must run inside a transaction. Global aggregate order is chat -> message -> attachment. */
    private fun lockChatThenMessage(messageId: String): LockedMessage? {
        val chatId = Messages.select(Messages.chatId)
            .where { Messages.id eq messageId }
            .limit(1)
            .firstOrNull()
            ?.get(Messages.chatId)
            ?: return null
        val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: return null
        val message = Messages.selectAll().where {
            (Messages.id eq messageId) and (Messages.chatId eq chatId)
        }.forUpdate().firstOrNull() ?: return null
        return LockedMessage(chat, message)
    }

    fun sendMessage(chatId: String, senderId: String, content: String, type: String = "TEXT", requestedId: String? = null, sealedSender: Boolean = false): SentMessage {
        if (type !in CLIENT_SENDABLE_TYPES) throw InvalidMessageTypeException(type)
        return sendMessageInternal(chatId, senderId, content, type, requestedId, sealedSender)
    }

    fun sendNudgeMessage(chatId: String, senderId: String, content: String): SentMessage =
        sendMessageInternal(chatId, senderId, content, type = "NUDGE", requestedId = null, sealedSender = false)

    private fun sendMessageInternal(
        chatId: String,
        senderId: String,
        content: String,
        type: String,
        requestedId: String?,
        sealedSender: Boolean
    ): SentMessage {
        val requestedMessageId = requestedId?.takeIf { it.isNotBlank() }
        return try {
            transaction {
                // 与 leave/kick/mute 串行：先锁 chat 行再校验成员与禁言
                val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
                    ?: throw NotParticipantException(chatId, senderId)
                val participantRow = ChatParticipants.selectAll().where {
                    (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq senderId)
                }.forUpdate().firstOrNull()
                    ?: throw NotParticipantException(chatId, senderId)
                // 群禁言：在锁内复检，避免 isMuted 预检通过后管理员立刻禁言仍插入
                if (chat[Chats.isGroup] &&
                    participantRow[ChatParticipants.role] != "OWNER" &&
                    participantRow[ChatParticipants.mutedUntil] > System.currentTimeMillis()
                ) {
                    throw MutedException(chatId, senderId)
                }

                val participantIds = ChatParticipants.selectAll()
                    .where { ChatParticipants.chatId eq chatId }
                    .map { it[ChatParticipants.userId] }

                // 1:1 拉黑：双向 — 任一方拉黑则拒绝（与创建私聊策略一致）
                if (!chat[Chats.isGroup]) {
                    val peers = participantIds.filter { it != senderId }
                    val blocked = peers.any { peerId ->
                        !BlockedUsers.selectAll().where {
                            (
                                (BlockedUsers.blockerId eq peerId) and (BlockedUsers.blockedId eq senderId)
                            ) or (
                                (BlockedUsers.blockerId eq senderId) and (BlockedUsers.blockedId eq peerId)
                            )
                        }.empty()
                    }
                    if (blocked) throw BlockedException(chatId, senderId)
                }

                if (requestedMessageId != null) {
                    val existing = Messages.selectAll().where { Messages.id eq requestedMessageId }.firstOrNull()
                    if (existing != null) {
                        return@transaction resolveExistingSend(
                            existing = existing,
                            chatId = chatId,
                            senderId = senderId,
                            content = content,
                            type = type,
                            sealedSender = sealedSender,
                            participantIds = participantIds
                        )
                    }
                }

                val id = requestedMessageId ?: "m_${UUID.randomUUID()}"
                val timestamp = System.currentTimeMillis()
                // 主键冲突时 PG/H2 会 abort 整事务，禁止在同事务内 catch 后继续写。
                // 冲突交给外层 catch，开新事务做幂等回读。
                Messages.insert {
                    it[Messages.id] = id
                    it[Messages.chatId] = chatId
                    it[Messages.senderId] = senderId
                    it[Messages.content] = content
                    it[Messages.type] = type
                    it[Messages.timestamp] = timestamp
                    it[Messages.status] = "SENT"
                    it[Messages.sealedSender] = sealedSender
                }
                if (type in ATTACHMENT_MESSAGE_TYPES) {
                    // 附件消息必须在同一事务内把已上传对象提交为 COMMITTED，否则对端永久无法下载
                    if (!commitPendingAttachment(id, chatId, senderId)) {
                        throw AttachmentNotReadyException(id)
                    }
                }
                SentMessage(
                    message = MessageResponse(id, chatId, senderId, content, type, timestamp, "SENT", editedAt = null),
                    participantIds = participantIds,
                    wasExisting = false
                )
            }
        } catch (e: Exception) {
            when (e) {
                is NotParticipantException, is MutedException, is BlockedException,
                is DuplicateMessageIdException, is AttachmentNotReadyException -> throw e
            }
            if (!isUniqueViolation(e)) throw e
            // 并发同 id 插入：赢家已提交；本事务已回滚，新事务回读做幂等
            val conflictId = requestedMessageId ?: throw e
            transaction {
                val existing = Messages.selectAll().where { Messages.id eq conflictId }.firstOrNull()
                    ?: throw e
                val participantIds = ChatParticipants.selectAll()
                    .where { ChatParticipants.chatId eq chatId }
                    .map { it[ChatParticipants.userId] }
                resolveExistingSend(
                    existing = existing,
                    chatId = chatId,
                    senderId = senderId,
                    content = content,
                    type = type,
                    sealedSender = sealedSender,
                    participantIds = participantIds
                )
            }
        }
    }

    private fun resolveExistingSend(
        existing: org.jetbrains.exposed.sql.ResultRow,
        chatId: String,
        senderId: String,
        content: String,
        type: String,
        sealedSender: Boolean,
        participantIds: List<String>,
    ): SentMessage {
        val existingResponse = existing.toMessageResponse()
        if (existingResponse.chatId == chatId &&
            existingResponse.senderId == senderId &&
            existingResponse.content == content &&
            existingResponse.type == type &&
            existing[Messages.sealedSender] == sealedSender
        ) {
            if (type in ATTACHMENT_MESSAGE_TYPES &&
                !commitPendingAttachment(existingResponse.id, chatId, senderId)
            ) {
                throw AttachmentNotReadyException(existingResponse.id)
            }
            return SentMessage(existingResponse, participantIds, wasExisting = true)
        }
        throw DuplicateMessageIdException(existingResponse.id)
    }

    /** @return true if an UPLOADED row was committed (or already COMMITTED for this message). */
    private fun commitPendingAttachment(messageId: String, chatId: String, senderId: String): Boolean {
        // FOR UPDATE：与 deleteForMessage / revoke 串行，避免删行与 COMMITTED 交叉
        val row = EncryptedAttachments.selectAll().where {
            (EncryptedAttachments.messageId eq messageId) and
                (EncryptedAttachments.chatId eq chatId) and
                (EncryptedAttachments.uploaderId eq senderId)
        }.forUpdate().firstOrNull() ?: return false
        when (row[EncryptedAttachments.status]) {
            "COMMITTED" -> return true
            "UPLOADED" -> {
                EncryptedAttachments.update({ EncryptedAttachments.id eq row[EncryptedAttachments.id] }) {
                    it[EncryptedAttachments.status] = "COMMITTED"
                    it[EncryptedAttachments.expiresAt] = null
                }
                return true
            }
            else -> return false
        }
    }

    fun getMessages(chatId: String, limit: Int = 50, viewerId: String? = null): List<MessageResponse> {
        return transaction {
            // 不过滤 SK_DIST 消息：客户端需要 SenderKey 分发消息来解密群聊消息。
            // 客户端 decryptIncomingMessage 会处理 SK_DIST 并返回 null（被 mapNotNull 过滤）。
            // viewer 拉黑的发送者（含其 SK_DIST）与实时 fanout 一致，不出现在历史里。
            val blockedSenders = blockedSenderIdsForViewerInTx(viewerId)
            val now = System.currentTimeMillis()
            val notExpired = (Messages.expiresAt.isNull()) or
                (Messages.expiresAt eq 0L) or
                (Messages.expiresAt greater now)
            val base = (Messages.chatId eq chatId) and notExpired
            val condition = if (blockedSenders.isEmpty()) base
            else base and (Messages.senderId notInList blockedSenders.toList())
            Messages.selectAll()
                .where { condition }
                .orderBy(Messages.timestamp to SortOrder.DESC, Messages.id to SortOrder.DESC)
                .limit(limit.coerceIn(1, 100))
                .map { it.toMessageResponse(viewerId) }
                .let { attachReactions(it, blockedSenders) }
                .reversed()
        }
    }

    /** 返回 (beforeMs,beforeId) 之前的历史消息，结果按时间+id 正序。 */
    fun getMessagesBefore(
        chatId: String,
        beforeMs: Long,
        beforeId: String? = null,
        limit: Int = 100,
        viewerId: String? = null,
    ): List<MessageResponse> {
        return transaction {
            val id = beforeId?.takeIf { it.isNotBlank() }
            val before = if (id == null) {
                (Messages.chatId eq chatId) and (Messages.timestamp less beforeMs)
            } else {
                (Messages.chatId eq chatId) and (
                    (Messages.timestamp less beforeMs) or
                        ((Messages.timestamp eq beforeMs) and (Messages.id less id))
                    )
            }
            val now = System.currentTimeMillis()
            val notExpired = (Messages.expiresAt.isNull()) or
                (Messages.expiresAt eq 0L) or
                (Messages.expiresAt greater now)
            val blockedSenders = blockedSenderIdsForViewerInTx(viewerId)
            var condition = before and notExpired
            if (blockedSenders.isNotEmpty()) {
                condition = condition and (Messages.senderId notInList blockedSenders.toList())
            }
            Messages.selectAll()
                .where { condition }
                .orderBy(Messages.timestamp to SortOrder.DESC, Messages.id to SortOrder.DESC)
                .limit(limit.coerceIn(1, 100))
                .map { it.toMessageResponse(viewerId) }
                .let { attachReactions(it, blockedSenders) }
                .reversed()
        }
    }

    /**
     * 多设备同步：返回指定 chatId 自 (sinceMs, sinceId) 之后的新消息（按时间+id 正序）
     * @param viewerId 当前读者；SQL 层过滤其拉黑发送者（与 WS NEW_MESSAGE 一致，游标不卡在隐藏行）
     */
    fun getMessagesSince(
        chatId: String,
        sinceMs: Long,
        limit: Int = 200,
        sinceId: String? = null,
        viewerId: String? = null,
    ): List<MessageResponse> {
        return transaction {
            val afterId = sinceId?.takeIf { it.isNotBlank() }
            // 同毫秒消息数 > limit 时，仅用 timestamp 会永远返回同一页。
            // 无 sinceId 且 sinceMs>0：用严格 greater（客户端应已消费过该 ms 边界上的旧 id）。
            // sinceMs==0 冷启动：仍用 greaterEq 以便从 0 拉起。
            // 有 sinceId：严格 (ts,id) 前缀游标。
            val timeCondition = when {
                afterId != null -> (Messages.chatId eq chatId) and (
                    (Messages.timestamp greater sinceMs) or (
                        (Messages.timestamp eq sinceMs) and (Messages.id greater afterId)
                    )
                )
                sinceMs <= 0L -> (Messages.chatId eq chatId) and (Messages.timestamp greaterEq sinceMs)
                else -> (Messages.chatId eq chatId) and (Messages.timestamp greater sinceMs)
            }
            val now = System.currentTimeMillis()
            val notExpired = (Messages.expiresAt.isNull()) or
                (Messages.expiresAt eq 0L) or
                (Messages.expiresAt greater now)
            val blockedSenders = blockedSenderIdsForViewerInTx(viewerId)
            val visible = timeCondition and notExpired
            val condition = if (blockedSenders.isEmpty()) visible
            else visible and (Messages.senderId notInList blockedSenders.toList())
            Messages.selectAll()
                .where { condition }
                .orderBy(Messages.timestamp to SortOrder.ASC, Messages.id to SortOrder.ASC)
                .limit(limit.coerceIn(1, 500))
                .map { it.toMessageResponse(viewerId) }
                .let { attachReactions(it, blockedSenders) }
        }
    }

    /**
     * viewer 需要隐藏的用户集合（双向拉黑语义，8.30 隐私修复）：
     * - viewer 拉黑的人（blocker=viewer）：拉黑方不想看到对方
     * - 拉黑 viewer 的人（blocked=viewer）：被拉黑方也不得继续读取拉黑方的历史消息/附件
     * 两者共同保证「拉黑 = 双方都从对方视线中消失」，与动态/附近/WS 广播的双向过滤一致。
     */
    private fun blockedSenderIdsForViewerInTx(viewerId: String?): Set<String> {
        if (viewerId.isNullOrBlank()) return emptySet()
        val blockedByMe = BlockedUsers.selectAll()
            .where { BlockedUsers.blockerId eq viewerId }
            .map { it[BlockedUsers.blockedId] }
            .toSet()
        val blockedMe = BlockedUsers.selectAll()
            .where { BlockedUsers.blockedId eq viewerId }
            .map { it[BlockedUsers.blockerId] }
            .toSet()
        return blockedByMe + blockedMe
    }

    /**
     * @param readerId when status is READ, also insert a per-user read receipt so unread queries clear.
     *                 Global Messages.status is only a coarse 1:1 delivery hint for senders.
     */
    fun updateStatus(messageId: String, status: String, readerId: String? = null): Boolean {
        return transaction {
            val resolvedReaderId = readerId?.takeIf(String::isNotBlank) ?: return@transaction false
            val locked = lockChatThenMessage(messageId) ?: return@transaction false
            val row = locked.message
            // 撤回消息不再接受状态变更（已读/送达回执），隐私加固（WS 层已提前丢弃，此为纵深防御）
            if (row[Messages.type] == "REVOKED") return@transaction false
            val chatId = locked.chat[Chats.id]
            val isParticipant = ChatParticipants.selectAll().where {
                (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq resolvedReaderId)
            }.firstOrNull() != null
            if (!isParticipant || row[Messages.senderId] == resolvedReaderId) return@transaction false
            val currentStatus = row[Messages.status]
            // 防止状态回退 — 只允许状态升级（SENT < DELIVERED < READ），不降级
            // FAILED 是客户端本地语义，服务端不接受覆盖已接受的发送状态
            if (statusRank(status) <= statusRank(currentStatus)) {
                // 即使全局状态不能降级/升级，单条 READ 仍应写入个人回执
                if (status == "READ") {
                    insertReadReceipt(messageId, resolvedReaderId)
                }
                return@transaction true
            }
            val isGroup = locked.chat[Chats.isGroup]
            // 群聊不把“任一成员已读”写成全局 READ，避免发送者误判全员已读
            if (!(isGroup && status == "READ")) {
                Messages.update({ Messages.id eq messageId }) {
                    it[Messages.status] = status
                }
            }
            if (status == "READ") {
                insertReadReceipt(messageId, resolvedReaderId)
            }
            true
        }
    }

    /**
     * 消息状态优先级：SENT(1) < DELIVERED(2) < READ(3)
     * FAILED 不可从 SENT 降级，也不作为服务端状态阶梯的一部分
     */
    private fun statusRank(status: String): Int = when (status) {
        "SENT" -> 1
        "DELIVERED" -> 2
        "READ" -> 3
        else -> 0
    }

    private fun insertReadReceipt(messageId: String, readerId: String) {
        // upsert：并发 READ 不得用同事务 catch unique（PG 会 abort 整事务）
        val readAt = System.currentTimeMillis()
        ReadReceipts.upsert(ReadReceipts.messageId, ReadReceipts.userId) {
            it[ReadReceipts.messageId] = messageId
            it[ReadReceipts.userId] = readerId
            it[ReadReceipts.readAt] = readAt
        }
    }

    /**
     * @return triples of (messageId, senderId, newlySetExpiresAt?)；
     * newlySetExpiresAt 非空时客户端应启动倒计时（仅 1:1 阅后即焚）。
     */
    fun markAllAsRead(chatId: String, readerId: String): List<Triple<String, String, Long?>> {
        return transaction {
            val chatRow = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
                ?: return@transaction emptyList()
            val isParticipant = ChatParticipants.selectAll().where {
                (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq readerId)
            }.firstOrNull() != null
            if (!isParticipant) return@transaction emptyList()
            val readByUser = ReadReceipts
                .select(ReadReceipts.messageId)
                .where { ReadReceipts.userId eq readerId }

            val isGroup = chatRow[Chats.isGroup]
            val blockedSenders = blockedSenderIdsForViewerInTx(readerId)
            val timerSeconds = if (!isGroup) {
                com.maodouchat.server.service.DisappearingMessagePolicy.effectiveSeconds(
                    isGroup = false,
                    requestedSeconds = chatRow[Chats.disappearingMessageSeconds]
                )
            } else {
                0
            }
            val readAt = System.currentTimeMillis()
            val results = mutableListOf<Triple<String, String, Long?>>()
            // 8.30 性能优化 B1：分批处理（每批 500 行），避免活跃大群一次"全部已读"
            // 把全部未读消息拉进内存 + 逐行 upsert；1:1 全局状态 UPDATE 与阅后即焚
            // 到期 UPDATE 均按 inList 批量。
            while (true) {
                val senderCondition = if (blockedSenders.isEmpty()) {
                    org.jetbrains.exposed.sql.Op.TRUE
                } else {
                    Messages.senderId notInList blockedSenders.toList()
                }
                val batch = Messages.selectAll()
                    .where {
                        (Messages.chatId eq chatId) and
                            (Messages.senderId neq readerId) and
                            senderCondition and
                            (Messages.type neq "SK_DIST") and (Messages.type neq "REVOKED") and
                            (Messages.id notInSubQuery readByUser)
                    }
                    .limit(MARK_READ_BATCH_SIZE)
                    .toList()
                if (batch.isEmpty()) break
                val ids = batch.map { it[Messages.id] }
                // 1:1 可把全局状态写成 READ；群聊只写个人回执，避免一人已读=全员已读
                if (!isGroup) {
                    Messages.update({
                        (Messages.chatId eq chatId) and
                            (Messages.senderId neq readerId) and
                            senderCondition and
                            (Messages.type neq "SK_DIST") and (Messages.type neq "REVOKED") and
                            (Messages.id inList ids)
                    }) {
                        it[Messages.status] = "READ"
                    }
                }
                // 批量写回执：先查已有回执，仅插入缺失行（H2 普通模式不支持 INSERT IGNORE；
                // markAllAsRead 持 chat 行锁，同 chat 并发已串行，不会撞 PK）
                val existingReceiptIds = ReadReceipts.select(ReadReceipts.messageId)
                    .where { (ReadReceipts.userId eq readerId) and (ReadReceipts.messageId inList ids) }
                    .map { it[ReadReceipts.messageId] }
                    .toSet()
                val toInsert = ids.filter { it !in existingReceiptIds }
                if (toInsert.isNotEmpty()) {
                    ReadReceipts.batchInsert(toInsert) { messageId ->
                        this[ReadReceipts.messageId] = messageId
                        this[ReadReceipts.userId] = readerId
                        this[ReadReceipts.readAt] = readAt
                    }
                }
                // 阅后即焚首次到期设置：只更新真正需要设置的子集
                if (timerSeconds > 0) {
                    val needExpiry = batch.filter { row ->
                        val existing = row[Messages.expiresAt]
                        existing == null || existing <= 0L
                    }.map { it[Messages.id] }
                    if (needExpiry.isNotEmpty()) {
                        Messages.update({
                            (Messages.id inList needExpiry) and
                                (Messages.expiresAt.isNull() or (Messages.expiresAt lessEq 0L))
                        }) {
                            it[Messages.expiresAt] = com.maodouchat.server.service.DisappearingMessagePolicy.resolveExpiresAt(
                                existingExpiresAt = null,
                                timerSeconds = timerSeconds,
                                readAtMs = readAt
                            ) ?: 0L
                        }
                    }
                }
                batch.forEach { row ->
                    val existingExpires = row[Messages.expiresAt]
                    val resolved = com.maodouchat.server.service.DisappearingMessagePolicy.resolveExpiresAt(
                        existingExpiresAt = existingExpires,
                        timerSeconds = timerSeconds,
                        readAtMs = readAt
                    )
                    results.add(Triple(row[Messages.id], row[Messages.senderId], resolved))
                }
            }
            results
        }
    }

    /** 服务端侧清理已到期消息（密文+元数据），返回被删 id 列表供广播。 */
    data class PurgedMessages(val messages: List<Pair<String, String>>, val attachmentIds: List<String>)

    fun purgeExpiredMessages(nowMs: Long = System.currentTimeMillis(), limit: Int = 200): PurgedMessages {
        return transaction {
            val candidates = Messages.select(Messages.id, Messages.chatId)
                .where {
                    (Messages.expiresAt.isNotNull()) and
                        (Messages.expiresAt greater 0L) and
                        (Messages.expiresAt lessEq nowMs)
                }
                .orderBy(Messages.expiresAt to SortOrder.ASC)
                .limit(limit.coerceIn(1, 500))
                .map { it[Messages.id] to it[Messages.chatId] }
            if (candidates.isEmpty()) return@transaction PurgedMessages(emptyList(), emptyList())
            val chatIds = candidates.map { it.second }.distinct().sorted()
            Chats.select(Chats.id)
                .where { Chats.id inList chatIds }
                .orderBy(Chats.id to SortOrder.ASC)
                .forUpdate()
                .toList()
            val candidateIds = candidates.map { it.first }
            val expired = Messages.select(Messages.id, Messages.chatId)
                .where {
                    (Messages.id inList candidateIds) and
                        (Messages.expiresAt.isNotNull()) and
                        (Messages.expiresAt greater 0L) and
                        (Messages.expiresAt lessEq nowMs)
                }
                .orderBy(Messages.id to SortOrder.ASC)
                .forUpdate()
                .map { it[Messages.id] to it[Messages.chatId] }
            if (expired.isEmpty()) return@transaction PurgedMessages(emptyList(), emptyList())
            val ids = expired.map { it.first }
            // chat -> message locks are held; attachments are always last.
            val attachmentIds = EncryptedAttachments.selectAll()
                .where { EncryptedAttachments.messageId inList ids }
                .orderBy(EncryptedAttachments.id to SortOrder.ASC)
                .forUpdate()
                .map { it[EncryptedAttachments.id] }
            if (attachmentIds.isNotEmpty()) {
                EncryptedAttachments.deleteWhere { EncryptedAttachments.messageId inList ids }
            }
            // 关联清理与硬删消息保持与主动删除一致的最小集合
            PinnedMessages.deleteWhere { PinnedMessages.messageId inList ids }
            StarMessages.deleteWhere { StarMessages.messageId inList ids }
            MessageReactions.deleteWhere { MessageReactions.messageId inList ids }
            ReadReceipts.deleteWhere { ReadReceipts.messageId inList ids }
            Messages.deleteWhere { Messages.id inList ids }
            PurgedMessages(expired, attachmentIds)
        }
    }

    /**
     * 清理过期的消息变更日志（DELETE/REVOKE/EDIT）。
     * 多设备同步的变更日志保留 [MUTATION_RETENTION_DAYS] 天后清除；
     * 超过保留期的设备应通过 getMessages 全量重拉而非依赖增量变更。
     */
    fun purgeOldMutations(nowMs: Long = System.currentTimeMillis()): Int {
        val cutoff = nowMs - MUTATION_RETENTION_DAYS * 24L * 60L * 60L * 1_000L
        return transaction {
            MessageMutations.deleteWhere { MessageMutations.createdAt less cutoff }
        }
    }

    /**
     * 清理超过保留期消息的派生行（MessageReactions / StarMessages），
     * 防止这些表随历史消息长期累积。子查询按 Messages.createdAt 定位旧消息，
     * 不动近期消息的任何数据。由 Routing.kt 的周期清理循环调用。
     *
     * 注意：**不能**清理 ReadReceipts——未读数按“回执是否存在”计算
     * （Messages.id notInSubQuery readByUser），清掉回执会让一年前的历史消息
     * 周期性“复活”为未读，造成未读角标永久振荡。
     */
    fun purgeOldDerivedRows(retentionDays: Long = DERIVED_ROW_RETENTION_DAYS): Int {
        val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60L * 60L * 1_000L
        return transaction {
            val oldMessageIds = Messages.slice(Messages.id).select { Messages.timestamp less cutoff }
            MessageReactions.deleteWhere { MessageReactions.messageId inSubQuery oldMessageIds } +
                StarMessages.deleteWhere { StarMessages.messageId inSubQuery oldMessageIds }
        }
    }

    fun getUnreadWindow(chatId: String, readerId: String, limit: Int = 24): UnreadWindowResponse {
        return transaction {
            val boundedLimit = limit.coerceIn(1, 50)
            val readByUser = ReadReceipts
                .select(ReadReceipts.messageId)
                .where { ReadReceipts.userId eq readerId }
            // 与 getMessages 一致：不计入 viewer 拉黑发送者的未读
            val blockedSenders = blockedSenderIdsForViewerInTx(readerId)
            // BUG-8 fix: 与 getMessages 一致，排除已过期的阅后即焚消息
            val now = System.currentTimeMillis()
            val notExpired = (Messages.expiresAt.isNull()) or (Messages.expiresAt eq 0L) or (Messages.expiresAt greater now)
            val base = (Messages.chatId eq chatId) and
                (Messages.senderId neq readerId) and
                (Messages.type neq "SK_DIST") and
                (Messages.id notInSubQuery readByUser) and
                notExpired
            val condition = if (blockedSenders.isEmpty()) base
            else base and (Messages.senderId notInList blockedSenders.toList())
            val countExpression = Messages.id.count()
            val totalCount = Messages
                .select(countExpression)
                .where { condition }
                .first()[countExpression]
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            val unreadIds = Messages
                .select(Messages.id)
                .where { condition }
                .orderBy(Messages.timestamp to SortOrder.DESC)
                .limit(boundedLimit)
                .map { it[Messages.id] }
            UnreadWindowResponse(
                messageIds = unreadIds.reversed(),
                totalCount = totalCount,
                truncated = totalCount > boundedLimit
            )
        }
    }

    fun getMessageById(messageId: String): MessageResponse? {
        return transaction {
            Messages.selectAll().where { Messages.id eq messageId }.firstOrNull()?.toMessageResponse()
                ?.let { attachReactions(listOf(it)).first() }
        }
    }

    fun getSenderId(messageId: String): String? {
        return transaction {
            Messages.selectAll().where { Messages.id eq messageId }.firstOrNull()?.get(Messages.senderId)
        }
    }

    fun setReaction(messageId: String, userId: String, emoji: String): List<MessageReactionResponse>? {
        // 并发首次反应可能撞 PK；PG 同事务 catch unique 会 abort，故外层 re-read
        return try {
            transaction {
                val locked = lockChatThenMessage(messageId) ?: return@transaction null
                val msg = locked.message
                if (msg[Messages.type] in NON_REACTABLE_TYPES) return@transaction null
                val chatId = locked.chat[Chats.id]
                val chat = locked.chat
                val participantRow = ChatParticipants.selectAll().where {
                    (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
                }.forUpdate().firstOrNull() ?: return@transaction null
                // 群禁言期间不可回应（与发消息一致）
                if (chat[Chats.isGroup] &&
                    participantRow[ChatParticipants.role] != "OWNER" &&
                    participantRow[ChatParticipants.mutedUntil] > System.currentTimeMillis()
                ) {
                    return@transaction null
                }
                val existing = MessageReactions.selectAll()
                    .where { (MessageReactions.messageId eq messageId) and (MessageReactions.userId eq userId) }
                    .firstOrNull()
                if (existing?.get(MessageReactions.emoji) == emoji) {
                    MessageReactions.deleteWhere { (MessageReactions.messageId eq messageId) and (MessageReactions.userId eq userId) }
                } else if (existing != null) {
                    MessageReactions.update({
                        (MessageReactions.messageId eq messageId) and (MessageReactions.userId eq userId)
                    }) {
                        it[MessageReactions.emoji] = emoji
                        it[MessageReactions.reactedAt] = System.currentTimeMillis()
                    }
                } else {
                    MessageReactions.insert {
                        it[MessageReactions.messageId] = messageId
                        it[MessageReactions.userId] = userId
                        it[MessageReactions.emoji] = emoji
                        it[MessageReactions.reactedAt] = System.currentTimeMillis()
                    }
                }
                getReactions(messageId)
            }
        } catch (e: Exception) {
            if (!isUniqueViolation(e)) throw e
            // getReactions 自带 transaction，勿再嵌套
            getReactions(messageId)
        }
    }

    private fun isUniqueViolation(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            val msg = (cur.message ?: "").lowercase()
            if (cur is java.sql.SQLException && cur.sqlState == "23505") return true
            if (msg.contains("unique") || msg.contains("duplicate key")) return true
            cur = cur.cause
        }
        return false
    }

    /**
     * 多设备变更回放：返回 (sinceMs, sinceId) 之后的 DELETE/REVOKE/EDIT。
     * 与 getMessagesSince 相同的 (ts, id) 前缀游标语义。
     * @param viewerId 过滤 actor 被 viewer 拉黑的变更（尤其 EDIT content），与 WS fanout 一致
     */
    fun getMutationsSince(
        chatId: String,
        sinceMs: Long,
        limit: Int = 200,
        sinceId: String? = null,
        viewerId: String? = null,
    ): List<MessageMutationResponse> {
        return transaction {
            val afterId = sinceId?.takeIf { it.isNotBlank() }
            // 与 getMessagesSince 相同：无 sinceId 且 sinceMs>0 时用 strict greater，避免同 ms 满页死循环
            val timeCondition = when {
                afterId != null -> (MessageMutations.chatId eq chatId) and (
                    (MessageMutations.createdAt greater sinceMs) or (
                        (MessageMutations.createdAt eq sinceMs) and (MessageMutations.id greater afterId)
                    )
                )
                sinceMs <= 0L -> (MessageMutations.chatId eq chatId) and (MessageMutations.createdAt greaterEq sinceMs)
                else -> (MessageMutations.chatId eq chatId) and (MessageMutations.createdAt greater sinceMs)
            }
            val blockedActors = blockedSenderIdsForViewerInTx(viewerId)
            val condition = if (blockedActors.isEmpty()) timeCondition
            else timeCondition and (MessageMutations.actorId notInList blockedActors.toList())
            MessageMutations.selectAll()
                .where { condition }
                .orderBy(MessageMutations.createdAt to SortOrder.ASC, MessageMutations.id to SortOrder.ASC)
                .limit(limit.coerceIn(1, 500))
                .map {
                    MessageMutationResponse(
                        id = it[MessageMutations.id],
                        chatId = it[MessageMutations.chatId],
                        messageId = it[MessageMutations.messageId],
                        action = it[MessageMutations.action],
                        actorId = it[MessageMutations.actorId],
                        content = it[MessageMutations.content],
                        editedAt = it[MessageMutations.editedAt],
                        createdAt = it[MessageMutations.createdAt]
                    )
                }
        }
    }

    /** 必须在已有 transaction 内调用 */
    private fun recordMutationInTx(
        chatId: String,
        messageId: String,
        action: String,
        actorId: String,
        content: String? = null,
        editedAt: Long? = null,
    ) {
        MessageMutations.insert {
            it[MessageMutations.id] = "mut_${UUID.randomUUID()}"
            it[MessageMutations.chatId] = chatId
            it[MessageMutations.messageId] = messageId
            it[MessageMutations.action] = action
            it[MessageMutations.actorId] = actorId
            it[MessageMutations.content] = content
            it[MessageMutations.editedAt] = editedAt
            it[MessageMutations.createdAt] = System.currentTimeMillis()
        }
    }

    fun getReactions(messageId: String): List<MessageReactionResponse> {
        return transaction {
            MessageReactions.selectAll()
                .where { MessageReactions.messageId eq messageId }
                .map { it.toReactionResponse() }
                .sortedBy { it.reactedAt }
        }
    }

    /** 按 viewer 双向拉黑过滤后的 reaction 列表（广播/详情用，避免泄露被拉黑用户互动）。 */
    fun getReactionsForViewer(messageId: String, viewerId: String): List<MessageReactionResponse> = transaction {
        val blocked = blockedSenderIdsForViewerInTx(viewerId)
        MessageReactions.selectAll()
            .where { MessageReactions.messageId eq messageId }
            .map { it.toReactionResponse() }
            .filter { it.userId !in blocked }
            .sortedBy { it.reactedAt }
    }

    data class DeleteMessageResult(
        /** true 删除成功；false 无权；null 消息已不存在（幂等） */
        val ok: Boolean?,
        val deletedAttachmentIds: List<String> = emptyList(),
        val message: MessageResponse? = null
    )

    /**
     * @return DeleteMessageResult.ok: true 成功 / false 无权 / null 已不存在
     * 附件行在同事务删除，避免消息删掉后 COMMITTED 孤儿永不过期。
     */
    fun deleteMessage(messageId: String, userId: String): DeleteMessageResult {
        return transaction {
            val locked = lockChatThenMessage(messageId)
                ?: return@transaction DeleteMessageResult(ok = null)
            val msg = locked.message
            if (msg[Messages.senderId] != userId) return@transaction DeleteMessageResult(ok = false)
            // SK_DIST 不可删：破坏群密钥
            if (msg[Messages.type] == "SK_DIST") return@transaction DeleteMessageResult(ok = false)
            // REVOKED 是撤回墓碑：硬删会让离线设备看不到撤回；按幂等成功保留
            if (msg[Messages.type] == "REVOKED") return@transaction DeleteMessageResult(ok = null)
            // 群禁言期间不允许主动删自己的消息（与 send/edit 一致，锁内复检）
            val chatId = locked.chat[Chats.id]
            val chat = locked.chat
            val participantRow = ChatParticipants.selectAll().where {
                (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
            }.forUpdate().firstOrNull() ?: return@transaction DeleteMessageResult(ok = false)
            if (chat[Chats.isGroup]) {
                if (participantRow[ChatParticipants.role] != "OWNER" &&
                    participantRow[ChatParticipants.mutedUntil] > System.currentTimeMillis()
                ) {
                    return@transaction DeleteMessageResult(ok = false)
                }
            }
            val attachmentIds = EncryptedAttachments.selectAll()
                .where { EncryptedAttachments.messageId eq messageId }
                .forUpdate()
                .map { it[EncryptedAttachments.id] }
            if (attachmentIds.isNotEmpty()) {
                EncryptedAttachments.deleteWhere { EncryptedAttachments.id inList attachmentIds }
            }
            MessageReactions.deleteWhere { MessageReactions.messageId eq messageId }
            ReadReceipts.deleteWhere { ReadReceipts.messageId eq messageId }
            StarMessages.deleteWhere { StarMessages.messageId eq messageId }
            PinnedMessages.deleteWhere { PinnedMessages.messageId eq messageId }
            Messages.deleteWhere { Messages.id eq messageId }
            recordMutationInTx(chatId = chatId, messageId = messageId, action = "DELETE", actorId = userId)
            DeleteMessageResult(ok = true, deletedAttachmentIds = attachmentIds)
        }
    }

    fun deleteMessageForModeration(messageId: String): DeleteMessageResult {
        return transaction {
            val locked = lockChatThenMessage(messageId)
                ?: return@transaction DeleteMessageResult(ok = null)
            val msg = locked.message
            if (msg[Messages.type] in NON_DELETABLE_TYPES) {
                return@transaction DeleteMessageResult(ok = false)
            }
            val response = msg.toMessageResponse()
            val attachmentIds = EncryptedAttachments.selectAll()
                .where { EncryptedAttachments.messageId eq messageId }
                .forUpdate()
                .map { it[EncryptedAttachments.id] }
            if (attachmentIds.isNotEmpty()) {
                EncryptedAttachments.deleteWhere { EncryptedAttachments.id inList attachmentIds }
            }
            MessageReactions.deleteWhere { MessageReactions.messageId eq messageId }
            ReadReceipts.deleteWhere { ReadReceipts.messageId eq messageId }
            StarMessages.deleteWhere { StarMessages.messageId eq messageId }
            PinnedMessages.deleteWhere { PinnedMessages.messageId eq messageId }
            Messages.deleteWhere { Messages.id eq messageId }
            recordMutationInTx(
                chatId = response.chatId,
                messageId = messageId,
                action = "DELETE",
                actorId = "moderation"
            )
            DeleteMessageResult(ok = true, deletedAttachmentIds = attachmentIds, message = response)
        }
    }

    /**
     * 撤回消息（仅发送者，5 分钟内）— 改 type=REVOKED, content="[消息已撤回]"
     * Applied：本次真正改写，附件行同事务删除并返回 IDs 供磁盘清理；
     * AlreadyDone：已是 REVOKED（路由不得再删附件/广播）；
     * Failure：不存在 / 非本人 / SK_DIST / 超时。
     */
    fun revokeMessage(messageId: String, userId: String): RevokeResult {
        return transaction {
            val locked = lockChatThenMessage(messageId) ?: return@transaction RevokeResult.Failure
            val msg = locked.message
            if (msg[Messages.senderId] != userId) return@transaction RevokeResult.Failure
            // SK_DIST 是内部密钥分发消息，撤回会导致群成员无法解密后续消息
            if (msg[Messages.type] == "SK_DIST") return@transaction RevokeResult.Failure
            // 已撤回：幂等成功，避免重复副作用（附件清理 / WS 广播）
            if (msg[Messages.type] == "REVOKED") return@transaction RevokeResult.AlreadyDone
            val elapsed = System.currentTimeMillis() - msg[Messages.timestamp]
            if (elapsed > REVOKE_WINDOW_MS) return@transaction RevokeResult.Failure
            // 群禁言期间不允许撤回（等同主动发内容变更）
            val chatId = locked.chat[Chats.id]
            val chat = locked.chat
            val participantRow = ChatParticipants.selectAll().where {
                (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
            }.forUpdate().firstOrNull() ?: return@transaction RevokeResult.Failure
            if (chat[Chats.isGroup]) {
                if (participantRow[ChatParticipants.role] != "OWNER" &&
                    participantRow[ChatParticipants.mutedUntil] > System.currentTimeMillis()
                ) {
                    return@transaction RevokeResult.Failure
                }
            }
            // 与 deleteMessage 一致：附件行同事务删除，避免 REVOKED 后 COMMITTED 孤儿永不过期
            val attachmentIds = EncryptedAttachments.selectAll()
                .where { EncryptedAttachments.messageId eq messageId }
                .forUpdate()
                .map { it[EncryptedAttachments.id] }
            if (attachmentIds.isNotEmpty()) {
                EncryptedAttachments.deleteWhere { EncryptedAttachments.id inList attachmentIds }
            }
            // 撤回后不可再作为置顶展示
            PinnedMessages.deleteWhere { PinnedMessages.messageId eq messageId }
            // 撤回后清除已读回执：避免“撤回的消息被读了”的隐私泄露（与 deleteMessage 一致）
            ReadReceipts.deleteWhere { ReadReceipts.messageId eq messageId }
            // 撤回后清除反应：避免“撤回的消息曾被谁回应”的元数据泄露
            MessageReactions.deleteWhere { MessageReactions.messageId eq messageId }
            Messages.update({ Messages.id eq messageId }) {
                it[type] = "REVOKED"
                it[content] = "[消息已撤回]"
            }
            recordMutationInTx(
                chatId = chatId,
                messageId = messageId,
                action = "REVOKE",
                actorId = userId,
                content = "[消息已撤回]"
            )
            RevokeResult.Applied(deletedAttachmentIds = attachmentIds)
        }
    }

    sealed class RevokeResult {
        data class Applied(val deletedAttachmentIds: List<String> = emptyList()) : RevokeResult()
        data object AlreadyDone : RevokeResult()
        data object Failure : RevokeResult()
    }

    companion object {
        const val REVOKE_WINDOW_MS = 5 * 60 * 1000L
        const val EDIT_WINDOW_MS = 5 * 60 * 1000L
        const val LIVE_LOCATION_EDIT_WINDOW_MS = 8L * 60L * 60L * 1000L + 60_000L
        const val MUTATION_RETENTION_DAYS = 30L
        const val DERIVED_ROW_RETENTION_DAYS = 365L
        /** markAllAsRead 每批处理行数（8.30 性能优化 B1）。 */
        const val MARK_READ_BATCH_SIZE = 500
        private val NON_REACTABLE_TYPES = setOf("SK_DIST", "SYSTEM", "REVOKED")
        // SK_DIST 是端到端加密的密钥分发消息，删除/撤回/编辑会破坏群聊加密
        // REVOKED 是撤回墓碑：硬删会让离线设备永远看不到撤回，必须保留
        private val NON_DELETABLE_TYPES = setOf("SK_DIST", "REVOKED")
        // 附件消息密文与 EncryptedAttachments 行绑定，编辑会让 blob 与 content 脱节
        private val NON_EDITABLE_TYPES = NON_REACTABLE_TYPES + ATTACHMENT_MESSAGE_TYPES
        private val CLIENT_SENDABLE_TYPES = setOf(
            "TEXT", "MARKDOWN", "IMAGE", "GIF", "STICKER", "LOCATION",
            "VOICE", "VIDEO", "FILE", "SK_DIST"
        )

        internal fun editWindowMsForType(type: String): Long =
            if (type == "LOCATION") LIVE_LOCATION_EDIT_WINDOW_MS else EDIT_WINDOW_MS
    }

    fun editMessage(messageId: String, userId: String, newContent: String): Boolean {
        return transaction {
            val locked = lockChatThenMessage(messageId) ?: return@transaction false
            val msg = locked.message
            if (msg[Messages.senderId] != userId) return@transaction false
            // SK_DIST/SYSTEM/REVOKED/附件类型不允许编辑
            if (msg[Messages.type] in NON_EDITABLE_TYPES) return@transaction false
            val elapsed = System.currentTimeMillis() - msg[Messages.timestamp]
            val editWindowMs = editWindowMsForType(msg[Messages.type])
            if (elapsed > editWindowMs) return@transaction false
            // 群禁言 / 非成员：编辑等同于再发一条内容，须与 send 一致在锁内复检
            val chatId = locked.chat[Chats.id]
            val chat = locked.chat
            val participantRow = ChatParticipants.selectAll().where {
                (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
            }.forUpdate().firstOrNull() ?: return@transaction false
            if (chat[Chats.isGroup] &&
                participantRow[ChatParticipants.role] != "OWNER" &&
                participantRow[ChatParticipants.mutedUntil] > System.currentTimeMillis()
            ) {
                return@transaction false
            }
            // 1:1 双向拉黑后禁止编辑（与 sendMessage 一致）
            if (!chat[Chats.isGroup]) {
                val peers = ChatParticipants.selectAll()
                    .where { ChatParticipants.chatId eq chatId }
                    .map { it[ChatParticipants.userId] }
                    .filter { it != userId }
                val blocked = peers.any { peer ->
                    !BlockedUsers.selectAll().where {
                        ((BlockedUsers.blockerId eq userId) and (BlockedUsers.blockedId eq peer)) or
                            ((BlockedUsers.blockerId eq peer) and (BlockedUsers.blockedId eq userId))
                    }.empty()
                }
                if (blocked) return@transaction false
            }
            val previousRevision = maxOf(msg[Messages.timestamp], msg[Messages.editedAt] ?: msg[Messages.timestamp])
            val minimumNextRevision = if (previousRevision == Long.MAX_VALUE) Long.MAX_VALUE else previousRevision + 1L
            val now = maxOf(System.currentTimeMillis(), minimumNextRevision)
            // 条件更新：若并发撤回已改 type，则 updated==0，避免改写已撤回内容
            val updated = Messages.update({
                (Messages.id eq messageId) and
                    (Messages.senderId eq userId) and
                    (Messages.type notInList NON_EDITABLE_TYPES.toList()) and
                    // 并发编辑 CAS：仅当已存 editedAt 未超出我们基于的版本时才提交，
                    // 避免同毫秒双编辑都成功且 editedAt 相同，导致客户端去重忽略较晚的编辑（丢失更新）
                    (Messages.editedAt.isNull() or (Messages.editedAt lessEq previousRevision))
            }) {
                it[content] = newContent
                it[editedAt] = now
            }
            if (updated > 0) {
                recordMutationInTx(
                    chatId = chatId,
                    messageId = messageId,
                    action = "EDIT",
                    actorId = userId,
                    content = newContent,
                    editedAt = now
                )
            }
            updated > 0
        }
    }

    fun getReadReceipts(messageId: String, viewerId: String): List<ReadReceiptResponse> {
        return transaction {
            val locked = lockChatThenMessage(messageId) ?: return@transaction emptyList()
            val message = locked.message
            val chatId = locked.chat[Chats.id]
            val isParticipant = ChatParticipants.selectAll().where {
                (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq viewerId)
            }.firstOrNull() != null
            if (!isParticipant) return@transaction emptyList()
            val blockedSenders = blockedSenderIdsForViewerInTx(viewerId)
            val receiptCondition = if (blockedSenders.isEmpty()) {
                ReadReceipts.messageId eq messageId
            } else {
                (ReadReceipts.messageId eq messageId) and
                    (ReadReceipts.userId notInList blockedSenders.toList())
            }
            ReadReceipts.selectAll()
                .where { receiptCondition }
                .map { ReadReceiptResponse(it[ReadReceipts.userId], it[ReadReceipts.readAt]) }
        }
    }

    private fun attachReactions(
        messages: List<MessageResponse>,
        blockedUserIds: Set<String> = emptySet()
    ): List<MessageResponse> {
        if (messages.isEmpty()) return messages
        val ids = messages.map { it.id }
        val reactionsByMessage = MessageReactions.selectAll()
            .where { MessageReactions.messageId inList ids }
            .map { row ->
                row[MessageReactions.messageId] to row.toReactionResponse()
            }
            .filter { (_, reaction) -> reaction.userId !in blockedUserIds }
            .groupBy({ it.first }, { it.second })
        return messages.map { message ->
            message.copy(reactions = reactionsByMessage[message.id].orEmpty().sortedBy { it.reactedAt })
        }
    }

    fun insertBotMessage(id: String, chatId: String, botUserId: String, content: String, timestamp: Long, type: String = "TEXT"): Boolean = transaction {
        BotApps.selectAll().where {
            (BotApps.id eq botUserId) and (BotApps.enabled eq true)
        }.forUpdate().firstOrNull() ?: return@transaction false
        Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: return@transaction false
        ChatParticipants.selectAll().where {
            (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq botUserId)
        }.forUpdate().firstOrNull() ?: return@transaction false
        val normalizedType = type.take(20).ifBlank { "TEXT" }
        if (normalizedType == "SK_DIST" || normalizedType == "REVOKED") return@transaction false
        // Bot messages are plaintext server-visible notices (not E2EE peer content).
        // 剥离 content 中伪造的 <meta> 键盘块（防 callback-data 注入），但保留
        // sendMessage 端点追加在末尾的服务端键盘块（否则内联键盘不落库，重拉历史后消失）。
        val cleanContent = stripInlineMetaPreservingTrailing(content)
        Messages.insert {
            it[Messages.id] = id
            it[Messages.chatId] = chatId
            it[Messages.senderId] = botUserId
            it[Messages.content] = cleanContent.take(8000)
            it[Messages.type] = normalizedType
            it[Messages.timestamp] = timestamp
            it[Messages.status] = "SENT"
        }
        Chats.update({ Chats.id eq chatId }) {
            it[lastMessage] = cleanContent.take(200)
            it[lastMessageType] = normalizedType
            it[lastMessageTime] = timestamp
        }
        true
    }

    /**
     * 全局搜索（按类型/日期/聊天范围过滤），返回匹配消息列表
     */
    fun globalSearch(
        userId: String,
        query: String,
        filterType: String = "",
        dateFrom: Long = 0L,
        dateTo: Long = 0L,
        chatId: String = "",
        limit: Int = 50
    ): List<MessageResponse> {
        if (query.isBlank()) return emptyList()
        val escapedQuery = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val pattern = "%${escapedQuery.lowercase()}%"
        return transaction {
            // 获取用户可参与的聊天
            val userChatIds = ChatParticipants.select(ChatParticipants.chatId)
                .where { ChatParticipants.userId eq userId }
                .map { it[ChatParticipants.chatId] }
                .toSet()
            if (userChatIds.isEmpty()) return@transaction emptyList()

            var baseQuery = Messages.selectAll().where {
                (Messages.chatId inList userChatIds) and
                (Messages.content.lowerCase() like pattern) and
                // 8.48 修复 M2：撤回墓碑按 type=REVOKED 过滤（撤回改 type 而非 status，
                // 此前过滤 status neq "REVOKED" 用错列 → 已撤回消息仍可被搜到）
                (Messages.type neq "REVOKED") and
                // 8.48 修复 M2：排除已过期阅后即焚（purge 循环清理前仍可搜到，
                // 是全局搜索唯一漏掉的搜索面）
                ((Messages.expiresAt.isNull()) or (Messages.expiresAt eq 0L) or
                    (Messages.expiresAt greater System.currentTimeMillis()))
            }

            // 8.38：双向拉黑过滤——被对方拉黑后不得再通过全局搜索读取其明文/元数据
            // （与 getMessages/getMessagesSince/WS fanout 的「拉黑 = 双方从对方视线消失」不变量一致）
            val blockedIds = blockedSenderIdsForViewerInTx(userId)
            if (blockedIds.isNotEmpty()) {
                baseQuery = baseQuery.andWhere { Messages.senderId notInList blockedIds }
            }

            // 按类型过滤
            if (filterType.isNotBlank() && filterType != "ALL") {
                val typeCondition = when (filterType) {
                    "TEXT" -> Messages.type eq "TEXT"
                    "IMAGE" -> Messages.type inList listOf("IMAGE", "ANIMATED_IMAGE")
                    "FILE" -> Messages.type inList listOf("FILE", "DOCUMENT")
                    "VOICE" -> Messages.type eq "VOICE"
                    "VIDEO" -> Messages.type eq "VIDEO"
                    "LINK" -> Messages.type inList listOf("LINK", "TEXT") // 内容含 http 的 TEXT 也在链路层处理
                    else -> null
                }
                if (typeCondition != null) {
                    baseQuery = baseQuery.andWhere { typeCondition }
                }
            }

            // 按日期过滤
            if (dateFrom > 0L) {
                baseQuery = baseQuery.andWhere { Messages.timestamp greaterEq dateFrom }
            }
            if (dateTo > 0L) {
                baseQuery = baseQuery.andWhere { Messages.timestamp lessEq dateTo }
            }

            // 按聊天过滤
            if (chatId.isNotBlank()) {
                baseQuery = baseQuery.andWhere { Messages.chatId eq chatId }
            }

            baseQuery.orderBy(Messages.timestamp to SortOrder.DESC)
                .limit(limit.coerceIn(1, 200))
                .map { it.toMessageResponse(viewerId = userId) }
        }
    }

}

private fun ResultRow.toMessageResponse(viewerId: String? = null): MessageResponse {
    val base = MessageResponse(
        id = this[Messages.id],
        chatId = this[Messages.chatId],
        senderId = this[Messages.senderId],
        content = this[Messages.content],
        type = this[Messages.type],
        timestamp = this[Messages.timestamp],
        status = this[Messages.status],
        editedAt = this[Messages.editedAt],
        expiresAt = this[Messages.expiresAt]?.takeIf { it > 0L },
        sealedSender = runCatching { this[Messages.sealedSender] }.getOrDefault(false)
    )
    return if (viewerId.isNullOrBlank()) base else SealedSenderDelivery.forViewer(base, viewerId)
}

private fun ResultRow.toReactionResponse(): MessageReactionResponse {
    return MessageReactionResponse(
        userId = this[MessageReactions.userId],
        emoji = this[MessageReactions.emoji],
        reactedAt = this[MessageReactions.reactedAt]
    )
}

/**
 * 剥离消息内容中的 `<meta>…</meta>` 内联键盘块（含孤立标签）。
 *
 * 服务端 Bot API 的内联键盘由 sendMessage 端点统一追加（`<meta>{json}</meta>` 后缀）；
 * 用户消息内容若含伪造的 meta 块，bot 复制/转发后客户端会渲染成"该 bot 的键盘"，
 * callbackData 可被攻击者选定 → 对信任 callbackData 的 bot 构成输入伪造。
 * 所有把用户内容变成 bot 消息的路径（copyMessage / insertBotMessage）都必须先剥离。
 */
internal fun stripInlineMeta(content: String): String {
    var out = Regex("<meta>.*?</meta>", RegexOption.DOT_MATCHES_ALL).replace(content, "")
    out = out.replace("</meta>", "").replace("<meta>", "")
    return out.trim()
}

/**
 * 同 [stripInlineMeta]，但保留**末尾**的 `<meta>...</meta>` 块。
 * sendMessage 端点对含键盘的 bot 消息会在内容末尾追加服务端键盘块；
 * 这里剥除用户在内容中部伪造的 meta 块，同时让服务端键盘落库，
 * 保证断线重拉历史后内联键盘仍然可见。
 */
internal fun stripInlineMetaPreservingTrailing(content: String): String {
    val metaPattern = Regex("<meta>.*?</meta>", RegexOption.DOT_MATCHES_ALL)
    val trailingMeta = metaPattern.findAll(content).lastOrNull()?.value
    var out = metaPattern.replace(content, "")
    out = out.replace("</meta>", "").replace("<meta>", "")
    out = out.trim()
    if (trailingMeta != null && trailingMeta !in out) out = out + trailingMeta
    return out
}
