package com.maodouchat.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// 迁移簇：v5→v15 的版本迁移，从 DatabaseMigrations 纯搬移。
internal object DatabaseMigrationsEarly {
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN editedAt INTEGER")
            db.execSQL("ALTER TABLE messages ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ai_summary_cache (
                    cacheKey TEXT NOT NULL PRIMARY KEY,
                    chatId TEXT NOT NULL,
                    startMessageId TEXT NOT NULL,
                    endMessageId TEXT NOT NULL,
                    messageCount INTEGER NOT NULL,
                    summary TEXT NOT NULL,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_summary_cache_chatId ON ai_summary_cache(chatId)")
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chats ADD COLUMN memberRevision INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS sender_key_retry_queue (
                    chatId TEXT NOT NULL PRIMARY KEY,
                    epoch INTEGER NOT NULL,
                    reason TEXT NOT NULL,
                    attempts INTEGER NOT NULL,
                    nextAttemptAt INTEGER NOT NULL,
                    lastError TEXT,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sender_key_retry_queue_nextAttemptAt ON sender_key_retry_queue(nextAttemptAt)")
        }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chats ADD COLUMN groupAnnouncement TEXT")
        }
    }

    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN reactionsJson TEXT NOT NULL DEFAULT '[]'")
        }
    }

    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ai_tasks (
                    id TEXT NOT NULL PRIMARY KEY,
                    chatId TEXT NOT NULL,
                    sourceQuery TEXT NOT NULL,
                    title TEXT NOT NULL,
                    owner TEXT,
                    dueText TEXT,
                    dueAt INTEGER,
                    isCompleted INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    completedAt INTEGER
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_tasks_chatId ON ai_tasks(chatId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_tasks_isCompleted ON ai_tasks(isCompleted)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_tasks_dueAt ON ai_tasks(dueAt)")
        }
    }

    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE ai_tasks ADD COLUMN remindedAt INTEGER")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_ai_tasks_isCompleted_remindedAt_dueAt " +
                    "ON ai_tasks(isCompleted, remindedAt, dueAt)"
            )
        }
    }

    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS message_search_documents (
                    messageId TEXT NOT NULL PRIMARY KEY,
                    chatId TEXT NOT NULL,
                    senderId TEXT NOT NULL,
                    searchableText TEXT NOT NULL,
                    contentHash TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    indexedAt INTEGER NOT NULL,
                    FOREIGN KEY(messageId) REFERENCES messages(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_message_search_documents_chatId ON message_search_documents(chatId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_message_search_documents_timestamp ON message_search_documents(timestamp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_message_search_documents_chatId_timestamp ON message_search_documents(chatId, timestamp)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS message_search_tokens (
                    messageId TEXT NOT NULL,
                    token TEXT NOT NULL,
                    chatId TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    PRIMARY KEY(messageId, token),
                    FOREIGN KEY(messageId) REFERENCES message_search_documents(messageId) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_message_search_tokens_token ON message_search_tokens(token)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_message_search_tokens_chatId ON message_search_tokens(chatId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_message_search_tokens_token_timestamp ON message_search_tokens(token, timestamp)")
        }
    }

    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS attachment_transfers (
                    messageId TEXT NOT NULL PRIMARY KEY,
                    ownerUserId TEXT NOT NULL,
                    chatId TEXT NOT NULL,
                    messageType TEXT NOT NULL,
                    sourceUri TEXT NOT NULL,
                    encryptedPath TEXT NOT NULL,
                    fileName TEXT NOT NULL,
                    mimeType TEXT NOT NULL,
                    plainSize INTEGER NOT NULL,
                    durationMs INTEGER,
                    keyBase64 TEXT NOT NULL,
                    ivBase64 TEXT NOT NULL,
                    cipherSha256 TEXT NOT NULL,
                    plainSha256 TEXT NOT NULL,
                    cipherSize INTEGER NOT NULL,
                    attachmentId TEXT,
                    wireContent TEXT,
                    state TEXT NOT NULL,
                    uploadedBytes INTEGER NOT NULL,
                    attempts INTEGER NOT NULL,
                    lastErrorCode TEXT,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_attachment_transfers_chatId ON attachment_transfers(chatId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_attachment_transfers_state ON attachment_transfers(state)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_attachment_transfers_updatedAt ON attachment_transfers(updatedAt)")
        }
    }

}
