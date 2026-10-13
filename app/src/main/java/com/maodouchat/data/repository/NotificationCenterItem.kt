package com.maodouchat.data.repository

import org.json.JSONObject

data class NotificationCenterItem(
    val id: String,
    val type: String,            // MESSAGE | MISSED_CALL | AI_TASK | POST_INTERACTION | FRIEND_REQUEST | GROUP_INVITE | REPORT | SECURITY
    val mergeKey: String,        // 用于合并同类同主键的通知
    val title: String,
    val subtitle: String? = null,
    val preview: String? = null,
    val deeplink: String? = null,
    val extra: Map<String, String> = emptyMap(),
    val read: Boolean = false,
    val count: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        // 注：旧 JSON 读写（toJson/fromJson）已随 Room 迁移删除；
        // 一次性 prefs 导入仍复用 fromJson（见 migrateLegacy）。
        fun fromJson(json: JSONObject): NotificationCenterItem {
            val extraJson = json.optJSONObject("extra")
            val extraMap = if (extraJson == null) emptyMap()
                else extraJson.keys().asSequence().associateWith { extraJson.optString(it) }
            return NotificationCenterItem(
                id = json.getString("id"),
                type = json.getString("type"),
                mergeKey = json.optString("mergeKey", json.optString("id", "")),
                title = json.optString("title", ""),
                subtitle = json.optString("subtitle", "").takeIf { it.isNotBlank() },
                preview = json.optString("preview", "").takeIf { it.isNotBlank() },
                deeplink = json.optString("deeplink", "").takeIf { it.isNotBlank() },
                extra = extraMap,
                read = json.optBoolean("read", false),
                count = json.optInt("count", 1).coerceAtLeast(1),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }
}
