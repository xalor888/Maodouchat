package com.maodouchat.ui.screen.chatdetail

/**
 * Attach-sheet contents by chat type. 1:1-only actions must not appear in groups/channels.
 */
enum class AttachMenuKind {
    IMAGE,
    VIEW_ONCE,
    SPOILER,
    PASTE,
    VIDEO,
    FILE,
    VOICE,
    LOCATION,
    LIVE_LOCATION,
    SCHEDULE,
    QUICK_PHRASES,
    CONTACT_CARD,
    AI,
    SILENT,
    NUDGE,
}
