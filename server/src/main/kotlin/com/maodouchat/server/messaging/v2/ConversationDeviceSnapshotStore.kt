package com.maodouchat.server.messaging.v2

import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.BlockedUsers
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.repository.DeviceRegistry
import com.maodouchat.server.repository.EncryptableDeviceDirectory
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * B06：会话设备快照子域。确认可加密设备集合经只读 [EncryptableDeviceDirectory]，
 * 会话快照与拉黑过滤是 admission 与 mailbox 共享的只读边界。
 */
class ConversationDeviceSnapshotStore(
    private val deviceDirectory: EncryptableDeviceDirectory = DeviceRegistry(),
) {

    fun resolveAuthenticatedDevice(userId: String, authSessionId: String): Int? = transaction {
        AuthSessions
            .select(AuthSessions.signalDeviceId)
            .where {
                (AuthSessions.id eq authSessionId) and
                    (AuthSessions.userId eq userId) and
                    AuthSessions.revokedAt.isNull()
            }
            .firstOrNull()
            ?.get(AuthSessions.signalDeviceId)
            ?.takeIf { deviceId ->
                deviceDirectory.isDeviceConfirmed(userId, deviceId)
            }
    }

    fun conversationSnapshot(
        conversationId: String,
        requesterUserId: String,
        requesterDeviceId: Int,
    ): ConversationSnapshotV2Response = transaction {
        val chat = Chats.selectAll().where { Chats.id eq conversationId }.firstOrNull()
            ?: throw MessagingV2ConversationNotFoundException()
        val participantIds = ChatParticipants
            .select(ChatParticipants.userId)
            .where { ChatParticipants.chatId eq conversationId }
            .map { it[ChatParticipants.userId] }
            .sorted()
        if (requesterUserId !in participantIds) throw MessagingV2NotParticipantException()
        val blockedPeerIds = blockedPeerIds(requesterUserId, participantIds)
        if (!chat[Chats.isGroup] && blockedPeerIds.isNotEmpty()) {
            throw MessagingV2BlockedConversationException()
        }
        val targets = confirmedEncryptableDeviceTargets(participantIds).map { target ->
            target.takeUnless {
                (it.userId == requesterUserId && it.deviceId == requesterDeviceId) ||
                    it.userId in blockedPeerIds
            }
        }.filterNotNull().sortedWith(compareBy({ it.userId }, { it.deviceId }))
        ConversationSnapshotV2Response(
            conversationId = conversationId,
            isGroup = chat[Chats.isGroup],
            memberRevision = chat[Chats.memberRevision],
            participantUserIds = participantIds,
            targets = targets.map { ConversationDeviceTargetV2(it.userId, it.deviceId) },
        )
    }

    internal fun confirmedEncryptableDeviceTargets(userIds: Collection<String>): MutableSet<DeviceTarget> {
        val ids = userIds.filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return linkedSetOf()
        return deviceDirectory.getConfirmedDeviceTargets(ids)
            .mapTo(linkedSetOf()) { (userId, deviceId) -> DeviceTarget(userId, deviceId) }
    }

    internal fun blockedPeerIds(senderUserId: String, participantIds: List<String>): Set<String> {
        val peers = participantIds.filterNot { it == senderUserId }
        if (peers.isEmpty()) return emptySet()
        return BlockedUsers.selectAll().where {
            ((BlockedUsers.blockerId eq senderUserId) and (BlockedUsers.blockedId inList peers)) or
                ((BlockedUsers.blockedId eq senderUserId) and (BlockedUsers.blockerId inList peers))
        }.mapTo(linkedSetOf()) { row ->
            if (row[BlockedUsers.blockerId] == senderUserId) {
                row[BlockedUsers.blockedId]
            } else {
                row[BlockedUsers.blockerId]
            }
        }
    }
}
