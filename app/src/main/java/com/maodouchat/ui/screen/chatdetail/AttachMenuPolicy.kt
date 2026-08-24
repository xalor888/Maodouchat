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

object AttachMenuPolicy {

    fun items(
        isGroup: Boolean,
        isChannel: Boolean,
        viewOnceEnabled: Boolean,
        contactCardEnabled: Boolean,
        nudgeEnabled: Boolean,
        aiEnabled: Boolean = false,
    ): List<AttachMenuKind> {
        if (isChannel) {
            return buildList {
                addAll(
                    listOf(
                        AttachMenuKind.IMAGE,
                        AttachMenuKind.PASTE,
                        AttachMenuKind.VIDEO,
                        AttachMenuKind.FILE,
                        AttachMenuKind.SCHEDULE,
                    )
                )
                if (aiEnabled) add(AttachMenuKind.AI)
                add(AttachMenuKind.SILENT)
            }
        }
        val items = mutableListOf(
            AttachMenuKind.IMAGE,
        )
        if (!isGroup && viewOnceEnabled) items += AttachMenuKind.VIEW_ONCE
        items += listOf(
            AttachMenuKind.SPOILER,
            AttachMenuKind.PASTE,
            AttachMenuKind.VIDEO,
            AttachMenuKind.FILE,
            AttachMenuKind.VOICE,
            AttachMenuKind.LOCATION,
        )
        if (!isGroup) items += AttachMenuKind.LIVE_LOCATION
        items += listOf(
            AttachMenuKind.SCHEDULE,
            AttachMenuKind.QUICK_PHRASES,
        )
        if (contactCardEnabled) items += AttachMenuKind.CONTACT_CARD
        if (aiEnabled) items += AttachMenuKind.AI
        items += AttachMenuKind.SILENT
        // Nudge/poke is overflow-only (chat overflow) so the plus menu stays media-first.
        if (nudgeEnabled) {
            // Intentionally not added to the attach sheet.
        }
        // Groups/channels must never surface 1:1-only tiles even if a caller
        // flips flags; IMAGE stays.
        if (isGroup) {
            items.removeAll { it == AttachMenuKind.VIEW_ONCE || it == AttachMenuKind.LIVE_LOCATION || it == AttachMenuKind.NUDGE }
        }
        return items
    }
}
