package com.maodouchat.server.db

import org.jetbrains.exposed.sql.transactions.TransactionManager



internal fun ensureIndexes() {
    val indexes = listOf(
        "CREATE INDEX IF NOT EXISTS idx_direct_chat_pairs_chat ON direct_chat_pairs(chat_id)",
        "CREATE INDEX IF NOT EXISTS idx_attachments_chat ON encrypted_attachments(chat_id)",
        "CREATE INDEX IF NOT EXISTS idx_attachments_uploader_status ON encrypted_attachments(uploader_id, status)",
        "CREATE INDEX IF NOT EXISTS idx_attachments_message ON encrypted_attachments(message_id)",
        "CREATE INDEX IF NOT EXISTS idx_attachments_expires ON encrypted_attachments(expires_at)",
        "CREATE INDEX IF NOT EXISTS idx_posts_created_at ON posts(created_at)",
        "CREATE INDEX IF NOT EXISTS idx_posts_author_created ON posts(author_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_post_likes_post_id ON post_likes(post_id)",
        // 9.155：点赞者列表按 (post_id, created_at DESC, user_id DESC) 键集分页——复合覆盖索引
        "CREATE INDEX IF NOT EXISTS idx_post_likes_post_created_user ON post_likes(post_id, created_at, user_id)",
        "CREATE INDEX IF NOT EXISTS idx_post_comments_post_created ON post_comments(post_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_friend_requests_to_status ON friend_requests(to_user_id, status)",
        "CREATE INDEX IF NOT EXISTS idx_friend_requests_from_status ON friend_requests(from_user_id, status)",
        "CREATE INDEX IF NOT EXISTS idx_friend_requests_pair ON friend_requests(from_user_id, to_user_id)",
        "CREATE INDEX IF NOT EXISTS idx_friendships_high ON friendships(user_high_id)",
        "CREATE INDEX IF NOT EXISTS idx_chat_folders_user_sort ON chat_folders(user_id, sort_order)",
        "CREATE INDEX IF NOT EXISTS idx_group_polls_chat_created ON group_polls(chat_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_group_poll_votes_user ON group_poll_votes(user_id, voted_at)",
        "CREATE INDEX IF NOT EXISTS idx_bot_apps_owner_created ON bot_apps(owner_user_id, created_at)",
        "CREATE UNIQUE INDEX IF NOT EXISTS uidx_bot_apps_token_hash ON bot_apps(token_hash)",
        "CREATE INDEX IF NOT EXISTS idx_bot_command_logs_bot_created ON bot_command_logs(bot_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_bot_update_inbox_bot_id ON bot_update_inbox(bot_id, id)",
        "CREATE INDEX IF NOT EXISTS idx_signal_keys_user_device_type ON signal_keys(user_id, device_id, key_type)",
        "CREATE INDEX IF NOT EXISTS idx_signal_devices_user ON signal_devices(user_id)",
        "CREATE INDEX IF NOT EXISTS idx_signal_devices_user_status ON signal_devices(user_id, status)",
        "CREATE INDEX IF NOT EXISTS idx_messaging_v2_messages_conversation_time ON messaging_v2_messages(conversation_id, server_timestamp)",
        "CREATE INDEX IF NOT EXISTS idx_messaging_v2_messages_sender_time ON messaging_v2_messages(sender_user_id, server_timestamp)",
        "CREATE UNIQUE INDEX IF NOT EXISTS uidx_messaging_v2_envelope_target ON messaging_v2_envelopes(message_id, recipient_user_id, recipient_device_id)",
        "CREATE INDEX IF NOT EXISTS idx_messaging_v2_inbox_pending ON messaging_v2_envelopes(recipient_user_id, recipient_device_id, acknowledged_at, sequence)",
        "CREATE INDEX IF NOT EXISTS idx_messaging_v2_envelope_message ON messaging_v2_envelopes(message_id)",
        "CREATE INDEX IF NOT EXISTS idx_service_messages_chat_time ON service_messages(chat_id, timestamp)",
        "CREATE INDEX IF NOT EXISTS idx_service_messages_sender_time ON service_messages(sender_id, timestamp)",
        "CREATE INDEX IF NOT EXISTS idx_service_reactions_message ON service_message_reactions(message_id)",
        "CREATE INDEX IF NOT EXISTS idx_signaling_to_user_ts ON signaling_messages(to_user_id, timestamp)",
        // Bug #26: chat_participants 按 userId 查询的索引
        "CREATE INDEX IF NOT EXISTS idx_chat_participants_user_id ON chat_participants(user_id)",
        "CREATE INDEX IF NOT EXISTS idx_user_locations_visible_expires ON user_locations(visible, expires_at)",
        "CREATE INDEX IF NOT EXISTS idx_auth_sessions_user_device ON auth_sessions(user_id, signal_device_id)",
        "CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id ON refresh_tokens(user_id)",
        "CREATE INDEX IF NOT EXISTS idx_refresh_tokens_session_id ON refresh_tokens(session_id)",
        "CREATE INDEX IF NOT EXISTS idx_revoked_access_tokens_user_id ON revoked_access_tokens(user_id)",
        "CREATE INDEX IF NOT EXISTS idx_revoked_access_tokens_expires_at ON revoked_access_tokens(expires_at)",
        "CREATE INDEX IF NOT EXISTS idx_pinned_messages_chat_pinned_at ON pinned_messages(chat_id, pinned_at)",
        "CREATE INDEX IF NOT EXISTS idx_pinned_messages_message_id ON pinned_messages(message_id)",
        "CREATE INDEX IF NOT EXISTS idx_push_tokens_user_id ON push_tokens(user_id)",
        "CREATE INDEX IF NOT EXISTS idx_push_tokens_auth_session ON push_tokens(auth_session_id)",
        "CREATE INDEX IF NOT EXISTS idx_ai_audit_user_created ON ai_audit_logs(user_id, created_at)",

        "CREATE INDEX IF NOT EXISTS idx_reports_reporter_created ON reports(reporter_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_reports_status_created ON reports(status, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_reports_target ON reports(target_type, target_id)",
        "CREATE INDEX IF NOT EXISTS idx_moderation_rules_enabled_priority ON moderation_rules(enabled, priority)",
        "CREATE INDEX IF NOT EXISTS idx_risk_events_user_created ON risk_events(user_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_risk_events_needs_review ON risk_events(needs_review, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_risk_events_rule_created ON risk_events(rule_id, created_at)",
        // B6 运维增强：公告 / 用户标签 / 审计导出 / 限流统计 / 设备一致性索引
        "CREATE INDEX IF NOT EXISTS idx_announcements_status_window ON system_announcements(status, starts_at, expires_at)",
        "CREATE INDEX IF NOT EXISTS idx_announcements_created_at ON system_announcements(created_at)",
        "CREATE INDEX IF NOT EXISTS idx_user_tag_assignments_user ON user_tag_assignments(user_id)",
        "CREATE INDEX IF NOT EXISTS idx_audit_export_actor_created ON audit_export_records(actor_id, requested_at)",
        "CREATE UNIQUE INDEX IF NOT EXISTS uidx_rate_limit_stats_bucket ON rate_limit_stats_snapshots(bucket_start_ms)",
        "CREATE INDEX IF NOT EXISTS idx_rate_limit_stats_bucket_start ON rate_limit_stats_snapshots(bucket_start_ms)",
        "CREATE INDEX IF NOT EXISTS idx_device_event_log_user_ts ON device_event_consistency_log(user_id, last_seen_at)",
        "CREATE INDEX IF NOT EXISTS idx_device_event_log_status ON device_event_consistency_log(status, last_seen_at)",
        // 8.48 修复：blocked_users.blocked_id 单列索引——此前仅在 Table.init{} 声明，
        // 但 ensureIndexes 是权威创建路径（createMissingTablesAndColumns 对已存在表不建索引），
        // 未加入列表则对已部署库永不生效（双向拉黑过滤每次读消息全表扫）
        "CREATE INDEX IF NOT EXISTS idx_blocked_users_blocked_id ON blocked_users(blocked_id)",
        // 8.30 调优：保留期清理/周期任务的时间列独立索引（8.29 性能扫描 F1/F2/F3/F5），
        // 消除全表扫描 DELETE/UPDATE：
        // - deleteExpired（15 分钟循环）按 refresh_tokens.expires_at 两次全扫
        // - purgeStaleInTx（每次 store/poll）按 signaling_messages.timestamp 全扫
        // - 全部周期清理按各自时间列
        "CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires_at ON refresh_tokens(expires_at)",
        // timestamp 是 SQL 保留字（Exposed 存小写带引号）；signal_keys 的 key_type 列
        // 同样为小写带引号存储（历史建表方式），两者原生 DDL 必须加引号，其余列大写裸写。
        "CREATE INDEX IF NOT EXISTS idx_signaling_ts ON signaling_messages(\"timestamp\")",
        "CREATE INDEX IF NOT EXISTS idx_group_audit_created_at ON group_audit_logs(created_at)",
        "CREATE INDEX IF NOT EXISTS idx_bot_update_inbox_created_at ON bot_update_inbox(created_at)",
        "CREATE INDEX IF NOT EXISTS idx_bot_command_logs_created_at ON bot_command_logs(created_at)",
        "CREATE INDEX IF NOT EXISTS idx_ai_audit_created_at ON ai_audit_logs(created_at)",
        "CREATE INDEX IF NOT EXISTS idx_risk_events_created_at ON risk_events(created_at)",
        "CREATE INDEX IF NOT EXISTS idx_signal_keys_type_created ON signal_keys(\"key_type\", created_at)",
        "CREATE INDEX IF NOT EXISTS idx_group_checkins_checked_at ON group_checkins(checked_at)",
        "CREATE INDEX IF NOT EXISTS idx_group_chains_created_at ON group_chains(created_at)",
        "CREATE INDEX IF NOT EXISTS idx_group_pk_rounds_created_at ON group_pk_rounds(created_at)",
        "CREATE INDEX IF NOT EXISTS idx_user_locations_expires_at ON user_locations(expires_at)",
        "CREATE INDEX IF NOT EXISTS idx_chat_user_settings_user_archive ON chat_user_settings(user_id, archived)",
        "CREATE INDEX IF NOT EXISTS idx_group_audit_chat_created ON group_audit_logs(chat_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_signaling_call ON signaling_messages(call_id, from_user_id, to_user_id)",
        "CREATE INDEX IF NOT EXISTS idx_signaling_group_call ON signaling_messages(group_id, call_id)",
        // B3 群玩法表索引：虽为新表（建表时 init 会建），补入列表保证 Table.init 与 ensureIndexes
        // 完全对齐（IF NOT EXISTS 无害，防任何建表路径差异）
        "CREATE INDEX IF NOT EXISTS idx_group_checkins_chat_date ON group_checkins(chat_id, checkin_date)",
        "CREATE INDEX IF NOT EXISTS idx_group_checkins_user ON group_checkins(chat_id, user_id)",
        "CREATE INDEX IF NOT EXISTS idx_group_chains_chat_created ON group_chains(chat_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_group_chain_entries_chain_seq ON group_chain_entries(chain_id, sequence)",
        "CREATE INDEX IF NOT EXISTS idx_group_chain_entries_chain_user ON group_chain_entries(chain_id, user_id)",
        "CREATE INDEX IF NOT EXISTS idx_group_pk_chat_created ON group_pk_rounds(chat_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_group_pk_votes_pk ON group_pk_votes(pk_id)",
        // 8.48 修复 L15：管理仪表盘趋势/活跃用户范围扫描（Users.last_seen、Reports.created_at）
        "CREATE INDEX IF NOT EXISTS idx_users_last_seen ON users(last_seen)",
        "CREATE INDEX IF NOT EXISTS idx_reports_created_at ON reports(created_at)",
    )
    for (sql in indexes) {
        TransactionManager.current().exec(sql)
    }
    // 部分唯一索引（8.27 调优）：PostgreSQL 支持 WHERE 子句并在部分行上强制唯一，
    // 兜底并发绕过应用层去重；H2 不支持该语法，测试环境跳过（应用层冲突捕获仍有）。
    val isPostgres = TransactionManager.current().db.dialect is org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
    if (isPostgres) {
        val partialUniqueIndexes = listOf(
            // 同一举报人对同一目标的 OPEN 举报唯一 —— 防并发提交绕过 24h 去重制造重复审核负载
            "CREATE UNIQUE INDEX IF NOT EXISTS uidx_reports_open_dedup ON reports(reporter_id, target_type, target_id) WHERE status = 'OPEN'",
            // 好友 PENDING 申请唯一：正常路径有用户对行锁串行化，这里兜底未来不经锁的插入路径
            "CREATE UNIQUE INDEX IF NOT EXISTS uidx_friend_requests_pending ON friend_requests(from_user_id, to_user_id) WHERE status = 'PENDING'",
        )
        for (sql in partialUniqueIndexes) {
            TransactionManager.current().exec(sql)
        }
    }
}



/** 9.4xx：PostgreSQL 模糊搜索索引。pg_trgm GIN 加速 %pattern% 形式的 LIKE（普通 B-tree 无法利用前导通配符）。 */
internal fun ensureSearchIndexes() {
    if (isH2Db()) return
    // PostgreSQL 语义陷阱（M4 用真 PG 跑迁移矩阵时抓到）：
    // 一条语句失败会让**整个事务**进入 aborted 状态，此后任何语句都报
    // "current transaction is aborted"。也就是说「只 catch 异常并吞掉」并不能降级——
    // 它会把基线迁移 v1 整个拖垮，服务直接起不来。而这正是这里声称要支持的场景
    // （受限托管 PG / 无 superuser 建不了扩展）。
    // 正解是用 SAVEPOINT 把失败点回滚掉，让事务恢复可用。
    TransactionManager.current().exec("SAVEPOINT pg_trgm_setup")
    try {
        TransactionManager.current().exec("CREATE EXTENSION IF NOT EXISTS pg_trgm")
        TransactionManager.current().exec(
            "CREATE INDEX IF NOT EXISTS idx_users_name_trgm ON users USING GIN (lower(name) gin_trgm_ops)"
        )
        TransactionManager.current().exec(
            "CREATE INDEX IF NOT EXISTS idx_users_username_trgm ON users USING GIN (lower(username) gin_trgm_ops)"
        )
        TransactionManager.current().exec(
            "CREATE INDEX IF NOT EXISTS idx_users_email_trgm ON users USING GIN (lower(email) gin_trgm_ops)"
        )
        TransactionManager.current().exec("RELEASE SAVEPOINT pg_trgm_setup")
    } catch (e: Exception) {
        // 扩展不可用（受限托管 PG / 权限不足 / 扩展不在 search_path）时降级为原 LIKE 全扫描。
        runCatching { TransactionManager.current().exec("ROLLBACK TO SAVEPOINT pg_trgm_setup") }
        org.slf4j.LoggerFactory.getLogger("Database")
            .warn("pg_trgm search indexes unavailable; user search falls back to full scan: {}", e.message)
    }
}
