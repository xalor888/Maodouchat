package com.maodouchat.server.db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager



internal fun migrateMessagingV2RecordClasses() {
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
internal fun migrateMessageControlForeignKeys() {
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
            require(name.matches(safeConstraintNamePattern)) { "unsafe constraint name" }
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



internal fun dropMessagingV2SenderUserForeignKey() {
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
        require(name.matches(safeConstraintNamePattern)) { "unsafe constraint name" }
        TransactionManager.current().exec(
            "ALTER TABLE messaging_v2_messages DROP CONSTRAINT \"$name\""
        )
    }
}



internal fun backfillChatTypes() {
    try {
        TransactionManager.current().exec(
            "UPDATE chats SET chat_type = 'GROUP' WHERE is_group = true AND (chat_type IS NULL OR chat_type = '' OR chat_type = 'DIRECT')"
        )
    } catch (e: Exception) {
        // Column naming differs across H2/Postgres migrations; rethrow without leaking schema dumps.
        throw e
    }
}


private val safeConstraintNamePattern = Regex("[A-Za-z0-9_]+")
