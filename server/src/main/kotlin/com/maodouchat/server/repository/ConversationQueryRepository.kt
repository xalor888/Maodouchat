package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.ChatUserSettings
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
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Builds conversation read models without owning membership or mutation policy. */
class ConversationQueryRepository {
    fun getById(chatId: String, viewerId: String = ""): ChatResponse? = transaction {
        val chat = Chats.selectAll().where { Chats.id eq chatId }.firstOrNull()
            ?: return@transaction null
        val blocked = ConversationVisibility.blockedUserIdsInTx(viewerId)
        val participants = (ChatParticipants innerJoin Users)
            .selectAll()
            .where { ChatParticipants.chatId eq chatId }
            .filterNot { it[Users.id] in blocked }
            .map(::toUserResponse)

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
    fun getForParticipant(chatId: String, userId: String): ChatResponse? = transaction {
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

    fun listForUser(userId: String): List<ChatResponse> = transaction {
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
                    .map(::toUserResponse)
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

    fun findDirectBetween(userId1: String, userId2: String): ChatResponse? {
        val conversation = ConversationCreationRepository().findDirect(userId1, userId2) ?: return null
        return getById(conversation.id, userId1)
    }

    fun shareConversation(userId1: String, userId2: String): Boolean = transaction {
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

    private fun latestMessageTimesInTx(chatIds: List<String>, blocked: Set<String>): Map<String, Long> {
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

    private fun latestMessageTimeInTx(chatId: String, blocked: Set<String>): Long {
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

    private fun effectiveTimer(chat: ResultRow): Int = DisappearingMessagePolicy.effectiveSeconds(
        isGroup = chat[Chats.isGroup],
        requestedSeconds = chat[Chats.disappearingMessageSeconds],
        isSecret = chat[Chats.chatType] == ChatType.SECRET,
    )

    private fun toUserResponse(row: ResultRow): UserResponse = UserResponse(
        id = row[Users.id],
        name = row[Users.name],
        email = "",
        avatar = row[Users.avatar],
        status = if (row[Users.showStatus]) row[Users.status] else "",
        isOnline = row[Users.showOnline] && row[Users.isOnline],
    )
}
