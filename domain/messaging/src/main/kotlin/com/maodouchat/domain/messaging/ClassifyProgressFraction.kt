package com.maodouchat.domain.messaging

// 分类进度条占比：count / max(1, maxCount)，钳制到 [0, 1]。
fun classifyProgressFraction(count: Int, maxCount: Int): Float =
    (count.toFloat() / (maxCount.takeIf { it > 0 } ?: 1).toFloat()).coerceIn(0f, 1f)
