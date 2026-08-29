package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** Owns group identity and member-facing profile mutations. */
class GroupProfileRepository {
    fun updateMemberTitle(
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
        bumpRevision(chatId, (access as GroupAdminAccess.Allowed).chat[Chats.memberRevision])
        GroupMutationTransaction.insertAudit(chatId, actorId, "TITLE_UPDATED", targetUserId)
        GroupMemberMutationResult.UPDATED
    }

    fun updateName(
        chatId: String,
        actorId: String,
        name: String,
        requireBotDeliverable: Boolean = false,
    ): GroupMemberMutationResult = updateAdminField(
        chatId = chatId,
        actorId = actorId,
        requireBotDeliverable = requireBotDeliverable,
        auditAction = "GROUP_RENAMED",
    ) {
        it[Chats.groupName] = name
    }

    fun updateAnnouncement(
        chatId: String,
        actorId: String,
        announcement: String?,
        requireBotDeliverable: Boolean = false,
    ): GroupMemberMutationResult = updateAdminField(
        chatId = chatId,
        actorId = actorId,
        requireBotDeliverable = requireBotDeliverable,
        auditAction = "ANNOUNCEMENT_UPDATED",
    ) {
        it[Chats.groupAnnouncement] = announcement
    }

    fun updateAvatar(
        chatId: String,
        actorId: String,
        avatarUrl: String,
        requireBotDeliverable: Boolean = false,
    ): GroupAvatarMutationResult = mutateAvatar(
        chatId = chatId,
        actorId = actorId,
        requireBotDeliverable = requireBotDeliverable,
        auditAction = "AVATAR_UPDATED",
        avatarUrl = avatarUrl,
    )

    fun clearAvatar(
        chatId: String,
        actorId: String,
        requireBotDeliverable: Boolean = false,
    ): GroupAvatarMutationResult = mutateAvatar(
        chatId = chatId,
        actorId = actorId,
        requireBotDeliverable = requireBotDeliverable,
        auditAction = "AVATAR_CLEARED",
        avatarUrl = null,
    )

    fun updateOwnNickname(
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
        bumpRevision(chatId, chat[Chats.memberRevision])
        GroupMutationTransaction.insertAudit(chatId, userId, "NICKNAME_UPDATED", userId)
        GroupMemberMutationResult.UPDATED
    }

    private fun updateAdminField(
        chatId: String,
        actorId: String,
        requireBotDeliverable: Boolean,
        auditAction: String,
        mutate: (UpdateBuilder<Int>) -> Unit,
    ): GroupMemberMutationResult = transaction {
        if (requireBotDeliverable && !BotRepository.isBotDeliverableInTx(actorId, System.currentTimeMillis())) {
            return@transaction GroupMemberMutationResult.FORBIDDEN
        }
        val access = GroupMutationTransaction.lockForAdmin(chatId, actorId)
        if (access is GroupAdminAccess.Denied) return@transaction access.result
        val chat = (access as GroupAdminAccess.Allowed).chat
        Chats.update({ Chats.id eq chatId }) { statement ->
            mutate(statement)
            statement[Chats.memberRevision] = chat[Chats.memberRevision] + 1
        }
        GroupMutationTransaction.insertAudit(chatId, actorId, auditAction)
        GroupMemberMutationResult.UPDATED
    }

    private fun mutateAvatar(
        chatId: String,
        actorId: String,
        requireBotDeliverable: Boolean,
        auditAction: String,
        avatarUrl: String?,
    ): GroupAvatarMutationResult = transaction {
        if (requireBotDeliverable && !BotRepository.isBotDeliverableInTx(actorId, System.currentTimeMillis())) {
            return@transaction GroupAvatarMutationResult(GroupMemberMutationResult.FORBIDDEN)
        }
        val access = GroupMutationTransaction.lockForAdmin(chatId, actorId)
        if (access is GroupAdminAccess.Denied) {
            return@transaction GroupAvatarMutationResult(access.result)
        }
        val chat = (access as GroupAdminAccess.Allowed).chat
        val previous = chat[Chats.groupAvatar]
        Chats.update({ Chats.id eq chatId }) {
            it[Chats.groupAvatar] = avatarUrl
            it[Chats.memberRevision] = chat[Chats.memberRevision] + 1
        }
        GroupMutationTransaction.insertAudit(chatId, actorId, auditAction)
        GroupAvatarMutationResult(GroupMemberMutationResult.UPDATED, previous)
    }

    private fun bumpRevision(chatId: String, previousRevision: Long) {
        Chats.update({ Chats.id eq chatId }) {
            it[Chats.memberRevision] = previousRevision + 1
        }
    }
}
