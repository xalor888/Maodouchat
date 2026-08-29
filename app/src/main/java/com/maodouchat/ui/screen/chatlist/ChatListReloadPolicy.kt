package com.maodouchat.ui.screen.chatlist

/**
 * Decide how lifecycle and group-membership triggers refresh the chat list via getChats.
 * Group-revision bursts are coalesced. Reconnect and foreground stay silent
 * (no isLoading shimmer); reconnect additionally waits [RECONNECT_DEBOUNCE_MS].
 * Pull-to-refresh stays visible.
 */
object ChatListReloadPolicy {

    enum class Trigger {
        /** User pull-to-refresh or explicit refresh(). */
        USER_REFRESH,
        /** Cold start / first load. */
        INITIAL,
        /** WebSocket Connected after reconnect. */
        RECONNECT,
        /** Activity ON_RESUME while already logged in. */
        FOREGROUND,
        /** Group membership revision burst. */
        GROUP_REVISION,
    }

    enum class Mode {
        /** Fire getChats now; may show list loading indicator. */
        IMMEDIATE_VISIBLE,
        /** Fire getChats now without flipping isLoading (background). */
        IMMEDIATE_SILENT,
        /** Coalesce with a short delay; silent getChats. */
        DEBOUNCED_SILENT
    }

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
