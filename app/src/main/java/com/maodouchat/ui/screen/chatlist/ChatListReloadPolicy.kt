package com.maodouchat.ui.screen.chatlist

/** 聊天列表刷新策略：各触发源如何经 getChats 刷新（重连静默、群修订合并）。 */
object ChatListReloadPolicy {

    /** Default coalesce window for bursty WS mutations. */
    const val DEFAULT_DEBOUNCE_MS: Long = 400L

    /** WS flaps: still silent (no isLoading) but coalesce getChats. */
    const val RECONNECT_DEBOUNCE_MS: Long = 800L

    fun modeFor(trigger: Trigger): Mode = when (trigger) {
        Trigger.USER_REFRESH, Trigger.INITIAL -> Mode.IMMEDIATE_VISIBLE
        Trigger.RECONNECT, Trigger.FOREGROUND -> Mode.IMMEDIATE_SILENT
        Trigger.GROUP_REVISION -> Mode.DEBOUNCED_SILENT
    }

    fun shouldShowLoading(mode: Mode): Boolean = mode == Mode.IMMEDIATE_VISIBLE

    fun debounceMs(mode: Mode, trigger: Trigger? = null): Long =
        when {
            trigger == Trigger.RECONNECT -> RECONNECT_DEBOUNCE_MS
            mode == Mode.DEBOUNCED_SILENT -> DEFAULT_DEBOUNCE_MS
            else -> 0L
        }
}
