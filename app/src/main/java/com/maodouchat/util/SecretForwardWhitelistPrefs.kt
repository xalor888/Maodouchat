package com.maodouchat.util

import android.content.Context

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
    private val switch = AccountFeatureSwitch("secret_forward_whitelist")

    fun isEnabled(context: Context): Boolean = switch.isEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) = switch.setEnabled(context, enabled)

    fun isUserSet(context: Context): Boolean = switch.isUserSet(context)

    fun applyServerDefault(context: Context, enabled: Boolean) = switch.applyServerDefault(context, enabled)
    private const val KEY_WHITELIST = "whitelist"

    /** 白名单目标 id 集合（会话/用户 id）。 */
    fun whitelist(context: Context): Set<String> {
        val userId = switch.userId(context) ?: return emptySet()
        return switch.prefs(context).getStringSet(switch.key(KEY_WHITELIST, userId), emptySet())
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun setWhitelist(context: Context, targets: Set<String>) {
        val userId = switch.userId(context) ?: return
        switch.prefs(context).edit()
            .putStringSet(switch.key(KEY_WHITELIST, userId), targets.map { it.trim() }.filter { it.isNotBlank() }.toSet())
            .apply()
    }

    fun isForwardAllowed(context: Context, targetId: String): Boolean {
        if (targetId.isBlank()) return false
        if (!isEnabled(context)) return true
        val allow = whitelist(context)
        // 白名单为空 = 完全禁止；非空则必须命中
        return allow.isNotEmpty() && targetId in allow
    }
}
