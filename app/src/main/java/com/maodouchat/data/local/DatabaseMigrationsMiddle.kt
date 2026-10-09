package com.maodouchat.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// 迁移簇：v15→v25 的版本迁移，从 DatabaseMigrations 纯搬移。
internal object DatabaseMigrationsMiddle {
    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ai_operations (
                    id TEXT NOT NULL PRIMARY KEY,
                    ownerUserId TEXT NOT NULL,
                    chatId TEXT NOT NULL,
                    type TEXT NOT NULL,
                    targetMessageId TEXT,
                    parametersJson TEXT NOT NULL,
                    state TEXT NOT NULL,
                    attempts INTEGER NOT NULL,
                    lastErrorCode TEXT,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_operations_ownerUserId ON ai_operations(ownerUserId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_operations_chatId ON ai_operations(chatId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_operations_state ON ai_operations(state)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_operations_updatedAt ON ai_operations(updatedAt)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_ai_operations_ownerUserId_chatId_state " +
                    "ON ai_operations(ownerUserId, chatId, state)"
            )
        }
    }

    val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS chat_drafts (
                    ownerUserId TEXT NOT NULL,
                    chatId TEXT NOT NULL,
                    text TEXT NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(ownerUserId, chatId)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_drafts_ownerUserId ON chat_drafts(ownerUserId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_drafts_updatedAt ON chat_drafts(updatedAt)")
        }
    }

    val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chats ADD COLUMN pinnedAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE chats ADD COLUMN notificationsMuted INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE chats ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE chats ADD COLUMN markedUnread INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE chats ADD COLUMN settingsUpdatedAt INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_chatId_type_timestamp ON messages(chatId, type, timestamp)")
        }
    }

    val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chats ADD COLUMN groupAvatar TEXT")
        }
    }

    val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chats ADD COLUMN disappearingMessageSeconds INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN expiresAt INTEGER")
        }
    }

    val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS secret_chats (
                    chatId TEXT NOT NULL PRIMARY KEY,
                    enabledAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // D1: messages.type 独立索引 -> 加速全局搜索的 type IN (...) 过滤
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_type ON messages(type)")
        }
    }

    val MIGRATION_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // D3: chats 表索引 -> 加速会话列表 archived/lastMessageTime 查询
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_archived ON chats(archived)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_lastMessageTime ON chats(lastMessageTime)")
            // D4: missed_calls 表索引 -> 加速未接来电 receivedAt/isRead 查询
            db.execSQL("CREATE INDEX IF NOT EXISTS index_missed_calls_receivedAt ON missed_calls(receivedAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_missed_calls_isRead ON missed_calls(isRead)")
        }
    }

    val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v24→v25: sender_key_retry_queue 加入 ownerUserId 列并改主键为 (ownerUserId, chatId)。
            // SQLite 不支持 ALTER TABLE 改主键，必须重建表。采用 CREATE-NEW + INSERT-SELECT + DROP-OLD + RENAME 模式
            // 保留旧数据，避免已排队的 SenderKey 分发重试条目丢失（ownerUserId 用空串占位，重试时会重新绑定当前用户）。
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS sender_key_retry_queue_new (
                    ownerUserId TEXT NOT NULL DEFAULT '',
                    chatId TEXT NOT NULL,
                    epoch INTEGER NOT NULL,
                    reason TEXT NOT NULL,
                    attempts INTEGER NOT NULL,
                    nextAttemptAt INTEGER NOT NULL,
                    lastError TEXT,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(ownerUserId, chatId)
                )
                """.trimIndent()
            )
            // 旧表 schema 假设列名为 chatId/epoch/reason/attempts/nextAttemptAt/lastError/updatedAt。
            // 用 INSERT-SELECT 把旧数据搬到新表，ownerUserId 留空串（迁移时无法可靠推断历史 owner）。
            // 旧表由 v9 的 MIGRATION_8_9 创建，执行到 v25 时必然存在；此处 DROP 在 INSERT 之后，
            // 所以 INSERT-SELECT 时旧表仍在。若极端情况下旧表缺失，INSERT-SELECT 会抛 no such table，
            // 由 Room 迁移框架上报（而非静默 0 行）。
            db.execSQL(
                """
                INSERT INTO sender_key_retry_queue_new
                    (ownerUserId, chatId, epoch, reason, attempts, nextAttemptAt, lastError, updatedAt)
                SELECT '', chatId, epoch, reason, attempts, nextAttemptAt, lastError, updatedAt
                FROM sender_key_retry_queue
                """.trimIndent()
            )
            db.execSQL("DROP TABLE IF EXISTS sender_key_retry_queue")
            db.execSQL("ALTER TABLE sender_key_retry_queue_new RENAME TO sender_key_retry_queue")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sender_key_retry_queue_ownerUserId ON sender_key_retry_queue(ownerUserId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sender_key_retry_queue_nextAttemptAt ON sender_key_retry_queue(nextAttemptAt)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_sender_key_retry_queue_ownerUserId_nextAttemptAt " +
                    "ON sender_key_retry_queue(ownerUserId, nextAttemptAt)"
            )
        }
    }

}
