package com.maodouchat.server.service

import at.favre.lib.crypto.bcrypt.BCrypt
import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.AccountDeactivationResult
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.SecureRandom
import java.util.Base64

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
        // 大事务并发下可能死锁：回滚后整体重跑安全（deletedAt 守卫保证幂等），仅串行化失败重试。
        return com.maodouchat.server.db.withSerializationRetry {
            transaction {
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
            // 待处理的好友申请（双向）与群邀请：账号注销后永无接受者，残留会污染对方列表。
            FriendRequests.deleteWhere {
                (FriendRequests.fromUserId eq userId) or (FriendRequests.toUserId eq userId)
            }
            GroupInvitations.deleteWhere {
                (GroupInvitations.userId eq userId) or (GroupInvitations.inviterId eq userId)
            }
            // 有意保留：ModerationAuditLog/Reports/RiskEvents（审计追溯）、
            // ModerationRules/UserTags/SystemAnnouncements/PinnedMessages（系统/运营数据）、
            // RateLimitStatsSnapshots/AuditExportRecords（运维快照）。
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
    }

    private companion object {
        private const val DELETED_USER_NAME = "已注销用户"
        private val secureRandom = SecureRandom()
    }
}
