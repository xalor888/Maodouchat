package com.maodouchat.server.repository

// 会话单查簇（邀请快照/按 id/成员门禁/1:1 查找/共享判定）：原 ConversationQueryRepository 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.Users
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.model.ChatResponse
import com.maodouchat.server.model.ChatType
import com.maodouchat.server.model.UserResponse
import com.maodouchat.server.service.DisappearingMessagePolicy
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** B12：群邀请链接 token 快照（替代 Bot 路由内联 Chats.selectAll 事务）。 */
fun ConversationQueryRepository.getGroupInviteTokenState(chatId: String): GroupInviteState? = transaction {
    Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()?.let { row ->
        GroupInviteState(
            token = row[Chats.groupInviteToken].orEmpty(),
            expiresAt = row[Chats.groupInviteExpiresAt],
            maxUses = row[Chats.groupInviteMaxUses],
            usedCount = row[Chats.groupInviteUseCount],
            changed = false,
        )
    }
}


fun ConversationQueryRepository.getById(chatId: String, viewerId: String = ""): ChatResponse? = transaction {
    val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
        ?: return@transaction null
    val blocked = ConversationVisibility.blockedUserIdsInTx(viewerId)
    val participants = (ChatParticipants innerJoin Users)
        .selectAll()
        .where { ChatParticipants.chatId eq chatId }
        .filterNot { it[Users.id] in blocked }
        .map { toUserResponse(it) }

    ChatResponse(
        id = chatId,
        participants = participants,
        lastMessage = "",
        lastMessageType = "TEXT",
        lastMessageTime = latestMessageTimeInTx(chatId, blocked),
        isGroup = chat[Chats.isGroup],
        chatType = chat[Chats.chatType],
        groupName = chat[Chats.groupName],
        groupAnnouncement = chat[Chats.groupAnnouncement],
        groupAvatar = chat[Chats.groupAvatar],
        memberRevision = chat[Chats.memberRevision],
        disappearingMessageSeconds = effectiveTimer(chat),
    )
}


/** Membership gate and read-model construction share one transaction to avoid check/read races. */
fun ConversationQueryRepository.getForParticipant(chatId: String, userId: String): ChatResponse? = transaction {
    val member = ChatParticipants.select(ChatParticipants.userId)
        .where {
            (ChatParticipants.chatId eq chatId) and
                (ChatParticipants.userId eq userId)
        }
        .limit(1)
        .any()
    if (!member) return@transaction null
    getById(chatId, userId)
}


fun ConversationQueryRepository.findDirectBetween(userId1: String, userId2: String): ChatResponse? {
    val conversation = ConversationCreationRepository().findDirect(userId1, userId2) ?: return null
    return getById(conversation.id, userId1)
}


fun ConversationQueryRepository.shareConversation(userId1: String, userId2: String): Boolean = transaction {
    val first = ChatParticipants.select(ChatParticipants.chatId)
        .where { ChatParticipants.userId eq userId1 }
        .mapTo(hashSetOf()) { it[ChatParticipants.chatId] }
    if (first.isEmpty()) return@transaction false
    ChatParticipants.select(ChatParticipants.chatId)
        .where {
            (ChatParticipants.userId eq userId2) and
                (ChatParticipants.chatId inList first)
        }
        .limit(1)
        .any()
}


private fun ConversationQueryRepository.latestMessageTimeInTx(chatId: String, blocked: Set<String>): Long {
    var condition = (MessagingV2Messages.conversationId eq chatId) and
        (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE)
    if (blocked.isNotEmpty()) {
        condition = condition and (MessagingV2Messages.senderUserId notInList blocked.toList())
    }
    return MessagingV2Messages.select(MessagingV2Messages.serverTimestamp)
        .where { condition }
        .orderBy(MessagingV2Messages.serverTimestamp to SortOrder.DESC)
        .limit(1)
        .firstOrNull()
        ?.get(MessagingV2Messages.serverTimestamp)
        ?: 0L
}


internal fun ConversationQueryRepository.effectiveTimer(chat: ResultRow): Int = DisappearingMessagePolicy.effectiveSeconds(
    isGroup = chat[Chats.isGroup],
    requestedSeconds = chat[Chats.disappearingMessageSeconds],
    isSecret = chat[Chats.chatType] == ChatType.SECRET,
)


internal fun ConversationQueryRepository.toUserResponse(row: ResultRow): UserResponse = UserResponse(
    id = row[Users.id],
    name = row[Users.name],
    email = "",
    avatar = row[Users.avatar],
    status = if (row[Users.showStatus]) row[Users.status] else "",
    isOnline = row[Users.showOnline] && row[Users.isOnline],
)
