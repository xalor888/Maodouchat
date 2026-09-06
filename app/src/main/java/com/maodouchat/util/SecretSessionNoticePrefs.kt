package com.maodouchat.util

import android.content.Context

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
    private val switch = AccountFeatureSwitch("secret_session_notice")

    fun isEnabled(context: Context): Boolean = switch.isEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) = switch.setEnabled(context, enabled)

    fun isUserSet(context: Context): Boolean = switch.isUserSet(context)

    fun applyServerDefault(context: Context, enabled: Boolean) = switch.applyServerDefault(context, enabled)
    private const val KEY_SHOW_PEER_NOTICE = "show_peer_notice"

    /** 是否展示对端密聊徽标（需要双方都开启密聊，由接入方传入对端状态）。 */
    fun shouldShowPeerNotice(context: Context, peerSecretEnabled: Boolean): Boolean {
        if (!isEnabled(context)) return false
        return peerSecretEnabled && showPeerNotice(context)
    }

    fun showPeerNotice(context: Context): Boolean {
        val userId = switch.userId(context) ?: return true
        return switch.prefs(context).getBoolean(switch.key(KEY_SHOW_PEER_NOTICE, userId), true)
    }

    fun setShowPeerNotice(context: Context, show: Boolean) {
        val userId = switch.userId(context) ?: return
        switch.prefs(context).edit().putBoolean(switch.key(KEY_SHOW_PEER_NOTICE, userId), show).apply()
    }
}
