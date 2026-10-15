package com.maodouchat.ui.screen.chatdetail

/**
 * Group member mute duration presets (aligned with admin mute catalog spirit).
 * Pure; UI maps [Preset] to localized labels.
 */
object GroupMutePolicy {
    /** Server enforces max ~30 days; keep presets under that. */
    const val MAX_MUTE_MS = 30L * 24L * 60L * 60L * 1_000L

    val presets: List<Preset> = Preset.entries

    fun mutedUntil(nowMs: Long, preset: Preset): Long {
        val until = nowMs + preset.durationMs
        val maxUntil = nowMs + MAX_MUTE_MS
        return until.coerceAtMost(maxUntil)
    }

    fun isActiveMute(mutedUntil: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        mutedUntil > nowMs

    fun clearMuteUntil(): Long = 0L
}
