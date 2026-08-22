package com.maodouchat.push

import android.content.Context

/**
 * 9.3xx：后台推送保活模式偏好（参考 Ideaura 的保活栈）。
 *
 * 模式：
 * - [MODE_OFF]        关闭（默认，零副作用）
 * - [MODE_FOREGROUND] 前台保活：dataSync 前台服务 + WakeLock/WifiLock + 网络变化重连
 *                     + START_STICKY + 守护服务互拉（Ideaura KeepAliveService 同款）
 * - [MODE_MEDIA]      音乐播放器模式：在前台保活基础上挂 MediaSession「播放中」状态 +
 *                     无声音频循环 + 媒体样式常驻通知（系统对媒体会话有额外豁免）
 * - [MODE_CALL]       来电模式（Ideaura 最强手段）：注册自管理 PhoneAccount 并挂一个
 *                     onHold 的「假来电」，进程获得通话级优先级；不响铃、不弹通话 UI；
 *                     真实通话开始/结束时自动让位（移除假来电→通话结束→恢复）
 */
object PushKeepAliveModeStore {
    const val MODE_OFF = "off"
    const val MODE_FOREGROUND = "foreground"
    const val MODE_MEDIA = "media"
    const val MODE_CALL = "call"

    val ALL_MODES = listOf(MODE_OFF, MODE_FOREGROUND, MODE_MEDIA, MODE_CALL)

    private const val PREFS = "maodouchat_push_keepalive"
    private const val KEY_MODE = "keepalive_mode"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 9.4xx：保活默认开启（前台保活模式），无关闭入口。 */
    fun mode(context: Context): String {
        val m = prefs(context).getString(KEY_MODE, MODE_FOREGROUND) ?: MODE_FOREGROUND
        return if (m in ALL_MODES) m else MODE_FOREGROUND
    }

    fun setMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_MODE, if (mode in ALL_MODES) mode else MODE_FOREGROUND).apply()
        PushKeepAlive.applyMode(context)
    }

    /** MODE_OFF 或非法模式视为关闭，避免 logout/stop 后 daemon 仍复活 FGS。 */
    fun isEnabled(context: Context): Boolean = PushKeepAlivePolicy.isEnabled(mode(context))

    /** 当前模式是否要求挂 MediaSession（音乐播放器伪装）。 */
    fun wantsMedia(context: Context): Boolean = mode(context) == MODE_MEDIA

    /** 当前模式是否要求挂假来电。 */
    fun wantsFakeCall(context: Context): Boolean = mode(context) == MODE_CALL
}
