package com.maodouchat.ui.screen.chatdetail

/**
 * Group member mute duration presets (aligned with admin mute catalog spirit).
 * Pure; UI maps [Preset] to localized labels.
 */
object GroupMutePolicy {
    /** Server enforces max ~30 days; keep presets under that. */
    const val MAX_MUTE_MS = 30L * 24L * 60L * 60L * 1_000L

    enum class Preset(val durationMs: Long) {
        MINUTES_5(5L * 60L * 1_000L),
        MINUTES_10(10L * 60L * 1_000L),
        MINUTES_30(30L * 60L * 1_000L),
        HOUR_1(60L * 60L * 1_000L),
        HOURS_2(2L * 60L * 60L * 1_000L),
        HOURS_3(3L * 60L * 60L * 1_000L),
        HOURS_6(6L * 60L * 60L * 1_000L),
        HOURS_8(8L * 60L * 60L * 1_000L),
        DAY_1(24L * 60L * 60L * 1_000L),
        DAYS_7(7L * 24L * 60L * 60L * 1_000L),
        DAYS_30(30L * 24L * 60L * 60L * 1_000L)
    }

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
