package com.maodouchat.core.model

/**
 * 保活启停纯门闩：登出 / MODE_OFF / 无 token 时禁止 daemon 复活与后台 FGS。
 *
 * 历史 MODE_MEDIA / MODE_CALL 偏好仍可读（兼容旧设置），但运行时一律归一为
 * dataSync 前台保活——不再挂 MediaSession 或合成 Telecom 假来电（Android 后台限制）。
 */
object PushKeepAlivePolicy {

    const val MODE_OFF = "off"
    const val MODE_FOREGROUND = "foreground"

    /** 遗留偏好键：仍可读，运行时归一为 [MODE_FOREGROUND]。 */
    const val MODE_MEDIA = "media"

    /** 遗留偏好键：仍可读，运行时归一为 [MODE_FOREGROUND]。 */
    const val MODE_CALL = "call"

    val ALL_MODES = listOf(MODE_OFF, MODE_FOREGROUND, MODE_MEDIA, MODE_CALL)

    /** UI / 新写入只应暴露仍有效的模式。 */
    val SUPPORTED_RUNTIME_MODES = listOf(MODE_OFF, MODE_FOREGROUND)

    fun isEnabled(mode: String): Boolean =
        mode != MODE_OFF && mode in ALL_MODES

    /**
     * 偏好字符串 → 实际执行模式。
     * media/call 遗留值映射为 foreground；非法值视为 off。
     */
    fun effectiveMode(storedMode: String): String = when (storedMode) {
        MODE_OFF -> MODE_OFF
        MODE_FOREGROUND,
        MODE_MEDIA,
        MODE_CALL -> MODE_FOREGROUND
        else -> MODE_OFF
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
