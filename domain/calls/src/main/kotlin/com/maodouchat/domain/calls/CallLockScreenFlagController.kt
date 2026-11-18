package com.maodouchat.domain.calls

/**
 * P08：来电锁屏旗标轮询。
 *
 * Activity 只提供「是否需要旗标」查询与「落旗」动作；本类负责 onStart 轮询 / onStop 收敛语义。
 */
class CallLockScreenFlagController(
    private val flagsNeeded: () -> Boolean,
    private val applyFlags: (enabled: Boolean) -> Unit,
    private val delayMs: Long = 500L,
) {
    fun onHostStartedTick(): Boolean {
        applyFlags(flagsNeeded())
        return true
    }

    fun onHostStopped() {
        applyFlags(flagsNeeded())
    }

    fun onHostDestroyed() {
        applyFlags(false)
    }
}
