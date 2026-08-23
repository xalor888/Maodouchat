package com.maodouchat.util

import android.content.Context

/**
 * 本地保存服务端下发的历史推送 HMAC 密钥（/api/push/verify-key）。
 * FCM 已移除；密钥仅兼容旧会话缓存，不再用于离线投递。
 */
object PushVerifyPrefs {
    private const val PREFS = "maodou_push_verify"
    private const val KEY = "push_hmac_key"

    fun getKey(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            // 排除字面 "null"：JSONObject.optString 缺失键返回 "null" 字符串，曾导致垃圾 key 写入
            ?.takeIf { it.isNotBlank() && it != "null" }
    }

    fun setKey(context: Context, key: String) {
        if (key.isBlank() || key == "null") return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, key)
            .apply()
    }

    /** 清除本地 key（服务端明确未配置密钥时调用 → 推送校验 fail-open）。 */
    fun clearKey(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .apply()
    }
}
