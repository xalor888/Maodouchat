package com.maodouchat.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// 迁移簇：v25→v34 的版本迁移，从 DatabaseMigrations 纯搬移。
internal object DatabaseMigrationsLate {
    val MIGRATION_25_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v25→v26: message_search_documents 加入 messageType 列，支持全局搜索按消息类型过滤。
            // 先用 TEXT 满足 NOT NULL，再从父消息回填真实类型。不能只依赖后续索引重建：
            // contentHash 未变化时增量索引会走快速路径，旧 IMAGE/FILE/VOICE 会永久冒充 TEXT。
            db.execSQL(
                "ALTER TABLE message_search_documents " +
                    "ADD COLUMN messageType TEXT NOT NULL DEFAULT 'TEXT'"
            )
            db.execSQL(
                """
                UPDATE message_search_documents
                SET messageType = COALESCE(
                    (SELECT messages.type FROM messages WHERE messages.id = message_search_documents.messageId),
                    messageType
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_26_27 = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v26→v27: chats 加入 chatType 列（DIRECT/GROUP/CHANNEL 广播频道）。
            // 存量行按 isGroup 推导：群聊 → GROUP，私聊 → DIRECT。
            db.execSQL("ALTER TABLE chats ADD COLUMN chatType TEXT NOT NULL DEFAULT 'DIRECT'")
            db.execSQL("UPDATE chats SET chatType = 'GROUP' WHERE isGroup = 1")
        }
    }

    val MIGRATION_27_28 = object : Migration(27, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // B7: 纯索引增量迁移——只追加 CREATE INDEX IF NOT EXISTS，不重写任何表、不改任何列。
            // 全部基于已有字段，补齐会话列表 / 会话内消息 / 发件箱 / 过期清扫 / 媒体搜索的排序与过滤热路径。

            // 1) chats：会话列表查询（ChatDao.getActiveChats）
            //    WHERE archived = 0 ORDER BY pinnedAt DESC, lastMessageTime DESC
            //    原 index_chats_archived 只能过滤 archived，排序仍需回表；复合索引直接覆盖过滤+排序。
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_chats_archived_pinnedAt_lastMessageTime " +
                    "ON chats(archived, pinnedAt, lastMessageTime)"
            )

            // 2) messages：会话内消息（MessageDao.getMessagesByChatId / getRecentMessages /
            //    getFirstMessageAtOrAfter / getEarliestMessageTimestamp）
            //    WHERE chatId = ? ORDER BY timestamp —— 原 chatId_type_timestamp 复合索引中间隔着 type，
            //    无法直接支撑 timestamp 排序；chatId_timestamp 精确命中。
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_messages_chatId_timestamp " +
                    "ON messages(chatId, timestamp)"
            )

            // 3) Historical local outbox index. V2 uses messaging_v2_outbox; keep this
            // migration statement so databases created through v28 retain schema parity.
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_messages_status_senderId_timestamp " +
                    "ON messages(status, senderId, timestamp)"
            )

            // 4) messages：过期消息清扫（MessageDao.getExpiredMessageIds）
            //    WHERE expiresAt IS NOT NULL AND expiresAt <= :now —— 全表扫会随消息量线性变慢。
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_expiresAt ON messages(expiresAt)")

            // 5) messages：媒体/搜索批量拉取（MessageDao.getImageMessages / getSearchableMessages）
            //    WHERE type IN (...) ORDER BY timestamp DESC LIMIT —— type 前缀 + timestamp 排序。
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_messages_type_timestamp " +
                    "ON messages(type, timestamp)"
            )
        }
    }

    // v28→v29: secret_chats 加入 lastActivityAt（密聊无活动 TTL 清扫数据源）。
    // 存量行默认 0 → 视为从未活跃，由 TTL 逻辑按开关/默认值决定是否销毁；新行默认当前时间。
    val MIGRATION_28_29 = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE secret_chats ADD COLUMN lastActivityAt INTEGER NOT NULL DEFAULT 0")
        }
    }

    // v29→v30: users 加入 lastSeen（联系人最后上线时间持久化，此前重启即丢失）。
    // 存量行默认 0 → 离线；新写入随 toEntity 带真实 lastSeen。
    val MIGRATION_29_30 = object : Migration(29, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE users ADD COLUMN lastSeen INTEGER NOT NULL DEFAULT 0")
        }
    }

    // v30→v31: messages 加入 sealedSender（8.49）——密封发送标志持久化。
    // 存量行默认 0；此前该标志只在瞬态域字段上，DB round-trip 后丢失，
    // outbox 重发会把密封消息降级为非密封发送。
    val MIGRATION_30_31 = object : Migration(30, 31) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN sealedSender INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_31_32 = object : Migration(31, 32) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS messaging_v2_inbox (
                    envelopeId TEXT NOT NULL PRIMARY KEY,
                    ownerUserId TEXT NOT NULL,
                    deviceId INTEGER NOT NULL,
                    sequence INTEGER NOT NULL,
                    messageId TEXT NOT NULL,
                    conversationId TEXT NOT NULL,
                    senderUserId TEXT NOT NULL,
                    senderDeviceId INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    groupRevision INTEGER,
                    clientTimestamp INTEGER NOT NULL,
                    serverTimestamp INTEGER NOT NULL,
                    ciphertextType TEXT NOT NULL,
                    ciphertext TEXT NOT NULL,
                    state TEXT NOT NULL,
                    attempts INTEGER NOT NULL,
                    nextAttemptAt INTEGER NOT NULL,
                    lastErrorCode TEXT,
                    receivedAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_messaging_v2_inbox_ownerUserId_deviceId_sequence " +
                    "ON messaging_v2_inbox(ownerUserId, deviceId, sequence)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_messaging_v2_inbox_ownerUserId_deviceId_state_nextAttemptAt_sequence " +
                    "ON messaging_v2_inbox(ownerUserId, deviceId, state, nextAttemptAt, sequence)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messaging_v2_inbox_messageId ON messaging_v2_inbox(messageId)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS messaging_v2_outbox (
                    messageId TEXT NOT NULL PRIMARY KEY,
                    ownerUserId TEXT NOT NULL,
                    conversationId TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    localPayload TEXT NOT NULL,
                    clientTimestamp INTEGER NOT NULL,
                    groupRevision INTEGER,
                    preparedEnvelopesJson TEXT,
                    state TEXT NOT NULL,
                    attempts INTEGER NOT NULL,
                    nextAttemptAt INTEGER NOT NULL,
                    lastErrorCode TEXT,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_messaging_v2_outbox_ownerUserId_state_nextAttemptAt_createdAt " +
                    "ON messaging_v2_outbox(ownerUserId, state, nextAttemptAt, createdAt)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_messaging_v2_outbox_ownerUserId_conversationId_createdAt " +
                    "ON messaging_v2_outbox(ownerUserId, conversationId, createdAt)",
            )
            // The legacy scanner cannot safely replay rows here: owner account, typed payload,
            // device coverage, and attachment transfer state were not persisted together. Mark
            // them failed so they remain visible and can be retried through the V2 command path
            // instead of spinning forever after the old flusher is removed.
            db.execSQL("UPDATE messages SET status = 'FAILED' WHERE status = 'SENDING'")
        }
    }

    val MIGRATION_32_33 = object : Migration(32, 33) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS messaging_v2_receipts (
                    ownerUserId TEXT NOT NULL,
                    messageId TEXT NOT NULL,
                    conversationId TEXT NOT NULL,
                    recipientUserId TEXT NOT NULL,
                    deliveredAt INTEGER,
                    readAt INTEGER,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(ownerUserId, messageId, recipientUserId)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_messaging_v2_receipts_ownerUserId_conversationId_messageId " +
                    "ON messaging_v2_receipts(ownerUserId, conversationId, messageId)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_messaging_v2_receipts_ownerUserId_recipientUserId_readAt " +
                    "ON messaging_v2_receipts(ownerUserId, recipientUserId, readAt)",
            )
        }
    }

    val MIGRATION_34_35 = object : Migration(34, 35) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Crash journal: plaintext captured after a successful ratchet step survives
            // process death so a replayed envelope is projected from the journal instead of
            // being acknowledged as a libsignal Duplicate without ever reaching the timeline.
            db.execSQL("ALTER TABLE messaging_v2_inbox ADD COLUMN plaintextJournal TEXT NOT NULL DEFAULT ''")
        }
    }

    val MIGRATION_33_34 = object : Migration(33, 34) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS message_mutation_tombstones (
                    ownerUserId TEXT NOT NULL,
                    messageId TEXT NOT NULL,
                    conversationId TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    terminalAt INTEGER NOT NULL,
                    PRIMARY KEY(ownerUserId, messageId),
                    FOREIGN KEY(conversationId) REFERENCES chats(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_message_mutation_tombstones_conversationId " +
                    "ON message_mutation_tombstones(conversationId)",
            )
        }
    }

}
