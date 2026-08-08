package com.maodouchat.util

import android.content.Context
import com.maodouchat.network.TokenManager

/**
 * 密聊转发白名单开关（B2 surface · 转发白名单，health 名 fwlz）。
 *
 * 开启后，密聊消息只允许转发到白名单内的会话（群聊 id / 用户 id 列表）；
 * 白名单为空时退化为「完全禁止转发」，与既有 `secret_forward_block_enabled` 一致。
 *
 * 账号隔离，默认开；仅本机生效，服务端不接触密聊明文。
 */
object SecretForwardWhitelistPrefs {
    private const val PREFS = "secret_forward_whitelist"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_USER_SET = "user_set"
    private const val KEY_WHITELIST = "whitelist"

    fun isEnabled(context: Context): Boolean {
        val userId = userId(context) ?: return true
        return prefs(context).getBoolean(key(KEY_ENABLED, userId), true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val userId = userId(context) ?: return
        prefs(context).edit()
            .putBoolean(key(KEY_ENABLED, userId), enabled)
            .putBoolean(key(KEY_USER_SET, userId), true)
            .apply()
    }

    /** 用户是否显式设置过该开关（设置页写入）；未设置时接受服务端默认值。 */
    fun isUserSet(context: Context): Boolean {
        val userId = userId(context) ?: return false
        return prefs(context).contains(key(KEY_USER_SET, userId))
    }

    /** 服务端下发默认值：仅当用户从未显式设置过时生效（本地开关优先）。 */
    fun applyServerDefault(context: Context, enabled: Boolean) {
        val userId = userId(context) ?: return
        if (isUserSet(context)) return
        prefs(context).edit().putBoolean(key(KEY_ENABLED, userId), enabled).apply()
    }

    /** 白名单目标 id 集合（会话/用户 id）。 */
    fun whitelist(context: Context): Set<String> {
        val userId = userId(context) ?: return emptySet()
        return prefs(context).getStringSet(key(KEY_WHITELIST, userId), emptySet())
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun setWhitelist(context: Context, targets: Set<String>) {
        val userId = userId(context) ?: return
        prefs(context).edit()
            .putStringSet(key(KEY_WHITELIST, userId), targets.map { it.trim() }.filter { it.isNotBlank() }.toSet())
            .apply()
    }

    fun isForwardAllowed(context: Context, targetId: String): Boolean {
        if (targetId.isBlank()) return false
        if (!isEnabled(context)) return true
        val allow = whitelist(context)
        // 白名单为空 = 完全禁止；非空则必须命中
        return allow.isNotEmpty() && targetId in allow
    }

    private fun userId(context: Context): String? =
        TokenManager.getInstance(context).getUserId()?.takeIf { it.isNotBlank() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(base: String, userId: String) = "$base:$userId"
}
