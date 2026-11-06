package com.maodouchat.push

import android.content.Context
import androidx.core.content.edit
import com.maodouchat.core.model.PushKeepAlivePolicy

/**
 * 后台推送保活模式偏好。
 *
 * 模式：
 * - [PushKeepAlivePolicy.MODE_OFF]        关闭
 * - [PushKeepAlivePolicy.MODE_FOREGROUND] 前台保活：dataSync 前台服务 + WakeLock/WifiLock + 网络变化重连
 *                     + START_STICKY + 守护服务互拉
 * - [PushKeepAlivePolicy.MODE_MEDIA]      **遗留偏好键**（仍可读/可写以兼容旧设置）；运行时由
 *                     [PushKeepAlivePolicy.effectiveMode] 归一为 [PushKeepAlivePolicy.MODE_FOREGROUND]，
 *                     不再挂 MediaSession / 无声音频
 * - [PushKeepAlivePolicy.MODE_CALL]       **遗留偏好键**；运行时同样归一为前台 dataSync，不再挂合成假来电
 */
object PushKeepAliveModeStore {
    private const val PREFS = "maodouchat_push_keepalive"
    private const val KEY_MODE = "keepalive_mode"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 9.4xx：保活默认开启（前台保活模式），无关闭入口。 */
    fun mode(context: Context): String {
        val m = prefs(context).getString(KEY_MODE, PushKeepAlivePolicy.MODE_FOREGROUND) ?: PushKeepAlivePolicy.MODE_FOREGROUND
        return if (m in PushKeepAlivePolicy.ALL_MODES) m else PushKeepAlivePolicy.MODE_FOREGROUND
    }

    /** 运行时实际模式（legacy media/call → foreground）。 */
    fun effectiveMode(context: Context): String =
        PushKeepAlivePolicy.effectiveMode(mode(context))

    fun setMode(context: Context, mode: String) {
        prefs(context).edit { putString(KEY_MODE, if (mode in PushKeepAlivePolicy.ALL_MODES) mode else PushKeepAlivePolicy.MODE_FOREGROUND) }
        PushKeepAlive.applyMode(context)
    }

    /** PushKeepAlivePolicy.MODE_OFF 或非法模式视为关闭，避免 logout/stop 后 daemon 仍复活 FGS。 */
    fun isEnabled(context: Context): Boolean = PushKeepAlivePolicy.isEnabled(mode(context))

    /** @deprecated 媒体伪装已退役；恒为 false。 */
    fun wantsMedia(context: Context): Boolean =
        PushKeepAlivePolicy.wantsMediaSession(mode(context))

    /** @deprecated 假来电伪装已退役；恒为 false。 */
    fun wantsFakeCall(context: Context): Boolean =
        PushKeepAlivePolicy.wantsFakeCall(mode(context))
}
