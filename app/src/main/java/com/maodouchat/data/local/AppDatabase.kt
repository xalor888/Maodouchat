package com.maodouchat.data.local

import android.content.Context
import android.util.Log
import com.maodouchat.BuildConfig
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.sqlcipher.database.SupportFactory
import com.maodouchat.data.local.dao.AiSummaryCacheDao
import com.maodouchat.data.local.dao.AiTaskDao
import com.maodouchat.data.local.dao.AiOperationDao
import com.maodouchat.data.local.dao.AttachmentTransferDao
import com.maodouchat.data.local.dao.ChatDao
import com.maodouchat.data.local.dao.ChatDraftDao
import com.maodouchat.data.local.dao.ChatLockDao
import com.maodouchat.data.local.dao.IdentityTrustDao
import com.maodouchat.data.local.dao.MessageDao
import com.maodouchat.data.local.dao.MessageSearchDao
import com.maodouchat.data.local.dao.MissedCallDao
import com.maodouchat.data.local.dao.SecretChatDao
import com.maodouchat.data.local.dao.SenderKeyRetryDao
import com.maodouchat.data.local.dao.SignalKeyDao
import com.maodouchat.data.local.dao.UserDao
import com.maodouchat.data.local.entity.AiSummaryCacheEntity
import com.maodouchat.data.local.entity.AiTaskEntity
import com.maodouchat.data.local.entity.AiOperationEntity
import com.maodouchat.data.local.entity.AttachmentTransferEntity
import com.maodouchat.data.local.entity.ChatEntity
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.data.local.entity.ChatLockEntity
import com.maodouchat.data.local.entity.IdentityTrustEntity
import com.maodouchat.data.local.entity.MessageEntity
import com.maodouchat.data.local.entity.MessageSearchDocumentEntity
import com.maodouchat.data.local.entity.MessageSearchTokenEntity
import com.maodouchat.data.local.entity.MissedCallEntity
import com.maodouchat.data.local.entity.SecretChatEntity
import com.maodouchat.data.local.entity.SenderKeyRetryEntity
import com.maodouchat.data.local.entity.SignalKeyEntity
import com.maodouchat.data.local.entity.UserEntity

@Database(
    entities = [UserEntity::class, ChatEntity::class, ChatDraftEntity::class, MessageEntity::class, SignalKeyEntity::class, IdentityTrustEntity::class, MissedCallEntity::class, ChatLockEntity::class, SecretChatEntity::class, AiSummaryCacheEntity::class, SenderKeyRetryEntity::class, AiTaskEntity::class, MessageSearchDocumentEntity::class, MessageSearchTokenEntity::class, AttachmentTransferEntity::class, AiOperationEntity::class],
    version = 31,
    exportSchema = true
)abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun chatDao(): ChatDao
    abstract fun chatDraftDao(): ChatDraftDao
    abstract fun messageDao(): MessageDao
    abstract fun messageSearchDao(): MessageSearchDao
    abstract fun signalKeyDao(): SignalKeyDao
    abstract fun identityTrustDao(): IdentityTrustDao
    abstract fun missedCallDao(): MissedCallDao
    abstract fun chatLockDao(): ChatLockDao
    abstract fun secretChatDao(): SecretChatDao
    abstract fun aiSummaryCacheDao(): AiSummaryCacheDao
    abstract fun aiTaskDao(): AiTaskDao
    abstract fun aiOperationDao(): AiOperationDao
    abstract fun senderKeyRetryDao(): SenderKeyRetryDao
    abstract fun attachmentTransferDao(): AttachmentTransferDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: openEncryptedDatabase(context.applicationContext, allowRecreate = BuildConfig.DEBUG)
                    .also { INSTANCE = it }
            }
        }

        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        fun destroyDatabase(context: Context) {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
                deleteDatabaseFiles(context.applicationContext)
                DatabasePassphraseProvider.destroyPassphrase(context.applicationContext)
            }
        }

        private fun openEncryptedDatabase(context: Context, allowRecreate: Boolean): AppDatabase {
            return try {
                buildAndValidateDatabase(context, allowDestructiveMigration = allowRecreate)
            } catch (error: Throwable) {
                if (!allowRecreate) throw error
                // Debug 环境才自动重建：生产环境不能因迁移缺失或瞬时 SQLCipher 错误静默丢失本地消息/密钥。
                // 8.48 修复：重建前备份旧库文件到 .bak（不直接删除）——Debug 侧载到真机有真实消息时，
                // 一次瞬时错误也保留数据可恢复；备份文件在应用私有目录，App 重启后仍保留。
                Log.w(TAG, "Encrypted database open failed; backing up and recreating local encrypted storage", error)
                backupDatabaseFiles(context)
                deleteDatabaseFiles(context)
                DatabasePassphraseProvider.destroyPassphrase(context)
                try {
                    buildAndValidateDatabase(context, allowDestructiveMigration = true)
                } catch (recreateError: Throwable) {
                    // 重建仍失败才真正崩溃，并附带完整错误链供 debug 使用
                    recreateError.addSuppressed(error)
                    throw recreateError
                }
            }
        }

        private fun buildAndValidateDatabase(context: Context, allowDestructiveMigration: Boolean): AppDatabase {
            val passphrase = DatabasePassphraseProvider.getPassphrase(context)
            try {
                val builder = Room.databaseBuilder(
                    context,
                    AppDatabase::class.java,
                    DATABASE_NAME
                ).openHelperFactory(SupportFactory(passphrase))
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31)
                if (allowDestructiveMigration) {
                    builder.fallbackToDestructiveMigration(dropAllTables = true)
                }
                val database = builder.build()

                // Room opens lazily. Force a read here so old plaintext DBs, wrong
                // passphrases, or corrupt encrypted files are handled before app UI starts.
                database.openHelper.writableDatabase.query("SELECT count(*) FROM sqlite_master").close()
                return database
            } finally {
                // 密钥材料卫生：无论成功或失败都清除本方法内的口令副本。
                passphrase.fill(0)
            }
        }

        private fun deleteDatabaseFiles(context: Context) {
            context.deleteDatabase(DATABASE_NAME)
            context.getDatabasePath(DATABASE_NAME).let { dbFile ->
                listOf("", "-journal", "-shm", "-wal").forEach { suffix ->
                    runCatching { dbFile.resolveSibling(dbFile.name + suffix).delete() }
                }
            }
        }

        /** 8.48：Debug 自动重建前把旧库文件重命名为 .bak 保留（可手动恢复真实数据）。 */
        private fun backupDatabaseFiles(context: Context) {
            runCatching {
                val dbFile = context.getDatabasePath(DATABASE_NAME)
                if (!dbFile.exists()) return@runCatching
                val bak = dbFile.resolveSibling(dbFile.name + ".bak." + System.currentTimeMillis())
                dbFile.renameTo(bak)
                listOf("-journal", "-shm", "-wal").forEach { suffix ->
                    val f = dbFile.resolveSibling(dbFile.name + suffix)
                    if (f.exists()) runCatching { f.renameTo(bak.resolveSibling(bak.name + suffix)) }
                }
            }.onFailure { Log.w(TAG, "backupDatabaseFiles failed", it) }
        }

        private const val TAG = "AppDatabase"
        private const val DATABASE_NAME = "maodouchat.db"

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN editedAt INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
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

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chats ADD COLUMN memberRevision INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
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

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chats ADD COLUMN groupAnnouncement TEXT")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reactionsJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
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

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_tasks ADD COLUMN remindedAt INTEGER")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ai_tasks_isCompleted_remindedAt_dueAt " +
                        "ON ai_tasks(isCompleted, remindedAt, dueAt)"
                )
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
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

        private val MIGRATION_14_15 = object : Migration(14, 15) {
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

        private val MIGRATION_15_16 = object : Migration(15, 16) {
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

        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v25→v26: message_search_documents 加入 messageType 列，支持全局搜索按消息类型过滤。
                // 存量行默认 TEXT（迁移前只有可搜索类型入索引，类型归类以重建索引后为准）。
                db.execSQL(
                    "ALTER TABLE message_search_documents " +
                        "ADD COLUMN messageType TEXT NOT NULL DEFAULT 'TEXT'"
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

                // 3) messages：本地发件箱轮询（MessageDao.getSendingOutbox）
                //    WHERE status='SENDING' AND senderId=? AND type IN (...) ORDER BY timestamp
                //    status 低区分度，放最左结合 senderId 收窄范围，再按 timestamp 排序。
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
    }
}
