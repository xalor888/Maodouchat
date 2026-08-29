package com.maodouchat.data.local

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.maodouchat.BuildConfig
import net.sqlcipher.database.SupportFactory

/**
 * 数据库生命周期（A03）：创建、解锁、换号销毁、迁移失败备份重建。
 * 从 AppDatabase companion 抽出，使 AppDatabase 只保留 DAO 契约与单例门面。
 */
internal object DatabaseLifecycle {
    private const val TAG = "DatabaseLifecycle"
    private const val DATABASE_NAME = "maodouchat.db"

    fun open(context: Context, allowRecreate: Boolean): AppDatabase {
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

    fun close(database: AppDatabase) = database.close()

    fun destroy(context: Context) {
        deleteDatabaseFiles(context)
        DatabasePassphraseProvider.destroyPassphrase(context)
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
}
