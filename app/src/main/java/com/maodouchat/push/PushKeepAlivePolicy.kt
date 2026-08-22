package com.maodouchat.push

/**
 * 保活启停纯门闩：登出 / MODE_OFF / 无 token 时禁止 daemon 复活与后台 FGS。
 */
object PushKeepAlivePolicy {

    fun isEnabled(mode: String): Boolean =
        mode != PushKeepAliveModeStore.MODE_OFF && mode in PushKeepAliveModeStore.ALL_MODES

    /** 允许拉起前台保活服务。 */
    fun shouldStartService(mode: String, hasToken: Boolean): Boolean =
        isEnabled(mode) && hasToken

    /** 服务被系统销毁时是否允许拉守护进程互拉。 */
    fun shouldResurrectDaemon(mode: String, hasToken: Boolean): Boolean =
        shouldStartService(mode, hasToken)
}
