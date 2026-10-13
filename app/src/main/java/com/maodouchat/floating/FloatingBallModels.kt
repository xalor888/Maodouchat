package com.maodouchat.floating

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * 悬浮球手势引擎（无状态单例，状态全部由调用方持有）：
 * - DOWN 记录起点；位移超过 touchSlop 进入拖动态；
 * - MOVE 按手指增量移动并夹在屏幕边界内；
 * - UP：拖动中且在顶部回收区 → 移除悬浮球；未发生拖动 → 视为点击打开 App；
 *   否则做短惯性滑行并持久化位置。
 */
internal object FloatingBallGesture {

    fun handle(
        app: Context,
        wm: WindowManager,
        view: View,
        params: WindowManager.LayoutParams,
        event: MotionEvent,
        controller: FloatingBallController,
    ): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                GestureState.epoch = controller.beginGesture()
                GestureState.downX = event.rawX
                GestureState.downY = event.rawY
                GestureState.lastRawX = event.rawX
                GestureState.lastRawY = event.rawY
                GestureState.dragging = false
                GestureState.moved = false
                GestureState.moveDx = 0f
                GestureState.moveDy = 0f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!controller.isCurrentGesture(GestureState.epoch) || !view.isAttachedToWindow) {
                    return true
                }
                val touchSlop = ViewConfiguration.get(app).scaledTouchSlop
                if (!GestureState.dragging &&
                    (abs(event.rawX - GestureState.downX) > touchSlop ||
                        abs(event.rawY - GestureState.downY) > touchSlop)
                ) {
                    GestureState.dragging = true
                }
                if (GestureState.dragging) {
                    val dx = event.rawX - GestureState.lastRawX
                    val dy = event.rawY - GestureState.lastRawY
                    GestureState.moveDx = dx
                    GestureState.moveDy = dy
                    val bounds = controller.boundsOf(wm)
                    params.x = (params.x + dx).toInt().coerceIn(0, (bounds.width() - params.width).coerceAtLeast(0))
                    params.y = (params.y + dy).toInt().coerceIn(0, (bounds.height() - params.height).coerceAtLeast(0))
                    runCatching { wm.updateViewLayout(view, params) }
                    GestureState.moved = true
                }
                GestureState.lastRawX = event.rawX
                GestureState.lastRawY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!controller.isCurrentGesture(GestureState.epoch) || !view.isAttachedToWindow) {
                    return true
                }
                val bounds = controller.boundsOf(wm)
                val recycleTop = (bounds.height() * FloatingBallController.RECYCLE_ZONE_RATIO).toInt()
                if (GestureState.dragging && params.y + params.height / 2 <= recycleTop) {
                    controller.removeByRecycle(app)
                    return true
                }
                if (!GestureState.moved) {
                    controller.openApp(app)
                    return true
                }
                // 短惯性滑行（postDelayed 逐帧衰减）
                glide(app, wm, view, params, GestureState.moveDx, GestureState.moveDy, controller, GestureState.epoch)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (controller.isCurrentGesture(GestureState.epoch) && view.isAttachedToWindow && GestureState.moved) {
                    controller.persistPosition(app, wm, params)
                }
                return true
            }
        }
        return false
    }

    private fun glide(
        app: Context,
        wm: WindowManager,
        view: View,
        params: WindowManager.LayoutParams,
        vx: Float,
        vy: Float,
        controller: FloatingBallController,
        epoch: Long,
    ) {
        val bounds = controller.boundsOf(wm)
        val startX = params.x
        val startY = params.y
        val total = 10
        var step = 0
        val runnable = object : Runnable {
            override fun run() {
                if (!controller.isCurrentGesture(epoch) || !view.isAttachedToWindow) {
                    return
                }
                if (step >= total) {
                    controller.persistPosition(app, wm, params)
                    return
                }
                step++
                val p = 1f - step.toFloat() / total
                params.x = (startX + vx * p).toInt().coerceIn(0, (bounds.width() - params.width).coerceAtLeast(0))
                params.y = (startY + vy * p).toInt().coerceIn(0, (bounds.height() - params.height).coerceAtLeast(0))
                runCatching { wm.updateViewLayout(view, params) }
                view.postDelayed(this, 16L)
            }
        }
        view.post(runnable)
    }
}

/** 手势会话状态（进程级单帧状态，避免每次构造对象） */
private object GestureState {
    var epoch = 0L
    var downX = 0f
    var downY = 0f
    var lastRawX = 0f
    var lastRawY = 0f
    var moveDx = 0f
    var moveDy = 0f
    var dragging = false
    var moved = false
}
