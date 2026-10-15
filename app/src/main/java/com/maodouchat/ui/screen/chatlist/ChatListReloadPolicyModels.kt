package com.maodouchat.ui.screen.chatlist

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
