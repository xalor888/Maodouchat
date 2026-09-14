package com.maodouchat.server.db

import com.maodouchat.server.config.ServerConfig
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.update

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

private fun migrateMessagingV2RecordClasses() {
    TransactionManager.current().exec(
        """
        UPDATE messaging_v2_messages
        SET record_class = 'INTERNAL'
        WHERE kind IN ('RECEIPT', 'SENDER_KEY')
        """.trimIndent()
    )
    TransactionManager.current().exec(
        """
        UPDATE messaging_v2_messages
        SET record_class = 'EVENT'
        WHERE kind = 'SERVICE'
          AND id IN (
            SELECT message_id
            FROM messaging_v2_envelopes
            WHERE ciphertext_type = 'SERVICE_PLAINTEXT'
              AND ciphertext LIKE '%\"type\":\"EVENT\"%'
          )
        """.trimIndent()
    )
}

/** Move star/pin ownership from the retired plaintext message table to v2 metadata. */
private fun migrateMessageControlForeignKeys() {
    val marker = "messaging_v2_message_controls"
    if (SystemSettings.selectAll().where { SystemSettings.key eq marker }.firstOrNull() != null) return

    listOf("star_messages", "pinned_messages").forEach { tableName ->
        val names = TransactionManager.current().exec(
            """
            SELECT tc.constraint_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON tc.constraint_name = kcu.constraint_name
             AND tc.constraint_schema = kcu.constraint_schema
            WHERE LOWER(tc.table_name) = '$tableName'
              AND LOWER(kcu.column_name) = 'message_id'
              AND tc.constraint_type = 'FOREIGN KEY'
              -- 必须限定在当前 schema：不限定会把别的 schema 里同名表的约束也查出来，
              -- 随后的 DROP CONSTRAINT 却在当前 schema 执行 → "constraint does not exist"。
              AND tc.table_schema = CURRENT_SCHEMA
              AND kcu.table_schema = CURRENT_SCHEMA
            """.trimIndent()
        ) { result ->
            buildList {
                while (result.next()) add(result.getString(1))
            }
        }.orEmpty()
        names.forEach { name ->
            require(name.matches(Regex("[A-Za-z0-9_]+"))) { "unsafe constraint name" }
            TransactionManager.current().exec("ALTER TABLE $tableName DROP CONSTRAINT \"$name\"")
        }
    }

    TransactionManager.current().exec(
        "DELETE FROM star_messages WHERE message_id NOT IN (SELECT id FROM messaging_v2_messages)"
    )
    TransactionManager.current().exec(
        "DELETE FROM pinned_messages WHERE message_id NOT IN (SELECT id FROM messaging_v2_messages)"
    )
    TransactionManager.current().exec(
        """
        ALTER TABLE star_messages
        ADD CONSTRAINT fk_star_messages_v2_message
        FOREIGN KEY (message_id) REFERENCES messaging_v2_messages(id)
        """.trimIndent()
    )
    TransactionManager.current().exec(
        """
        ALTER TABLE pinned_messages
        ADD CONSTRAINT fk_pinned_messages_v2_message
        FOREIGN KEY (message_id) REFERENCES messaging_v2_messages(id)
        """.trimIndent()
    )
    SystemSettings.insert {
        it[key] = marker
        it[value] = "1"
        it[updatedAt] = System.currentTimeMillis()
        it[updatedBy] = null
    }
}

private fun dropMessagingV2SenderUserForeignKey() {
    val names = TransactionManager.current().exec(
        """
        SELECT tc.constraint_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
          ON tc.constraint_name = kcu.constraint_name
         AND tc.constraint_schema = kcu.constraint_schema
        WHERE LOWER(tc.table_name) = 'messaging_v2_messages'
          AND LOWER(kcu.column_name) = 'sender_user_id'
          AND tc.constraint_type = 'FOREIGN KEY'
          AND tc.table_schema = CURRENT_SCHEMA
          AND kcu.table_schema = CURRENT_SCHEMA
        """.trimIndent()
    ) { result ->
        buildList {
            while (result.next()) add(result.getString(1))
        }
    }.orEmpty()
    names.forEach { name ->
        require(name.matches(Regex("[A-Za-z0-9_]+"))) { "unsafe constraint name" }
        TransactionManager.current().exec(
            "ALTER TABLE messaging_v2_messages DROP CONSTRAINT \"$name\""
        )
    }
}

/**
 * 存量 chats 行补全 chat_type：群聊推导为 GROUP，私聊推导为 DIRECT。
 * 新列由 createMissingTablesAndColumns 自动补列（默认 DIRECT），随后此处修正历史群聊。
 */

private fun backfillChatTypes() {
    try {
        TransactionManager.current().exec(
            "UPDATE chats SET chat_type = 'GROUP' WHERE is_group = true AND (chat_type IS NULL OR chat_type = '' OR chat_type = 'DIRECT')"
        )
    } catch (e: Exception) {
        // Column naming differs across H2/Postgres migrations; rethrow without leaking schema dumps.
        throw e
    }
}

private fun migrateAuthSessionState() {
    val now = System.currentTimeMillis()
    // Pre-session refresh tokens cannot satisfy the mandatory auth_session_id contract.
    TransactionManager.current().exec(
        "UPDATE refresh_tokens SET revoked_at = $now " +
            "WHERE revoked_at IS NULL AND (session_id IS NULL OR session_id = '')"
    )
    TransactionManager.current().exec(
        "UPDATE refresh_tokens SET revoked_at = $now WHERE revoked_at IS NULL AND NOT EXISTS (" +
            "SELECT 1 FROM auth_sessions s WHERE s.id = refresh_tokens.session_id " +
            "AND s.user_id = refresh_tokens.user_id AND s.revoked_at IS NULL)"
    )
    TransactionManager.current().exec(
        "DELETE FROM push_tokens WHERE auth_session_id IS NULL OR auth_session_id = '' OR NOT EXISTS (" +
            "SELECT 1 FROM auth_sessions s WHERE s.id = push_tokens.auth_session_id " +
            "AND s.user_id = push_tokens.user_id AND s.revoked_at IS NULL)"
    )
}

private fun widenClientPrefsWritingStyleColumn() {
    // 按「实际连接的数据库」判断（而非 ServerConfig.databaseDriver——测试直接 connect 时该值可能仍是默认 H2）
    val isPostgres = org.jetbrains.exposed.sql.transactions.TransactionManager.current()
        .db.vendor.contains("postgres", ignoreCase = true)
    val sql = if (isPostgres) {
        "ALTER TABLE client_prefs ALTER COLUMN writing_style_custom TYPE VARCHAR(320)"
    } else {
        "ALTER TABLE client_prefs ALTER COLUMN writing_style_custom VARCHAR(320)"
    }
    TransactionManager.current().exec(sql)
}

private fun widenFriendRequestMessageColumn() {
    val isPostgres = org.jetbrains.exposed.sql.transactions.TransactionManager.current()
        .db.vendor.contains("postgres", ignoreCase = true)
    val sql = if (isPostgres) {
        "ALTER TABLE friend_requests ALTER COLUMN message TYPE VARCHAR(300)"
    } else {
        "ALTER TABLE friend_requests ALTER COLUMN message VARCHAR(300)"
    }
    TransactionManager.current().exec(sql)
}

/** 9.144：既有库加宽 reports 文本列（createMissingTablesAndColumns 只补列不扩列）。 */
private fun widenReportsColumns() {
    val isPostgres = org.jetbrains.exposed.sql.transactions.TransactionManager.current()
        .db.vendor.contains("postgres", ignoreCase = true)
    val alters = if (isPostgres) {
        listOf(
            "ALTER TABLE reports ALTER COLUMN reason TYPE VARCHAR(80)",
            "ALTER TABLE reports ALTER COLUMN description TYPE VARCHAR(800)",
            "ALTER TABLE reports ALTER COLUMN resolution_note TYPE VARCHAR(800)",
        )
    } else {
        listOf(
            "ALTER TABLE reports ALTER COLUMN reason VARCHAR(80)",
            "ALTER TABLE reports ALTER COLUMN description VARCHAR(800)",
            "ALTER TABLE reports ALTER COLUMN resolution_note VARCHAR(800)",
        )
    }
    alters.forEach { TransactionManager.current().exec(it) }
}

/** 9.144：既有库加宽 risk_events.matched（同 reports）。 */
private fun widenRiskEventsMatchedColumn() {
    val isPostgres = org.jetbrains.exposed.sql.transactions.TransactionManager.current()
        .db.vendor.contains("postgres", ignoreCase = true)
    val sql = if (isPostgres) {
        "ALTER TABLE risk_events ALTER COLUMN \"matched\" TYPE VARCHAR(280)"
    } else {
        "ALTER TABLE risk_events ALTER COLUMN \"matched\" VARCHAR(280)"
    }
    TransactionManager.current().exec(sql)
}

/** 9.154：既有库加宽 moderation_audit_log.detail 500→800（同 reports 口径，createMissingTablesAndColumns 只补列不扩列）。 */
private fun widenModerationAuditDetailColumn() {
    val isPostgres = org.jetbrains.exposed.sql.transactions.TransactionManager.current()
        .db.vendor.contains("postgres", ignoreCase = true)
    val sql = if (isPostgres) {
        "ALTER TABLE moderation_audit_log ALTER COLUMN detail TYPE VARCHAR(800)"
    } else {
        "ALTER TABLE moderation_audit_log ALTER COLUMN detail VARCHAR(800)"
    }
    TransactionManager.current().exec(sql)
}

private fun ensureIndexes() {
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
private fun ensureSearchIndexes() {
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

/** B03：补全 signal_keys.device_id 空值（遗留行）。 */
internal fun backfillSignalKeyDeviceIds() {
    TransactionManager.current().exec("UPDATE signal_keys SET device_id = 1 WHERE device_id IS NULL")
}

/** B03：补全 signal_devices.status / confirmed_at。 */
internal fun backfillSignalDeviceConfirmation() {
    val now = System.currentTimeMillis()
    TransactionManager.current().exec("UPDATE signal_devices SET status = 'CONFIRMED' WHERE status IS NULL OR status = ''")
    TransactionManager.current().exec("UPDATE signal_devices SET confirmed_at = $now WHERE status = 'CONFIRMED' AND confirmed_at IS NULL")
}

/**
 * B03：为仅有 signal_keys、缺少 signal_devices 行的 (user, device) 补元数据行。
 * 删除 DeviceRegistry 读路径「缺行即 PENDING」长期兼容前必须先落地。
 */
internal fun backfillMissingSignalDevices() {
    val now = System.currentTimeMillis()
    TransactionManager.current().exec(
        """
        INSERT INTO signal_devices (user_id, device_id, device_name, status, confirmed_at, created_at, last_seen_at)
        SELECT DISTINCT k.user_id, k.device_id,
               ('设备 #' || CAST(k.device_id AS VARCHAR(16))),
               'CONFIRMED', $now, $now, $now
        FROM signal_keys k
        WHERE NOT EXISTS (
            SELECT 1 FROM signal_devices d
            WHERE d.user_id = k.user_id AND d.device_id = k.device_id
        )
        """.trimIndent()
    )
}

private fun backfillMemberRoles() {
    try {
        TransactionManager.current().exec("UPDATE chat_participants SET \"role\" = 'MEMBER' WHERE \"role\" IS NULL OR \"role\" = ''")
    } catch (e: Exception) {
        // Column naming differs across H2/Postgres migrations; rethrow without leaking schema dumps.
        throw e
    }
}

private fun backfillModeratorEmails() {
    // 8.46：改为 Exposed 参数化 DSL，替代原生 SQL 字符串拼接（配置来源也统一走参数绑定，
    // 彻底消除注入面；语义与旧 LOWER(email) IN (...) 完全一致）。
    val emails = com.maodouchat.server.config.ServerConfig.moderatorEmails.map { it.lowercase() }
    if (emails.isEmpty()) return
    Users.update({ Users.email.lowerCase() inList emails }) {
        it[Users.isModerator] = true
    }
}

private fun backfillModerationRules() {
    val now = System.currentTimeMillis()
    DEFAULT_MODERATION_RULES.forEach { seed ->
        if (ModerationRules.selectAll().where { ModerationRules.id eq seed.id }.firstOrNull() != null) {
            // 修复旧数据：rule_spam_keywords / rule_short_link 早期以 KEYWORD/URL 类型存了
            // 正则 pattern（字面量匹配永不命中，默认内容安全防线形同虚设），统一纠正为 REGEX。
            if (seed.matchType == "REGEX") {
                val existingType = ModerationRules.select(ModerationRules.matchType)
                    .where { ModerationRules.id eq seed.id }.firstOrNull()?.get(ModerationRules.matchType)
                if (existingType != "REGEX") {
                    ModerationRules.update({ ModerationRules.id eq seed.id }) { it[ModerationRules.matchType] = "REGEX" }
                }
            }
            return@forEach
        }
        ModerationRules.insert {
            it[ModerationRules.id] = seed.id
            it[ModerationRules.name] = seed.name
            it[ModerationRules.description] = seed.description
            it[ModerationRules.scope] = seed.scope
            it[ModerationRules.matchType] = seed.matchType
            it[ModerationRules.pattern] = seed.pattern
            it[ModerationRules.action] = seed.action
            it[ModerationRules.windowMs] = seed.windowMs
            it[ModerationRules.hitThreshold] = seed.hitThreshold
            it[ModerationRules.escalationAction] = seed.escalationAction
            it[ModerationRules.enabled] = true
            it[ModerationRules.priority] = seed.priority
            it[ModerationRules.createdAt] = now
            it[ModerationRules.updatedAt] = now
        }
    }
}

private data class SeedModerationRule(
    val id: String,
    val name: String,
    val description: String,
    val scope: String,
    val matchType: String,
    val pattern: String,
    val action: String,
    val windowMs: Long,
    val hitThreshold: Int,
    val escalationAction: String?,
    val priority: Int
)

private val DEFAULT_MODERATION_RULES = listOf(
    SeedModerationRule(
        id = "rule_spam_keywords",
        name = "常见营销引流关键词",
        description = "命中常见营销、博彩或引流词时进入人工复核",
        scope = "ALL",
        matchType = "REGEX",
        pattern = "兼职\\s*日结|免费\\s*约|加我\\s*v?x|代刷|网赚|博彩|网赌",
        action = "WARN_MOD",
        windowMs = 24L * 60L * 60L * 1000L,
        hitThreshold = 3,
        escalationAction = "AUTO_RATE_LIMIT",
        priority = 50
    ),
    SeedModerationRule(
        id = "rule_short_link",
        name = "可疑短链",
        description = "动态或评论中出现常见短链时进入人工复核",
        scope = "ALL",
        matchType = "REGEX",
        pattern = "(?:t\\.cn|bit\\.ly|tinyurl\\.com|goo\\.gl|is\\.gd|ow\\.ly|buff\\.ly|adf\\.ly)",
        action = "WARN_MOD",
        windowMs = 24L * 60L * 60L * 1000L,
        hitThreshold = 2,
        escalationAction = "AUTO_HOLD",
        priority = 80
    ),
    SeedModerationRule(
        id = "rule_post_flood",
        name = "高频发动态",
        description = "一分钟内发布超过五条动态会进入限流",
        scope = "POST",
        matchType = "FREQUENCY",
        pattern = "*",
        action = "AUTO_RATE_LIMIT",
        windowMs = 60_000L,
        hitThreshold = 5,
        escalationAction = "AUTO_HOLD",
        priority = 20
    ),
    SeedModerationRule(
        id = "rule_comment_flood",
        name = "高频发评论",
        description = "一分钟内发布超过十条评论会进入限流",
        scope = "COMMENT",
        matchType = "FREQUENCY",
        pattern = "*",
        action = "AUTO_RATE_LIMIT",
        windowMs = 60_000L,
        hitThreshold = 10,
        escalationAction = "AUTO_HOLD",
        priority = 20
    )
)


/** B04：回填 direct_chat_pairs 映射，供后续删除 findLegacyDirectIdInTx 热路径兼容。 */
internal fun backfillDirectChatPairs() {
    val memberCounts = ChatParticipants.selectAll()
        .groupBy { it[ChatParticipants.chatId] }
        .filter { (_, rows) -> rows.size == 2 }
    if (memberCounts.isEmpty()) return
    val chatIds = memberCounts.keys
    val eligibleChats = Chats.selectAll().where { Chats.id inList chatIds }
        .filter { !it[Chats.isGroup] && it[Chats.chatType] != com.maodouchat.server.model.ChatType.SECRET }
    val now = System.currentTimeMillis()
    eligibleChats.forEach { chat ->
        val members = memberCounts.getValue(chat[Chats.id]).map { it[ChatParticipants.userId] }.sorted()
        val key = members.joinToString(":")
        val exists = DirectChatPairs.selectAll().where { DirectChatPairs.pairKey eq key }.any()
        if (!exists) {
            DirectChatPairs.insert {
                it[DirectChatPairs.pairKey] = key
                it[DirectChatPairs.chatId] = chat[Chats.id]
                it[DirectChatPairs.createdAt] = now
            }
        }
    }
}

/** B09：为既有库补充 signaling epoch / seq_no / idempotency_key 列（新库由基线建表自带）。 */
internal fun addSignalingEpochSequenceColumns() {
    org.jetbrains.exposed.sql.transactions.TransactionManager.current().exec(
        "ALTER TABLE signaling_messages ADD COLUMN IF NOT EXISTS epoch BIGINT DEFAULT 0 NOT NULL"
    )
    org.jetbrains.exposed.sql.transactions.TransactionManager.current().exec(
        "ALTER TABLE signaling_messages ADD COLUMN IF NOT EXISTS seq_no BIGINT DEFAULT 0 NOT NULL"
    )
    org.jetbrains.exposed.sql.transactions.TransactionManager.current().exec(
        "ALTER TABLE signaling_messages ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(200) DEFAULT '' NOT NULL"
    )
}
