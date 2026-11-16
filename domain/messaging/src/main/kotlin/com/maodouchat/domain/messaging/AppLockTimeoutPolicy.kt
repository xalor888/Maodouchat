package com.maodouchat.domain.messaging

// 应用锁超时白名单（分钟）；非法值与原 else 一致回落 5 分钟。
fun normalizeAppLockTimeout(timeoutMinutes: Long): Long =
    when (timeoutMinutes) {
        1L, 2L, 5L, 10L, 15L, 30L, 60L, 120L, 240L, 360L -> timeoutMinutes
        else -> 5L
    }
