package com.maodouchat.ui.screen.chatlist

/**
 * Decide how WS / lifecycle triggers should refresh the chat list via getChats.
 *
 * Bursty delete/revoke/group-revision events already apply local preview/tombstone
 * optimistically; full getChats is coalesced. Reconnect and explicit pull-to-refresh
 * stay immediate. Unknown-chat new messages need a row soon (immediate, silent).
 */
object ChatListReloadPolicy {

    enum class Trigger {
        /** User pull-to-refresh or explicit refresh(). */
        USER_REFRESH,
        /** Cold start / first load. */
        INITIAL,
        /** WebSocket Connected after reconnect. */
        RECONNECT,
        /** Remote delete while list is open. */
        MESSAGE_DELETED,
        /** Remote revoke while list is open. */
        MESSAGE_REVOKED,
        /** Group membership revision burst. */
        GROUP_REVISION,
        /** Incoming message for a chat not yet on the list. */
        UNKNOWN_CHAT_MESSAGE
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

    fun modeFor(trigger: Trigger): Mode = when (trigger) {
        Trigger.USER_REFRESH, Trigger.INITIAL -> Mode.IMMEDIATE_VISIBLE
        Trigger.RECONNECT -> Mode.IMMEDIATE_VISIBLE
        Trigger.UNKNOWN_CHAT_MESSAGE -> Mode.IMMEDIATE_SILENT
        Trigger.MESSAGE_DELETED, Trigger.MESSAGE_REVOKED, Trigger.GROUP_REVISION ->
            Mode.DEBOUNCED_SILENT
    }

    fun shouldShowLoading(mode: Mode): Boolean = mode == Mode.IMMEDIATE_VISIBLE

    fun debounceMs(mode: Mode): Long =
        if (mode == Mode.DEBOUNCED_SILENT) DEFAULT_DEBOUNCE_MS else 0L
}
