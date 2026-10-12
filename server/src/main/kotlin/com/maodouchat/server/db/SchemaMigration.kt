package com.maodouchat.server.db



/** Applies the historical non-destructive and corrective changes captured by migration version 1. */
internal fun applyBaselineSchemaMigration() {
    createSchemaTables()
    dropMessagingV2SenderUserForeignKey()
    migrateMessagingV2RecordClasses()
    migrateMessageControlForeignKeys()
    widenClientPrefsWritingStyleColumn()
    widenFriendRequestMessageColumn()
    // 9.144：既有库加宽（新增实例由 Table 定义直接建宽列）
    widenReportsColumns()
    widenRiskEventsMatchedColumn()
    widenModerationAuditDetailColumn()
    // 确保 init {} 中的索引被创建（createMissingTablesAndColumns 可能不会自动建）
    ensureIndexes()
    // 9.4xx：PostgreSQL 全文/模糊搜索索引（pg_trgm；H2 与受限环境自动跳过）
    ensureSearchIndexes()
    migrateAuthSessionState()
    backfillMemberRoles()
    backfillChatTypes()
    backfillModeratorEmails()
    backfillModerationRules()
}
