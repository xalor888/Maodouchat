package com.maodouchat.floating

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import com.maodouchat.MainActivity
import com.maodouchat.R
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * B5 悬浮球（SYSTEM_ALERT_WINDOW）。
 *
 * 设计约束：
 * - 悬浮球本体不展示任何消息/会话内容（纯通用小球），不受密聊/锁定门禁约束；
 * - 点击打开 App 仍走 MainActivity 既有 App 锁 / 假聊天 / 密聊门禁，不绕过任何保护；
 * - 会话失效/账号切换时点击仅拉起 App，由 MainActivity 自行判断导航。
 *
 * 手势优化：
 * - touchSlop 阈值区分「点击」与「拖动」，避免拖动误触打开 App；
 * - 拖动期间按手指增量移动，松手后做短惯性滑行（postDelayed 逐帧衰减）；
 * - 拖到屏幕顶部「回收区」松手 → 移除悬浮球（关闭手势）；
 * - 位置按屏幕宽高百分比持久化，进程重建后恢复。
 */
object FloatingBallController {

    private const val BALL_SIZE_DP = 52
    internal const val RECYCLE_ZONE_RATIO = 0.12f     // 顶部 12% 为回收区

    private var windowManager: WindowManager? = null
    // WindowManager itself keeps a strong reference while the overlay is attached. Holding only a
    // weak reference here prevents the singleton from retaining the View after removal.
    private var ballView: WeakReference<View>? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // ---- 状态 ----
    fun isShowing(): Boolean = ballView?.get()?.isAttachedToWindow == true

    fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(context.applicationContext)

    fun openOverlaySettings(context: Context) {
        runCatching {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun isEnabled(context: Context): Boolean = FloatingBallPreferences.isEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) {
        FloatingBallPreferences.setEnabled(context, enabled)
        val app = context.applicationContext
        if (enabled) {
            if (isGranted(app)) start(app)
            else {
                Toast.makeText(app, R.string.floating_ball_permission_needed, Toast.LENGTH_SHORT).show()
                openOverlaySettings(app)
            }
        } else {
            stop(app)
        }
    }

    /** 应用内显式启动（权限已具备时调用） */
    fun start(context: Context) {
        val app = context.applicationContext
        if (isShowing()) return
        if (!isGranted(app)) return
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            BALL_SIZE_DP.toFloat(),
            app.resources.displayMetrics
        ).toInt()
        val ball = ImageView(app).apply {
            setImageResource(R.drawable.ic_notification)
            background = circleBackground()
            contentDescription = app.getString(R.string.floating_ball_content_description)
            alpha = 0.92f
        }
        val bounds = boundsOf(wm)
        val saved = FloatingBallPreferences.position(app)
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((saved.first * bounds.width()).toInt()).coerceIn(0, (bounds.width() - size).coerceAtLeast(0))
            y = ((saved.second * bounds.height()).toInt()).coerceIn(0, (bounds.height() - size).coerceAtLeast(0))
        }
        ball.setOnTouchListener { _, event ->
            FloatingBallGesture.handle(app, wm, ball, params, event, this)
        }
        runCatching { wm.addView(ball, params) }.onFailure { return }
        windowManager = wm
        ballView = WeakReference(ball)
        layoutParams = params
    }

    /** 停止并移除悬浮球 */
    fun stop(context: Context) {
        val app = context.applicationContext
        val wm = windowManager ?: runCatching { app.getSystemService(Context.WINDOW_SERVICE) as WindowManager }.getOrNull() ?: return
        val view = ballView?.get()
        if (view == null) {
            windowManager = null
            ballView = null
            layoutParams = null
            return
        }
        runCatching { wm.removeView(view) }
        windowManager = null
        ballView = null
        layoutParams = null
    }

    fun toggle(context: Context) {
        if (isShowing()) stop(context) else start(context)
    }

    // ---- 内部 ----
    internal fun removeByRecycle(context: Context) {
        stop(context)
        Toast.makeText(context.applicationContext, R.string.floating_ball_removed, Toast.LENGTH_SHORT).show()
    }

    internal fun openApp(context: Context) {
        runCatching {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        }
    }

    internal fun persistPosition(context: Context, wm: WindowManager, params: WindowManager.LayoutParams) {
        val bounds = boundsOf(wm)
        val w = bounds.width().coerceAtLeast(1)
        val h = bounds.height().coerceAtLeast(1)
        FloatingBallPreferences.setPosition(
            context,
            ((params.x + params.width / 2f) / w).coerceIn(0f, 1f),
            ((params.y + params.height / 2f) / h).coerceIn(0f, 1f),
        )
    }

    private fun circleBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF007AFF.toInt())
            setStroke(2, 0xFFFFFFFF.toInt())
        }

    internal fun boundsOf(wm: WindowManager): Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }
}

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
                    wm.updateViewLayout(view, params)
                    GestureState.moved = true
                }
                GestureState.lastRawX = event.rawX
                GestureState.lastRawY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP -> {
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
                glide(app, wm, view, params, GestureState.moveDx, GestureState.moveDy, controller)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (GestureState.moved) controller.persistPosition(app, wm, params)
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
    ) {
        val bounds = controller.boundsOf(wm)
        val startX = params.x
        val startY = params.y
        val total = 10
        var step = 0
        val runnable = object : Runnable {
            override fun run() {
                if (step >= total) {
                    controller.persistPosition(app, wm, params)
                    return
                }
                step++
                val p = 1f - step.toFloat() / total
                params.x = (startX + vx * p).toInt().coerceIn(0, (bounds.width() - params.width).coerceAtLeast(0))
                params.y = (startY + vy * p).toInt().coerceIn(0, (bounds.height() - params.height).coerceAtLeast(0))
                wm.updateViewLayout(view, params)
                view.postDelayed(this, 16L)
            }
        }
        view.post(runnable)
    }
}

/** 手势会话状态（进程级单帧状态，避免每次构造对象） */
private object GestureState {
    var downX = 0f
    var downY = 0f
    var lastRawX = 0f
    var lastRawY = 0f
    var moveDx = 0f
    var moveDy = 0f
    var dragging = false
    var moved = false
}
