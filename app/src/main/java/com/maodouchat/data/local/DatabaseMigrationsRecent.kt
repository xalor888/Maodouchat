package com.maodouchat.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// 迁移簇：v35→v40 的版本迁移，从 DatabaseMigrations 纯搬移。
internal object DatabaseMigrationsRecent {
    val MIGRATION_35_36 = object : Migration(35, 36) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messaging_v2_receipts ADD COLUMN playedAt INTEGER")
        }
    }

    val MIGRATION_36_37 = object : Migration(36, 37) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS scheduled_messages (
                    id TEXT NOT NULL PRIMARY KEY,
                    ownerUserId TEXT NOT NULL,
                    chatId TEXT NOT NULL,
                    peerUserId TEXT NOT NULL,
                    text TEXT NOT NULL,
                    sendAtMillis INTEGER NOT NULL,
                    createdAtMillis INTEGER NOT NULL,
                    isGroup INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    attempt INTEGER NOT NULL,
                    repeatIntervalMs INTEGER NOT NULL,
                    repeatCount INTEGER NOT NULL,
                    occurrencesSent INTEGER NOT NULL,
                    weekdaysOnly INTEGER NOT NULL,
                    timeZoneId TEXT NOT NULL,
                    idempotencyKey TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_scheduled_messages_ownerUserId ON scheduled_messages(ownerUserId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_scheduled_messages_chatId ON scheduled_messages(chatId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_scheduled_messages_sendAtMillis ON scheduled_messages(sendAtMillis)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_scheduled_messages_idempotencyKey ON scheduled_messages(idempotencyKey)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_scheduled_messages_ownerUserId_chatId " +
                    "ON scheduled_messages(ownerUserId, chatId)",
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS message_reminders (
                    id TEXT NOT NULL PRIMARY KEY,
                    ownerUserId TEXT NOT NULL,
                    chatId TEXT NOT NULL,
                    messageId TEXT NOT NULL,
                    messagePreview TEXT NOT NULL,
                    remindAtMillis INTEGER NOT NULL,
                    createdAtMillis INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_message_reminders_ownerUserId ON message_reminders(ownerUserId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_message_reminders_chatId ON message_reminders(chatId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_message_reminders_remindAtMillis ON message_reminders(remindAtMillis)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_message_reminders_ownerUserId_chatId " +
                    "ON message_reminders(ownerUserId, chatId)",
            )
        }
    }

    val MIGRATION_37_38 = object : Migration(37, 38) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS archive_suggestion_dismissals (
                    ownerUserId TEXT NOT NULL,
                    chatId TEXT NOT NULL,
                    dismissedAtMillis INTEGER NOT NULL,
                    PRIMARY KEY(ownerUserId, chatId)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_archive_suggestion_dismissals_ownerUserId ON archive_suggestion_dismissals(ownerUserId)")
        }
    }

    val MIGRATION_38_39 = object : Migration(38, 39) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS voice_played (
                    ownerUserId TEXT NOT NULL,
                    messageId TEXT NOT NULL,
                    playedAtMillis INTEGER NOT NULL,
                    PRIMARY KEY(ownerUserId, messageId)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_voice_played_ownerUserId ON voice_played(ownerUserId)")
        }
    }

    val MIGRATION_39_40 = object : Migration(39, 40) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS notification_center_items (
                    ownerUserId TEXT NOT NULL,
                    id TEXT NOT NULL,
                    type TEXT NOT NULL,
                    mergeKey TEXT NOT NULL,
                    title TEXT NOT NULL,
                    subtitle TEXT,
                    preview TEXT,
                    deeplink TEXT,
                    extraJson TEXT NOT NULL,
                    read INTEGER NOT NULL,
                    count INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(ownerUserId, id)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_notification_center_items_ownerUserId ON notification_center_items(ownerUserId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_notification_center_items_ownerUserId_updatedAt ON notification_center_items(ownerUserId, updatedAt)")
        }
    }

}
