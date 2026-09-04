package com.maodouchat.server.db

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager

/** 当前连接的数据库是否为 H2（测试常用）。H2 2.x 不支持 Exposed 单键 upsert 生成的 MERGE ... USING (VALUES)。 */
internal fun isH2Db(): Boolean =
    TransactionManager.current().db.vendor.contains("h2", ignoreCase = true)


fun initDatabase() {
    org.jetbrains.exposed.sql.transactions.transaction {
        createSchemaTables()
    }
}

/**
 * Creates missing application tables and columns without deleting data or applying data migrations.
 * Versioned changes belong in [com.maodouchat.server.db.migration.runDatabaseMigrations].
 */
internal fun createSchemaTables() {
    SchemaUtils.createMissingTablesAndColumns(
            Users, Chats, ChatParticipants, ChatUserSettings, GroupAuditLogs,
            EncryptedAttachments, SignalKeys, SignalDevices, SignalingMessages, Posts, PostImageClaims, PostLikes, PostComments, CommentLikes,
            BlockedUsers, UserLocations, AuthSessions, RefreshTokens, RevokedAccessTokens, StarMessages, PinnedMessages,
            NotificationPreferences,
            PushTokens, GroupPolls, GroupPollVotes, BotApps, BotCommandLogs, BotUpdateInbox, BotWebhookOutbox, Reports, ModerationAuditLog, AiAuditLogs, ModerationRules,
            RiskEvents, DirectChatPairs, SecretChatPairs, FriendRequests, Friendships, ChatFolders, ClientPrefs, SystemSettings,
            // 9.3xx：群邀请同意流程（成员入群前须本人接受）
            GroupInvitations,
            // 群玩法 B3：群签到 / 群接龙 / 群 PK（表定义见 PollTables.kt）
            GroupCheckins, GroupChains, GroupChainEntries, GroupPkRounds, GroupPkVotes,
            // B6 运维增强：用户标签先于公告建表（公告 target_tag_id 外键引用 user_tags.id）
            UserTags, UserTagAssignments, SystemAnnouncements, AnnouncementAcks, AuditExportRecords,
            RateLimitStatsSnapshots, DeviceEventSequences, DeviceEventConsistencyLog,
            // V2 messaging owns durable per-device delivery. WebSocket is notification only.
            MessagingV2Messages, MessagingV2Envelopes, ServiceMessages, ServiceMessageReactions
    )
}
