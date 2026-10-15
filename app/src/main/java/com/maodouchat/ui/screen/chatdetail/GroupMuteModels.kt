package com.maodouchat.ui.screen.chatdetail

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
