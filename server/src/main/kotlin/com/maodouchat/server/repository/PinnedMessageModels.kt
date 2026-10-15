package com.maodouchat.server.repository

import com.maodouchat.server.model.PinnedMessageResponse

enum class PinResult {
    PINNED,
    UNPINNED,
    NOT_FOUND,
    FORBIDDEN,
    LIMIT,
    NOT_PINNABLE
}

data class ToggleOutcome(
    val result: PinResult,
    val pins: List<PinnedMessageResponse> = emptyList()
)
