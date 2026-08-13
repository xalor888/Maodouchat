package com.maodouchat.server.repository

import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.BotCommandLogs
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.ChatUserSettings
import com.maodouchat.server.db.DirectChatPairs
import com.maodouchat.server.db.GroupAuditLogs
import com.maodouchat.server.db.GroupChainEntries
import com.maodouchat.server.db.GroupChains
import com.maodouchat.server.db.GroupCheckins
import com.maodouchat.server.db.GroupPkRounds
import com.maodouchat.server.db.GroupPkVotes
import com.maodouchat.server.db.GroupPolls
import com.maodouchat.server.db.GroupPollVotes
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.AiPreferences
import com.maodouchat.server.db.EncryptedAttachments
import com.maodouchat.server.db.MessageMutations
import com.maodouchat.server.db.MessageReactions
import com.maodouchat.server.db.Messages
import com.maodouchat.server.db.PinnedMessages
import com.maodouchat.server.db.ReadReceipts
import com.maodouchat.server.db.SenderKeyDistributions
import com.maodouchat.server.db.StarMessages
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.ChatResponse
import com.maodouchat.server.model.ChatSettingsResponse
import com.maodouchat.server.model.ChatType
import com.maodouchat.server.model.DisappearingMessagesResponse
import com.maodouchat.server.model.UpdateChatSettingsRequest
import com.maodouchat.server.model.GroupMemberResponse
import com.maodouchat.server.model.GroupAuditLogResponse
import com.maodouchat.server.model.UserResponse
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInSubQuery
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

class ChatRepository {

    enum class LeaveChatResult { LEFT, NOT_PARTICIPANT, OWNER_TRANSFER_REQUIRED }

    /** leaveChat outcome; attachment IDs are only populated when the last participant tears down the chat. */
    data class LeaveChatOutcome(
        val result: LeaveChatResult,
        val deletedAttachmentIds: List<String> = emptyList(),
        val deletedGroupAvatarUrl: String? = null
    )
    enum class TransferOwnershipResult { TRANSFERRED, CHAT_NOT_FOUND, NOT_GROUP, NOT_OWNER, TARGET_NOT_PARTICIPANT, SAME_USER }
    enum class GroupMemberMutationResult {
        UPDATED,
        CHAT_NOT_FOUND,
        NOT_GROUP,
        ACTOR_NOT_PARTICIPANT,
        TARGET_NOT_PARTICIPANT,
        FORBIDDEN,
        SELF_NOT_ALLOWED,
        OWNER_PROTECTED,
        PEER_ADMIN_PROTECTED,
        MEMBER_LIMIT_EXCEEDED,
        USER_NOT_FOUND,
        BLOCKED
    }
    enum class AddOwnedBotResult {
        ADDED,
        ALREADY_MEMBER,
        CHAT_NOT_FOUND,
        NOT_GROUP,
        FORBIDDEN,
        BOT_NOT_FOUND,
        BOT_NOT_OWNED,
        BOT_DISABLED,
        MEMBER_LIMIT_EXCEEDED
    }
    data class AddGroupMembersResult(
        val result: GroupMemberMutationResult,
        val addedUserIds: List<String> = emptyList(),
        val missingUserId: String? = null,
        val blockedUserId: String? = null
    )
    data class GroupAvatarMutationResult(
        val result: GroupMemberMutationResult,
        val previousAvatarUrl: String? = null
    )
    data class GroupInviteMutationResult(
        val result: GroupMemberMutationResult,
        val invite: InviteState? = null
    )

    fun createChat(
        participantIds: List<String>,
        isGroup: Boolean = false,
        groupName: String? = null,
        creatorId: String? = null,
        chatType: String = if (isGroup) ChatType.GROUP else ChatType.DIRECT
    ): ChatResponse {
        return transaction {
            val uniqueParticipantIds = participantIds.distinct()
            require(uniqueParticipantIds.isNotEmpty()) { "chat_participants_empty" }
            require(creatorId == null || creatorId in uniqueParticipantIds) { "chat_creator_not_participant" }
            val activeUsers = lockUsersInTx(uniqueParticipantIds)
            require(activeUsers.size == uniqueParticipantIds.size && activeUsers.none { it[Users.deletedAt] != null }) {
                "chat_participant_not_found"
            }
            // 完整 UUID，避免 take(8)（32-bit 熵）在聊天量到万级时与已有 chat 主键碰撞，
            // 导致整事务回滚 / 群创建偶发 500（与 getOrCreateDirectChat 一致）。
            val chatId = "c_${UUID.randomUUID()}"
            Chats.insert {
                it[Chats.id] = chatId
                it[Chats.isGroup] = isGroup
                it[Chats.chatType] = chatType
                it[Chats.groupName] = groupName
                it[Chats.memberRevision] = if (isGroup) 1 else 0
            }
            val now = System.currentTimeMillis()
            uniqueParticipantIds.forEach { userId ->
                ChatParticipants.insert {
                    it[ChatParticipants.chatId] = chatId
                    it[ChatParticipants.userId] = userId
                    it[ChatParticipants.joinedAt] = now
                    // 群聊/频道中创建者为 OWNER，其他人为 MEMBER；非群聊全部为 MEMBER
                    it[ChatParticipants.role] = if (isGroup && userId == (creatorId ?: uniqueParticipantIds.first())) "OWNER" else "MEMBER"
                }
            }
            getChatByIdInTx(chatId, creatorId ?: uniqueParticipantIds.first())!!
        }
    }

    /**
     * 1:1 幂等创建：用 direct_chat_pairs 唯一约束跨进程串行化。
     *
     * 注意：PG/H2 在唯一冲突后会 abort 整事务，不能在同事务内 catch 再继续写。
     * 冲突时让 transaction 失败回滚（含已插的 chat/participants），再新事务回读赢家。
     */
    fun getOrCreateDirectChat(userId1: String, userId2: String): ChatResponse {
        require(userId1 != userId2) { "direct_chat_self" }
        val pairKey = listOf(userId1, userId2).sorted().joinToString(":")

        return try {
            transaction {
                val activeUsers = lockUsersInTx(listOf(userId1, userId2))
                require(activeUsers.size == 2 && activeUsers.none { it[Users.deletedAt] != null }) {
                    "direct_chat_user_not_found"
                }
                // 双向拉黑拒绝重建 1:1 会话（8.30 隐私修复）：A 拉黑 B 后 B 不能靠
                // 重新发起私聊"重建"与 A 的会话壳（发消息会被拒，但列表会残留）。
                require(!isBlockedEitherWayInTx(userId1, userId2)) { "direct_chat_blocked" }
                // 事务内再检一次，减少无谓 insert
                lookupDirectChatInTx(pairKey, userId1, userId2)?.let { return@transaction it }

                // 使用完整 UUID 作 chatId，避免 take(8)（32-bit 熵）在聊天量到万级时
                // 与已有 chat 主键碰撞导致整事务回滚、私聊创建偶发失败。
                val chatId = "c_${UUID.randomUUID()}"
                val now = System.currentTimeMillis()
                Chats.insert {
                    it[Chats.id] = chatId
                    it[Chats.isGroup] = false
                    it[Chats.groupName] = null
                    it[Chats.memberRevision] = 0
                }
                listOf(userId1, userId2).forEach { uid ->
                    ChatParticipants.insert {
                        it[ChatParticipants.chatId] = chatId
                        it[ChatParticipants.userId] = uid
                        it[ChatParticipants.joinedAt] = now
                        it[ChatParticipants.role] = "MEMBER"
                    }
                }
                // 唯一约束：pair_key PK。冲突 → 整事务回滚，外层 catch 回读。
                DirectChatPairs.insert {
                    it[DirectChatPairs.pairKey] = pairKey
                    it[DirectChatPairs.chatId] = chatId
                    it[DirectChatPairs.createdAt] = now
                }
                getChatByIdInTx(chatId, userId1)!!
            }
        } catch (e: Exception) {
            if (!isUniqueViolation(e)) throw e
            lookupDirectChat(pairKey, userId1, userId2)
                ?: throw e
        }
    }

    private fun lookupDirectChat(pairKey: String, userId1: String, userId2: String): ChatResponse? {
        val found = transaction {
            val activeUsers = lockUsersInTx(listOf(userId1, userId2))
            if (activeUsers.size != 2 || activeUsers.any { it[Users.deletedAt] != null }) {
                return@transaction null
            }
            lookupDirectChatInTx(pairKey, userId1, userId2)
        } ?: return null
        // 历史 chat 无 pair 行时，独立事务补写（冲突则忽略，绝不能污染主读事务）
        ensureDirectPairMapping(pairKey, found.id, userId1, userId2)
        return found
    }

    /** 已在 transaction 内：只读查找，不写 pair（避免唯一冲突 abort 读事务） */
    private fun lookupDirectChatInTx(pairKey: String, userId1: String, userId2: String): ChatResponse? {
        val mapped = DirectChatPairs.selectAll()
            .where { DirectChatPairs.pairKey eq pairKey }
            .firstOrNull()
            ?.get(DirectChatPairs.chatId)
        if (mapped != null) {
            // 半空/脏 pair：双方都必须仍是成员，否则不能当有效 1:1
            if (isIntactDirectChatInTx(mapped, userId1, userId2)) {
                getChatByIdInTx(mapped, userId1)?.let { return it }
            } else {
                DirectChatPairs.deleteWhere { DirectChatPairs.pairKey eq pairKey }
            }
        }
        val existing = findDirectChatIdInTx(userId1, userId2) ?: return null
        return getChatByIdInTx(existing, userId1)
    }

    private fun isIntactDirectChatInTx(chatId: String, userId1: String, userId2: String): Boolean {
        val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull() ?: return false
        if (chat[Chats.isGroup]) return false
        val members = ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }
            .map { it[ChatParticipants.userId] }
            .toSet()
        return members.size == 2 && userId1 in members && userId2 in members
    }

    private fun ensureDirectPairMapping(pairKey: String, chatId: String, userId1: String, userId2: String) {
        try {
            transaction {
                val activeUsers = lockUsersInTx(listOf(userId1, userId2))
                if (activeUsers.size != 2 || activeUsers.any { it[Users.deletedAt] != null }) {
                    return@transaction
                }
                Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
                    ?: return@transaction
                if (!isIntactDirectChatInTx(chatId, userId1, userId2)) return@transaction
                val already = DirectChatPairs.selectAll()
                    .where { DirectChatPairs.pairKey eq pairKey }
                    .firstOrNull() != null
                if (already) return@transaction
                DirectChatPairs.insert {
                    it[DirectChatPairs.pairKey] = pairKey
                    it[DirectChatPairs.chatId] = chatId
                    it[DirectChatPairs.createdAt] = System.currentTimeMillis()
                }
            }
        } catch (e: Exception) {
            if (!isUniqueViolation(e)) throw e
        }
    }

    private fun findDirectChatIdInTx(userId1: String, userId2: String): String? {
        val chats1 = ChatParticipants.selectAll().where { ChatParticipants.userId eq userId1 }
            .map { it[ChatParticipants.chatId] }.toSet()
        val chats2 = ChatParticipants.selectAll().where { ChatParticipants.userId eq userId2 }
            .map { it[ChatParticipants.chatId] }.toSet()
        val common = chats1.intersect(chats2)
        if (common.isEmpty()) return null
        val groupFlags = Chats.selectAll().where { Chats.id inList common.toList() }
            .associate { it[Chats.id] to it[Chats.isGroup] }
        val counts = ChatParticipants.selectAll().where { ChatParticipants.chatId inList common.toList() }
            .groupBy { it[ChatParticipants.chatId] }
            .mapValues { it.value.size }
        // 稳定选取：按 chatId 排序，避免 firstOrNull  nondeterministic
        return common.filter { id -> groupFlags[id] == false && counts[id] == 2 }.minOrNull()
    }

    /** 已在 transaction 内调用 */
    private fun getChatByIdInTx(chatId: String, viewerId: String? = null): ChatResponse? {
        val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull() ?: return null
        val blocked = blockedUserIdsInTx(viewerId)
        val participants = (ChatParticipants innerJoin Users)
            .selectAll()
            .where { ChatParticipants.chatId eq chatId }
            .filterNot { it[Users.id] in blocked }
            .map {
                UserResponse(
                    id = it[Users.id],
                    name = it[Users.name],
                    email = "",
                    avatar = it[Users.avatar],
                    status = if (it[Users.showStatus]) it[Users.status] else "",
                    isOnline = it[Users.showOnline] && it[Users.isOnline]
                )
            }
        val lastMsg = Messages.selectAll().where { Messages.chatId eq chatId }
            .orderBy(Messages.timestamp to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
        val visibleLastMsg = if (lastMsg?.get(Messages.type) == HIDDEN_SENDER_KEY_TYPE) {
            lastVisibleMessage(chatId)
        } else {
            lastMsg
        }
        val effectiveLastMsg = visibleLastMsg ?: if (lastMsg?.get(Messages.type) == HIDDEN_SENDER_KEY_TYPE) null else lastMsg
        return ChatResponse(
            id = chatId,
            participants = participants,
            lastMessage = effectiveLastMsg?.let { previewForType(it[Messages.type]) } ?: "",
            lastMessageType = effectiveLastMsg?.get(Messages.type) ?: "TEXT",
            lastMessageTime = effectiveLastMsg?.get(Messages.timestamp) ?: 0,
            isGroup = chat[Chats.isGroup],
            chatType = chat[Chats.chatType],
            groupName = chat[Chats.groupName],
            groupAnnouncement = chat[Chats.groupAnnouncement],
            groupAvatar = chat[Chats.groupAvatar],
            memberRevision = chat[Chats.memberRevision],
            disappearingMessageSeconds = com.maodouchat.server.service.DisappearingMessagePolicy.effectiveSeconds(
                isGroup = chat[Chats.isGroup],
                requestedSeconds = chat[Chats.disappearingMessageSeconds]
            )
        )
    }

    fun getChatById(chatId: String, viewerId: String = ""): ChatResponse? {
        return transaction {
            val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull() ?: return@transaction null
            // 8.48 修复：viewer 提供时按双向拉黑过滤最后消息预览（H2 不变量——
            // 打开单个聊天也不泄露被拉黑方的明文预览，与 getChatsForUser 一致）。
            val blockedEitherWay = if (viewerId.isBlank()) emptySet() else {
                BlockedUsers.selectAll()
                    .where { (BlockedUsers.blockerId eq viewerId) or (BlockedUsers.blockedId eq viewerId) }
                    .map { row ->
                        val blocker = row[BlockedUsers.blockerId]
                        val blocked = row[BlockedUsers.blockedId]
                        if (blocker == viewerId) blocked else blocker
                    }.toSet()
            }
            val participants = (ChatParticipants innerJoin Users)
                .selectAll()
                .where { ChatParticipants.chatId eq chatId }
                .filterNot { it[Users.id] in blockedEitherWay }
                .map {
                    UserResponse(
                        id = it[Users.id],
                        name = it[Users.name],
                        email = "",
                        avatar = it[Users.avatar],
                        status = if (it[Users.showStatus]) it[Users.status] else "",
                        isOnline = it[Users.showOnline] && it[Users.isOnline]
                    )
                }
            val lastMsg = Messages.selectAll().where { Messages.chatId eq chatId }
                .orderBy(Messages.timestamp to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
            val lastMsgType = lastMsg?.get(Messages.type)
            val lastMsgSender = lastMsg?.get(Messages.senderId)
            val visibleLastMsg = if (
                lastMsgType == HIDDEN_SENDER_KEY_TYPE ||
                (viewerId.isNotBlank() && lastMsgSender != null && lastMsgSender != viewerId && lastMsgSender in blockedEitherWay)
            ) {
                null
            } else {
                lastMsg
            }

            // 修复 lastMessage/lastMessageType 不一致：当只有 SK_DIST 消息时，统一用空预览
            val effectiveLastMsg = visibleLastMsg ?: if (lastMsgType == HIDDEN_SENDER_KEY_TYPE) null else visibleLastMsg
            // SK_DIST 时间仍参与排序，避免密钥分发后会话掉底
            val isLastSkDist = lastMsgType == HIDDEN_SENDER_KEY_TYPE
            val lastMsgTime = lastMsg?.get(Messages.timestamp) ?: 0

            ChatResponse(
                id = chatId,
                participants = participants,
                lastMessage = effectiveLastMsg?.let { previewForType(it[Messages.type]) } ?: "",
                lastMessageType = effectiveLastMsg?.get(Messages.type) ?: "TEXT",
                lastMessageTime = if (isLastSkDist) lastMsgTime else (effectiveLastMsg?.get(Messages.timestamp) ?: 0),
                isGroup = chat[Chats.isGroup],
                chatType = chat[Chats.chatType],
                groupName = chat[Chats.groupName],
                        groupAnnouncement = chat[Chats.groupAnnouncement],
                        groupAvatar = chat[Chats.groupAvatar],
                memberRevision = chat[Chats.memberRevision],
                disappearingMessageSeconds = com.maodouchat.server.service.DisappearingMessagePolicy.effectiveSeconds(
                    isGroup = chat[Chats.isGroup],
                    requestedSeconds = chat[Chats.disappearingMessageSeconds]
                )
            )
        }
    }

    fun getChatsForUser(userId: String): List<ChatResponse> {
        return transaction {
            val chatIds = ChatParticipants.selectAll().where { ChatParticipants.userId eq userId }
                .map { it[ChatParticipants.chatId] }.distinct()
            if (chatIds.isEmpty()) return@transaction emptyList()

            // 批量查询所有 chat 信息，避免 N+1
            val chats = Chats.selectAll().where { Chats.id inList chatIds }.associate { it[Chats.id] to it }
            // 批量查询所有参与者
            val participantsByChat = ChatParticipants.selectAll()
                .where { ChatParticipants.chatId inList chatIds }
                .groupBy { it[ChatParticipants.chatId] }
                .mapValues { (_, rows) -> rows.joinToString("|") { it[ChatParticipants.userId] } }
            // 批量查询最后一条消息：优化为两步查询，避免加载全部消息到内存
            // Step 1: 用 SQL 聚合获取每个聊心的最大 timestamp（走索引，不加载消息体）
            val maxTsExpr = Messages.timestamp.max()
            val maxTsByChat: Map<String, Long> = Messages
                .select(Messages.chatId, maxTsExpr)
                .where { Messages.chatId inList chatIds }
                .groupBy(Messages.chatId)
                .associate { it[Messages.chatId] to (it[maxTsExpr] ?: 0L) }

            // Step 2: 单条窗口函数 SQL 一次取回每个 chat 的最后一条消息 id
            // （8.30 性能优化 A2：替代逐 chat 查询；H2/PostgreSQL 均支持 ROW_NUMBER OVER）
            val lastMsgIds: Set<String> = if (chatIds.isEmpty()) {
                emptySet()
            } else {
                val placeholders = chatIds.joinToString(",") { "?" }
                TransactionManager.current().exec(
                    """
                    SELECT chat_id, id FROM (
                        SELECT chat_id, id,
                               ROW_NUMBER() OVER (PARTITION BY chat_id ORDER BY "timestamp" DESC, id DESC) AS rn
                        FROM messages
                        WHERE chat_id IN ($placeholders)
                    ) ranked WHERE rn = 1
                    """.trimIndent(),
                    chatIds.map { org.jetbrains.exposed.sql.VarCharColumnType() to it }
                ) { rs ->
                    val ids = LinkedHashSet<String>()
                    while (rs.next()) ids.add(rs.getString("id"))
                    ids
                }.orEmpty()
            }
            val lastMsgMap = if (lastMsgIds.isEmpty()) {
                emptyMap()
            } else {
                Messages.selectAll()
                    .where { Messages.id inList lastMsgIds }
                    .associateBy { it[Messages.chatId] }
            }

            // 批量查询所有参与者对应的用户信息，避免 N+1（原来每个 participant 一次 SELECT）
            val allParticipantIds = participantsByChat.values.flatMap { it.split("|") }.distinct()
            val userMap = Users.selectAll().where { Users.id inList allParticipantIds }
                .associateBy { it[Users.id] }
            fun userRow(uid: String): UserResponse? {
                val row = userMap[uid] ?: return null
                // 与 getChatById 保持一致：showOnline/showStatus 隐私开关
                return UserResponse(
                    id = row[Users.id],
                    name = row[Users.name],
                    email = "",
                    avatar = row[Users.avatar],
                    status = if (row[Users.showStatus]) row[Users.status] else "",
                    isOnline = row[Users.showOnline] && row[Users.isOnline]
                )
            }

            // Per-user receipts are the unread source of truth, especially for groups.
            // 与历史一致：不计入 viewer 拉黑发送者的未读
            val countExpr = Messages.id.count()
            val readByUser = ReadReceipts
                .select(ReadReceipts.messageId)
                .where { ReadReceipts.userId eq userId }
            // 8.48 修复 H2：拉黑为双向语义——预览与未读都应同时过滤「拉黑对方」与「被对方拉黑」。
            // 此前仅查 blockerId eq userId（单向），B 拉黑 A 后 A 仍看到 B 的最后消息预览与未读数
            //（历史已双向过滤 → 明文泄露 + 无法清零的未读角标）。
            val blockedEitherWay = BlockedUsers.selectAll()
                .where { (BlockedUsers.blockerId eq userId) or (BlockedUsers.blockedId eq userId) }
                .map { row ->
                    val blocker = row[BlockedUsers.blockerId]
                    val blocked = row[BlockedUsers.blockedId]
                    if (blocker == userId) blocked else blocker
                }
                .toSet()
            // BUG-8 fix: 排除已过期的阅后即焚消息，与 getMessages 保持一致
            val nowMs = System.currentTimeMillis()
            val notExpired = (Messages.expiresAt.isNull()) or (Messages.expiresAt eq 0L) or (Messages.expiresAt greater nowMs)
            val unreadBase = (Messages.chatId inList chatIds) and
                (Messages.type neq "SK_DIST") and
                (Messages.senderId neq userId) and
                (Messages.id notInSubQuery readByUser) and
                notExpired
            val unreadCondition = if (blockedEitherWay.isEmpty()) unreadBase
            else unreadBase and (Messages.senderId notInList blockedEitherWay.toList())
            val unreadByChat: Map<String, Int> = Messages
                .select(Messages.chatId, countExpr)
                .where { unreadCondition }
                .groupBy(Messages.chatId)
                .associate {
                    it[Messages.chatId] to it[countExpr].coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }

            val settingsByChat = ChatUserSettings.selectAll()
                .where { (ChatUserSettings.userId eq userId) and (ChatUserSettings.chatId inList chatIds) }
                .associateBy { it[ChatUserSettings.chatId] }

            chatIds.mapNotNull { id -> chats[id]?.let { chatRow -> id to chatRow } }
                .map { (id, chatRow) ->
                    val participantIds = participantsByChat[id]?.split("|") ?: emptyList()
                    val participants = participantIds
                        .filterNot { it in blockedEitherWay }
                        .mapNotNull { pid -> userRow(pid) }
                    val lastMsg = lastMsgMap[id]
                    // SK_DIST 是内部密钥分发消息，不作为预览显示
                    val lastMsgType = lastMsg?.get(Messages.type)
                    val lastMsgSender = lastMsg?.get(Messages.senderId)
                    val lastMsgTime = lastMsg?.get(Messages.timestamp) ?: 0
                    // 8.48：最后消息发送者被双向拉黑 → 不显示明文预览（与历史双向过滤一致）
                    val previewBlocked = lastMsgSender != null && lastMsgSender != userId && lastMsgSender in blockedEitherWay
                    val isLastSkDist = lastMsgType == "SK_DIST"
                    val effectiveLastMsg = if (isLastSkDist || previewBlocked) {
                        null
                    } else {
                        lastMsg
                    }
                    val settings = settingsByChat[id]
                    ChatResponse(
                        id = id,
                        participants = participants,
                        lastMessage = effectiveLastMsg?.let { row -> previewForType(row[Messages.type]) } ?: "",
                        lastMessageType = effectiveLastMsg?.get(Messages.type) ?: "TEXT",
                        // SK_DIST 时间仍参与排序，避免密钥分发后会话列表掉到底部
                        lastMessageTime = if (isLastSkDist) lastMsgTime else (effectiveLastMsg?.get(Messages.timestamp) ?: 0),
                        unreadCount = unreadByChat[id] ?: 0,
                        isGroup = chatRow[Chats.isGroup],
                        chatType = chatRow[Chats.chatType],
                        groupName = chatRow[Chats.groupName],
                        groupAnnouncement = chatRow[Chats.groupAnnouncement],
                        groupAvatar = chatRow[Chats.groupAvatar],
                        memberRevision = chatRow[Chats.memberRevision],
                        pinnedAt = settings?.get(ChatUserSettings.pinnedAt) ?: 0,
                        notificationsMuted = settings?.get(ChatUserSettings.notificationsMuted) ?: false,
                        archived = settings?.get(ChatUserSettings.archived) ?: false,
                        markedUnread = settings?.get(ChatUserSettings.markedUnread) ?: false,
                        settingsUpdatedAt = settings?.get(ChatUserSettings.updatedAt) ?: 0,
                        disappearingMessageSeconds = com.maodouchat.server.service.DisappearingMessagePolicy.effectiveSeconds(
                            isGroup = chatRow[Chats.isGroup],
                            requestedSeconds = chatRow[Chats.disappearingMessageSeconds]
                        )
                    )
                }
                .sortedByDescending { it.lastMessageTime }
        }
    }

    fun getChatBetweenUsers(userId1: String, userId2: String): ChatResponse? {
        if (userId1 == userId2) return null
        val pairKey = listOf(userId1, userId2).sorted().joinToString(":")
        // 优先 pair 表（O(1)）；历史数据回落到 participants 扫描
        return lookupDirectChat(pairKey, userId1, userId2)
    }

    fun getParticipantIds(chatId: String): List<String> {
        return transaction {
            ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }.map { it[ChatParticipants.userId] }
        }
    }

    /** 批量取群 memberRevision 与参与者（Bot 删除广播等场景，避免逐群 2 次查询）。 */
    fun getGroupRevisionAndParticipantIds(chatIds: List<String>): Map<String, Pair<Long, List<String>>> = transaction {
        if (chatIds.isEmpty()) return@transaction emptyMap()
        val revisions = Chats.selectAll()
            .where { (Chats.id inList chatIds) and (Chats.isGroup eq true) }
            .map { it[Chats.id] to it[Chats.memberRevision] }
            .toMap()
        if (revisions.isEmpty()) return@transaction emptyMap()
        val participants = ChatParticipants.selectAll()
            .where { ChatParticipants.chatId inList revisions.keys }
            .groupBy { it[ChatParticipants.chatId] }
            .mapValues { (_, rows) -> rows.map { it[ChatParticipants.userId] } }
        revisions.mapValues { (chatId, revision) -> revision to participants[chatId].orEmpty() }
    }

    fun isParticipant(chatId: String, userId: String): Boolean {
        return transaction {
            ChatParticipants
                .selectAll()
                .where { (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId) }
                .limit(1)
                .firstOrNull() != null
        }
    }

    fun getChatType(chatId: String): String {
        return transaction {
            Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()?.get(Chats.chatType) ?: ChatType.DIRECT
        }
    }

    fun isChannelOwner(chatId: String, userId: String): Boolean {
        return transaction {
            ChatParticipants
                .selectAll()
                .where { (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId) }
                .limit(1)
                .firstOrNull()
                ?.get(ChatParticipants.role) == "OWNER"
        }
    }

    fun updateUserSettings(chatId: String, userId: String, request: UpdateChatSettingsRequest): ChatSettingsResponse = transaction {
        Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: error("chat_not_found")
        require(ChatParticipants.selectAll().where {
            (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
        }.firstOrNull() != null) { "not_a_participant" }
        val existing = ChatUserSettings.selectAll()
            .where { (ChatUserSettings.chatId eq chatId) and (ChatUserSettings.userId eq userId) }
            .firstOrNull()
        val now = System.currentTimeMillis()
        val pinnedAt = when (request.pinned) {
            true -> existing?.get(ChatUserSettings.pinnedAt)?.takeIf { it > 0 } ?: now
            false -> 0L
            null -> existing?.get(ChatUserSettings.pinnedAt) ?: 0L
        }
        val muted = request.notificationsMuted ?: existing?.get(ChatUserSettings.notificationsMuted) ?: false
        val archived = request.archived ?: existing?.get(ChatUserSettings.archived) ?: false
        val markedUnread = request.markedUnread ?: existing?.get(ChatUserSettings.markedUnread) ?: false
        ChatUserSettings.upsert(ChatUserSettings.chatId, ChatUserSettings.userId) {
            it[ChatUserSettings.chatId] = chatId
            it[ChatUserSettings.userId] = userId
            it[ChatUserSettings.pinnedAt] = pinnedAt
            it[ChatUserSettings.notificationsMuted] = muted
            it[ChatUserSettings.archived] = archived
            it[ChatUserSettings.markedUnread] = markedUnread
            it[ChatUserSettings.updatedAt] = now
        }
        ChatSettingsResponse(chatId, pinnedAt, muted, archived, markedUnread, now)
    }

    fun areNotificationsMuted(chatId: String, userId: String): Boolean = transaction {
        ChatUserSettings.select(ChatUserSettings.notificationsMuted)
            .where { (ChatUserSettings.chatId eq chatId) and (ChatUserSettings.userId eq userId) }
            .firstOrNull()
            ?.get(ChatUserSettings.notificationsMuted)
            ?: false
    }

    /** 批量静音查询（8.30 性能优化 A1）：一次 SQL 返回 [userIds] 中对该 chat 静音的集合。 */
    fun mutedUserIdsInTx(chatId: String, userIds: List<String>): Set<String> {
        if (userIds.isEmpty()) return emptySet()
        return transaction {
            ChatUserSettings.select(ChatUserSettings.userId)
                .where {
                    (ChatUserSettings.chatId eq chatId) and
                        (ChatUserSettings.userId inList userIds) and
                        (ChatUserSettings.notificationsMuted eq true)
                }
                .map { it[ChatUserSettings.userId] }
                .toSet()
        }
    }

    /**
     * 1:1 会话级阅后即焚；群聊拒绝。双方共享同一 timer。
     */
    fun setDisappearingMessages(
        chatId: String,
        actorId: String,
        requestedSeconds: Int
    ): DisappearingMessagesResponse = transaction {
        val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: error("chat_not_found")
        require(ChatParticipants.selectAll().where {
            (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq actorId)
        }.firstOrNull() != null) { "not_a_participant" }
        require(!chat[Chats.isGroup]) { "group_not_supported" }
        val seconds = com.maodouchat.server.service.DisappearingMessagePolicy.normalizeSeconds(requestedSeconds)
        require(com.maodouchat.server.service.DisappearingMessagePolicy.isAllowedSeconds(seconds)) {
            "invalid_timer"
        }
        val now = System.currentTimeMillis()
        Chats.update({ Chats.id eq chatId }) {
            it[Chats.disappearingMessageSeconds] = seconds
        }
        DisappearingMessagesResponse(chatId = chatId, seconds = seconds, updatedAt = now)
    }

    /**
     * 退出聊天（群聊退出 / 私聊删除）
     * - 从 ChatParticipants 删除该用户
     * - 如果聊天已无参与者，则级联删除聊天、消息和加密附件元数据
     * - 返回被删除的附件 ID，由调用方清理磁盘对象（避免事务内做 IO）
     */
    fun leaveChat(chatId: String, userId: String): LeaveChatOutcome {
        return transaction {
            val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
                ?: return@transaction LeaveChatOutcome(LeaveChatResult.NOT_PARTICIPANT)
            val participants = ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }.toList()
            val current = participants.firstOrNull { it[ChatParticipants.userId] == userId }
                ?: return@transaction LeaveChatOutcome(LeaveChatResult.NOT_PARTICIPANT)
            // 广播频道：创建者（OWNER）离开即删除整个频道（所有订阅者随之退订）；订阅者离开仅退订。
            if (chat[Chats.chatType] == ChatType.CHANNEL && current[ChatParticipants.role] == "OWNER") {
                // 8.48 修复 L4：频道删除前为所有订阅者写 MEMBER_LEFT 审计 + bump memberRevision，
                // 让在线订阅者收到群变更事件及时失效本地密钥/刷新列表（此前无任何感知）。
                val subscriberIds = participants
                    .filter { it[ChatParticipants.userId] != userId }
                    .map { it[ChatParticipants.userId] }
                subscriberIds.forEach { sid -> insertGroupAudit(chatId, userId, "MEMBER_LEFT", sid) }
                if (chat[Chats.isGroup]) {
                    Chats.update({ Chats.id eq chatId }) {
                        it[Chats.memberRevision] = chat[Chats.memberRevision] + 1
                    }
                }
                ChatUserSettings.deleteWhere { (ChatUserSettings.chatId eq chatId) and (ChatUserSettings.userId eq userId) }
                val attachmentIds = deleteChatRows(chatId)
                return@transaction LeaveChatOutcome(
                    result = LeaveChatResult.LEFT,
                    deletedAttachmentIds = attachmentIds,
                    deletedGroupAvatarUrl = chat[Chats.groupAvatar]
                )
            }
            if (chat[Chats.isGroup] && current[ChatParticipants.role] == "OWNER" && participants.size > 1) {
                return@transaction LeaveChatOutcome(LeaveChatResult.OWNER_TRANSFER_REQUIRED)
            }
            ChatUserSettings.deleteWhere { (ChatUserSettings.chatId eq chatId) and (ChatUserSettings.userId eq userId) }
            val deleted = ChatParticipants.deleteWhere { (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId) }
            val remaining = ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }.count()
            if (remaining == 0L) {
                val attachmentIds = deleteChatRows(chatId)
                LeaveChatOutcome(
                    result = LeaveChatResult.LEFT,
                    deletedAttachmentIds = attachmentIds,
                    deletedGroupAvatarUrl = chat[Chats.groupAvatar]
                )
            } else if (!chat[Chats.isGroup] && remaining == 1L) {
                // 1:1 一方删除后只剩对方一人：整会话清除（含对方侧的幽灵 1 人会话），
                // 否则对方列表残留“只有自己”的旧会话，且 getOrCreateDirectChat 要求恰好 2 名成员
                // 会新建一个私聊，同一对用户出现重复会话。
                val attachmentIds = deleteChatRows(chatId)
                LeaveChatOutcome(
                    result = LeaveChatResult.LEFT,
                    deletedAttachmentIds = attachmentIds,
                    deletedGroupAvatarUrl = null
                )
            } else {
                // 1:1 任一方离开后 pair 映射失效：否则 getOrCreateDirectChat 会回落到半空 chat，重开私聊永远加不回自己
                if (!chat[Chats.isGroup]) {
                    DirectChatPairs.deleteWhere { DirectChatPairs.chatId eq chatId }
                }
                if (deleted > 0) {
                    if (chat[Chats.isGroup]) {
                        insertGroupAudit(chatId, userId, "MEMBER_LEFT", userId)
                    }
                    bumpMemberRevisionIfGroup(chatId)
                }
                LeaveChatOutcome(LeaveChatResult.LEFT)
            }
        }
    }

    fun transferOwnership(chatId: String, ownerId: String, targetUserId: String): TransferOwnershipResult = transaction {
        if (ownerId == targetUserId) return@transaction TransferOwnershipResult.SAME_USER
        val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: return@transaction TransferOwnershipResult.CHAT_NOT_FOUND
        if (!chat[Chats.isGroup]) return@transaction TransferOwnershipResult.NOT_GROUP
        val participants = ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }.toList()
        val owner = participants.firstOrNull { it[ChatParticipants.userId] == ownerId }
            ?: return@transaction TransferOwnershipResult.NOT_OWNER
        if (owner[ChatParticipants.role] != "OWNER") return@transaction TransferOwnershipResult.NOT_OWNER
        val target = participants.firstOrNull { it[ChatParticipants.userId] == targetUserId }
            ?: return@transaction TransferOwnershipResult.TARGET_NOT_PARTICIPANT

        ChatParticipants.update({
            (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq ownerId)
        }) { it[ChatParticipants.role] = "ADMIN" }
        ChatParticipants.update({
            (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq targetUserId)
        }) { it[ChatParticipants.role] = "OWNER" }
        Chats.update({ Chats.id eq chatId }) {
            it[Chats.memberRevision] = chat[Chats.memberRevision] + 1
        }
        insertGroupAudit(chatId, ownerId, "OWNERSHIP_TRANSFERRED", targetUserId)
        TransferOwnershipResult.TRANSFERRED
    }

    /** Adds the whole batch or none of it; permission, limit, users, revision and audit share one lock. */
    fun addGroupMembersAs(
        chatId: String,
        actorId: String,
        requestedUserIds: List<String>,
        maxMembers: Int
    ): AddGroupMembersResult = transaction {
        val requestedIds = requestedUserIds.distinct()
        val lockedRequestedUsers = lockUsersInTx(requestedIds)
        val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: return@transaction AddGroupMembersResult(GroupMemberMutationResult.CHAT_NOT_FOUND)
        if (!chat[Chats.isGroup]) return@transaction AddGroupMembersResult(GroupMemberMutationResult.NOT_GROUP)
        val participants = ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }.toList()
        val actor = participants.firstOrNull { it[ChatParticipants.userId] == actorId }
            ?: return@transaction AddGroupMembersResult(GroupMemberMutationResult.ACTOR_NOT_PARTICIPANT)
        if (actor[ChatParticipants.role] !in ADMIN_ROLES) {
            return@transaction AddGroupMembersResult(GroupMemberMutationResult.FORBIDDEN)
        }
        val existingIds = participants.map { it[ChatParticipants.userId] }.toSet()
        // BUG-7 fix: 去重 requestedUserIds，防止重复 ID 导致 PK 冲突和成员数误计
        val addedIds = requestedIds.filterNot(existingIds::contains)
        val boundedMaxMembers = maxMembers.coerceAtLeast(0)
        if (existingIds.size > boundedMaxMembers || addedIds.size > boundedMaxMembers - existingIds.size) {
            return@transaction AddGroupMembersResult(GroupMemberMutationResult.MEMBER_LIMIT_EXCEEDED)
        }
        // 已注销账号不可再拉进群（deletedAt 非空）
        val addedIdSet = addedIds.toSet()
        val knownIds = lockedRequestedUsers
            .filter { it[Users.id] in addedIdSet }
            .filter { it[Users.deletedAt] == null }
            .map { it[Users.id] }
            .toSet()
        val missing = addedIds.firstOrNull { it !in knownIds }
        if (missing != null) {
            return@transaction AddGroupMembersResult(
                GroupMemberMutationResult.USER_NOT_FOUND,
                missingUserId = missing
            )
        }
        // 与任一现有成员双向拉黑的用户不可被拉入（整批失败；与邀请入群一致）
        // 8.48 修复 H3：一次批量载入 (现有成员∪新增) 间的 BlockedUsers 对，内存判断双向
        // 拉黑——此前双层循环每组合一次查询（O(N×M) 次 DB 查询，500 人群加 20 人=1 万次）
        val blockedId = if (existingIds.isEmpty()) null else {
            val involved = existingIds + addedIds
            val pairSet = BlockedUsers.select(BlockedUsers.blockerId, BlockedUsers.blockedId)
                .where {
                    (BlockedUsers.blockerId inList involved) and (BlockedUsers.blockedId inList involved)
                }
                .toList()
                .map { it[BlockedUsers.blockerId] to it[BlockedUsers.blockedId] }
                .toSet()
            addedIds.firstOrNull { candidate ->
                existingIds.any { memberId ->
                    pairSet.contains(memberId to candidate) || pairSet.contains(candidate to memberId)
                }
            }
        }
        if (blockedId != null) {
            return@transaction AddGroupMembersResult(
                GroupMemberMutationResult.BLOCKED,
                blockedUserId = blockedId
            )
        }
        val now = System.currentTimeMillis()
        addedIds.forEach { userId ->
            ChatParticipants.insert {
                it[ChatParticipants.chatId] = chatId
                it[ChatParticipants.userId] = userId
                it[ChatParticipants.role] = "MEMBER"
                it[ChatParticipants.joinedAt] = now
            }
            insertGroupAudit(chatId, actorId, "MEMBER_ADDED", userId)
        }
        if (addedIds.isNotEmpty()) {
            Chats.update({ Chats.id eq chatId }) {
                it[Chats.memberRevision] = chat[Chats.memberRevision] + 1
            }
        }
        AddGroupMembersResult(GroupMemberMutationResult.UPDATED, addedUserIds = addedIds)
    }

    /** Adds an owned bot as ADMIN while serializing against bot deletion and membership mutations. */
    fun addOwnedBotAsAdmin(
        chatId: String,
        actorId: String,
        botId: String,
        maxMembers: Int
    ): AddOwnedBotResult = transaction {
        // Bot deletion uses bot -> chat; use the same order here.
        val bot = BotApps.selectAll().where { BotApps.id eq botId }.forUpdate().firstOrNull()
        val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: return@transaction AddOwnedBotResult.CHAT_NOT_FOUND
        if (!chat[Chats.isGroup]) return@transaction AddOwnedBotResult.NOT_GROUP
        val participants = ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }.toList()
        val actor = participants.firstOrNull { it[ChatParticipants.userId] == actorId }
            ?: return@transaction AddOwnedBotResult.FORBIDDEN
        if (actor[ChatParticipants.role] !in ADMIN_ROLES) return@transaction AddOwnedBotResult.FORBIDDEN
        if (bot == null) return@transaction AddOwnedBotResult.BOT_NOT_FOUND
        if (bot[BotApps.ownerUserId] != actorId) return@transaction AddOwnedBotResult.BOT_NOT_OWNED
        if (!bot[BotApps.enabled]) return@transaction AddOwnedBotResult.BOT_DISABLED
        if (participants.any { it[ChatParticipants.userId] == botId }) {
            return@transaction AddOwnedBotResult.ALREADY_MEMBER
        }
        if (participants.size >= maxMembers.coerceAtLeast(1)) {
            return@transaction AddOwnedBotResult.MEMBER_LIMIT_EXCEEDED
        }
        ChatParticipants.insert {
            it[ChatParticipants.chatId] = chatId
            it[ChatParticipants.userId] = botId
            it[ChatParticipants.joinedAt] = System.currentTimeMillis()
            it[ChatParticipants.role] = "ADMIN"
        }
        Chats.update({ Chats.id eq chatId }) {
            it[Chats.memberRevision] = chat[Chats.memberRevision] + 1
        }
        insertGroupAudit(chatId, actorId, "BOT_ADDED", botId)
        AddOwnedBotResult.ADDED
    }

    /** Permission check, removal, revision bump and audit are serialized by the chat row lock. */
    fun removeGroupMemberAs(
        chatId: String,
        actorId: String,
        targetUserId: String
    ): GroupMemberMutationResult = transaction {
        val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: return@transaction GroupMemberMutationResult.CHAT_NOT_FOUND
        if (!chat[Chats.isGroup]) return@transaction GroupMemberMutationResult.NOT_GROUP
        val participants = ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }.toList()
        val actor = participants.firstOrNull { it[ChatParticipants.userId] == actorId }
            ?: return@transaction GroupMemberMutationResult.ACTOR_NOT_PARTICIPANT
        val target = participants.firstOrNull { it[ChatParticipants.userId] == targetUserId }
            ?: return@transaction GroupMemberMutationResult.TARGET_NOT_PARTICIPANT
        val actorRole = actor[ChatParticipants.role]
        val targetRole = target[ChatParticipants.role]
        if (actorRole !in ADMIN_ROLES) return@transaction GroupMemberMutationResult.FORBIDDEN
        if (actorId == targetUserId) return@transaction GroupMemberMutationResult.SELF_NOT_ALLOWED
        if (targetRole == "OWNER") return@transaction GroupMemberMutationResult.OWNER_PROTECTED
        if (actorRole == "ADMIN" && targetRole == "ADMIN") {
            return@transaction GroupMemberMutationResult.PEER_ADMIN_PROTECTED
        }
        ChatUserSettings.deleteWhere {
            (ChatUserSettings.chatId eq chatId) and (ChatUserSettings.userId eq targetUserId)
        }
        val deleted = ChatParticipants.deleteWhere {
            (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq targetUserId)
        }
        if (deleted != 1) return@transaction GroupMemberMutationResult.TARGET_NOT_PARTICIPANT
        Chats.update({ Chats.id eq chatId }) {
            it[Chats.memberRevision] = chat[Chats.memberRevision] + 1
        }
        insertGroupAudit(chatId, actorId, "MEMBER_REMOVED", targetUserId)
        GroupMemberMutationResult.UPDATED
    }

    fun updateGroupMemberRoleAsOwner(
        chatId: String,
        actorId: String,
        targetUserId: String,
        role: String
    ): GroupMemberMutationResult = transaction {
        if (role !in MUTABLE_MEMBER_ROLES) return@transaction GroupMemberMutationResult.FORBIDDEN
        val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: return@transaction GroupMemberMutationResult.CHAT_NOT_FOUND
        if (!chat[Chats.isGroup]) return@transaction GroupMemberMutationResult.NOT_GROUP
        val participants = ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }.toList()
        val actor = participants.firstOrNull { it[ChatParticipants.userId] == actorId }
            ?: return@transaction GroupMemberMutationResult.ACTOR_NOT_PARTICIPANT
        if (actor[ChatParticipants.role] != "OWNER") return@transaction GroupMemberMutationResult.FORBIDDEN
        if (actorId == targetUserId) return@transaction GroupMemberMutationResult.SELF_NOT_ALLOWED
        if (participants.none { it[ChatParticipants.userId] == targetUserId }) {
            return@transaction GroupMemberMutationResult.TARGET_NOT_PARTICIPANT
        }
        val updated = ChatParticipants.update({
            (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq targetUserId)
        }) { it[ChatParticipants.role] = role }
        if (updated != 1) return@transaction GroupMemberMutationResult.TARGET_NOT_PARTICIPANT
        Chats.update({ Chats.id eq chatId }) {
            it[Chats.memberRevision] = chat[Chats.memberRevision] + 1
        }
        insertGroupAudit(chatId, actorId, if (role == "ADMIN") "MEMBER_PROMOTED" else "MEMBER_DEMOTED", targetUserId)
        GroupMemberMutationResult.UPDATED
    }

    fun updateGroupMemberTitleAsAdmin(
        chatId: String,
        actorId: String,
        targetUserId: String,
        title: String?
    ): GroupMemberMutationResult = transaction {
        val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: return@transaction GroupMemberMutationResult.CHAT_NOT_FOUND
        if (!chat[Chats.isGroup]) return@transaction GroupMemberMutationResult.NOT_GROUP
        val participants = ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }.toList()
        val actor = participants.firstOrNull { it[ChatParticipants.userId] == actorId }
            ?: return@transaction GroupMemberMutationResult.ACTOR_NOT_PARTICIPANT
        if (actor[ChatParticipants.role] !in ADMIN_ROLES) return@transaction GroupMemberMutationResult.FORBIDDEN
        if (participants.none { it[ChatParticipants.userId] == targetUserId }) {
            return@transaction GroupMemberMutationResult.TARGET_NOT_PARTICIPANT
        }
        val updated = ChatParticipants.update({
            (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq targetUserId)
        }) { it[ChatParticipants.title] = title }
        if (updated != 1) return@transaction GroupMemberMutationResult.TARGET_NOT_PARTICIPANT
        Chats.update({ Chats.id eq chatId }) {
            it[Chats.memberRevision] = chat[Chats.memberRevision] + 1
        }
        insertGroupAudit(chatId, actorId, "TITLE_UPDATED", targetUserId)
        GroupMemberMutationResult.UPDATED
    }

    fun updateGroupMemberMuteAsAdmin(
        chatId: String,
        actorId: String,
        targetUserId: String,
        mutedUntil: Long
    ): GroupMemberMutationResult = transaction {
        val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: return@transaction GroupMemberMutationResult.CHAT_NOT_FOUND
        if (!chat[Chats.isGroup]) return@transaction GroupMemberMutationResult.NOT_GROUP
        val participants = ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }.toList()
        val actor = participants.firstOrNull { it[ChatParticipants.userId] == actorId }
            ?: return@transaction GroupMemberMutationResult.ACTOR_NOT_PARTICIPANT
        val target = participants.firstOrNull { it[ChatParticipants.userId] == targetUserId }
            ?: return@transaction GroupMemberMutationResult.TARGET_NOT_PARTICIPANT
        val actorRole = actor[ChatParticipants.role]
        val targetRole = target[ChatParticipants.role]
        if (actorRole !in ADMIN_ROLES) return@transaction GroupMemberMutationResult.FORBIDDEN
        if (actorId == targetUserId) return@transaction GroupMemberMutationResult.SELF_NOT_ALLOWED
        if (targetRole == "OWNER") return@transaction GroupMemberMutationResult.OWNER_PROTECTED
        if (actorRole == "ADMIN" && targetRole == "ADMIN") {
            return@transaction GroupMemberMutationResult.PEER_ADMIN_PROTECTED
        }
        val updated = ChatParticipants.update({
            (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq targetUserId)
        }) { it[ChatParticipants.mutedUntil] = mutedUntil.coerceAtLeast(0) }
        if (updated != 1) return@transaction GroupMemberMutationResult.TARGET_NOT_PARTICIPANT
        Chats.update({ Chats.id eq chatId }) {
            it[Chats.memberRevision] = chat[Chats.memberRevision] + 1
        }
        insertGroupAudit(chatId, actorId, if (mutedUntil > 0) "MEMBER_MUTED" else "MEMBER_UNMUTED", targetUserId)
        GroupMemberMutationResult.UPDATED
    }

    /**
     * 8.48 修复 M8：批量设置群成员禁言（bot setChatPermissions 全群静音）。
     * 一次事务完成锁群+角色判定+批量 UPDATE+审计——此前逐成员 isOwnerOrAdmin +
     * updateGroupMemberMuteAsAdmin（独立事务 ≈5 次查询/人，500 人群 ≈2500 次）。
     * 返回实际更新人数。
     */
    fun muteGroupMembersAsAdmin(
        chatId: String,
        actorId: String,
        targetUserIds: List<String>,
        mutedUntil: Long
    ): Int = transaction {
        val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: return@transaction 0
        if (!chat[Chats.isGroup]) return@transaction 0
        val participants = ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }.toList()
        val actor = participants.firstOrNull { it[ChatParticipants.userId] == actorId }
            ?: return@transaction 0
        val actorRole = actor[ChatParticipants.role]
        if (actorRole !in ADMIN_ROLES) return@transaction 0
        val roleByUser = participants.associate { it[ChatParticipants.userId] to it[ChatParticipants.role] }
        val targets = targetUserIds.filter { uid ->
            uid != actorId &&
                roleByUser[uid] != null &&
                roleByUser[uid] != "OWNER" &&
                !(actorRole == "ADMIN" && roleByUser[uid] == "ADMIN")
        }
        if (targets.isEmpty()) return@transaction 0
        val updated = ChatParticipants.update({
            (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId inList targets)
        }) { it[ChatParticipants.mutedUntil] = mutedUntil.coerceAtLeast(0) }
        if (updated > 0) {
            Chats.update({ Chats.id eq chatId }) {
                it[Chats.memberRevision] = chat[Chats.memberRevision] + 1
            }
            targets.forEach { uid ->
                insertGroupAudit(chatId, actorId, if (mutedUntil > 0) "MEMBER_MUTED" else "MEMBER_UNMUTED", uid)
            }
        }
        updated
    }

    /** Deletes all chat-owned rows and returns encrypted attachment IDs that still need file cleanup. */
    private fun deleteChatRows(chatId: String): List<String> {
        val messageIds = Messages
            .select(Messages.id)
            .where { Messages.chatId eq chatId }
            .orderBy(Messages.id to SortOrder.ASC)
            .forUpdate()
            .map { it[Messages.id] }
        if (messageIds.isNotEmpty()) {
            MessageReactions.deleteWhere { MessageReactions.messageId inList messageIds }
            ReadReceipts.deleteWhere { ReadReceipts.messageId inList messageIds }
            StarMessages.deleteWhere { StarMessages.messageId inList messageIds }
            // BUG-1 fix: PinnedMessages 有 FK 到 Messages 和 Chats，必须先删除
            PinnedMessages.deleteWhere { PinnedMessages.messageId inList messageIds }
        }
        // The caller holds chat; keep the global chat -> message -> attachment lock order.
        Messages.deleteWhere { Messages.chatId eq chatId }
        val attachmentIds = EncryptedAttachments
            .select(EncryptedAttachments.id)
            .where { EncryptedAttachments.chatId eq chatId }
            .orderBy(EncryptedAttachments.id to SortOrder.ASC)
            .forUpdate()
            .map { it[EncryptedAttachments.id] }
        if (attachmentIds.isNotEmpty()) {
            EncryptedAttachments.deleteWhere { EncryptedAttachments.id inList attachmentIds }
        }
        val pollIds = GroupPolls.select(GroupPolls.id)
            .where { GroupPolls.chatId eq chatId }
            .orderBy(GroupPolls.id to SortOrder.ASC)
            .forUpdate()
            .map { it[GroupPolls.id] }
        if (pollIds.isNotEmpty()) {
            GroupPollVotes.deleteWhere { GroupPollVotes.pollId inList pollIds }
            GroupPolls.deleteWhere { GroupPolls.id inList pollIds }
        }
        BotCommandLogs.deleteWhere { BotCommandLogs.chatId eq chatId }
        // FK: direct_chat_pairs.chat_id / message_mutations.chat_id → chats.id
        DirectChatPairs.deleteWhere { DirectChatPairs.chatId eq chatId }
        MessageMutations.deleteWhere { MessageMutations.chatId eq chatId }
        SenderKeyDistributions.deleteWhere { SenderKeyDistributions.chatId eq chatId }
        AiPreferences.deleteWhere { AiPreferences.chatId eq chatId }
        ChatUserSettings.deleteWhere { ChatUserSettings.chatId eq chatId }
        GroupAuditLogs.deleteWhere { GroupAuditLogs.chatId eq chatId }
        // B3 群玩法：先删明细/投票（FK 指向主表），再删主表，避免删群后残留孤儿行
        GroupChainEntries.deleteWhere { GroupChainEntries.chainId inList chainIds(chatId) }
        GroupChains.deleteWhere { GroupChains.chatId eq chatId }
        GroupPkVotes.deleteWhere { GroupPkVotes.pkId inList pkIds(chatId) }
        GroupPkRounds.deleteWhere { GroupPkRounds.chatId eq chatId }
        GroupCheckins.deleteWhere { GroupCheckins.chatId eq chatId }
        ChatParticipants.deleteWhere { ChatParticipants.chatId eq chatId }
        Chats.deleteWhere { Chats.id eq chatId }
        return attachmentIds
    }

    /** 群内全部接龙 id（删群级联用）。 */
    private fun chainIds(chatId: String): List<String> =
        GroupChains.select(GroupChains.id)
            .where { GroupChains.chatId eq chatId }
            .map { it[GroupChains.id] }

    /** 群内全部 PK 回合 id（删群级联用）。 */
    private fun pkIds(chatId: String): List<String> =
        GroupPkRounds.select(GroupPkRounds.id)
            .where { GroupPkRounds.chatId eq chatId }
            .map { it[GroupPkRounds.id] }

    /**
     * 清理超过保留期的群操作审计日志（默认 365 天），防止活跃群无限增长。
     * 由 Routing.kt 的周期清理循环调用。
     */
    fun purgeOldAuditLogs(retentionDays: Int = 365): Int {
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        return transaction {
            GroupAuditLogs.deleteWhere { GroupAuditLogs.createdAt less cutoff }
        }
    }

    fun updateGroupNameAsAdmin(chatId: String, actorId: String, name: String): GroupMemberMutationResult = transaction {
        val chat = lockedGroupForAdmin(chatId, actorId) ?: return@transaction groupAdminFailure(chatId, actorId)
        Chats.update({ Chats.id eq chatId }) {
            it[Chats.groupName] = name
            it[Chats.memberRevision] = chat[Chats.memberRevision] + 1
        }
        insertGroupAudit(chatId, actorId, "GROUP_RENAMED", null)
        GroupMemberMutationResult.UPDATED
    }

    fun updateGroupAnnouncementAsAdmin(
        chatId: String,
        actorId: String,
        announcement: String?
    ): GroupMemberMutationResult = transaction {
        val chat = lockedGroupForAdmin(chatId, actorId) ?: return@transaction groupAdminFailure(chatId, actorId)
        Chats.update({ Chats.id eq chatId }) {
            it[Chats.groupAnnouncement] = announcement
            it[Chats.memberRevision] = chat[Chats.memberRevision] + 1
        }
        insertGroupAudit(chatId, actorId, "ANNOUNCEMENT_UPDATED", null)
        GroupMemberMutationResult.UPDATED
    }

    fun updateGroupAvatarAsAdmin(
        chatId: String,
        actorId: String,
        avatarUrl: String
    ): GroupAvatarMutationResult = transaction {
        val chat = lockedGroupForAdmin(chatId, actorId)
            ?: return@transaction GroupAvatarMutationResult(groupAdminFailure(chatId, actorId))
        val previous = chat[Chats.groupAvatar]
        Chats.update({ Chats.id eq chatId }) {
            it[Chats.groupAvatar] = avatarUrl
            it[Chats.memberRevision] = chat[Chats.memberRevision] + 1
        }
        insertGroupAudit(chatId, actorId, "AVATAR_UPDATED", null)
        GroupAvatarMutationResult(GroupMemberMutationResult.UPDATED, previous)
    }

    fun updateOwnGroupNickname(
        chatId: String,
        userId: String,
        nickname: String?
    ): GroupMemberMutationResult = transaction {
        val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: return@transaction GroupMemberMutationResult.CHAT_NOT_FOUND
        if (!chat[Chats.isGroup]) return@transaction GroupMemberMutationResult.NOT_GROUP
        val updated = ChatParticipants.update({
            (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId)
        }) { it[ChatParticipants.groupNickname] = nickname }
        if (updated != 1) return@transaction GroupMemberMutationResult.ACTOR_NOT_PARTICIPANT
        Chats.update({ Chats.id eq chatId }) {
            it[Chats.memberRevision] = chat[Chats.memberRevision] + 1
        }
        insertGroupAudit(chatId, userId, "NICKNAME_UPDATED", userId)
        GroupMemberMutationResult.UPDATED
    }

    private fun lockedGroupForAdmin(chatId: String, actorId: String): ResultRow? {
        val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull() ?: return null
        if (!chat[Chats.isGroup]) return null
        val role = ChatParticipants.select(ChatParticipants.role)
            .where { (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq actorId) }
            .firstOrNull()?.get(ChatParticipants.role)
        return chat.takeIf { role in ADMIN_ROLES }
    }

    private fun groupAdminFailure(chatId: String, actorId: String): GroupMemberMutationResult {
        val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
            ?: return GroupMemberMutationResult.CHAT_NOT_FOUND
        if (!chat[Chats.isGroup]) return GroupMemberMutationResult.NOT_GROUP
        val role = ChatParticipants.select(ChatParticipants.role)
            .where { (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq actorId) }
            .firstOrNull()?.get(ChatParticipants.role)
            ?: return GroupMemberMutationResult.ACTOR_NOT_PARTICIPANT
        return if (role in ADMIN_ROLES) GroupMemberMutationResult.UPDATED else GroupMemberMutationResult.FORBIDDEN
    }

    data class InviteState(val token: String, val expiresAt: Long, val maxUses: Int, val usedCount: Int, val changed: Boolean) {
        val remainingUses: Int get() = (maxUses - usedCount).coerceAtLeast(0)
    }

    fun configureGroupInviteAsAdmin(
        chatId: String,
        actorId: String,
        rotate: Boolean,
        expiresAt: Long,
        maxUses: Int
    ): GroupInviteMutationResult = transaction {
        val chat = lockedGroupForAdmin(chatId, actorId)
            ?: return@transaction GroupInviteMutationResult(groupAdminFailure(chatId, actorId))
        val existingToken = chat[Chats.groupInviteToken]
        val mustRotate = rotate || existingToken.isNullOrBlank() || chat[Chats.groupInviteExpiresAt] <= System.currentTimeMillis() || chat[Chats.groupInviteUseCount] >= chat[Chats.groupInviteMaxUses]
        if (!mustRotate) {
            // 不轮换 token 时仍应落盘新的过期/次数上限（否则 UI 改限无效果）
            val limitsChanged = chat[Chats.groupInviteExpiresAt] != expiresAt ||
                chat[Chats.groupInviteMaxUses] != maxUses
            if (limitsChanged) {
                Chats.update({ Chats.id eq chatId }) {
                    it[Chats.groupInviteExpiresAt] = expiresAt
                    it[Chats.groupInviteMaxUses] = maxUses
                }
                insertGroupAudit(chatId, actorId, "INVITE_CONFIGURED", null)
            }
            return@transaction GroupInviteMutationResult(
                GroupMemberMutationResult.UPDATED,
                InviteState(
                    existingToken,
                    expiresAt,
                    maxUses,
                    chat[Chats.groupInviteUseCount],
                    limitsChanged
                )
            )
        }
        val token = generateInviteToken()
        val usedCount = 0
        Chats.update({ Chats.id eq chatId }) {
            it[Chats.groupInviteToken] = token
            it[Chats.groupInviteExpiresAt] = expiresAt
            it[Chats.groupInviteMaxUses] = maxUses
            it[Chats.groupInviteUseCount] = usedCount
        }
        insertGroupAudit(chatId, actorId, if (rotate) "INVITE_ROTATED" else "INVITE_CONFIGURED", null)
        GroupInviteMutationResult(
            GroupMemberMutationResult.UPDATED,
            InviteState(token, expiresAt, maxUses, usedCount, true)
        )
    }

    fun revokeGroupInviteAsAdmin(chatId: String, actorId: String): GroupMemberMutationResult = transaction {
        val chat = lockedGroupForAdmin(chatId, actorId) ?: return@transaction groupAdminFailure(chatId, actorId)
        Chats.update({ Chats.id eq chatId }) {
            it[Chats.groupInviteToken] = null
            it[Chats.groupInviteExpiresAt] = 0
            it[Chats.groupInviteMaxUses] = 0
            it[Chats.groupInviteUseCount] = 0
            it[Chats.memberRevision] = chat[Chats.memberRevision] + 1
        }
        insertGroupAudit(chatId, actorId, "INVITE_REVOKED", null)
        GroupMemberMutationResult.UPDATED
    }

    fun clearGroupAvatarAsAdmin(chatId: String, actorId: String): GroupAvatarMutationResult = transaction {
        val chat = lockedGroupForAdmin(chatId, actorId)
            ?: return@transaction GroupAvatarMutationResult(groupAdminFailure(chatId, actorId))
        val previous = chat[Chats.groupAvatar]
        Chats.update({ Chats.id eq chatId }) {
            it[Chats.groupAvatar] = null
            it[Chats.memberRevision] = chat[Chats.memberRevision] + 1
        }
        insertGroupAudit(chatId, actorId, "AVATAR_CLEARED", null)
        GroupAvatarMutationResult(GroupMemberMutationResult.UPDATED, previous)
    }

    fun groupAvatarUrlsForParticipant(userId: String): List<String> = transaction {
        (ChatParticipants innerJoin Chats)
            .select(Chats.groupAvatar)
            .where { (ChatParticipants.userId eq userId) and (Chats.isGroup eq true) }
            .mapNotNull { it[Chats.groupAvatar] }
            .distinct()
    }

    /**
     * 8.33：删号前快照用户所在全部群及其成员（删号后路由侧据此广播 memberRevision）。
     * 必须在删除前调用，否则成员列表已不含该用户。
     */
    fun groupMembershipSnapshotForDeletion(userId: String): List<Pair<String, List<String>>> = transaction {
        val memberChatIds = ChatParticipants.select(ChatParticipants.chatId)
            .where { ChatParticipants.userId eq userId }
            .map { it[ChatParticipants.chatId] }
            .distinct()
        if (memberChatIds.isEmpty()) return@transaction emptyList()
        val groupIds = Chats.select(Chats.id)
            .where { (Chats.id inList memberChatIds) and (Chats.isGroup eq true) }
            .map { it[Chats.id] }
            .toSet()
        if (groupIds.isEmpty()) return@transaction emptyList()
        ChatParticipants.selectAll()
            .where { ChatParticipants.chatId inList groupIds }
            .groupBy { it[ChatParticipants.chatId] }
            .map { (chatId, rows) -> chatId to rows.map { it[ChatParticipants.userId] } }
    }

    fun isGroupAvatarUrlReferenced(url: String): Boolean = transaction {
        Chats.select(Chats.id).where { Chats.groupAvatar eq url }.limit(1).any()
    }

    fun allReferencedGroupAvatarFilenames(): Set<String> = transaction {
        Chats.select(Chats.groupAvatar).mapNotNullTo(linkedSetOf()) { row ->
            row[Chats.groupAvatar]?.substringAfterLast('/')
        }
    }

    /**
     * Consume a group invite token.
     * @return JoinInviteResult；blocked=true 表示与现有成员存在拉黑（不消耗 useCount）；
     * limitExceeded=true 表示群已满（同样不消耗 useCount）。
     */
    data class JoinInviteResult(
        val chat: ChatResponse,
        val newlyJoined: Boolean,
        val limitExceeded: Boolean = false,
        val blocked: Boolean = false,
        val channelRejected: Boolean = false
    )

    fun consumeGroupInvite(
        token: String,
        userId: String,
        maxMembers: Int = DEFAULT_MAX_GROUP_MEMBERS
    ): JoinInviteResult? {
        val normalized = token.trim()
        if (!isValidInviteToken(normalized)) return null
        return transaction {
            val lockedJoiner = lockUsersInTx(listOf(userId)).firstOrNull()
            if (lockedJoiner == null || lockedJoiner[Users.deletedAt] != null) return@transaction null
            val chat = Chats.selectAll()
                .where { (Chats.groupInviteToken eq normalized) and (Chats.isGroup eq true) }
                .forUpdate()
                .limit(1).firstOrNull() ?: return@transaction null
            val now = System.currentTimeMillis()
            if (chat[Chats.groupInviteExpiresAt] <= now || chat[Chats.groupInviteUseCount] >= chat[Chats.groupInviteMaxUses]) return@transaction null
            val chatId = chat[Chats.id]
            // 8.63：广播频道不开放邀请加入——在写入成员前就拒绝（此前路由侧 403 在 consume 之后，
            // 造成「先入群后 403」的状态副作用；Bot 生成的频道邀请同样被拦）
            if (chat[Chats.chatType] == ChatType.CHANNEL) {
                val channelResponse = getChatByIdInTx(chatId, userId) ?: return@transaction null
                return@transaction JoinInviteResult(channelResponse, newlyJoined = false, channelRejected = true)
            }
            val alreadyMember = ChatParticipants.selectAll()
                .where { (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId) }
                .limit(1).firstOrNull() != null
            if (!alreadyMember) {
                val memberIds = ChatParticipants.selectAll()
                    .where { ChatParticipants.chatId eq chatId }
                    .map { it[ChatParticipants.userId] }
                // 与任一现有成员双向拉黑则拒绝（不消耗邀请次数）
                // 8.48 修复 H4：一次批量载入 (成员∪自己) 间的 blocked 对，内存判断——
                // 此前每成员一次查询（O(N) 次 DB 查询，500 人群入群=500 次）
                val blockedWithMember = if (memberIds.isEmpty()) false else {
                    val involved = memberIds + userId
                    val pairSet = BlockedUsers.select(BlockedUsers.blockerId, BlockedUsers.blockedId)
                        .where {
                            (BlockedUsers.blockerId inList involved) and (BlockedUsers.blockedId inList involved)
                        }
                        .toList()
                        .map { it[BlockedUsers.blockerId] to it[BlockedUsers.blockedId] }
                        .toSet()
                    memberIds.any { m ->
                        pairSet.contains(m to userId) || pairSet.contains(userId to m)
                    }
                }
                if (blockedWithMember) {
                    val response = getChatByIdInTx(chatId, userId) ?: return@transaction null
                    return@transaction JoinInviteResult(response, newlyJoined = false, blocked = true)
                }
                if (memberIds.size >= maxMembers) {
                    val response = getChatByIdInTx(chatId, userId) ?: return@transaction null
                    return@transaction JoinInviteResult(response, newlyJoined = false, limitExceeded = true)
                }
                ChatParticipants.insert {
                    it[ChatParticipants.chatId] = chatId
                    it[ChatParticipants.userId] = userId
                    it[ChatParticipants.role] = "MEMBER"
                    it[ChatParticipants.joinedAt] = now
                }
                Chats.update({ Chats.id eq chatId }) {
                    it[Chats.groupInviteUseCount] = chat[Chats.groupInviteUseCount] + 1
                    it[Chats.memberRevision] = chat[Chats.memberRevision] + 1
                }
            }
            val response = getChatByIdInTx(chatId, userId) ?: return@transaction null
            JoinInviteResult(response, newlyJoined = !alreadyMember)
        }
    }

    private fun isBlockedEitherWayInTx(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank() || a == b) return false
        return !BlockedUsers.selectAll().where {
            ((BlockedUsers.blockerId eq a) and (BlockedUsers.blockedId eq b)) or
                ((BlockedUsers.blockerId eq b) and (BlockedUsers.blockedId eq a))
        }.empty()
    }

    private fun blockedUserIdsInTx(viewerId: String?): Set<String> {
        if (viewerId.isNullOrBlank()) return emptySet()
        return BlockedUsers.selectAll()
            .where {
                (BlockedUsers.blockerId eq viewerId) or (BlockedUsers.blockedId eq viewerId)
            }
            .map { row ->
                if (row[BlockedUsers.blockerId] == viewerId) row[BlockedUsers.blockedId]
                else row[BlockedUsers.blockerId]
            }
            .toSet()
    }

    fun recordGroupAudit(chatId: String, actorId: String, action: String, targetUserId: String? = null) {
        transaction {
            insertGroupAudit(chatId, actorId, action, targetUserId)
        }
    }

    private fun insertGroupAudit(chatId: String, actorId: String, action: String, targetUserId: String?) {
        GroupAuditLogs.insert {
            it[GroupAuditLogs.id] = "gal_${UUID.randomUUID()}"
            it[GroupAuditLogs.chatId] = chatId
            it[GroupAuditLogs.actorId] = actorId
            it[GroupAuditLogs.action] = action.take(40)
            it[GroupAuditLogs.targetUserId] = targetUserId
            it[GroupAuditLogs.createdAt] = System.currentTimeMillis()
        }
    }

    fun getGroupAudit(
        chatId: String,
        limit: Int = 50,
        offset: Int = 0,
        viewerId: String? = null
    ): List<GroupAuditLogResponse> = transaction {
        // 8.64：offset 分页——此前历史审计最多可见 100 条，活跃群的更早记录永远无法通过 API 获取
        val safeOffset = offset.coerceAtLeast(0)
        val blocked = blockedUserIdsInTx(viewerId)
        val rows = GroupAuditLogs.selectAll().where { GroupAuditLogs.chatId eq chatId }
            .orderBy(GroupAuditLogs.createdAt to SortOrder.DESC, GroupAuditLogs.id to SortOrder.DESC)
            .limit(limit.coerceIn(1, 100), safeOffset.toLong())
            .toList()
            .filter { row ->
                row[GroupAuditLogs.actorId] !in blocked &&
                    (row[GroupAuditLogs.targetUserId] == null || row[GroupAuditLogs.targetUserId] !in blocked)
            }
        val ids = rows.flatMap { listOfNotNull(it[GroupAuditLogs.actorId], it[GroupAuditLogs.targetUserId]) }.distinct()
        val names = if (ids.isEmpty()) emptyMap() else Users.selectAll().where { Users.id inList ids }.associate { it[Users.id] to it[Users.name] }
        rows.map {
            GroupAuditLogResponse(
                id = it[GroupAuditLogs.id],
                actorId = it[GroupAuditLogs.actorId],
                actorName = names[it[GroupAuditLogs.actorId]].orEmpty(),
                action = it[GroupAuditLogs.action],
                targetUserId = it[GroupAuditLogs.targetUserId],
                targetUserName = it[GroupAuditLogs.targetUserId]?.let(names::get),
                createdAt = it[GroupAuditLogs.createdAt]
            )
        }
    }

    fun shareChat(userId1: String, userId2: String): Boolean {
        return transaction {
            val chats1 = ChatParticipants.selectAll().where { ChatParticipants.userId eq userId1 }.map { it[ChatParticipants.chatId] }.toSet()
            val chats2 = ChatParticipants.selectAll().where { ChatParticipants.userId eq userId2 }.map { it[ChatParticipants.chatId] }.toSet()
            chats1.intersect(chats2).isNotEmpty()
        }
    }

    private fun previewForType(type: String): String = when (type) {
        "TEXT" -> "[端到端加密消息]"
        "IMAGE" -> "[端到端加密图片]"
        "GIF" -> "[端到端加密GIF]"
        "STICKER" -> "[贴纸]"
        "LOCATION" -> "[位置]"
        "FILE" -> "[文件]"
        "VOICE" -> "[端到端加密语音]"
        "VIDEO" -> "[端到端加密视频]"
        "NUDGE" -> "[提醒]"
        "SYSTEM" -> "[系统]"
        "REVOKED" -> "[已撤回]"
        else -> "[端到端加密消息]"
    }

    private fun lastVisibleMessage(chatId: String): ResultRow? = Messages
        .selectAll()
        .where { (Messages.chatId eq chatId) and (Messages.type neq HIDDEN_SENDER_KEY_TYPE) }
        .orderBy(Messages.timestamp to SortOrder.DESC)
        .limit(1)
        .firstOrNull()

    private fun bumpMemberRevisionIfGroup(chatId: String) {
        val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull() ?: return
        if (!chat[Chats.isGroup]) return
        Chats.update({ Chats.id eq chatId }) {
            it[Chats.memberRevision] = chat[Chats.memberRevision] + 1
        }
    }

    private fun lockUsersInTx(userIds: List<String>): List<ResultRow> {
        val orderedIds = userIds.distinct().sorted()
        if (orderedIds.isEmpty()) return emptyList()
        return Users.selectAll()
            .where { Users.id inList orderedIds }
            .orderBy(Users.id to SortOrder.ASC)
            .forUpdate()
            .toList()
    }

    private fun isUniqueViolation(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty().lowercase()
            if (current is java.sql.SQLException && current.sqlState == "23505") return true
            if (message.contains("unique") || message.contains("duplicate key")) return true
            current = current.cause
        }
        return false
    }

    private companion object {
        const val HIDDEN_SENDER_KEY_TYPE = "SK_DIST"
        const val DEFAULT_MAX_GROUP_MEMBERS = 500
        val ADMIN_ROLES = setOf("OWNER", "ADMIN")
        val MUTABLE_MEMBER_ROLES = setOf("ADMIN", "MEMBER")
        val INVITE_TOKEN_REGEX = Regex("^[A-Za-z0-9_-]{32,80}$")
        val SECURE_RANDOM = SecureRandom()

        fun generateInviteToken(): String {
            val bytes = ByteArray(32)
            SECURE_RANDOM.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun isValidInviteToken(token: String): Boolean = INVITE_TOKEN_REGEX.matches(token)
    }

    // ─── 群权限相关 ────────────────────

    fun getMemberRole(chatId: String, userId: String): String? {
        return transaction {
            ChatParticipants.selectAll()
                .where { (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId) }
                .firstOrNull()?.get(ChatParticipants.role)
        }
    }

    fun isOwnerOrAdmin(chatId: String, userId: String): Boolean {
        return getMemberRole(chatId, userId) in ADMIN_ROLES
    }

    fun getGroupMembers(chatId: String, viewerId: String? = null): List<GroupMemberResponse> {
        return transaction {
            val blocked = blockedUserIdsInTx(viewerId)
            (ChatParticipants innerJoin Users)
                .selectAll()
                .where { ChatParticipants.chatId eq chatId }
                .filterNot { it[Users.id] in blocked }
                .map {
                    GroupMemberResponse(
                        userId = it[Users.id],
                        name = it[Users.name],
                        avatar = it[Users.avatar],
                        role = it[ChatParticipants.role],
                        title = it[ChatParticipants.title],
                        groupNickname = it[ChatParticipants.groupNickname],
                        joinedAt = it[ChatParticipants.joinedAt],
                        isOnline = it[Users.showOnline] && it[Users.isOnline],
                        mutedUntil = it[ChatParticipants.mutedUntil]
                    )
                }
                .sortedWith(compareBy(
                    { if (it.role == "OWNER") 0 else if (it.role == "ADMIN") 1 else 2 },
                    { it.joinedAt }
                ))
        }
    }

    fun getMutedUntil(chatId: String, userId: String): Long {
        return transaction {
            ChatParticipants.selectAll()
                .where { (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq userId) }
                .firstOrNull()
                ?.get(ChatParticipants.mutedUntil)
                ?: 0L
        }
    }

    fun isMuted(chatId: String, userId: String, now: Long = System.currentTimeMillis()): Boolean {
        val role = getMemberRole(chatId, userId)
        if (role == "OWNER") return false
        return getMutedUntil(chatId, userId) > now
    }
}
