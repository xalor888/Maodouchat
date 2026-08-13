package com.maodouchat.call

import android.content.Context
import com.maodouchat.network.TokenManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 全量通话记录（8.52）：本地按账号保存呼出/已接/未接通话 + 时长。
 * 与 MissedCallRecorder 互补（未接来电另写 Room 用于会话列表卡片/角标）；
 * 本 store 提供完整历史，UI 展示最近 [MAX_ENTRIES] 条。
 */
object CallLogStore {

    private const val PREFS_NAME = "call_log_store"
    private const val KEY_LOG = "call_log"
    private const val MAX_ENTRIES = 200

    enum class Direction { OUTGOING, INCOMING }

    enum class State { MISSED, ANSWERED }

    data class CallLogEntry(
        val id: String,
        val peerId: String,
        val peerName: String,
        val isVideo: Boolean,
        val direction: Direction,
        val state: State,
        val startedAt: Long,
        val durationMs: Long = 0L
    )

    fun list(context: Context, peerId: String? = null): List<CallLogEntry> {
        val userId = currentUserId(context) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(prefs(context).getString(key(userId), null) ?: return emptyList())
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val peer = obj.optString("peer").trim()
                    if (peerId != null && peer != peerId) continue
                    add(
                        CallLogEntry(
                            id = obj.optString("id"),
                            peerId = peer,
                            peerName = obj.optString("name"),
                            isVideo = obj.optBoolean("video", false),
                            direction = if (obj.optString("dir") == "out") Direction.OUTGOING else Direction.INCOMING,
                            state = if (obj.optString("state") == "missed") State.MISSED else State.ANSWERED,
                            startedAt = obj.optLong("at", 0L),
                            durationMs = obj.optLong("dur", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList()).sortedByDescending { it.startedAt }
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
        runCatching {
            val arr = JSONArray(prefs(context).getString(key(liveUserId), null) ?: "[]")
            val next = JSONArray()
            var replaced = false
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optString("id") == entry.id) {
                    next.put(encode(entry))
                    replaced = true
                } else {
                    next.put(obj)
                }
            }
            if (!replaced) next.put(encode(entry))
            // 截断到最近 MAX_ENTRIES：按 startedAt 降序裁剪，避免乱序写入（晚写旧记录）
            // 导致按插入序 remove(0) 裁掉较新记录。
            val trimmed = JSONArray()
            (0 until next.length())
                .map { next.getJSONObject(it) }
                .sortedByDescending { it.optLong("at", 0L) }
                .take(MAX_ENTRIES)
                .forEach { trimmed.put(it) }
            prefs(context).edit().putString(key(liveUserId), trimmed.toString()).apply()
        }
    }

    fun clearForUser(context: Context, userId: String) {
        if (userId.isBlank()) return
        prefs(context).edit().remove(key(userId)).apply()
    }

    /** 清空当前账号通话记录。 */
    fun clear(context: Context) {
        currentUserId(context)?.takeIf { it.isNotBlank() }?.let { uid ->
            prefs(context).edit().remove(key(uid)).apply()
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
        var removed = false
        runCatching {
            val arr = JSONArray(prefs(context).getString(key(userId), null) ?: "[]")
            val next = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optString("id") != entryId) next.put(obj) else removed = true
            }
            if (removed) {
                prefs(context).edit().putString(key(userId), next.toString()).apply()
            }
        }
        return removed
    }

    private fun encode(entry: CallLogEntry): JSONObject =
        JSONObject()
            .put("id", entry.id)
            .put("peer", entry.peerId)
            .put("name", entry.peerName)
            .put("video", entry.isVideo)
            .put("dir", if (entry.direction == Direction.OUTGOING) "out" else "in")
            .put("state", if (entry.state == State.MISSED) "missed" else "answered")
            .put("at", entry.startedAt)
            .put("dur", entry.durationMs)

    private fun currentUserId(context: Context): String? =
        TokenManager.getInstance(context.applicationContext).getUserId()?.takeIf { it.isNotBlank() }

    private fun prefs(context: Context): android.content.SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(userId: String): String = "${KEY_LOG}_$userId"
}
