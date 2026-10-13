package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** 群成员面向资料：头衔与个人群昵称。 */

fun GroupProfileRepository.updateMemberTitle(
    chatId: String,
    actorId: String,
    targetUserId: String,
    title: String?,
): GroupMemberMutationResult = transaction {
    val access = GroupMutationTransaction.lockForAdmin(chatId, actorId)
    if (access is GroupAdminAccess.Denied) return@transaction access.result
    val targetExists = ChatParticipants.selectAll()
        .where {
            (ChatParticipants.chatId eq chatId) and
                (ChatParticipants.userId eq targetUserId)
        }
        .any()
    if (!targetExists) return@transaction GroupMemberMutationResult.TARGET_NOT_PARTICIPANT
    val updated = ChatParticipants.update({
        (ChatParticipants.chatId eq chatId) and
            (ChatParticipants.userId eq targetUserId)
    }) {
        it[ChatParticipants.title] = title
    }
    if (updated != 1) return@transaction GroupMemberMutationResult.TARGET_NOT_PARTICIPANT
    GroupMutationTransaction.bumpRevision(chatId, (access as GroupAdminAccess.Allowed).chat[Chats.memberRevision])
    GroupMutationTransaction.insertAudit(chatId, actorId, "TITLE_UPDATED", targetUserId)
    GroupMemberMutationResult.UPDATED
}

fun GroupProfileRepository.updateOwnNickname(
    chatId: String,
    userId: String,
    nickname: String?,
): GroupMemberMutationResult = transaction {
    val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
        ?: return@transaction GroupMemberMutationResult.CHAT_NOT_FOUND
    if (!chat[Chats.isGroup]) return@transaction GroupMemberMutationResult.NOT_GROUP
    val updated = ChatParticipants.update({
        (ChatParticipants.chatId eq chatId) and
            (ChatParticipants.userId eq userId)
    }) {
        it[ChatParticipants.groupNickname] = nickname
    }
    if (updated != 1) return@transaction GroupMemberMutationResult.ACTOR_NOT_PARTICIPANT
    GroupMutationTransaction.bumpRevision(chatId, chat[Chats.memberRevision])
    GroupMutationTransaction.insertAudit(chatId, userId, "NICKNAME_UPDATED", userId)
    GroupMemberMutationResult.UPDATED
}
