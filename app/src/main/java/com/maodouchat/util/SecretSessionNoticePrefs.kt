package com.maodouchat.util

import android.content.Context
import com.maodouchat.network.TokenManager

/**
 * 密聊双向提示开关（B2 surface · 双向密聊提示，health 名 sntz）。
 *
 * 开启后，只有当「本机」与「对端」双方都开启了密聊时，才会展示密聊气泡提示与
 * 对端防泄漏徽标（防止单向密聊给用户虚假安全感）；单边密聊时仅提示「仅本机防护」。
 *
 * 账号隔离，默认开；仅本机生效，服务端不接触密聊明文。
 */
object SecretSessionNoticePrefs {
    private const val PREFS = "secret_session_notice"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_USER_SET = "user_set"
    private const val KEY_SHOW_PEER_NOTICE = "show_peer_notice"

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

    /** 是否展示对端密聊徽标（需要双方都开启密聊，由接入方传入对端状态）。 */
    fun shouldShowPeerNotice(context: Context, peerSecretEnabled: Boolean): Boolean {
        if (!isEnabled(context)) return false
        return peerSecretEnabled && showPeerNotice(context)
    }

    fun showPeerNotice(context: Context): Boolean {
        val userId = userId(context) ?: return true
        return prefs(context).getBoolean(key(KEY_SHOW_PEER_NOTICE, userId), true)
    }

    fun setShowPeerNotice(context: Context, show: Boolean) {
        val userId = userId(context) ?: return
        prefs(context).edit().putBoolean(key(KEY_SHOW_PEER_NOTICE, userId), show).apply()
    }

    private fun userId(context: Context): String? =
        TokenManager.getInstance(context).getUserId()?.takeIf { it.isNotBlank() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(base: String, userId: String) = "$base:$userId"
}
