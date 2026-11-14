package com.maodouchat.domain.calls

/** 未接来电保留 28 天；列表只观测该窗口。 */
const val MISSED_CALL_RETENTION_MS: Long = 28L * 24L * 60L * 60L * 1000L

fun missedCallRetentionCutoffMs(nowMs: Long = System.currentTimeMillis()): Long =
    nowMs - MISSED_CALL_RETENTION_MS
