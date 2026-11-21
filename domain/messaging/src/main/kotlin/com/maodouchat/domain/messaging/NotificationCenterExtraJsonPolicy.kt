package com.maodouchat.domain.messaging

import org.json.JSONObject

/** 通知中心条目 extra 映射与 JSON 字符串互转（键值均为 String）。 */
object NotificationCenterExtraJsonPolicy {
    fun encode(extra: Map<String, String>): String {
        val json = JSONObject()
        extra.forEach { (k, v) -> json.put(k, v) }
        return json.toString()
    }

    fun decode(extraJson: String): Map<String, String> {
        val json = runCatching { JSONObject(extraJson) }.getOrNull() ?: return emptyMap()
        return json.keys().asSequence().associateWith { json.optString(it) }
    }

    fun displayCount(count: Int): Int = count.coerceAtLeast(1)
}
