package com.maodouchat.util

import android.content.Context
import com.maodouchat.network.TokenManager

/**
 * 密聊截屏即焚开关（B2 surface · 截屏即焚，health 名 burnz）。
 *
 * 开启后，检测到截屏/录屏尝试时立即焚毁该密聊会话的本地缓存媒体
 * （见 [com.maodouchat.security.ScreenshotBurnDetector]），并在界面提示，
 * 可选的 [KEY_PURGE_MEDIA] 决定是否连本地解密缓存一并清除。
 *
 * 账号隔离，默认开；仅本机生效，服务端不接触密聊明文。
 */
object SecretScreenshotBurnPrefs {
    private const val PREFS = "secret_screenshot_burn"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_USER_SET = "user_set"
    private const val KEY_PURGE_MEDIA = "purge_media"

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

    /** 截屏即焚时是否连本地解密缓存媒体一并清除（默认是）。 */
    fun shouldPurgeMedia(context: Context): Boolean {
        val userId = userId(context) ?: return true
        return prefs(context).getBoolean(key(KEY_PURGE_MEDIA, userId), true)
    }

    fun setPurgeMedia(context: Context, purge: Boolean) {
        val userId = userId(context) ?: return
        prefs(context).edit().putBoolean(key(KEY_PURGE_MEDIA, userId), purge).apply()
    }

    private fun userId(context: Context): String? =
        TokenManager.getInstance(context).getUserId()?.takeIf { it.isNotBlank() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(base: String, userId: String) = "$base:$userId"
}
