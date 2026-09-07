package com.maodouchat.push

import android.content.Context

/**
 * 后台推送保活模式偏好。
 *
 * 模式：
 * - [MODE_OFF]        关闭
 * - [MODE_FOREGROUND] 前台保活：dataSync 前台服务 + WakeLock/WifiLock + 网络变化重连
 *                     + START_STICKY + 守护服务互拉
 * - [MODE_MEDIA]      **遗留偏好键**（仍可读/可写以兼容旧设置）；运行时由
 *                     [PushKeepAlivePolicy.effectiveMode] 归一为 [MODE_FOREGROUND]，
 *                     不再挂 MediaSession / 无声音频
 * - [MODE_CALL]       **遗留偏好键**；运行时同样归一为前台 dataSync，不再挂合成假来电
 */
object PushKeepAliveModeStore {
    const val MODE_OFF = "off"
    const val MODE_FOREGROUND = "foreground"
    const val MODE_MEDIA = "media"
    const val MODE_CALL = "call"

    val ALL_MODES = listOf(MODE_OFF, MODE_FOREGROUND, MODE_MEDIA, MODE_CALL)

    /** UI / 新写入只应暴露仍有效的模式。 */
    val SUPPORTED_RUNTIME_MODES = listOf(MODE_OFF, MODE_FOREGROUND)

    private const val PREFS = "maodouchat_push_keepalive"
    private const val KEY_MODE = "keepalive_mode"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 9.4xx：保活默认开启（前台保活模式），无关闭入口。 */
    fun mode(context: Context): String {
        val m = prefs(context).getString(KEY_MODE, MODE_FOREGROUND) ?: MODE_FOREGROUND
        return if (m in ALL_MODES) m else MODE_FOREGROUND
    }

    /** 运行时实际模式（legacy media/call → foreground）。 */
    fun effectiveMode(context: Context): String =
        PushKeepAlivePolicy.effectiveMode(mode(context))

    fun setMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_MODE, if (mode in ALL_MODES) mode else MODE_FOREGROUND).apply()
        PushKeepAlive.applyMode(context)
    }

    /** MODE_OFF 或非法模式视为关闭，避免 logout/stop 后 daemon 仍复活 FGS。 */
    fun isEnabled(context: Context): Boolean = PushKeepAlivePolicy.isEnabled(mode(context))

    /** @deprecated 媒体伪装已退役；恒为 false。 */
    fun wantsMedia(context: Context): Boolean =
        PushKeepAlivePolicy.wantsMediaSession(mode(context))

    /** @deprecated 假来电伪装已退役；恒为 false。 */
    fun wantsFakeCall(context: Context): Boolean =
        PushKeepAlivePolicy.wantsFakeCall(mode(context))
}
