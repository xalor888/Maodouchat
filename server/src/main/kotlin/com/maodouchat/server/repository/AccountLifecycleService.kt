package com.maodouchat.server.repository

import at.favre.lib.crypto.bcrypt.BCrypt
import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** 账号注销编排（B02「拆 AccountLifecycleService」）：删除账号并清理全部关联数据。 */
class AccountLifecycleService {

    fun deleteAccount(userId: String, password: String): AccountDeactivationResult? =
        deactivateAccount(userId, authorized = { row ->
            BCrypt.verifyer().verify(password.toCharArray(), row[Users.passwordHash]).verified
        })

    fun adminDeactivateAccount(userId: String, actorId: String): AccountDeactivationResult? = deactivateAccount(
        userId = userId,
        authorized = { true },
        onDeactivated = { deletedAt ->
            ModerationAuditLog.insert {
                it[ModerationAuditLog.userId] = userId
                it[action] = "ADMIN_ACCOUNT_DEACTIVATED"
                it[detail] = "deletedAt=$deletedAt"
                it[ModerationAuditLog.actorId] = actorId
                it[createdAt] = deletedAt
            }
        }
    )

    private fun deactivateAccount(
        userId: String,
        authorized: (ResultRow) -> Boolean,
        onDeactivated: (Long) -> Unit = {}
    ): AccountDeactivationResult? {
        return transaction {
            val row = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
                ?: return@transaction null
            if (row[Users.deletedAt] != null) return@transaction null
            if (!authorized(row)) return@transaction null

            val now = System.currentTimeMillis()
            val avatarUrl = row[Users.avatar]
            val deletedEmail = "deleted_${userId}_${now}@deleted.maodouchat.local"
            val randomPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(48).also(secureRandom::nextBytes))
            val deletedHash = BCrypt.withDefaults().hashToString(12, randomPassword.toCharArray())

            // 与 BotRepository.delete 保持 owner -> bot -> chat 锁序。
            val orphanedBotAttachmentIds = removeOwnedBots(userId, now)
            // 再处理群主转让与退群；空会话级联删除时收集附件 ID，供调用方清磁盘。
            val orphanedAttachmentIds = (orphanedBotAttachmentIds + removeUserFromAllChats(userId, now)).distinct()

            BlockedUsers.deleteWhere { (BlockedUsers.blockerId eq userId) or (BlockedUsers.blockedId eq userId) }
            StarMessages.deleteWhere { StarMessages.userId eq userId }
            RefreshTokens.deleteWhere { RefreshTokens.userId eq userId }
            AuthSessions.deleteWhere { AuthSessions.userId eq userId }
            RevokedAccessTokens.deleteWhere { RevokedAccessTokens.userId eq userId }
            AiAuditLogs.deleteWhere { AiAuditLogs.userId eq userId }
            NotificationPreferences.deleteWhere { NotificationPreferences.userId eq userId }
            ClientPrefs.deleteWhere { ClientPrefs.userId eq userId }
            ChatFolders.deleteWhere { ChatFolders.userId eq userId }
            AnnouncementAcks.deleteWhere { AnnouncementAcks.userId eq userId }
            UserTagAssignments.deleteWhere { UserTagAssignments.userId eq userId }
            DeviceEventSequences.deleteWhere { DeviceEventSequences.userId eq userId }
            DeviceEventConsistencyLog.deleteWhere { DeviceEventConsistencyLog.userId eq userId }
            Friendships.deleteWhere {
                (Friendships.userLowId eq userId) or (Friendships.userHighId eq userId)
            }
            PushTokens.deleteWhere { PushTokens.userId eq userId }
            UserLocations.deleteWhere { UserLocations.userId eq userId }
            SignalKeys.deleteWhere { SignalKeys.userId eq userId }
            SignalDevices.deleteWhere { SignalDevices.userId eq userId }
            SignalingMessages.deleteWhere { (SignalingMessages.fromUserId eq userId) or (SignalingMessages.toUserId eq userId) }
            PostLikes.deleteWhere { PostLikes.userId eq userId }
            PostComments.deleteWhere { PostComments.authorId eq userId }
            CommentLikes.deleteWhere { CommentLikes.userId eq userId }
            val authoredPostIds = Posts.selectAll().where { Posts.authorId eq userId }.map { it[Posts.id] }
            if (authoredPostIds.isNotEmpty()) {
                PostLikes.deleteWhere { PostLikes.postId inList authoredPostIds }
                PostComments.deleteWhere { PostComments.postId inList authoredPostIds }
                PostImageClaims.deleteWhere { PostImageClaims.postId inList authoredPostIds }
            }
            Posts.deleteWhere { Posts.authorId eq userId }
            deletePollsCreatedBy(listOf(userId))
            deleteGroupPlayCreatedBy(listOf(userId))
            GroupPollVotes.deleteWhere { GroupPollVotes.userId eq userId }
            GroupCheckins.deleteWhere { GroupCheckins.userId eq userId }
            GroupChainEntries.deleteWhere { GroupChainEntries.userId eq userId }
            GroupPkVotes.deleteWhere { GroupPkVotes.userId eq userId }
            // 用户自己调用过的机器人命令日志也清除
            BotCommandLogs.deleteWhere { BotCommandLogs.userId eq userId }

            Users.update({ Users.id eq userId }) {
                it[Users.name] = DELETED_USER_NAME
                it[Users.email] = deletedEmail
                it[passwordHash] = deletedHash
                it[avatar] = null
                it[status] = "账号已注销"
                it[isOnline] = false
                it[showOnline] = false
                it[showStatus] = false
                it[searchable] = false
                it[defaultPostVisibility] = "PRIVATE"
                it[accessTokenVersion] = row[Users.accessTokenVersion] + 1
                it[deletedAt] = now
                it[lastSeen] = now
            }
            onDeactivated(now)
            AccountDeactivationResult(
                deletedAt = now,
                orphanedAttachmentIds = orphanedAttachmentIds,
                avatarUrl = avatarUrl
            )
        }
    }

    /** Must run after locking the owner Users row. */
    private fun removeOwnedBots(ownerUserId: String, now: Long): List<String> {
        val ownedBots = BotApps.selectAll()
            .where { BotApps.ownerUserId eq ownerUserId }
            .orderBy(BotApps.id to SortOrder.ASC)
            .forUpdate()
            .toList()
        if (ownedBots.isEmpty()) return emptyList()
        val botIds = ownedBots.map { it[BotApps.id] }
        val botIdSet = botIds.toSet()
        val chatIds = ChatParticipants.select(ChatParticipants.chatId)
            .where { ChatParticipants.userId inList botIds }
            .map { it[ChatParticipants.chatId] }
            .distinct()
            .sorted()
        val lockedChats: List<ResultRow> = if (chatIds.isEmpty()) emptyList() else Chats.selectAll()
            .where { Chats.id inList chatIds }
            .orderBy(Chats.id to SortOrder.ASC)
            .forUpdate()
            .toList()

        lockedChats.forEach { chat ->
            if (!chat[Chats.isGroup]) return@forEach
            val chatId = chat[Chats.id]
            val participants = ChatParticipants.selectAll()
                .where { ChatParticipants.chatId eq chatId }
                .toList()
            val deletingOwner = participants.any {
                it[ChatParticipants.userId] in botIdSet && it[ChatParticipants.role] == "OWNER"
            }
            if (deletingOwner) {
                val successor = participants
                    .filterNot { it[ChatParticipants.userId] in botIdSet }
                    .sortedWith(
                        compareByDescending<ResultRow> {
                            when (it[ChatParticipants.role]) {
                                "ADMIN" -> 2
                                "MEMBER" -> 1
                                else -> 0
                            }
                        }.thenBy { it[ChatParticipants.joinedAt] }
                            .thenBy { it[ChatParticipants.userId] }
                    )
                    .firstOrNull()
                if (successor != null) {
                    val successorId = successor[ChatParticipants.userId]
                    ChatParticipants.update({
                        (ChatParticipants.chatId eq chatId) and
                            (ChatParticipants.userId eq successorId)
                    }) { it[ChatParticipants.role] = "OWNER" }
                    GroupAuditLogs.insert {
                        it[GroupAuditLogs.id] = "gal_${UUID.randomUUID()}"
                        it[GroupAuditLogs.chatId] = chatId
                        it[GroupAuditLogs.actorId] = ownerUserId
                        it[GroupAuditLogs.action] = "OWNERSHIP_TRANSFERRED"
                        it[GroupAuditLogs.targetUserId] = successorId
                        it[GroupAuditLogs.createdAt] = now
                    }
                }
            }
        }

        lockedChats.forEach { chat ->
            botIds.forEach { botId ->
                deleteMessagingV2ParticipantStateInTx(chat[Chats.id], botId)
            }
        }
        ChatUserSettings.deleteWhere { ChatUserSettings.userId inList botIds }
        ChatParticipants.deleteWhere { ChatParticipants.userId inList botIds }
        val orphanedAttachmentIds = mutableListOf<String>()
        lockedChats.forEach { chat ->
            val chatId = chat[Chats.id]
            val remaining = ChatParticipants.select(ChatParticipants.userId)
                .where { ChatParticipants.chatId eq chatId }
                .limit(1)
                .any()
            if (!remaining) {
                orphanedAttachmentIds += tearDownEmptyChat(chatId)
            } else if (chat[Chats.isGroup]) {
                Chats.update({ Chats.id eq chatId }) {
                    it[Chats.memberRevision] = chat[Chats.memberRevision] + 1
                }
            } else {
                DirectChatPairs.deleteWhere { DirectChatPairs.chatId eq chatId }
                SecretChatPairs.deleteWhere { SecretChatPairs.chatId eq chatId }
            }
        }

        deletePollsCreatedBy(botIds)
        GroupPollVotes.deleteWhere { GroupPollVotes.userId inList botIds }
        StarMessages.deleteWhere { StarMessages.userId inList botIds }
        botIds.forEach { botId ->
            BotCommandLogs.deleteWhere {
                (BotCommandLogs.botId eq botId) or (BotCommandLogs.userId eq botId)
            }
        }
        BotUpdateInbox.deleteWhere { BotUpdateInbox.botId inList botIds }
        BotApps.deleteWhere {
            (BotApps.ownerUserId eq ownerUserId) and (BotApps.id inList botIds)
        }
        Users.update({ Users.id inList botIds }) {
            it[Users.name] = "deleted-bot"
            it[Users.status] = ""
            it[Users.avatar] = null
            it[Users.isOnline] = false
            it[Users.showOnline] = false
            it[Users.showStatus] = false
            it[Users.searchable] = false
            it[Users.deletedAt] = now
            it[Users.lastSeen] = 0L
        }
        return orphanedAttachmentIds
    }

    private fun deletePollsCreatedBy(creatorIds: List<String>) {
        if (creatorIds.isEmpty()) return
        val pollIds = GroupPolls.select(GroupPolls.id)
            .where { GroupPolls.creatorId inList creatorIds }
            .orderBy(GroupPolls.id to SortOrder.ASC)
            .forUpdate()
            .map { it[GroupPolls.id] }
        if (pollIds.isEmpty()) return
        GroupPollVotes.deleteWhere { GroupPollVotes.pollId inList pollIds }
        GroupPolls.deleteWhere { GroupPolls.id inList pollIds }
    }

    private fun deleteGroupPlayCreatedBy(creatorIds: List<String>) {
        if (creatorIds.isEmpty()) return
        val chainIds = GroupChains.select(GroupChains.id)
            .where { GroupChains.creatorId inList creatorIds }
            .map { it[GroupChains.id] }
        if (chainIds.isNotEmpty()) {
            GroupChainEntries.deleteWhere { GroupChainEntries.chainId inList chainIds }
            GroupChains.deleteWhere { GroupChains.id inList chainIds }
        }
        val pkIds = GroupPkRounds.select(GroupPkRounds.id)
            .where { GroupPkRounds.creatorId inList creatorIds }
            .map { it[GroupPkRounds.id] }
        if (pkIds.isNotEmpty()) {
            GroupPkVotes.deleteWhere { GroupPkVotes.pkId inList pkIds }
            GroupPkRounds.deleteWhere { GroupPkRounds.id inList pkIds }
        }
    }

    /**
     * On account deactivation: transfer group ownership when needed, remove membership,
     * and tear down empty chats. Returns attachment IDs removed with empty chats so callers
     * can delete disk objects; remaining uploader-owned attachments use deleteForUploader.
     */
    private fun removeUserFromAllChats(userId: String, now: Long): List<String> {
        val orphanedAttachmentIds = mutableListOf<String>()
        val memberships = ChatParticipants.selectAll()
            .where { ChatParticipants.userId eq userId }
            .orderBy(ChatParticipants.chatId to SortOrder.ASC)
            .map { it[ChatParticipants.chatId] to it[ChatParticipants.role] }
        if (memberships.isEmpty()) return orphanedAttachmentIds
        val chatIds = memberships.map { it.first }

        // 9.4xx 性能修复（N+1 → 2 次批量查询）：
        // 此前逐会话 SELECT chat + SELECT members（3N 次查询）；
        // 现一次锁全部 chat 行（保持 chat 先于 participants 的全局锁顺序），
        // 一次取全部会话的成员，内存中完成继任者计算，写入批量执行。
        val chatsById = Chats.selectAll()
            .where { Chats.id inList chatIds }
            .forUpdate()
            .associateBy { it[Chats.id] }
        val participantsByChat = ChatParticipants.selectAll()
            .where { ChatParticipants.chatId inList chatIds }
            .groupBy { it[ChatParticipants.chatId] }

        val successorUpdates = mutableListOf<Pair<String, String>>()      // chatId -> successorId
        val directPairCleanups = mutableListOf<String>()                  // 非空 1:1 chat
        val revisionBumps = mutableListOf<String>()                       // 非空群聊
        val auditRows = mutableListOf<Pair<String, Triple<String, String, String>>>() // chatId -> (action, actor, target)

        for ((chatId, role) in memberships) {
            val chat = chatsById[chatId] ?: continue
            deleteMessagingV2ParticipantStateInTx(chatId, userId)
            val others = participantsByChat[chatId].orEmpty()
                .filter { it[ChatParticipants.userId] != userId }
            if (chat[Chats.isGroup] && role == "OWNER" && others.isNotEmpty()) {
                val successor = others
                    .sortedWith(
                        compareByDescending<ResultRow> {
                            when (it[ChatParticipants.role]) {
                                "ADMIN" -> 2
                                "MEMBER" -> 1
                                else -> 0
                            }
                        }.thenBy { it[ChatParticipants.joinedAt] }
                    )
                    .first()
                val successorId = successor[ChatParticipants.userId]
                successorUpdates += chatId to successorId
                auditRows += chatId to Triple("OWNERSHIP_TRANSFERRED", userId, successorId)
            }
            if (others.isEmpty()) {
                orphanedAttachmentIds += tearDownEmptyChat(chatId)
            } else if (!chat[Chats.isGroup]) {
                // 1:1 注销后 pair 映射失效，避免对方 getOrCreate 回落到半空 chat
                directPairCleanups += chatId
            } else {
                revisionBumps += chatId
                auditRows += chatId to Triple("MEMBER_LEFT", userId, userId)
            }
        }

        // 批量写
        successorUpdates.forEach { (chatId, successorId) ->
            ChatParticipants.update({
                (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq successorId)
            }) { it[ChatParticipants.role] = "OWNER" }
        }
        ChatUserSettings.deleteWhere {
            (ChatUserSettings.chatId inList chatIds) and (ChatUserSettings.userId eq userId)
        }
        ChatParticipants.deleteWhere {
            (ChatParticipants.chatId inList chatIds) and (ChatParticipants.userId eq userId)
        }
        if (directPairCleanups.isNotEmpty()) {
            DirectChatPairs.deleteWhere { DirectChatPairs.chatId inList directPairCleanups }
            SecretChatPairs.deleteWhere { SecretChatPairs.chatId inList directPairCleanups }
        }
        revisionBumps.forEach { chatId ->
            val latest = chatsById[chatId]
            if (latest != null) {
                Chats.update({ Chats.id eq chatId }) {
                    it[Chats.memberRevision] = latest[Chats.memberRevision] + 1
                }
            }
        }
        auditRows.forEach { (chatId, triple) ->
            GroupAuditLogs.insert {
                it[GroupAuditLogs.id] = "gal_${UUID.randomUUID()}"
                it[GroupAuditLogs.chatId] = chatId
                it[GroupAuditLogs.action] = triple.first
                it[GroupAuditLogs.actorId] = triple.second
                it[GroupAuditLogs.targetUserId] = triple.third
                it[GroupAuditLogs.createdAt] = now
            }
        }
        return orphanedAttachmentIds
    }

    /** Deletes all chat-owned rows; returns attachment IDs that still need file cleanup. */
    private fun tearDownEmptyChat(chatId: String): List<String> {
        deleteMessagingV2ConversationInTx(chatId)
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
        // Keep account deletion aligned with ConversationStateDeletion's group-play cleanup set.
        // 残留（此前最后成员注销/清空群时留下群玩法孤儿行）
        GroupCheckins.deleteWhere { GroupCheckins.chatId eq chatId }
        val chainIds = GroupChains.select(GroupChains.id)
            .where { GroupChains.chatId eq chatId }
            .map { it[GroupChains.id] }
        if (chainIds.isNotEmpty()) {
            GroupChainEntries.deleteWhere { GroupChainEntries.chainId inList chainIds }
        }
        GroupChains.deleteWhere { GroupChains.chatId eq chatId }
        val pkIds = GroupPkRounds.select(GroupPkRounds.id)
            .where { GroupPkRounds.chatId eq chatId }
            .map { it[GroupPkRounds.id] }
        if (pkIds.isNotEmpty()) {
            GroupPkVotes.deleteWhere { GroupPkVotes.pkId inList pkIds }
        }
        GroupPkRounds.deleteWhere { GroupPkRounds.chatId eq chatId }
        BotCommandLogs.deleteWhere { BotCommandLogs.chatId eq chatId }
        // FK: direct_chat_pairs / secret_chat_pairs -> chats.id
        DirectChatPairs.deleteWhere { DirectChatPairs.chatId eq chatId }
        SecretChatPairs.deleteWhere { SecretChatPairs.chatId eq chatId }
        ChatUserSettings.deleteWhere { ChatUserSettings.chatId eq chatId }
        GroupAuditLogs.deleteWhere { GroupAuditLogs.chatId eq chatId }
        ChatParticipants.deleteWhere { ChatParticipants.chatId eq chatId }
        Chats.deleteWhere { Chats.id eq chatId }
        return attachmentIds
    }

    private companion object {
        private const val DELETED_USER_NAME = "已注销用户"
        private val secureRandom = SecureRandom()
    }
}
