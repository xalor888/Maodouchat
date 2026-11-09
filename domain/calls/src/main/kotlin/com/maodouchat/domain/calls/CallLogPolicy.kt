package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallLogEntry
import com.maodouchat.core.model.Direction
import com.maodouchat.core.model.State
import org.json.JSONArray
import org.json.JSONObject

/** 通话记录保留上限：只留最近这么多条（按 startedAt 降序裁剪）。 */
const val MAX_CALL_LOG_ENTRIES = 200

fun decodeCallLogEntry(obj: JSONObject): CallLogEntry = CallLogEntry(
    id = obj.optString("id"),
    peerId = obj.optString("peer").trim(),
    peerName = obj.optString("name"),
    isVideo = obj.optBoolean("video", false),
    direction = if (obj.optString("dir") == "out") Direction.OUTGOING else Direction.INCOMING,
    state = if (obj.optString("state") == "missed") State.MISSED else State.ANSWERED,
    startedAt = obj.optLong("at", 0L),
    durationMs = obj.optLong("dur", 0L),
    isGroup = obj.optBoolean("group", false),
)

fun encodeCallLogEntry(entry: CallLogEntry): JSONObject =
    JSONObject()
        .put("id", entry.id)
        .put("peer", entry.peerId)
        .put("name", entry.peerName)
        .put("video", entry.isVideo)
        .put("dir", if (entry.direction == Direction.OUTGOING) "out" else "in")
        .put("state", if (entry.state == State.MISSED) "missed" else "answered")
        .put("at", entry.startedAt)
        .put("dur", entry.durationMs)
        .put("group", entry.isGroup)

/**
 * 解析整份通话记录 JSON（按 startedAt 降序）；损坏的 JSON 返回 null，
 * 调用方自行决定视为空还是放弃写入。
 */
fun decodeCallLog(json: String): List<CallLogEntry>? = runCatching {
    val arr = JSONArray(json)
    val out = ArrayList<CallLogEntry>(arr.length())
    for (i in 0 until arr.length()) {
        out.add(decodeCallLogEntry(arr.optJSONObject(i) ?: continue))
    }
    out.sortedByDescending { it.startedAt }
}.getOrNull()

fun encodeCallLog(entries: List<CallLogEntry>): String =
    JSONArray().apply { entries.forEach { put(encodeCallLogEntry(it)) } }.toString()

/** 插入或更新（同 id 幂等：已接回写时长），再按 startedAt 降序截断到上限。 */
fun upsertCallLog(
    entries: List<CallLogEntry>,
    entry: CallLogEntry,
    maxEntries: Int = MAX_CALL_LOG_ENTRIES,
): List<CallLogEntry> {
    val next = if (entries.any { it.id == entry.id }) {
        entries.map { if (it.id == entry.id) entry else it }
    } else {
        entries + entry
    }
    return next.sortedByDescending { it.startedAt }.take(maxEntries)
}

/** 按 id 删除一条；不存在时原样返回。 */
fun removeCallLogEntry(entries: List<CallLogEntry>, entryId: String): List<CallLogEntry> =
    entries.filter { it.id != entryId }
