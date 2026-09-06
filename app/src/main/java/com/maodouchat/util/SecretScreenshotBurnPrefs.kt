package com.maodouchat.util

import android.content.Context

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
    private val switch = AccountFeatureSwitch("secret_screenshot_burn")

    fun isEnabled(context: Context): Boolean = switch.isEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) = switch.setEnabled(context, enabled)

    fun isUserSet(context: Context): Boolean = switch.isUserSet(context)

    fun applyServerDefault(context: Context, enabled: Boolean) = switch.applyServerDefault(context, enabled)
    private const val KEY_PURGE_MEDIA = "purge_media"

    /** 截屏即焚时是否连本地解密缓存媒体一并清除（默认是）。 */
    fun shouldPurgeMedia(context: Context): Boolean {
        val userId = switch.userId(context) ?: return true
        return switch.prefs(context).getBoolean(switch.key(KEY_PURGE_MEDIA, userId), true)
    }

    fun setPurgeMedia(context: Context, purge: Boolean) {
        val userId = switch.userId(context) ?: return
        switch.prefs(context).edit().putBoolean(switch.key(KEY_PURGE_MEDIA, userId), purge).apply()
    }
}
