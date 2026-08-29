package com.maodouchat.server.db

import org.jetbrains.exposed.sql.transactions.TransactionManager

/**
 * One-way retirement for the plaintext v1 messaging store.
 *
 * Bot-authored rows are the only legacy content that is still meaningful to the
 * server. Copy them into the dedicated service store, then remove every v1
 * message table. New databases never create these tables.
 */
internal fun retireLegacyMessagingTables() {
    if (legacyTableExists("messages")) {
        val humanMessageCount = TransactionManager.current().exec(
            "SELECT COUNT(*) FROM messages WHERE sender_id NOT LIKE 'bot_%'"
        ) { result ->
            result.next()
            result.getLong(1)
        } ?: 0L
        check(humanMessageCount == 0L) {
            "Legacy messaging retirement is blocked: messages contains $humanMessageCount human-authored row(s). " +
                "Export or migrate them before applying this one-way contract migration."
        }
        TransactionManager.current().exec(
            """
            INSERT INTO service_messages
                (id, chat_id, sender_id, content, "type", "timestamp", edited_at, deleted_at)
            SELECT m.id, m.chat_id, m.sender_id, m.content, m."type", m."timestamp", m.edited_at, NULL
            FROM messages m
            WHERE m.sender_id LIKE 'bot_%'
              AND NOT EXISTS (
                  SELECT 1 FROM service_messages s WHERE s.id = m.id
              )
            """.trimIndent()
        )
    }

    listOf(
        "read_receipts",
        "message_reactions",
        "message_mutations",
        "messages",
        // Sender Key coverage is now derived from v2 mailbox envelopes. The old
        // mutable report table must not remain as a second source of truth.
        "sender_key_distributions",
    ).forEach { tableName ->
        TransactionManager.current().exec("DROP TABLE IF EXISTS $tableName")
    }
}

private fun legacyTableExists(tableName: String): Boolean =
    TransactionManager.current().exec(
        """
        SELECT 1
        FROM information_schema.tables
        WHERE LOWER(table_name) = '$tableName'
        """.trimIndent()
    ) { result -> result.next() } == true
