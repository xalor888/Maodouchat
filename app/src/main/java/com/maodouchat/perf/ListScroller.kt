package com.maodouchat.perf

import android.view.Choreographer
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 会话/消息列表滚动预算（B7 性能预算：列表滚动 ≥ 55fps）。
 *
 * 解决的问题：
 * 1. 高频 `scrollToItem` 竞态：点击置顶/定位到某条消息时，快速连点会排队多个动画，
 *    每帧都在启动/取消动画，造成 jank。用 [CoalescedScroller] 合并同一帧内的重复请求。
 * 2. 长距离跳转不应播放长动画：距离超出预算时改为瞬时跳转（snap），守住帧预算。
 * 3. 滚动流畅度可观测：用 [ListFrameMeter] 按窗口统计掉帧数，输出日志辅助回归。
 *
 * 用法：
 * ```kotlin
 * val scope = rememberCoroutineScope()
 * val scroller = rememberCoalescedScroller(scope)
 * // 点击「回到底部」
 * scroller.scrollToItem(listState, index = lastIndex, animated = true)
 * ```
 */
object ListScroller {

    /** 滚动帧预算：16ms（60Hz 一帧），超出即掉帧。 */
    const val FRAME_BUDGET_MS = 16L

    /** 超过该距离（px）的跳转不做动画，避免长距离滚动长时间占帧。 */
    const val ANIMATED_SCROLL_MAX_DISTANCE = 800

    /** 合并窗口：同一窗口内的重复跳转只执行最后一次。 */
    const val COALESCE_WINDOW_MS = 16L

    /** 判断一次跳转距离是否值得播动画（会话列表点击切换时常用）。 */
    fun shouldAnimateScroll(distancePx: Int): Boolean = distancePx in 1..ANIMATED_SCROLL_MAX_DISTANCE

    /** 距离估算：目标 index 与当前可见第一个 index 的差 × 估算项高（px）。用 Long 防溢出后收敛回 Int。 */
    fun estimateDistancePx(state: LazyListState, targetIndex: Int, estimatedItemHeightPx: Int = 72): Int =
        ((targetIndex.toLong() - state.firstVisibleItemIndex.toLong()) * estimatedItemHeightPx)
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()
}

/**
 * 合并高频滚动请求的执行器。
 *
 * [scrollToItem] 会取消上一帧未完成的动画任务，并对同一帧内的连续请求去重，
 * 只保留最后一次（防止置顶/回底连点把动画任务叠成队列）。
 */
class CoalescedScroller(private val scope: CoroutineScope) {

    private var pendingJob: Job? = null

    fun scrollToItem(state: LazyListState, index: Int, animated: Boolean = true) {
        val target = index.coerceAtLeast(0)
        val lastIndex = state.layoutInfo.totalItemsCount - 1
        val clamped = target.coerceAtMost(lastIndex.coerceAtLeast(0))

        // 同帧内重复请求：直接接管上一帧任务，避免动画排队
        pendingJob?.cancel()
        pendingJob = scope.launch {
            // 目标已在首项位置：无需滚动（含列表仅 1 项等退化场景）
            if (clamped == state.firstVisibleItemIndex) {
                return@launch
            }
            if (animated && ListScroller.shouldAnimateScroll(
                    ListScroller.estimateDistancePx(state, clamped)
                )
            ) {
                state.animateScrollToItem(clamped)
            } else {
                state.requestScrollToItem(clamped)
            }
        }
    }

    /** 取消未完成的滚动任务。 */
    fun cancel() {
        pendingJob?.cancel()
        pendingJob = null
    }
}

/** 获取绑定当前 Composable 作用域的 [CoalescedScroller]（remember 一次，随重组复用）。 */
@Composable
fun rememberCoalescedScroller(): CoalescedScroller {
    val scope = rememberCoroutineScope()
    return remember(scope) { CoalescedScroller(scope) }
}

/**
 * 滚动帧率计量（jank 看板）。
 *
 * 用 Choreographer 采样相邻两帧间隔，超过 [ListScroller.FRAME_BUDGET_MS] 记一次掉帧，
 * 按 [reportEveryMillis] 窗口汇总输出。只在 [start]/[stop] 期间采样，随列表离开屏幕即可停止。
 */
class ListFrameMeter(
    private val reportEveryMillis: Long = 1_000L,
    private val onReport: ((fps: Double, droppedFrames: Int, windowMillis: Long) -> Unit)? = null
) {

    private var frameCallback: Choreographer.FrameCallback? = null
    private var lastFrameNanos = 0L
    private var frameCount = 0
    private var droppedCount = 0
    private var windowStartNanos = 0L
    private var started = false

    fun start() {
        if (started) return
        started = true
        frameCount = 0
        droppedCount = 0
        windowStartNanos = System.nanoTime()
        lastFrameNanos = 0L
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!started) return
                frameCount++
                if (lastFrameNanos != 0L) {
                    val frameMillis = (frameTimeNanos - lastFrameNanos) / 1_000_000L
                    if (frameMillis > ListScroller.FRAME_BUDGET_MS) droppedCount++
                }
                lastFrameNanos = frameTimeNanos
                val windowMillis = (frameTimeNanos - windowStartNanos) / 1_000_000L
                if (windowMillis >= reportEveryMillis) {
                    val fps = frameCount * 1_000.0 / windowMillis
                    val dropped = droppedCount
                    val window = windowMillis
                    val report = onReport ?: defaultReport
                    report(fps, dropped, window)
                    frameCount = 0
                    droppedCount = 0
                    windowStartNanos = frameTimeNanos
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        frameCallback = callback
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stop() {
        started = false
        frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frameCallback = null
    }

    private val defaultReport: (Double, Int, Long) -> Unit = { fps, dropped, window ->
        if (fps > 0.0 && fps < 55.0) {
            Log.w(
                TAG,
                "ListFrameMeter: fps=${"%.1f".format(fps)} dropped=$dropped window=${window}ms " +
                    "below 55fps budget (${ListScroller.FRAME_BUDGET_MS}ms/frame)"
            )
        }
    }

    private companion object {
        const val TAG = "ListFrameMeter"
    }
}
