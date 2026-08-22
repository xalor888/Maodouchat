package com.maodouchat.network

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Token 加密存储管理
 *
 * 使用 EncryptedSharedPreferences 加密存储 JWT Token
 *
 * 设计决策：
 * - saveToken / saveUserId 使用 apply() 异步写入，避免阻塞 Main 线程
 * - 构造函数中的 Keystore 操作是同步的，因此通过 lazy 把首次初始化推到
 *   第一次 IO 访问时（不是在 Application 启动时 Main 线程）
 * - 写入/清除失败都会 Log 警告，不再静默丢弃
 */
class TokenManager private constructor(private val context: Context) {

    enum class ConditionalSessionSaveResult {
        SAVED,
        SESSION_CHANGED,
        WRITE_FAILED
    }

    private val sessionLock = Any()

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            ApiConfig.Prefs.PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** @return true if the value was successfully saved */
    fun saveToken(token: String): Boolean {
        return runCatching { prefs.edit().putString(ApiConfig.Prefs.TOKEN_KEY, token).apply() }
            .onFailure { Log.w(TAG, "saveToken failed", it) }
            .isSuccess
    }

    fun getToken(): String? =
        readPrefsWithRetry(ApiConfig.Prefs.TOKEN_KEY) { it.getString(ApiConfig.Prefs.TOKEN_KEY, null) }

    /**
     * 9.3xx：EncryptedSharedPreferences 在部分机型冷启动首次读取可能瞬时失败（Keystore 未就绪），
     * 此前 runCatching 静默吞掉 → isLoggedIn() 误判 false → 启动路由落 LOGIN，闪登录页；
     * 这里做 3 次小间隔重试，仍失败才返回 null 并打日志。
     */
    private fun <T> readPrefsWithRetry(key: String, block: (android.content.SharedPreferences) -> T): T? {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                return block(prefs)
            } catch (error: Throwable) {
                lastError = error
                if (attempt < 2) {
                    try {
                        Thread.sleep(40L * (attempt + 1))
                    } catch (ignored: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }
        Log.w(TAG, "TokenManager read failed after retries (key=$key)", lastError)
        return null
    }

    fun saveRefreshToken(refreshToken: String): Boolean {
        return runCatching { prefs.edit().putString(ApiConfig.Prefs.REFRESH_TOKEN_KEY, refreshToken).apply() }
            .onFailure { Log.w(TAG, "saveRefreshToken failed", it) }
            .isSuccess
    }

    fun getRefreshToken(): String? =
        readPrefsWithRetry(ApiConfig.Prefs.REFRESH_TOKEN_KEY) { it.getString(ApiConfig.Prefs.REFRESH_TOKEN_KEY, null) }

    fun saveTokenExpiries(accessTokenExpiresAt: Long, refreshTokenExpiresAt: Long): Boolean {
        return runCatching {
            prefs.edit()
                .putLong(ApiConfig.Prefs.ACCESS_TOKEN_EXPIRES_AT_KEY, accessTokenExpiresAt)
                .putLong(ApiConfig.Prefs.REFRESH_TOKEN_EXPIRES_AT_KEY, refreshTokenExpiresAt)
                .apply()
        }
            .onFailure { Log.w(TAG, "saveTokenExpiries failed", it) }
            .isSuccess
    }

    fun getAccessTokenExpiresAt(): Long =
        readPrefsWithRetry(ApiConfig.Prefs.ACCESS_TOKEN_EXPIRES_AT_KEY) { it.getLong(ApiConfig.Prefs.ACCESS_TOKEN_EXPIRES_AT_KEY, 0L) } ?: 0L

    fun getRefreshTokenExpiresAt(): Long =
        readPrefsWithRetry(ApiConfig.Prefs.REFRESH_TOKEN_EXPIRES_AT_KEY) { it.getLong(ApiConfig.Prefs.REFRESH_TOKEN_EXPIRES_AT_KEY, 0L) } ?: 0L

    fun saveAuthSession(
        token: String,
        refreshToken: String,
        userId: String,
        accessTokenExpiresAt: Long,
        refreshTokenExpiresAt: Long
    ): Boolean = synchronized(sessionLock) {
        saveAuthSessionLocked(token, refreshToken, userId, accessTokenExpiresAt, refreshTokenExpiresAt)
    }

    fun saveAuthSessionIfCurrent(
        expectedUserId: String,
        expectedRefreshToken: String,
        token: String,
        refreshToken: String,
        userId: String,
        accessTokenExpiresAt: Long,
        refreshTokenExpiresAt: Long
    ): ConditionalSessionSaveResult = synchronized(sessionLock) {
        val currentMatches = runCatching {
            prefs.getString(ApiConfig.Prefs.USER_ID_KEY, null) == expectedUserId &&
                prefs.getString(ApiConfig.Prefs.REFRESH_TOKEN_KEY, null) == expectedRefreshToken
        }.getOrDefault(false)
        if (!currentMatches || userId != expectedUserId) {
            return@synchronized ConditionalSessionSaveResult.SESSION_CHANGED
        }
        if (saveAuthSessionLocked(token, refreshToken, userId, accessTokenExpiresAt, refreshTokenExpiresAt)) {
            ConditionalSessionSaveResult.SAVED
        } else {
            ConditionalSessionSaveResult.WRITE_FAILED
        }
    }

    private fun saveAuthSessionLocked(
        token: String,
        refreshToken: String,
        userId: String,
        accessTokenExpiresAt: Long,
        refreshTokenExpiresAt: Long
    ): Boolean = runCatching {
            prefs.edit()
                .putString(ApiConfig.Prefs.TOKEN_KEY, token)
                .putString(ApiConfig.Prefs.REFRESH_TOKEN_KEY, refreshToken)
                .putString(ApiConfig.Prefs.USER_ID_KEY, userId)
                .putLong(ApiConfig.Prefs.ACCESS_TOKEN_EXPIRES_AT_KEY, accessTokenExpiresAt)
                .putLong(ApiConfig.Prefs.REFRESH_TOKEN_EXPIRES_AT_KEY, refreshTokenExpiresAt)
                .commit()
        }
            .onFailure { Log.w(TAG, "saveAuthSession failed", it) }
            .getOrDefault(false)

    /** @return true if the value was successfully saved */
    fun saveUserId(userId: String): Boolean {
        return runCatching { prefs.edit().putString(ApiConfig.Prefs.USER_ID_KEY, userId).apply() }
            .onFailure { Log.w(TAG, "saveUserId failed", it) }
            .isSuccess
    }

    fun getUserId(): String? =
        readPrefsWithRetry(ApiConfig.Prefs.USER_ID_KEY) { it.getString(ApiConfig.Prefs.USER_ID_KEY, null) }

    fun isLoggedIn(): Boolean = SessionPresencePolicy.isLoggedIn(
        token = getToken(),
        userId = getUserId(),
        accessTokenExpiresAt = getAccessTokenExpiresAt(),
        refreshToken = getRefreshToken(),
        refreshTokenExpiresAt = getRefreshTokenExpiresAt(),
    )

    /** @deprecated Prefer per-chat cursors via [saveLastSyncAtMs]/chatId] / [getLastSyncAtMs]. */
    fun saveLastSyncAtMs(timestamp: Long): Boolean =
        runCatching { prefs.edit().putLong(ApiConfig.Prefs.LAST_SYNC_AT_KEY, timestamp).apply() }
            .onFailure { Log.w(TAG, "saveLastSyncAtMs failed", it) }
            .isSuccess

    /** @deprecated Prefer per-chat cursors via [getLastSyncAtMs] with chatId. */
    fun getLastSyncAtMs(): Long =
        runCatching { prefs.getLong(ApiConfig.Prefs.LAST_SYNC_AT_KEY, 0L) }.getOrDefault(0L)

    /**
     * Per-chat incremental sync cursor (timestamp + optional message id for same-ms stability).
     * A single global cursor would advance when opening a newer chat and permanently skip
     * older un-synced messages in other chats.
     */
    data class SyncCursor(val timestampMs: Long, val messageId: String = "")

    /**
     * 仅更新时间戳时不得清空 messageId：
     * 同毫秒消息数 > 一页时，空 id 会让服务端/客户端在同一 ts 上反复拉满页。
     * 时间戳前进才重置 id；时间戳相同则保留原 id。
     */
    fun saveLastSyncAtMs(chatId: String, timestamp: Long): Boolean {
        if (chatId.isBlank() || timestamp < 0L) return false
        val previous = getSyncCursor(chatId)
        val messageId = when {
            timestamp > previous.timestampMs -> ""
            timestamp == previous.timestampMs -> previous.messageId
            else -> previous.messageId // 不允许时间戳回退时清掉 id
        }
        return saveSyncCursor(chatId, SyncCursor(timestampMs = maxOf(timestamp, previous.timestampMs), messageId = messageId))
    }

    fun saveSyncCursor(chatId: String, cursor: SyncCursor): Boolean {
        if (chatId.isBlank() || cursor.timestampMs < 0L) return false
        return runCatching {
            // 只允许单调前进：并发慢路径不得把游标回退
            val previous = getSyncCursor(chatId)
            if (!isCursorStrictlyAfter(cursor, previous) &&
                !(cursor.timestampMs == previous.timestampMs && cursor.messageId == previous.messageId)
            ) {
                // equal is ok (idempotent); strictly older is dropped
                if (isCursorStrictlyAfter(previous, cursor)) return@runCatching true
            }
            // commit：避免进程在 apply 未落盘前被杀导致游标回退、同页死循环
            prefs.edit()
                .putLong(chatSyncKey(chatId), cursor.timestampMs)
                .putString(chatSyncIdKey(chatId), cursor.messageId)
                .commit()
        }.onFailure { Log.w(TAG, "saveSyncCursor($chatId) failed", it) }
            .getOrDefault(false)
    }

    fun getLastSyncAtMs(chatId: String): Long = getSyncCursor(chatId).timestampMs

    fun getSyncCursor(chatId: String): SyncCursor {
        if (chatId.isBlank()) return SyncCursor(0L)
        return runCatching {
            val key = chatSyncKey(chatId)
            if (!prefs.contains(key)) {
                // 8.40：不再回退到旧全局游标——旧安装中该键保留历史最新同步时刻，
                // 任何「从未同步过的新会话」（新入群/新加会话）会从旧时间点起步，
                // 服务端更早的历史消息被静默跳过；无 per-chat 键时从 0 全量拉取。
                return@runCatching SyncCursor(0L)
            }
            val ts = prefs.getLong(key, 0L)
            val id = prefs.getString(chatSyncIdKey(chatId), "").orEmpty()
            SyncCursor(timestampMs = ts, messageId = id)
        }.getOrDefault(SyncCursor(0L))
    }

    private fun chatSyncKey(chatId: String): String =
        "${ApiConfig.Prefs.LAST_SYNC_AT_KEY}:$chatId"

    private fun chatSyncIdKey(chatId: String): String =
        "${ApiConfig.Prefs.LAST_SYNC_AT_KEY}:id:$chatId"

    /** BacklogSyncWorker 节流时钟：最近一次对该会话执行增量同步的时刻（成功或失败均更新）。 */
    fun getLastBacklogSyncAtMs(chatId: String): Long {
        if (chatId.isBlank()) return 0L
        return runCatching { prefs.getLong(backlogSyncKey(chatId), 0L) }.getOrDefault(0L)
    }

    fun markBacklogSyncAttempted(chatId: String) {
        if (chatId.isBlank()) return
        runCatching { prefs.edit().putLong(backlogSyncKey(chatId), System.currentTimeMillis()).commit() }
    }

    private fun backlogSyncKey(chatId: String): String =
        "${ApiConfig.Prefs.LAST_SYNC_AT_KEY}:backlog:$chatId"

    /**
     * 消息变更（DELETE/REVOKE/EDIT）增量游标，与消息同步游标独立：
     * 变更日志时间轴与消息时间轴不同，混用会导致漏删或重复。
     */
    fun saveMutationCursor(chatId: String, cursor: SyncCursor): Boolean {
        if (chatId.isBlank() || cursor.timestampMs < 0L) return false
        return runCatching {
            val previous = getMutationCursor(chatId)
            if (isCursorStrictlyAfter(previous, cursor)) return@runCatching true
            prefs.edit()
                .putLong(chatMutationKey(chatId), cursor.timestampMs)
                .putString(chatMutationIdKey(chatId), cursor.messageId)
                .commit()
        }.onFailure { Log.w(TAG, "saveMutationCursor($chatId) failed", it) }
            .getOrDefault(false)
    }

    fun getMutationCursor(chatId: String): SyncCursor {
        if (chatId.isBlank()) return SyncCursor(0L)
        return runCatching {
            SyncCursor(
                timestampMs = prefs.getLong(chatMutationKey(chatId), 0L),
                messageId = prefs.getString(chatMutationIdKey(chatId), "").orEmpty()
            )
        }.getOrDefault(SyncCursor(0L))
    }

    /**
     * Drop message + mutation sync cursors when the user leaves/deletes a chat.
     * Prevents prefs growth and avoids replaying a stale high-water mark if re-joined.
     */
    fun clearChatCursors(chatId: String): Boolean {
        if (chatId.isBlank()) return false
        return runCatching {
            prefs.edit()
                .remove(chatSyncKey(chatId))
                .remove(chatSyncIdKey(chatId))
                .remove(chatMutationKey(chatId))
                .remove(chatMutationIdKey(chatId))
                .commit()
        }.onFailure { Log.w(TAG, "clearChatCursors($chatId) failed", it) }
            .getOrDefault(false)
    }

    private fun chatMutationKey(chatId: String): String =
        "mutation_sync_at:$chatId"

    private fun chatMutationIdKey(chatId: String): String =
        "mutation_sync_id:$chatId"

    /** (ts, id) lexicographic strict greater — matches server getMessagesSince / getMutationsSince. */
    private fun isCursorStrictlyAfter(a: SyncCursor, b: SyncCursor): Boolean {
        if (a.timestampMs > b.timestampMs) return true
        if (a.timestampMs < b.timestampMs) return false
        return a.messageId > b.messageId
    }

    /** @return true if the preferences were successfully cleared */
    fun clear(): Boolean = synchronized(sessionLock) {
        runCatching { prefs.edit().clear().commit() }
            .onFailure { Log.w(TAG, "clear failed", it) }
            // commit() may fail by returning false without throwing. isSuccess would turn
            // that storage failure into a false-positive logout and revive old JWTs on restart.
            .getOrDefault(false)
    }

    companion object {
        private const val TAG = "TokenManager"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: TokenManager? = null

        fun getInstance(context: Context): TokenManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TokenManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        /** Already-created singleton, if any (avoids needing Context on hot paths like WS reconnect). */
        fun getInstanceOrNull(): TokenManager? = INSTANCE
    }
}
