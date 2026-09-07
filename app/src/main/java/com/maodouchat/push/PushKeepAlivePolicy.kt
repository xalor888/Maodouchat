package com.maodouchat.push

/**
 * 保活启停纯门闩：登出 / MODE_OFF / 无 token 时禁止 daemon 复活与后台 FGS。
 *
 * 历史 MODE_MEDIA / MODE_CALL 偏好仍可读（兼容旧设置），但运行时一律归一为
 * dataSync 前台保活——不再挂 MediaSession 或合成 Telecom 假来电（Android 后台限制）。
 */
object PushKeepAlivePolicy {

    fun isEnabled(mode: String): Boolean =
        mode != PushKeepAliveModeStore.MODE_OFF && mode in PushKeepAliveModeStore.ALL_MODES

    /**
     * 偏好字符串 → 实际执行模式。
     * media/call 遗留值映射为 foreground；非法值视为 off。
     */
    fun effectiveMode(storedMode: String): String = when (storedMode) {
        PushKeepAliveModeStore.MODE_OFF -> PushKeepAliveModeStore.MODE_OFF
        PushKeepAliveModeStore.MODE_FOREGROUND,
        PushKeepAliveModeStore.MODE_MEDIA,
        PushKeepAliveModeStore.MODE_CALL -> PushKeepAliveModeStore.MODE_FOREGROUND
        else -> PushKeepAliveModeStore.MODE_OFF
    }

    /** 允许拉起前台保活服务。 */
    fun shouldStartService(mode: String, hasToken: Boolean): Boolean =
        isEnabled(mode) && hasToken

    /** 服务被系统销毁时是否允许拉守护进程互拉。 */
    fun shouldResurrectDaemon(mode: String, hasToken: Boolean): Boolean =
        shouldStartService(mode, hasToken)

    /** 媒体伪装已退役：任何偏好都不得再挂 MediaSession / 无声循环。 */
    fun wantsMediaSession(storedMode: String): Boolean = false

    /** 假来电伪装已退役：任何偏好都不得再挂合成 Telecom 通话。 */
    fun wantsFakeCall(storedMode: String): Boolean = false
}
