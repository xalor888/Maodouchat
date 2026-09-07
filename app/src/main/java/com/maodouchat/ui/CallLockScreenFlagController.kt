package com.maodouchat.ui

/**
 * P08：来电锁屏旗标轮询（从 MainActivity 抽出）。
 *
 * Activity 只提供「是否需要旗标」查询与「落旗」动作；本类负责 onStart 轮询 / onStop 收敛语义。
 */
class CallLockScreenFlagController(
    private val flagsNeeded: () -> Boolean,
    private val applyFlags: (enabled: Boolean) -> Unit,
    private val delayMs: Long = 500L,
) {
    /**
     * Host entered foreground: overwrite any stale intent-carried flags, then poll.
     * @return true while the poll loop should continue (caller sleeps [delayMs] between ticks).
     */
    fun onHostStartedTick(): Boolean {
        applyFlags(flagsNeeded())
        return true
    }

    /** Host left foreground: keep only flags still justified by a live call. */
    fun onHostStopped() {
        applyFlags(flagsNeeded())
    }

    /** Activity is destroyed: never leave lock-screen visibility for a later instance. */
    fun onHostDestroyed() {
        applyFlags(false)
    }
}
