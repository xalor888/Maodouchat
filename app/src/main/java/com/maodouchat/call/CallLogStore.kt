package com.maodouchat.call

import android.content.Context
import androidx.core.content.edit
import com.maodouchat.core.model.CallLogEntry
import com.maodouchat.domain.calls.decodeCallLog
import com.maodouchat.domain.calls.encodeCallLog
import com.maodouchat.domain.calls.removeCallLogEntry
import com.maodouchat.domain.calls.upsertCallLog
import com.maodouchat.util.currentUserId

/**
 * 全量通话记录（8.52）：本地按账号保存呼出/已接/未接通话 + 时长。
 * 与 MissedCallRecorder 互补（未接来电另写 Room 用于会话列表卡片/角标）；
 * 本 store 提供完整历史，UI 展示最近 MAX_CALL_LOG_ENTRIES 条。
 * 编解码与增删裁剪见 :domain:calls 的 CallLogPolicy，这里只留
 * SharedPreferences 读写与账号守卫。
 */
object CallLogStore {

    private const val PREFS_NAME = "call_log_store"
    private const val KEY_LOG = "call_log"

    fun list(context: Context, peerId: String? = null): List<CallLogEntry> {
        val userId = currentUserId(context) ?: return emptyList()
        val raw = prefs(context).getString(key(userId), null) ?: return emptyList()
        val entries = decodeCallLog(raw) ?: return emptyList()
        return if (peerId == null) entries else entries.filter { it.peerId == peerId }
    }

    fun listForChat(context: Context, peerId: String): List<CallLogEntry> =
        list(context, peerId)

    /** 插入或更新（同 id 幂等：已接回写时长）。 */
    @Synchronized
    fun upsert(context: Context, entry: CallLogEntry, expectedUserId: String? = null) {
        val liveUserId = currentUserId(context)
        if (liveUserId == null) return
        // 8.52：调用方若提供期望账号（如来电记录在挂起点后重读 userId），不匹配则拒绝写入，
        // 避免 repo.insert 挂起点与 upsert 之间换号导致旧通话写进新账号 key
        if (expectedUserId != null && expectedUserId != liveUserId) return
        if (entry.id.isBlank() || entry.peerId.isBlank()) return
        // 已存 JSON 损坏时不覆盖（与原 runCatching 静默放弃一致）。
        val existing = decodeCallLog(prefs(context).getString(key(liveUserId), null) ?: "[]") ?: return
        runCatching {
            prefs(context).edit { putString(key(liveUserId), encodeCallLog(upsertCallLog(existing, entry))) }
        }
    }

    fun clearForUser(context: Context, userId: String) {
        if (userId.isBlank()) return
        prefs(context).edit { remove(key(userId)) }
    }

    /** 清空当前账号通话记录。 */
    fun clear(context: Context) {
        currentUserId(context)?.takeIf { it.isNotBlank() }?.let { uid ->
            prefs(context).edit { remove(key(uid)) }
        }
    }

    /**
     * 1.282：删除单条通话记录（按 id，仅操作本地 call-log prefs）。
     * 若该条是未接来电，调用方应同步删除 Room missed_calls（同 id）保持角标一致。
     */
    @Synchronized
    fun remove(context: Context, entryId: String): Boolean {
        if (entryId.isBlank()) return false
        val userId = currentUserId(context) ?: return false
        val existing = decodeCallLog(prefs(context).getString(key(userId), null) ?: "[]") ?: return false
        val next = removeCallLogEntry(existing, entryId)
        val removed = next.size != existing.size
        if (removed) {
            runCatching {
                prefs(context).edit { putString(key(userId), encodeCallLog(next)) }
            }
        }
        return removed
    }

    private fun prefs(context: Context): android.content.SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(userId: String): String = "${KEY_LOG}_$userId"
}
