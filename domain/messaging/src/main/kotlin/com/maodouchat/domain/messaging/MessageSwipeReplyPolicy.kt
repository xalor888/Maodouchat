package com.maodouchat.domain.messaging

import kotlin.math.abs

// 左滑回复手势的进度/图标缩放数学；dp→px 换算与动画仍在 app 侧。
object MessageSwipeReplyPolicy {

    fun replyProgress(offsetPx: Float, thresholdPx: Float): Float =
        (abs(offsetPx) / thresholdPx).coerceIn(0f, 1f)

    fun replyIconScale(offsetPx: Float, thresholdPx: Float): Float =
        if (abs(offsetPx) >= thresholdPx) 1.12f else 0.7f + 0.3f * replyProgress(offsetPx, thresholdPx)

    fun isReplyIconVisible(progress: Float, gestureEligible: Boolean): Boolean =
        progress > 0.05f && gestureEligible

    fun clampSwipeOffset(offsetPx: Float, maxSwipePx: Float): Float =
        offsetPx.coerceIn(-maxSwipePx, 0f)
}
