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
import com.maodouchat.data.local.dao.MessagingV2Dao
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
import com.maodouchat.data.local.entity.MessageMutationTombstoneEntity
import com.maodouchat.data.local.entity.MessageSearchDocumentEntity
import com.maodouchat.data.local.entity.MessageSearchTokenEntity
import com.maodouchat.data.local.entity.MissedCallEntity
import com.maodouchat.data.local.entity.MessagingV2InboxEntity
import com.maodouchat.data.local.entity.MessagingV2OutboxEntity
import com.maodouchat.data.local.entity.MessagingV2ReceiptEntity
import com.maodouchat.data.local.entity.SecretChatEntity
import com.maodouchat.data.local.entity.SenderKeyRetryEntity
import com.maodouchat.data.local.entity.SignalKeyEntity
import com.maodouchat.data.local.entity.UserEntity

@Database(
    entities = [UserEntity::class, ChatEntity::class, ChatDraftEntity::class, MessageEntity::class, SignalKeyEntity::class, IdentityTrustEntity::class, MissedCallEntity::class, ChatLockEntity::class, SecretChatEntity::class, AiSummaryCacheEntity::class, SenderKeyRetryEntity::class, AiTaskEntity::class, MessageSearchDocumentEntity::class, MessageSearchTokenEntity::class, AttachmentTransferEntity::class, AiOperationEntity::class, MessagingV2InboxEntity::class, MessagingV2OutboxEntity::class, MessagingV2ReceiptEntity::class, MessageMutationTombstoneEntity::class],
    version = 35,
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
    abstract fun messagingV2Dao(): MessagingV2Dao

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
                    .addMigrations(*DatabaseMigrations.ALL.toTypedArray())
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
    }
}
