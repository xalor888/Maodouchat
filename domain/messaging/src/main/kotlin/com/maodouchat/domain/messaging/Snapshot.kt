package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Preset

data class Snapshot(
    val enabled: Boolean = false,
    val preset: Preset = Preset.NONE,
    val customNote: String = ""
) {
    val hasMemorableContent: Boolean
        get() = enabled && (preset != Preset.NONE || customNote.isNotBlank())
}
