package com.maodouchat.server.repository

// 会话列表簇（用户会话列表 + 最新消息时间聚合）：原 ConversationQueryRepository 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.ChatUserSettings
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.Users
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.model.ChatResponse
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun ConversationQueryRepository.listForUser(userId: String): List<ChatResponse> = transaction {
    val chatIds = ChatParticipants.select(ChatParticipants.chatId)
        .where { ChatParticipants.userId eq userId }
        .map { it[ChatParticipants.chatId] }
        .distinct()
    if (chatIds.isEmpty()) return@transaction emptyList()

    val chats = Chats.selectAll()
        .where { Chats.id inList chatIds }
        .associateBy { it[Chats.id] }
    val participantIdsByChat = ChatParticipants.selectAll()
        .where { ChatParticipants.chatId inList chatIds }
        .groupBy { it[ChatParticipants.chatId] }
        .mapValues { (_, rows) -> rows.map { it[ChatParticipants.userId] } }
    val allParticipantIds = participantIdsByChat.values.flatten().distinct()
    val usersById = if (allParticipantIds.isEmpty()) {
        emptyMap()
    } else {
        Users.selectAll().where { Users.id inList allParticipantIds }.associateBy { it[Users.id] }
    }
    val blocked = ConversationVisibility.blockedUserIdsInTx(userId)
    val latestByChat = latestMessageTimesInTx(chatIds, blocked)
    val settingsByChat = ChatUserSettings.selectAll()
        .where {
            (ChatUserSettings.userId eq userId) and
                (ChatUserSettings.chatId inList chatIds)
        }
        .associateBy { it[ChatUserSettings.chatId] }

    chatIds.mapNotNull { chatId ->
        val chat = chats[chatId] ?: return@mapNotNull null
        val settings = settingsByChat[chatId]
        ChatResponse(
            id = chatId,
            participants = participantIdsByChat[chatId].orEmpty()
                .asSequence()
                .filterNot { it in blocked }
                .mapNotNull(usersById::get)
                .map { toUserResponse(it) }
                .toList(),
            lastMessage = "",
            lastMessageType = "TEXT",
            lastMessageTime = latestByChat[chatId] ?: 0L,
            unreadCount = 0,
            isGroup = chat[Chats.isGroup],
            chatType = chat[Chats.chatType],
            groupName = chat[Chats.groupName],
            groupAnnouncement = chat[Chats.groupAnnouncement],
            groupAvatar = chat[Chats.groupAvatar],
            memberRevision = chat[Chats.memberRevision],
            pinnedAt = settings?.get(ChatUserSettings.pinnedAt) ?: 0L,
            notificationsMuted = settings?.get(ChatUserSettings.notificationsMuted) ?: false,
            archived = settings?.get(ChatUserSettings.archived) ?: false,
            markedUnread = settings?.get(ChatUserSettings.markedUnread) ?: false,
            settingsUpdatedAt = settings?.get(ChatUserSettings.updatedAt) ?: 0L,
            disappearingMessageSeconds = effectiveTimer(chat),
        )
    }.sortedByDescending(ChatResponse::lastMessageTime)
}


private fun ConversationQueryRepository.latestMessageTimesInTx(chatIds: List<String>, blocked: Set<String>): Map<String, Long> {
    val latestTime = MessagingV2Messages.serverTimestamp.max()
    var condition = (MessagingV2Messages.conversationId inList chatIds) and
        (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE)
    if (blocked.isNotEmpty()) {
        condition = condition and (MessagingV2Messages.senderUserId notInList blocked.toList())
    }
    return MessagingV2Messages
        .select(MessagingV2Messages.conversationId, latestTime)
        .where { condition }
        .groupBy(MessagingV2Messages.conversationId)
        .associate { it[MessagingV2Messages.conversationId] to (it[latestTime] ?: 0L) }
}
