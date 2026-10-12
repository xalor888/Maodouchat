package com.maodouchat.server.db

import com.maodouchat.server.config.ServerConfig
import org.jetbrains.exposed.sql.transactions.TransactionManager



internal fun migrateAuthSessionState() {
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



internal fun widenClientPrefsWritingStyleColumn() {
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



internal fun widenFriendRequestMessageColumn() {
    val isPostgres = org.jetbrains.exposed.sql.transactions.TransactionManager.current()
        .db.vendor.contains("postgres", ignoreCase = true)
    val sql = if (isPostgres) {
        "ALTER TABLE friend_requests ALTER COLUMN message TYPE VARCHAR(300)"
    } else {
        "ALTER TABLE friend_requests ALTER COLUMN message VARCHAR(300)"
    }
    TransactionManager.current().exec(sql)
}
