package com.maodouchat.util

import org.json.JSONArray

/**
 * SharedPreferences JSON 字符串列表编解码（P09/UI 偏好）。
 *
 * 收敛 GifSearch/QuickPhrase/EmojiRecent/Sticker 四处逐字相同的
 * 私有 `encodeList`/`decodeList`（空串过滤 + 损坏输入回空列表）。
 */
object PrefsJsonLists {

    fun encode(items: List<String>): String {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        return arr.toString()
    }

    fun decode(raw: String): List<String> = runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "").trim()
                if (v.isNotEmpty()) add(v)
            }
        }
    }.getOrDefault(emptyList())
}
