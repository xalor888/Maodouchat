package com.maodouchat.server.repository

import java.util.concurrent.atomic.AtomicLong

// 会结束通话振铃态的终端信令；offersOnly 轮询时必须与 offer 同批交付。
internal val TERMINAL_SIGNAL_TYPES = listOf("hang-up", "busy", "reject")

// 与 Validation.ALLOWED_SIGNALING_TYPES 对齐；"ice" 仅兼容历史脏数据。
internal val ICE_SIGNAL_TYPES = listOf("ice-candidate", "ice")

// 清理节流：store/consume 每次事务都全表 DELETE 会拉长写锁持有，最多每 60s 清一次。
internal const val PURGE_MIN_INTERVAL_MS = 60_000L

internal val lastPurgeAt = AtomicLong(0L)
