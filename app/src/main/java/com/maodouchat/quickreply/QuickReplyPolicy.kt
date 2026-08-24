package com.maodouchat.quickreply

import android.content.Context
import android.os.SystemClock
import com.maodouchat.MaodouchatApp
import com.maodouchat.network.TokenManager
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.security.ChatLockSession
import com.maodouchat.widget.ConversationWidgetContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * B5 快捷回复门禁与去重（纯策略，不触碰任何 View/通知）。
 *
 * 红线约定：快捷回复**不绕过**密聊/锁定门禁——
 *  1. 密聊会话（chatType=SECRET）一律拒绝；
 *  2. 会话 PIN 锁（chatLockDao 磁盘锁）一律拒绝，即使 ChatLockSession 进程内已解锁
 *     （后台无法完成 PIN 验证，宁可不发）；
 *  3. 会话失效/账号切换（BackgroundSessionGate）一律拒绝；
 *  4. App 锁开启时不显示消息预览（脱敏），但快捷回复本身属于用户主动操作，仍允许。
 */
object QuickReplyPolicy {

    private const val PREFS = "quick_reply"
    private const val KEY_ENABLED = "enabled"
    private const val DEDUPE_WINDOW_MS = 5000L   // 去抖窗口：同会话同文本 5s 内只发一次
    private const val DEDUPE_MAX_KEYS = 32

    // ---- 开关（按账号隔离） ----
    fun isEnabled(context: Context): Boolean {
        val userId = userId(context)
        if (userId.isBlank()) return false
        return prefs(context).getBoolean(AccountIsolationKey(KEY_ENABLED, userId), true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val userId = userId(context)
        if (userId.isBlank()) return
        prefs(context).edit().putBoolean(AccountIsolationKey(KEY_ENABLED, userId), enabled).apply()
    }

    // ---- 同步前置校验（不触库，可在广播线程直接调用） ----
    /**
     * 同步门禁结果：text 非法 / 未登录 / 功能关闭 / 账号归属不符时直接拒绝。
     * DB 级门禁（密聊/PIN 锁）由 [gateForChat] 在 IO 线程完成。
     */
    fun canAttemptReply(context: Context, chatId: String, rawText: String?): SyncVerdict {
        val text = sanitizeReplyText(rawText)
        if (text.isBlank()) return SyncVerdict.Rejected("empty_text")
        if (!isEnabled(context)) return SyncVerdict.Rejected("disabled")
        val tokenManager = TokenManager.getInstance(context)
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (ownerUserId.isBlank() || tokenManager.getToken().isNullOrBlank()) {
            return SyncVerdict.Rejected("not_logged_in")
        }
        if (chatId.isBlank()) return SyncVerdict.Rejected("blank_chat")
        return SyncVerdict.Allowed(text, ownerUserId)
    }

    // ---- DB 级门禁（IO 线程） ----
    suspend fun gateForChat(
        app: MaodouchatApp,
        chatId: String,
        ownerUserId: String,
    ): ChatGateVerdict = withContext(Dispatchers.IO) {
        try {
            val tokenManager = TokenManager.getInstance(app)
            val liveToken = tokenManager.getToken()
            val liveUserId = tokenManager.getUserId()
            if (!BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = liveToken,
                    liveUserId = liveUserId,
                )
            ) {
                return@withContext ChatGateVerdict.Rejected("session_changed")
            }
            // 密聊：拒绝
            if (app.database.chatDao().isSecretChat(chatId)) {
                return@withContext ChatGateVerdict.Rejected("secret_chat")
            }
            // 会话 PIN 锁：磁盘有锁即拒绝（后台无法验证 PIN）
            if (app.database.chatLockDao().get(chatId) != null) {
                return@withContext ChatGateVerdict.Rejected("chat_locked")
            }
            ChatGateVerdict.Allowed
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ChatGateVerdict.Rejected("db_error")
        }
    }

    /** 会话 PIN 锁是否命中进程级解锁缓存（纯内存预检，不触库） */
    fun isProcessUnlocked(chatId: String): Boolean = ChatLockSession.isUnlocked(chatId)

    // ---- 去重 ----
    /**
     * 同账号同会话同文本在 [DEDUPE_WINDOW_MS] 内只允许发送一次（防止系统重复投递 RemoteInput）。
     * 8.49 修复：纯检查不记账——发送成功后由调用方 [rememberSent] 记键。此前发送前就记键，
     * 发送路径异常时用户 5 秒内的显式重试被误判为系统重复投递而静默丢弃。
     * @return true 表示应丢弃本次（重复）
     */
    fun shouldSuppressDuplicate(context: Context, ownerUserId: String, chatId: String, text: String): Boolean {
        val key = dedupeKey(ownerUserId, chatId, text)
        val prefs = prefs(context)
        val now = SystemClock.elapsedRealtime()
        val last = prefs.getLong(KEY_DEDUPE_PREFIX + key, 0L)
        return now - last < DEDUPE_WINDOW_MS
    }

    fun rememberSent(context: Context, ownerUserId: String, chatId: String, text: String) {
        rememberSent(context, dedupeKey(ownerUserId, chatId, text), SystemClock.elapsedRealtime())
    }

    fun sanitizeReplyText(raw: String?): String =
        raw?.trim().orEmpty().let { if (it.length > ConversationWidgetContract.MAX_REPLY_LENGTH) it.take(ConversationWidgetContract.MAX_REPLY_LENGTH) else it }

    fun dedupeKey(ownerUserId: String, chatId: String, text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return "$ownerUserId|$chatId|$digest"
    }

    private fun rememberSent(context: Context, key: String, now: Long) {
        val prefs = prefs(context)
        val editor = prefs.edit().putLong(KEY_DEDUPE_PREFIX + key, now)
        // 裁剪过期键，避免无限增长
        val entries = prefs.all.filterKeys { it.startsWith(KEY_DEDUPE_PREFIX) }
        if (entries.size > DEDUPE_MAX_KEYS) {
            val stale = entries.entries
                .sortedBy { it.value as? Long ?: 0L }
                .take(entries.size - DEDUPE_MAX_KEYS)
            stale.forEach { editor.remove(it.key) }
        }
        editor.apply()
    }

    private fun userId(ctx: Context): String =
        TokenManager.getInstance(ctx).getUserId().orEmpty()

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val KEY_DEDUPE_PREFIX = "dedupe:"
}

/** 按账号隔离的偏好键：<base>:<userId>，与 AccountIsolationPolicy 约定一致 */
private fun AccountIsolationKey(base: String, userId: String): String = "$base:$userId"

/** 同步门禁结果 */
sealed class SyncVerdict {
    data class Allowed(val text: String, val ownerUserId: String) : SyncVerdict()
    data class Rejected(val reason: String) : SyncVerdict()
}

/** DB 级门禁结果 */
sealed class ChatGateVerdict {
    data object Allowed : ChatGateVerdict()
    data class Rejected(val reason: String) : ChatGateVerdict()
}
