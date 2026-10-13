package com.maodouchat.server.repository

// 群成员加入簇：加人、加自有 bot（原 GroupMembershipChanges 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.lockUsersInTx
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction

fun GroupMembershipRepository.addMembers(
    chatId: String,
    actorId: String,
    requestedUserIds: List<String>,
    maxMembers: Int,
    requireBotDeliverable: Boolean = false,
): AddGroupMembersResult = transaction {
    if (requireBotDeliverable && !BotRepository.isBotDeliverableInTx(actorId, System.currentTimeMillis())) {
        return@transaction AddGroupMembersResult(GroupMemberMutationResult.FORBIDDEN)
    }
    val requestedIds = requestedUserIds.distinct()
    val requestedUsers = lockUsersInTx(requestedIds)
    val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
        ?: return@transaction AddGroupMembersResult(GroupMemberMutationResult.CHAT_NOT_FOUND)
    if (!chat[Chats.isGroup]) return@transaction AddGroupMembersResult(GroupMemberMutationResult.NOT_GROUP)
    val participants = ChatParticipants.selectAll().where { ChatParticipants.chatId eq chatId }.toList()
    val actor = participants.firstOrNull { it[ChatParticipants.userId] == actorId }
        ?: return@transaction AddGroupMembersResult(GroupMemberMutationResult.ACTOR_NOT_PARTICIPANT)
    if (actor[ChatParticipants.role] !in ADMIN_ROLES) {
        return@transaction AddGroupMembersResult(GroupMemberMutationResult.FORBIDDEN)
    }
    val existingIds = participants.mapTo(linkedSetOf()) { it[ChatParticipants.userId] }
    val addedIds = requestedIds.filterNot(existingIds::contains)
    val boundedLimit = maxMembers.coerceAtLeast(0)
    if (existingIds.size > boundedLimit || addedIds.size > boundedLimit - existingIds.size) {
        return@transaction AddGroupMembersResult(GroupMemberMutationResult.MEMBER_LIMIT_EXCEEDED)
    }
    val addedSet = addedIds.toSet()
    val activeIds = requestedUsers
        .filter { it[Users.id] in addedSet && it[Users.deletedAt] == null }
        .mapTo(hashSetOf()) { it[Users.id] }
    val missing = addedIds.firstOrNull { it !in activeIds }
    if (missing != null) {
        return@transaction AddGroupMembersResult(
            GroupMemberMutationResult.USER_NOT_FOUND,
            missingUserId = missing,
        )
    }
    val blockedId = if (existingIds.isEmpty() || addedIds.isEmpty()) null else {
        val involved = existingIds + addedIds
        val pairs = BlockedUsers.select(BlockedUsers.blockerId, BlockedUsers.blockedId)
            .where {
                (BlockedUsers.blockerId inList involved) and
                    (BlockedUsers.blockedId inList involved)
            }
            .mapTo(hashSetOf()) { it[BlockedUsers.blockerId] to it[BlockedUsers.blockedId] }
        addedIds.firstOrNull { candidate ->
            existingIds.any { memberId ->
                (memberId to candidate) in pairs || (candidate to memberId) in pairs
            }
        }
    }
    if (blockedId != null) {
        return@transaction AddGroupMembersResult(
            GroupMemberMutationResult.BLOCKED,
            blockedUserId = blockedId,
        )
    }
    val now = System.currentTimeMillis()
    addedIds.forEach { userId ->
        ChatParticipants.insert {
            it[ChatParticipants.chatId] = chatId
            it[ChatParticipants.userId] = userId
            it[role] = "MEMBER"
            it[joinedAt] = now
        }
        insertAuditInTx(chatId, actorId, "MEMBER_ADDED", userId)
    }
    if (addedIds.isNotEmpty()) {
        Chats.update({ Chats.id eq chatId }) {
            it[memberRevision] = chat[Chats.memberRevision] + 1
        }
    }
    AddGroupMembersResult(GroupMemberMutationResult.UPDATED, addedUserIds = addedIds)
}


fun GroupMembershipRepository.addOwnedBot(
    chatId: String,
    actorId: String,
    botId: String,
    maxMembers: Int,
): AddOwnedBotResult = transaction {
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
        it[joinedAt] = System.currentTimeMillis()
        it[role] = ROLE_ADMIN
    }
    Chats.update({ Chats.id eq chatId }) {
        it[memberRevision] = chat[Chats.memberRevision] + 1
    }
    insertAuditInTx(chatId, actorId, "BOT_ADDED", botId)
    AddOwnedBotResult.ADDED
}
