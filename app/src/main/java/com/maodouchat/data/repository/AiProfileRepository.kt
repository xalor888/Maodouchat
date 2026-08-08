package com.maodouchat.data.repository

import android.content.Context
import android.util.Log
import com.maodouchat.data.local.DatabasePassphraseProvider
import com.maodouchat.network.ApiConfig
import com.maodouchat.network.TokenManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import net.sqlcipher.database.SQLiteDatabase
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * B4 · AI 增强能力本地存储仓库。
 *
 * 与主库（AppDatabase / maodouchat.db）隔离的独立 SQLCipher 库 ai_enhance.db，
 * 复用同一 SQLCipher passphrase（Android Keystore 背书的 EncryptedSharedPreferences）。
 * 所有读写均在 [SQLiteDatabase] 加密通道内完成，明文不外泄到服务端。
 *
 * 存储内容（全部按 ownerUserId 隔离，随账号切换自然隔离）：
 * - ai_chat_profiles     会话画像（本地统计 + 服务端叙事摘要缓存）
 * - ai_archive_suggestions 智能归档建议
 * - ai_weekly_reports    群周报（按周缓存）
 * - ai_message_classes   消息分类（会话级类别统计）
 *
 * 红线约束：
 * - 只依赖 net.sqlcipher + 现有 DatabasePassphraseProvider，不改动 AppDatabase；
 * - 端侧 embedding 不落库（OnDeviceEmbeddingGate.isImplementationAllowed=false），
 *   本库只存文本统计与结果，不存向量/权重。
 */
class AiProfileRepository(private val context: Context) {

    private val mutex = Mutex()
    @Volatile private var db: SQLiteDatabase? = null

    /** 当前登录账号 ID；未登录时用空串占位（本地数据随登录态隔离）。 */
    private fun ownerUserId(): String =
        TokenManager.getInstance(context.applicationContext).getUserId()?.takeIf(String::isNotBlank).orEmpty()

    private suspend fun <T> withDb(block: (SQLiteDatabase, String) -> T): T =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val database = db ?: openDatabase().also { db = it }
                block(database, ownerUserId())
            }
        }

    private fun openDatabase(): SQLiteDatabase {
        val appContext = context.applicationContext
        val passphrase = DatabasePassphraseProvider.getPassphrase(appContext)
        val file = File(appContext.getDatabasePath("ai_enhance.db").absolutePath)
        file.parentFile?.mkdirs()
        val database = SQLiteDatabase.openOrCreateDatabase(file.absolutePath, passphrase, null)
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ai_chat_profiles (
                ownerUserId TEXT NOT NULL,
                chatId TEXT NOT NULL,
                statsJson TEXT NOT NULL,
                narrative TEXT,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY(ownerUserId, chatId)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ai_archive_suggestions (
                ownerUserId TEXT NOT NULL,
                chatId TEXT NOT NULL,
                score INTEGER NOT NULL,
                reason TEXT NOT NULL,
                suggestedAt INTEGER NOT NULL,
                PRIMARY KEY(ownerUserId, chatId)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ai_weekly_reports (
                ownerUserId TEXT NOT NULL,
                chatId TEXT NOT NULL,
                weekStart INTEGER NOT NULL,
                weekEnd INTEGER NOT NULL,
                report TEXT NOT NULL,
                model TEXT,
                createdAt INTEGER NOT NULL,
                PRIMARY KEY(ownerUserId, chatId, weekStart)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ai_message_classes (
                ownerUserId TEXT NOT NULL,
                chatId TEXT NOT NULL,
                category TEXT NOT NULL,
                count INTEGER NOT NULL,
                confidence REAL NOT NULL,
                classifiedAt INTEGER NOT NULL,
                PRIMARY KEY(ownerUserId, chatId, category)
            )
            """.trimIndent()
        )
        return database
    }

    fun close() {
        runCatching { db?.close() }
        db = null
    }

    // ── 会话画像 ──────────────────────────────────────────────

    suspend fun getProfile(chatId: String): ProfileRow? = withDb { database, owner ->
        database.rawQuery(
            "SELECT statsJson, narrative, updatedAt FROM ai_chat_profiles WHERE ownerUserId = ? AND chatId = ?",
            arrayOf(owner, chatId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@withDb null
            ProfileRow(
                chatId = chatId,
                statsJson = cursor.getString(0),
                narrative = cursor.getString(1),
                updatedAt = cursor.getLong(2)
            )
        }
    }

    suspend fun saveProfile(
        chatId: String,
        statsJson: String,
        narrative: String?,
        updatedAt: Long = System.currentTimeMillis()
    ) = withDb { database, owner ->
        database.execSQL(
            """
            INSERT OR REPLACE INTO ai_chat_profiles (ownerUserId, chatId, statsJson, narrative, updatedAt)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(owner, chatId, statsJson.take(MAX_JSON_LEN), narrative?.take(MAX_NARRATIVE_LEN), updatedAt)
        )
    }

    // ── 智能归档建议 ──────────────────────────────────────────

    suspend fun saveArchiveSuggestion(
        chatId: String,
        score: Int,
        reason: String,
        suggestedAt: Long = System.currentTimeMillis()
    ) = withDb { database, owner ->
        database.execSQL(
            """
            INSERT OR REPLACE INTO ai_archive_suggestions (ownerUserId, chatId, score, reason, suggestedAt)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(owner, chatId, score, reason.take(200), suggestedAt)
        )
    }

    suspend fun listArchiveSuggestions(limit: Int = 20): List<ArchiveRow> = withDb { database, owner ->
        database.rawQuery(
            "SELECT chatId, score, reason, suggestedAt FROM ai_archive_suggestions " +
                "WHERE ownerUserId = ? ORDER BY score DESC LIMIT ?",
            arrayOf(owner, limit.coerceIn(1, 50).toString())
        ).use { cursor ->
            val rows = mutableListOf<ArchiveRow>()
            while (cursor.moveToNext()) {
                rows += ArchiveRow(
                    chatId = cursor.getString(0),
                    score = cursor.getInt(1),
                    reason = cursor.getString(2),
                    suggestedAt = cursor.getLong(3)
                )
            }
            rows
        }
    }

    suspend fun clearArchiveSuggestions() = withDb { database, owner ->
        database.execSQL(
            "DELETE FROM ai_archive_suggestions WHERE ownerUserId = ?",
            arrayOf(owner)
        )
    }

    // ── 群周报 ────────────────────────────────────────────────

    suspend fun getWeeklyReport(chatId: String, weekStart: Long): WeeklyReportRow? = withDb { database, owner ->
        database.rawQuery(
            "SELECT weekEnd, report, model, createdAt FROM ai_weekly_reports " +
                "WHERE ownerUserId = ? AND chatId = ? AND weekStart = ?",
            arrayOf(owner, chatId, weekStart.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@withDb null
            WeeklyReportRow(
                chatId = chatId,
                weekStart = weekStart,
                weekEnd = cursor.getLong(0),
                report = cursor.getString(1),
                model = cursor.getString(2),
                createdAt = cursor.getLong(3)
            )
        }
    }

    suspend fun listWeeklyReports(chatId: String, limit: Int = 12): List<WeeklyReportRow> = withDb { database, owner ->
        database.rawQuery(
            "SELECT weekStart, weekEnd, report, model, createdAt FROM ai_weekly_reports " +
                "WHERE ownerUserId = ? AND chatId = ? ORDER BY weekStart DESC LIMIT ?",
            arrayOf(owner, chatId, limit.coerceIn(1, 30).toString())
        ).use { cursor ->
            val rows = mutableListOf<WeeklyReportRow>()
            while (cursor.moveToNext()) {
                rows += WeeklyReportRow(
                    chatId = chatId,
                    weekStart = cursor.getLong(0),
                    weekEnd = cursor.getLong(1),
                    report = cursor.getString(2),
                    model = cursor.getString(3),
                    createdAt = cursor.getLong(4)
                )
            }
            rows
        }
    }

    suspend fun saveWeeklyReport(
        chatId: String,
        weekStart: Long,
        weekEnd: Long,
        report: String,
        model: String?,
        createdAt: Long = System.currentTimeMillis()
    ) = withDb { database, owner ->
        database.execSQL(
            """
            INSERT OR REPLACE INTO ai_weekly_reports (ownerUserId, chatId, weekStart, weekEnd, report, model, createdAt)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(owner, chatId, weekStart.toString(), weekEnd.toString(), report.take(MAX_REPORT_LEN), model, createdAt)
        )
    }

    // ── 消息分类 ──────────────────────────────────────────────

    suspend fun saveChatClasses(chatId: String, categories: List<CategoryCount>) = withDb { database, owner ->
        database.execSQL("DELETE FROM ai_message_classes WHERE ownerUserId = ? AND chatId = ?", arrayOf(owner, chatId))
        categories.forEach { category ->
            database.execSQL(
                """
                INSERT OR REPLACE INTO ai_message_classes (ownerUserId, chatId, category, count, confidence, classifiedAt)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(owner, chatId, category.category.take(40), category.count, category.confidence, System.currentTimeMillis())
            )
        }
    }

    suspend fun getChatClasses(chatId: String): List<CategoryCount> = withDb { database, owner ->
        database.rawQuery(
            "SELECT category, count, confidence FROM ai_message_classes " +
                "WHERE ownerUserId = ? AND chatId = ? ORDER BY count DESC",
            arrayOf(owner, chatId)
        ).use { cursor ->
            val rows = mutableListOf<CategoryCount>()
            while (cursor.moveToNext()) {
                rows += CategoryCount(category = cursor.getString(0), count = cursor.getInt(1), confidence = cursor.getDouble(2))
            }
            rows
        }
    }

    data class ProfileRow(val chatId: String, val statsJson: String, val narrative: String?, val updatedAt: Long)
    data class ArchiveRow(val chatId: String, val score: Int, val reason: String, val suggestedAt: Long)
    data class WeeklyReportRow(val chatId: String, val weekStart: Long, val weekEnd: Long, val report: String, val model: String?, val createdAt: Long)
    data class CategoryCount(val category: String, val count: Int, val confidence: Double)

    private companion object {
        const val MAX_JSON_LEN = 8_000
        const val MAX_NARRATIVE_LEN = 6_000
        const val MAX_REPORT_LEN = 20_000
    }
}

/**
 * B4 · 轻量 HTTP 助手：带 Bearer Token 的 JSON POST，供 AI 增强能力客户端调用服务端
 * /api/ai/enhance/ 端点。独立于 ApiService（ApiService 不在本任务可改范围）。
 */
internal object AiEnhanceHttp {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun <T> post(
        context: Context,
        path: String,
        requestBodyJson: String,
        deserializer: KSerializer<T>
    ): Result<T> = withContext(Dispatchers.IO) {
        val token = TokenManager.getInstance(context.applicationContext).getToken()?.takeIf(String::isNotBlank)
            ?: return@withContext Result.failure(IllegalStateException("not_logged_in"))
        try {
            val request = Request.Builder()
                .url("${ApiConfig.BASE_URL}$path")
                .addHeader("Authorization", "Bearer $token")
                .post(requestBodyJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(RuntimeException("HTTP ${response.code}: ${body.take(160)}"))
                }
                try {
                    Result.success(json.decodeFromString(deserializer, body))
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "AI enhance request failed: $path", error)
            Result.failure(error)
        }
    }

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private const val TAG = "AiEnhanceHttp"
}
